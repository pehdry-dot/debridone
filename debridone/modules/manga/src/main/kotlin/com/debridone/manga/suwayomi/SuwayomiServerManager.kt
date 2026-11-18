package com.debridone.manga.suwayomi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/** The Java runtime and the Suwayomi-Server jar DebridOne starts. */
data class SuwayomiRuntime(val java: Path, val jar: Path)

/** Why there is nothing to start, in words for the person. */
class SuwayomiUnavailable(message: String) : IOException(message)

object SuwayomiRuntimeLocator {
    /**
     * Finds the bundled runtime. Order: the `debridone.suwayomi.java` / `.jar` system properties,
     * the `DEBRIDONE_SUWAYOMI_JAVA` / `_JAR` environment variables (development and tests), then
     * the copies inside the packaged app. A Java found on the computer is never used: Suwayomi
     * needs Java 21 and DebridOne ships the one it was tested with.
     */
    fun locate(): SuwayomiRuntime {
        val java = System.getProperty("debridone.suwayomi.java") ?: System.getenv("DEBRIDONE_SUWAYOMI_JAVA")
        val jar = System.getProperty("debridone.suwayomi.jar") ?: System.getenv("DEBRIDONE_SUWAYOMI_JAR")
        if (!java.isNullOrBlank() && !jar.isNullOrBlank()) return checked(Paths.get(java), Paths.get(jar))
        val resources = System.getProperty("compose.application.resources.dir")
            ?: throw SuwayomiUnavailable("The manga server is not part of this build. Run scripts/debridone/fetch-suwayomi-runtime.sh, or use a packaged DebridOne.")
        val root = Paths.get(resources, "suwayomi")
        val launcher = if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) "java.exe" else "java"
        return checked(root.resolve("jre").resolve("bin").resolve(launcher), root.resolve("Suwayomi-Server.jar"))
    }

    private fun checked(java: Path, jar: Path): SuwayomiRuntime {
        if (!Files.isRegularFile(java)) throw SuwayomiUnavailable("The Java runtime for the manga server is missing (${java.fileName} was expected in ${java.parent}).")
        if (!Files.isRegularFile(jar)) throw SuwayomiUnavailable("Suwayomi-Server is missing (${jar.fileName} was expected in ${jar.parent}).")
        // Packaging can drop the executable bit; the file is ours, so it is put back.
        if (!Files.isExecutable(java)) runCatching { java.toFile().setExecutable(true) }
        return SuwayomiRuntime(java, jar)
    }
}

/** Where a Suwayomi server is reached and how DebridOne signs in to it. */
data class ServerEndpoint(
    val baseUrl: String,
    val username: String?,
    val password: String?,
    /** True for the server DebridOne started itself; false for one the person runs elsewhere. */
    val managed: Boolean,
    /** How long one request may take. */
    val timeoutSeconds: Int = 60,
) {
    override fun toString(): String = "ServerEndpoint($baseUrl, managed=$managed)"
}

sealed interface ServerState {
    object Stopped : ServerState
    data class Starting(val profileId: Long) : ServerState
    data class Ready(val profileId: Long, val endpoint: ServerEndpoint, val version: String) : ServerState

    /** [log] holds the server's last lines, for the diagnostics view; it never contains the password. */
    data class Failed(val profileId: Long, val message: String, val log: List<String>) : ServerState
}

/**
 * Starts, watches and stops the Suwayomi-Server that belongs to one DebridOne profile.
 *
 * Suwayomi has one library per data folder and no user accounts, so each profile gets a folder of
 * its own (`<root>/profile-<id>`) and only the active profile's server runs. The server listens on
 * the loopback interface, on a port chosen at start, and asks for a password that is generated at
 * every start and written only to that folder's `server.conf`: another program or a web page on
 * this computer cannot use it, and neither can another computer.
 *
 * DebridOne stops only a process it started. A server left behind by a crash is recognised by the
 * pid file in its own folder and by its command line before it is touched.
 */
