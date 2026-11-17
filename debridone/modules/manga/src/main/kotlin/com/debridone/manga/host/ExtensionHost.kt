package com.debridone.manga.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Everything needed to start one host process. */
data class HostLaunch(
    val runtime: HostRuntime,
    /** Plugin folder, laid out as Houdoku's (`@tiyo/core/…`). The host may only read it. */
    val pluginsDir: Path,
    /** The only folder the host may write to; images are handed over through it. */
    val spoolDir: Path,
    /** A private, empty home folder, so extensions do not see the user's own. */
    val homeDir: Path,
    /** `http://host:port` of a proxy for the extensions' requests, or null for a direct connection. */
    val proxy: String? = null,
    /** Run under Node's permission model (file access limited to the folders above; no child processes). */
    val restricted: Boolean = true,
    val testMode: Boolean = false,
)

data class HostInfo(
    val hostVersion: String,
    val nodeVersion: String,
    val akiVersion: String,
    val restricted: Boolean,
    val pid: Long,
    val runtimeOrigin: String,
)

sealed interface HostState {
    data object Stopped : HostState
    data object Starting : HostState
    data class Running(val info: HostInfo) : HostState

    /** Not running and will not be started again automatically until [ExtensionHost.restart] is called. */
    data class Unavailable(val code: String, val reason: String) : HostState
}

data class HostLogLine(val at: Long, val level: String, val text: String)

/**
 * Runs DebridOne's extension host (a Node.js process with the Tiyo package inside) and talks to it
 * over its standard input and output. Nothing here opens a network port.
 *
 * - The process is started on first use and stopped by [close].
 * - Every call has a time limit. A process that stops answering is ended and replaced.
 * - If the process dies, the calls that were waiting fail with `HOST_STOPPED` and the next call
 *   starts a new one. Five crashes within a minute stop the automatic restarts.
 * - [generation] changes each time a new process starts, so the owner knows when to load the
 *   plugins and apply settings again.
 */
