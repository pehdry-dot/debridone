package com.debridone.manga.suwayomi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URI
import java.nio.file.Path

/** A Suwayomi server the person runs themselves, used instead of the one DebridOne starts. */
data class RemoteServer(
    val url: String,
    val username: String?,
    val password: String?,
    val timeoutSeconds: Int = 60,
    /** Refuse an address that is not https (plain http to this computer itself is always allowed). */
    val requireHttps: Boolean = true,
) {
    override fun toString(): String = "RemoteServer($url)"
}

/** Where Manga stands with its backend, for the active profile. */
sealed interface MangaConnection {
    /** Nothing asked for yet: the server is started when Manga is first opened. */
    object Idle : MangaConnection

    data class Connecting(val profileId: Long, val remote: Boolean) : MangaConnection

    data class Ready(val profileId: Long, val version: String, val remote: Boolean, val address: String) : MangaConnection

    /** [retrying] is true while DebridOne keeps trying by itself. [log] is the managed server's last output. */
    data class Failed(val profileId: Long, val message: String, val log: List<String>, val remote: Boolean, val retrying: Boolean = false) : MangaConnection
}

/**
 * The one connection Manga has: to the Suwayomi-Server of the active profile.
 *
 * By default that is a server DebridOne starts itself, one per profile, each with its own data
 * folder ([SuwayomiServerManager]) — Suwayomi has no user accounts, so separate folders are what
 * keeps one profile's library, progress, extensions and trackers apart from another's. A profile
 * can instead point at a server the person runs elsewhere ([RemoteServer]); that library is then
 * the server's, shared by every profile and device that uses it.
 *
 * Nothing is started until [ensureStarted] is called (when Manga is first opened): a person who
 * never reads manga pays nothing for it.
 */