class SuwayomiServerManager(
    private val dataRoot: Path,
    private val runtime: () -> SuwayomiRuntime = { SuwayomiRuntimeLocator.locate() },
    private val startTimeoutMs: Long = 120_000,
    private val maxHeapMegabytes: Int = 1024,
) : AutoCloseable {
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val lines = ArrayDeque<String>()
    private var process: Process? = null
    private var lock: FileLock? = null
    private var lockChannel: FileChannel? = null
    private var profileId: Long? = null

    @Volatile
    private var stopping = false

    /** The server's last lines of output. */
    fun log(): List<String> = synchronized(lines) { lines.toList() }

    /** The process this manager started, for tests that play the part of a DebridOne that died. */
    internal fun runningProcess(): Process? = process

    /** The process id of the server this manager started, or null when none is running. */
    val runningProcess: Long? get() = process?.takeIf { it.isAlive }?.pid()

    fun directory(profile: Long): Path = dataRoot.resolve("profile-$profile")

    /** The folder Suwayomi's built-in Local source reads from. */
    fun localSourceDirectory(profile: Long): Path = directory(profile).resolve("local")

    /**
     * Starts the server of [profile] and waits until it answers. Any other profile's server is
     * stopped first. Throws [SuwayomiUnavailable] with a sentence for the person when it cannot.
     */
    @Synchronized
    fun start(profile: Long): ServerEndpoint {
        (_state.value as? ServerState.Ready)?.takeIf { it.profileId == profile && process?.isAlive == true }?.let { return it.endpoint }
        stopLocked()
        _state.value = ServerState.Starting(profile)
        synchronized(lines) { lines.clear() }
        return try {
            launch(profile)
        } catch (e: Exception) {
            stopLocked()
            val message = (e as? SuwayomiUnavailable)?.message ?: "The manga server could not be started: ${e.message ?: e::class.simpleName}"
            _state.value = ServerState.Failed(profile, message, log())
            if (e is SuwayomiUnavailable) throw e else throw SuwayomiUnavailable(message)
        }
    }

    private fun launch(profile: Long): ServerEndpoint {
        val runtime = runtime()
        val directory = directory(profile)
        Files.createDirectories(directory)
        ownerOnly(directory, "rwx------")
        acquireLock(directory)
        endLeftover(directory)

        val username = "debridone"
        var lastProblem: String? = null
        // A profile's first start. Suwayomi comes with new-chapter checks that leave out series with
        // unread chapters, series not started and completed ones; in a new library that is every
        // series, so "check for new chapters" would find nothing. A new server starts with every
        // series looked at; the person can switch the rules on in Manga's server settings, and from
        // then on the choice is theirs (it is never written again).
        val firstStart = !Files.exists(directory.resolve("server.conf"))
        for (attempt in 1..PORT_ATTEMPTS) {
            val port = freePort()
            val password = newPassword()
            ServerConf.write(
                directory.resolve("server.conf"),
                linkedMapOf<String, String>().apply {
                    if (firstStart) {
                        put("excludeUnreadChapters", "false")
                        put("excludeNotStarted", "false")
                        put("excludeCompleted", "false")
                    }
                } + linkedMapOf(
                    "ip" to ServerConf.text("127.0.0.1"),
                    "port" to port.toString(),
                    // Nothing but the API: no web interface, no browser window, no tray icon, no bundled Chromium.
                    "webUIEnabled" to "false",
                    "initialOpenInBrowserEnabled" to "false",
                    "systemTrayEnabled" to "false",
                    "kcefEnabled" to "false",
                    "authMode" to "BASIC_AUTH",
                    "authUsername" to ServerConf.text(username),
                    "authPassword" to ServerConf.text(password),
                ),
            )
            ownerOnly(directory.resolve("server.conf"), "rw-------")
            val temporary = Files.createDirectories(directory.resolve("tmp"))
            val command = listOf(
                runtime.java.toString(),
                "-Xmx${maxHeapMegabytes}m",
                // No window, no Dock icon: this is a helper of DebridOne, not an application of its own.
                "-Djava.awt.headless=true",
                "-Dapple.awt.UIElement=true",
                "-Djava.io.tmpdir=$temporary",
                "-Dsuwayomi.tachidesk.config.server.rootDir=$directory",
                "-javaagent:${watchdogJar(directory)}",
                "-jar", runtime.jar.toString(),
            )
            val started = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start()
            process = started
            profileId = profile
            stopping = false
            Thread({ drain(started) }, "suwayomi-output").apply { isDaemon = true }.start()
            runCatching { Files.writeString(directory.resolve(PID_FILE), "${started.pid()}\n") }

            val endpoint = ServerEndpoint("http://127.0.0.1:$port", username, password, managed = true)
            val version = awaitReady(started, endpoint)
            if (version != null) {
                _state.value = ServerState.Ready(profile, endpoint, version)
                started.onExit().thenAccept { ended(started, profile) }
                return endpoint
            }
            // It ended before answering. If another program took the port in the meantime, a new port is tried.
            val output = log()
            val portTaken = output.any { it.contains("Address already in use", ignoreCase = true) }
            lastProblem = output.lastOrNull { it.contains("Address already in use", ignoreCase = true) || it.contains("ERROR") } ?: output.lastOrNull()
            started.destroyForcibly()
            process = null
            if (!portTaken) break
        }
        throw SuwayomiUnavailable(describe(lastProblem))
    }

    private fun describe(lastLine: String?): String =
        "The manga server ended while starting." + (lastLine?.let { " Its last message was: ${it.take(300)}" } ?: "")

    /** The server's version once it answers with our password, or null when the process ended first. */
    private fun awaitReady(started: Process, endpoint: ServerEndpoint): String? {
        val transport = SuwayomiTransport(endpoint)
        val deadline = System.nanoTime() + startTimeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (!started.isAlive) return null
            try {
                return SuwayomiApi(transport).about().version
            } catch (e: SuwayomiException) {
                // A password that is refused means the port is answered by something that is not our server.
                if (e.kind == SuwayomiException.Kind.UNAUTHORIZED) throw SuwayomiUnavailable("Another program is answering on the manga server's port.")
            }
            Thread.sleep(250)
        }
        throw SuwayomiUnavailable("The manga server did not answer within ${startTimeoutMs / 1000} seconds.")
    }

    private fun ended(ended: Process, profile: Long) {
        synchronized(this) {
            if (process !== ended) return
            process = null
            releaseLock()
            if (stopping) return
            _state.value = ServerState.Failed(
                profile, "The manga server stopped unexpectedly (exit code ${runCatching { ended.exitValue() }.getOrNull() ?: "unknown"}).", log(),
            )
        }
    }

    private fun drain(started: Process) {
        runCatching {
            started.inputStream.bufferedReader().useLines { output ->
                for (line in output) synchronized(lines) {
                    lines.addLast(line.take(MAX_LINE))
                    while (lines.size > KEPT_LINES) lines.removeFirst()
                }
            }
        }
    }

    /** Stops the server this manager started, cleanly, and waits for it. */
    @Synchronized
    fun stop() {
        stopLocked()
        _state.value = ServerState.Stopped
    }

    private fun stopLocked() {
        val running = process
        stopping = true
        process = null
        if (running != null && running.isAlive) {
            // The server closes its database in its shutdown hooks; it gets time to do so.
            running.destroy()
            if (!running.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                running.destroyForcibly()
                running.waitFor(5, TimeUnit.SECONDS)
            }
        }
        profileId?.let { runCatching { Files.deleteIfExists(directory(it).resolve(PID_FILE)) } }
        profileId = null
        releaseLock()
    }

    override fun close() = stop()

    private fun acquireLock(directory: Path) {
        val channel = FileChannel.open(directory.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val acquired = runCatching { channel.tryLock() }.getOrNull()
        if (acquired == null) {
            channel.close()
            throw SuwayomiUnavailable("This profile's manga data is in use by another DebridOne that is running.")
        }
        lockChannel = channel
        lock = acquired
    }

    private fun releaseLock() {
        runCatching { lock?.release() }
        runCatching { lockChannel?.close() }
        lock = null
        lockChannel = null
    }

    /**
     * Ends a server that an earlier DebridOne left running in this folder (it crashed before it
     * could stop it). Only a process whose id is in this folder's pid file **and** whose command
     * line names this folder is touched: nothing else on the computer is ours to stop.
     */
    private fun endLeftover(directory: Path) {
        val file = directory.resolve(PID_FILE)
        val pid = runCatching { Files.readString(file).trim().toLong() }.getOrNull() ?: return
        val handle = ProcessHandle.of(pid).orElse(null)
        if (handle != null && handle.isAlive) {
            val commandLine = handle.info().commandLine().orElse("") + " " + handle.info().arguments().map { it.joinToString(" ") }.orElse("")
            if (commandLine.contains("rootDir=$directory")) {
                handle.destroy()
                runCatching { handle.onExit().get(STOP_GRACE_SECONDS, TimeUnit.SECONDS) }
                if (handle.isAlive) handle.destroyForcibly()
            }
        }
        runCatching { Files.deleteIfExists(file) }
    }

    /** The watchdog as a jar of its own, written beside the data: a `-javaagent` has to be a jar file. */
    private fun watchdogJar(directory: Path): Path {
        val name = "com/debridone/manga/suwayomi/watchdog/ParentWatchdog.class"
        val bytes = javaClass.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
            ?: throw SuwayomiUnavailable("DebridOne is incomplete: the manga server's watchdog is missing.")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Premain-Class", "com.debridone.manga.suwayomi.watchdog.ParentWatchdog")
        }
        val jar = directory.resolve("debridone-watchdog.jar")
        JarOutputStream(Files.newOutputStream(jar), manifest).use { output ->
            output.putNextEntry(JarEntry(name))
            output.write(bytes)
            output.closeEntry()
        }
        return jar
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun newPassword(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })

    private fun ownerOnly(path: Path, permissions: String) {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions)) }
    }

    private companion object {
        const val PID_FILE = "debridone-server.pid"
        const val LOCK_FILE = ".debridone.lock"
        const val PORT_ATTEMPTS = 3
        const val STOP_GRACE_SECONDS = 12L
        const val KEPT_LINES = 400
        const val MAX_LINE = 600
    }
}

/**
 * The few lines of Suwayomi's `server.conf` that DebridOne decides. The file is Suwayomi's (HOCON,
 * `server.<key> = <value>` lines with comments); only the named keys are replaced or added, and
 * everything else, including what the person changed through DebridOne's settings, is kept.
 */
internal object ServerConf {
    fun text(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun write(file: Path, values: Map<String, String>) {
        val existing = if (Files.isRegularFile(file)) Files.readAllLines(file) else emptyList()
        val pending = LinkedHashMap(values)
        val result = existing.map { line ->
            val key = KEY.find(line)?.groupValues?.get(1)
            val replacement = key?.let { pending.remove(it) }
            if (replacement != null) "server.$key = $replacement" else line
        }.toMutableList()
        pending.forEach { (key, value) -> result += "server.$key = $value" }
        Files.write(file, result)
    }

    private val KEY = Regex("^server\\.([A-Za-z0-9_]+)\\s*=")
}