class ExtensionHost(
    private val launch: () -> HostLaunch,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val json = Json { ignoreUnknownKeys = true }
    private val startLock = Mutex()
    private val nextId = AtomicLong(1)
    private val generationCounter = AtomicInteger(0)
    private val crashes = ArrayDeque<Long>()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var closed = false

    private val _state = MutableStateFlow<HostState>(HostState.Stopped)
    val state: StateFlow<HostState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<HostLogLine>>(emptyList())

    /** What the host and the extensions printed, newest last. Kept short; never written to disk. */
    val log: StateFlow<List<HostLogLine>> = _log.asStateFlow()

    /** Number of host processes started so far. */
    val generation: Int get() = generationCounter.get()

    /** How many times the host stopped without being asked to, since the app started. */
    val crashCount: Int get() = crashTotal.get()
    private val crashTotal = AtomicInteger(0)

    /** Starts the host if needed and returns the [generation] of the running process. */
    suspend fun ensureStarted(): Int = startLock.withLock {
        if (closed) throw HostException("HOST_STOPPED", "The extension host has been shut down.")
        session?.takeIf { it.alive }?.let { return@withLock generationCounter.get() }
        val now = clock()
        synchronized(crashes) {
            while (crashes.isNotEmpty() && now - crashes.first() > CRASH_WINDOW_MS) crashes.removeFirst()
            if (crashes.size >= MAX_CRASHES) {
                val reason = "The extension host stopped $MAX_CRASHES times within a minute, so it was not started again. " +
                    "Look at the diagnostics, then restart it from Plugins › Manga › Settings."
                _state.value = HostState.Unavailable("HOST_UNSTABLE", reason)
                throw HostException("HOST_UNSTABLE", reason)
            }
        }
        _state.value = HostState.Starting
        try {
            val started = withContext(Dispatchers.IO) { start() }
            session = started
            _state.value = HostState.Running(started.info)
            generationCounter.incrementAndGet()
        } catch (failure: HostException) {
            _state.value = HostState.Unavailable(failure.code, failure.message ?: "The extension host could not be started.")
            throw failure
        }
    }

    /** Sends one request and waits for its answer. Throws [HostException] on any failure. */
    suspend fun call(method: String, params: JsonObject = EMPTY, timeoutMs: Long = DEFAULT_TIMEOUT_MS): JsonElement {
        ensureStarted()
        val current = session ?: throw HostException("HOST_STOPPED", "The extension host is not running.")
        val id = nextId.getAndIncrement()
        val answer = CompletableDeferred<JsonElement>()
        current.pending[id] = answer
        try {
            val request = buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            }
            withContext(Dispatchers.IO) { current.write(json.encodeToString(JsonObject.serializer(), request)) }
            val result = withTimeout(timeoutMs) { answer.await() }
            current.timeouts.set(0)
            return result
        } catch (timeout: TimeoutCancellationException) {
            // A promise inside the host cannot be cancelled from here. One slow site is not a reason
            // to restart everything, but a host that stops answering altogether is.
            if (current.timeouts.incrementAndGet() >= TIMEOUTS_BEFORE_RESTART) {
                append("warn", "The host did not answer $TIMEOUTS_BEFORE_RESTART calls in a row; restarting it.")
                current.kill()
            }
            throw HostException("TIMEOUT", "The source did not answer within ${timeoutMs / 1000} seconds.")
        } finally {
            current.pending.remove(id)
        }
    }

    /** Stops the running process (if any) and forgets earlier crashes; the next call starts a new one. */
    suspend fun restart() {
        startLock.withLock {
            synchronized(crashes) { crashes.clear() }
            session?.let { withContext(Dispatchers.IO) { it.stop() } }
            session = null
            if (!closed) _state.value = HostState.Stopped
        }
    }

    /** Stops the process. Calls made afterwards fail. */
    override fun close() {
        closed = true
        session?.stop()
        session = null
        _state.value = HostState.Stopped
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    private fun append(level: String, text: String) {
        val line = HostLogLine(clock(), level, text.take(MAX_LOG_LINE))
        _log.value = (_log.value + line).takeLast(MAX_LOG_LINES)
    }

    private fun start(): Session {
        val config = launch()
        Files.createDirectories(config.pluginsDir)
        Files.createDirectories(config.spoolDir)
        Files.createDirectories(config.homeDir)
        // The permission model compares real paths (on macOS /var is a link to /private/var).
        val hostDir = config.runtime.hostDir.toRealPath()
        val pluginsDir = config.pluginsDir.toRealPath()
        val spoolDir = config.spoolDir.toRealPath()

        val command = mutableListOf(config.runtime.node.toString())
        if (config.restricted) {
            command += "--permission"
            command += "--allow-fs-read=$hostDir"
            command += "--allow-fs-read=$pluginsDir"
            command += "--allow-fs-read=$spoolDir"
            command += "--allow-fs-write=$spoolDir"
        }
        command += hostDir.resolve("src").resolve("host.js").toString()
        command += listOf("--plugins", pluginsDir.toString(), "--spool", spoolDir.toString())

        val builder = ProcessBuilder(command).directory(spoolDir.toFile())
        val environment = builder.environment()
        // Extensions get none of the app's environment: no tokens, no paths, no user name.
        environment.clear()
        environment["PATH"] = "/usr/bin:/bin"
        environment["HOME"] = config.homeDir.toRealPath().toString()
        environment["TMPDIR"] = spoolDir.toString()
        environment["LANG"] = "en_US.UTF-8"
        config.proxy?.takeIf { it.isNotBlank() }?.let { proxy ->
            environment["HTTPS_PROXY"] = proxy
            environment["HTTP_PROXY"] = proxy
            environment["NO_PROXY"] = "localhost,127.0.0.1,::1"
            environment["NODE_USE_ENV_PROXY"] = "1"
        }
        // Certificate authorities added by the computer's administrator (a file of public certificates).
        System.getenv("NODE_EXTRA_CA_CERTS")?.takeIf { it.isNotBlank() }?.let { environment["NODE_EXTRA_CA_CERTS"] = it }
        if (config.testMode) environment["DEBRIDONE_MANGA_HOST_TEST"] = "1"

        val process = try {
            builder.start()
        } catch (failure: Exception) {
            throw HostException("NO_RUNTIME", "The extension runtime could not be started: ${failure.message}")
        }
        val started = Session(process, config.runtime.origin)
        started.listen()
        val ready = started.awaitReady(START_TIMEOUT_MS)
        if (!ready) {
            val tail = _log.value.takeLast(4).joinToString(" | ") { it.text }
            started.stop()
            throw HostException("START_FAILED", "The extension host did not start." + if (tail.isNotBlank()) " ($tail)" else "")
        }
        return started
    }

    private inner class Session(private val process: Process, private val origin: String) {
        val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
        val timeouts = AtomicInteger(0)
        private val writer: BufferedWriter = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        private val ready = java.util.concurrent.CountDownLatch(1)

        @Volatile
        private var stopping = false

        @Volatile
        var info: HostInfo = HostInfo("?", "?", "?", false, process.pid(), origin)
            private set

        val alive: Boolean get() = process.isAlive && !stopping

        fun listen() {
            Thread({ readAnswers() }, "manga-host-stdout").apply { isDaemon = true }.start()
            Thread({ readDiagnostics() }, "manga-host-stderr").apply { isDaemon = true }.start()
        }

        fun awaitReady(timeoutMs: Long): Boolean = ready.await(timeoutMs, TimeUnit.MILLISECONDS) && process.isAlive

        @Synchronized
        fun write(line: String) {
            try {
                writer.write(line)
                writer.write("\n")
                writer.flush()
            } catch (failure: Exception) {
                throw HostException("HOST_STOPPED", "The extension host stopped before the request could be sent.")
            }
        }

        fun kill() {
            process.destroyForcibly()
        }

        fun stop() {
            stopping = true
            runCatching { write("""{"id":0,"method":"shutdown","params":{}}""") }
            runCatching { writer.close() }
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            failPending("The extension host was stopped.")
        }

        private fun failPending(message: String) {
            val waiting = pending.values.toList()
            pending.clear()
            waiting.forEach { it.completeExceptionally(HostException("HOST_STOPPED", message)) }
        }

        private fun readAnswers() {
            try {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                    if (!line.startsWith(PREFIX)) {
                        if (line.isNotBlank()) append("out", line)
                        return@forEachLine
                    }
                    val message = runCatching { json.parseToJsonElement(line.substring(PREFIX.length)).jsonObject }.getOrNull()
                        ?: return@forEachLine
                    val event = message["event"]?.jsonPrimitive?.contentOrNull
                    if (event == "ready") {
                        info = HostInfo(
                            hostVersion = message["host"]?.jsonPrimitive?.contentOrNull ?: "?",
                            nodeVersion = message["node"]?.jsonPrimitive?.contentOrNull ?: "?",
                            akiVersion = "",
                            restricted = message["restricted"]?.jsonPrimitive?.booleanOrNull ?: false,
                            pid = process.pid(),
                            runtimeOrigin = origin,
                        )
                        ready.countDown()
                        return@forEachLine
                    }
                    val id = message["id"]?.jsonPrimitive?.longOrNull ?: return@forEachLine
                    val waiting = pending.remove(id) ?: return@forEachLine
                    if (message["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                        waiting.complete(message["result"] ?: JsonNull)
                    } else {
                        val error = message["error"] as? JsonObject
                        waiting.completeExceptionally(
                            HostException(
                                code = error?.get("code")?.jsonPrimitive?.contentOrNull ?: "SOURCE_ERROR",
                                message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "The source reported an error.",
                                network = (error?.get("network") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
                                detail = (error?.get("stack") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
                            ),
                        )
                    }
                }
            } catch (_: Exception) {
                // The stream closes when the process ends; handled below.
            }
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            ready.countDown()
            if (!stopping) {
                crashTotal.incrementAndGet()
                synchronized(crashes) { crashes.addLast(clock()) }
                append("error", "The extension host stopped unexpectedly (exit code $code).")
                failPending("The extension host stopped unexpectedly. It will be started again on the next request.")
                if (session === this) _state.value = HostState.Stopped
            }
        }

        private fun readDiagnostics() {
            try {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                    if (line.isBlank() || NOISE.any { line.contains(it) }) return@forEachLine
                    val match = LEVEL.find(line)
                    if (match != null) append(match.groupValues[1], line.substring(match.value.length)) else append("err", line)
                }
            } catch (_: Exception) {
                // Stream closed.
            }
        }
    }

    companion object {
        const val PREFIX = "@@DO1@@ "
        const val DEFAULT_TIMEOUT_MS = 45_000L
        private const val START_TIMEOUT_MS = 20_000L
        private const val CRASH_WINDOW_MS = 60_000L
        private const val MAX_CRASHES = 5
        private const val TIMEOUTS_BEFORE_RESTART = 4
        private const val MAX_LOG_LINES = 400
        private const val MAX_LOG_LINE = 2_000
        private val EMPTY = JsonObject(emptyMap())
        private val LEVEL = Regex("^\\[(log|info|debug|warn|error|trace)] ")
        private val NOISE = listOf("EnvHttpProxyAgent is experimental", "--trace-warnings")
    }
}