class MangaServer(
    val manager: SuwayomiServerManager,
    private val scope: CoroutineScope,
    private val remoteFor: (profileId: Long) -> RemoteServer?,
) : AutoCloseable {
    private class Session(val profileId: Long, val transport: SuwayomiTransport, val remote: Boolean) {
        val api = SuwayomiApi(transport)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow<MangaConnection>(MangaConnection.Idle)
    val state: StateFlow<MangaConnection> = _state.asStateFlow()

    private val _generation = MutableStateFlow(0)

    /** Changes whenever what is connected to changes; everything remembered from before is then stale. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var wanted: Long? = null

    @Volatile
    private var started = false
    private var job: Job? = null
    private val restarts = ArrayDeque<Long>()

    /** How often a remote server is asked whether it is still there, and how long between attempts when it is not. */
    var healthIntervalMs: Long = 20_000
    var retryIntervalMs: Long = 5_000

    init {
        // A managed server that ends by itself is started again, a few times; then it is reported.
        scope.launch(worker) {
            manager.state.collect { managed ->
                val current = session
                if (managed is ServerState.Failed && current != null && !current.remote && current.profileId == managed.profileId && wanted == managed.profileId) {
                    session = null
                    val now = System.currentTimeMillis()
                    restarts.addLast(now)
                    while (restarts.isNotEmpty() && now - restarts.first() > RESTART_WINDOW_MS) restarts.removeFirst()
                    if (restarts.size <= MAX_RESTARTS) {
                        _state.value = MangaConnection.Failed(managed.profileId, managed.message, managed.log, remote = false, retrying = true)
                        delay(1_000L * restarts.size)
                        if (wanted == managed.profileId && session == null) connect(managed.profileId)
                    } else {
                        _state.value = MangaConnection.Failed(managed.profileId, managed.message + " It was started again several times and kept stopping.", managed.log, remote = false)
                    }
                }
            }
        }
    }

    val profileId: Long? get() = wanted

    /** True when the active profile uses a server of its own rather than the one DebridOne starts. */
    val isRemote: Boolean get() = session?.remote ?: (wanted?.let { remoteFor(it) } != null)

    /** The folder the managed server's Local source reads, or null when the server is someone else's computer. */
    fun localSourceDirectory(): Path? {
        val profile = wanted ?: return null
        return if (remoteFor(profile) != null) null else manager.localSourceDirectory(profile)
    }

    /** The data folder of the server DebridOne runs for the active profile, or null when it uses a server of its own. */
    fun managedDirectory(): Path? {
        val profile = wanted ?: return null
        return if (remoteFor(profile) != null) null else manager.directory(profile)
    }

    /** The Java program of the server DebridOne runs for the active profile, or null when it uses a server of its own. */
    fun managedJava(): Path? = if (managedDirectory() == null) null else manager.javaExecutable()

    /** The active profile changed. A server that is running is replaced at once; otherwise nothing starts yet. */
    fun activate(profileId: Long?) {
        if (wanted == profileId) return
        wanted = profileId
        if (profileId == null) {
            disconnect()
        } else if (started) {
            reconnect()
        }
    }

    /** Starts (or connects to) the active profile's server if that has not been asked for yet. */
    fun ensureStarted() {
        val profile = wanted ?: return
        val state = _state.value
        val settled = (state is MangaConnection.Ready && state.profileId == profile) || (state is MangaConnection.Connecting && state.profileId == profile) ||
            (state is MangaConnection.Failed && state.profileId == profile)
        if (!settled) reconnect()
    }

    /** Connects afresh: after the settings changed, or when the person asks for another try. */
    fun reconnect() {
        val profile = wanted ?: return
        started = true
        job?.cancel()
        job = scope.launch(worker) {
            restarts.clear()
            connect(profile)
        }
    }

    private fun disconnect() {
        job?.cancel()
        job = scope.launch(worker) {
            session = null
            _generation.update { it + 1 }
            runCatching { manager.stop() }
            _state.value = MangaConnection.Idle
        }
    }

    private suspend fun connect(profile: Long) {
        val remote = remoteFor(profile)
        session = null
        _generation.update { it + 1 }
        _state.value = MangaConnection.Connecting(profile, remote != null)
        try {
            val next = if (remote != null) {
                // Only one profile's own server runs; a profile that reads from elsewhere needs none.
                runCatching { manager.stop() }
                val endpoint = endpoint(remote)
                val transport = SuwayomiTransport(endpoint)
                val version = SuwayomiApi(transport).about().version
                Session(profile, transport, remote = true) to version
            } else {
                val endpoint = manager.start(profile)
                val version = (manager.state.value as? ServerState.Ready)?.version.orEmpty()
                Session(profile, SuwayomiTransport(endpoint), remote = false) to version
            }
            if (wanted != profile) return
            session = next.first
            _generation.update { it + 1 }
            _state.value = MangaConnection.Ready(profile, next.second, remote != null, next.first.transport.endpoint.baseUrl)
            if (remote != null) watch(next.first)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (wanted != profile) return
            val message = when (failure) {
                is SuwayomiUnavailable -> failure.message
                is SuwayomiException -> remoteProblem(failure)
                else -> failure.message
            } ?: "The manga server could not be reached."
            _state.value = MangaConnection.Failed(profile, message, manager.log(), remote != null, retrying = remote != null && failure is SuwayomiException && failure.kind != SuwayomiException.Kind.UNAUTHORIZED)
            if (remote != null && failure is SuwayomiException && failure.kind != SuwayomiException.Kind.UNAUTHORIZED) retry(profile)
        }
    }

    /** Keeps an eye on a remote server, and on its return when it goes away. */
    private fun watch(watched: Session) {
        scope.launch(Dispatchers.IO) {
            while (isActive && session === watched) {
                delay(healthIntervalMs)
                if (session !== watched) break
                val problem = try {
                    watched.api.about()
                    null
                } catch (failure: SuwayomiException) {
                    failure
                }
                if (problem != null && session === watched) {
                    session = null
                    _generation.update { it + 1 }
                    val again = problem.kind != SuwayomiException.Kind.UNAUTHORIZED
                    _state.value = MangaConnection.Failed(watched.profileId, remoteProblem(problem), emptyList(), remote = true, retrying = again)
                    if (again) retry(watched.profileId)
                    break
                }
            }
        }
    }

    private fun retry(profile: Long) {
        job = scope.launch(worker) {
            delay(retryIntervalMs)
            if (wanted == profile && session == null) connect(profile)
        }
    }

    private fun remoteProblem(failure: SuwayomiException): String = when (failure.kind) {
        SuwayomiException.Kind.UNAUTHORIZED -> "The server did not accept the user name and password."
        SuwayomiException.Kind.UNREACHABLE -> "The server cannot be reached. Check the address, and that the server is running."
        SuwayomiException.Kind.TIMEOUT -> "The server did not answer in time."
        SuwayomiException.Kind.PROTOCOL -> "What answers at that address is not a Suwayomi server (or is a version DebridOne does not understand)."
        SuwayomiException.Kind.SERVER -> failure.message ?: "The server reported an error."
    }

    /** The API of the connected server. Throws, with a sentence for the person, while there is none. */
    fun api(): SuwayomiApi = (session ?: throw notConnected()).api

    fun transport(): SuwayomiTransport = (session ?: throw notConnected()).transport

    val isReady: Boolean get() = session != null

    private fun notConnected(): SuwayomiException = SuwayomiException(
        SuwayomiException.Kind.UNREACHABLE,
        when (val state = _state.value) {
            is MangaConnection.Connecting -> "The manga server is still starting."
            is MangaConnection.Failed -> state.message
            else -> "The manga server is not running."
        },
    )

    override fun close() {
        wanted = null
        job?.cancel()
        session = null
        manager.close()
        _state.value = MangaConnection.Idle
    }

    companion object {
        private const val MAX_RESTARTS = 3
        private const val RESTART_WINDOW_MS = 5 * 60 * 1000L

        /**
         * Checks a remote address and turns it into an endpoint. Throws [SuwayomiUnavailable] with
         * the reason when the address cannot be used.
         */
        fun endpoint(remote: RemoteServer): ServerEndpoint {
            val text = remote.url.trim().trimEnd('/')
            val uri = runCatching { URI(text) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            val host = uri?.host
            if (uri == null || host.isNullOrBlank() || (scheme != "http" && scheme != "https")) {
                throw SuwayomiUnavailable("\"${remote.url}\" is not a server address. It should look like https://manga.example.org or http://192.168.1.20:4567.")
            }
            if (uri.userInfo != null) throw SuwayomiUnavailable("Put the user name and password in their own fields, not in the address.")
            val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
            if (scheme == "http" && remote.requireHttps && !loopback) {
                throw SuwayomiUnavailable("This address is not encrypted (http). Use an https address, or switch off \"Require a secure connection\" if the server is on your own network.")
            }
            return ServerEndpoint(text, remote.username?.takeIf { it.isNotBlank() }, remote.password, managed = false, timeoutSeconds = remote.timeoutSeconds.coerceIn(5, 300))
        }

        /** Tries a remote server once and returns its version. Throws with the reason when it does not work. */
        fun test(remote: RemoteServer): String = SuwayomiApi(SuwayomiTransport(endpoint(remote))).about().version
    }
}
