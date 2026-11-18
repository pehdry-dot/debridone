package com.debridone.manga.tracking

import com.debridone.manga.suwayomi.MangaServer
import com.debridone.manga.suwayomi.SuwayomiApi
import com.debridone.manga.suwayomi.SuwayomiException
import com.debridone.manga.suwayomi.SwTrackRecord
import com.debridone.manga.suwayomi.SwTracker
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One of the states a reading-list service has for a series ("Reading", "Completed", …). */
data class TrackStatus(val value: Int, val label: String)

/**
 * A reading-list service Suwayomi-Server can keep up to date (AniList, MyAnimeList, MangaUpdates,
 * Kitsu, Shikimori, Bangumi…). Which ones exist is the server's; DebridOne adds none of its own.
 */
data class Tracker(
    val id: Int,
    val name: String,
    /** A path of the server for the tracker's icon. */
    val iconPath: String,
    val signedIn: Boolean,
    /** Set for a tracker that signs in on its own web page rather than with a user name and password. */
    val browserSignInUrl: String?,
    /** The sign-in ran out and has to be done again. */
    val signInExpired: Boolean,
    val statuses: List<TrackStatus>,
    /** The scores the tracker accepts, as it writes them. */
    val scores: List<String>,
    val canDeleteRemoteEntry: Boolean,
    val supportsPrivateEntries: Boolean,
) {
    val signsInWithBrowser: Boolean get() = browserSignInUrl != null
}

/** A series' entry on one tracker. */
data class TrackEntry(
    val recordId: Int,
    val seriesId: Long,
    val trackerId: Int,
    val title: String,
    val url: String,
    val status: Int,
    val lastChapterRead: Double,
    /** 0 when the tracker does not know. */
    val totalChapters: Int,
    val score: String,
    val private: Boolean,
)

/** What a tracker found for a title, to choose the right entry from. */
data class TrackCandidate(
    val trackerId: Int,
    val remoteId: String,
    val title: String,
    val coverUrl: String,
    val summary: String,
    val publishingStatus: String,
    val type: String,
    val started: String,
    val totalChapters: Int,
    val url: String,
)

/**
 * A sign-in that is finished in the person's browser. [address] is what to open there. When the
 * tracker sends the browser back, DebridOne hands the result to the server; [outcome] then
 * completes, with the reason when the server did not accept it. [close] gives up.
 */
class BrowserSignIn internal constructor(
    val trackerId: Int,
    val address: String,
    val outcome: CompletableFuture<Unit>,
    private val listener: HttpServer,
) : AutoCloseable {
    override fun close() {
        runCatching { listener.stop(0) }
        outcome.completeExceptionally(java.util.concurrent.CancellationException("The sign-in was cancelled."))
    }
}

/**
 * Tracking, exactly as Suwayomi-Server does it: the server holds the sign-ins (in the profile's
 * own data folder), links series to entries, and reports progress as chapters are read. DebridOne
 * shows it and passes the person's choices on; it keeps no tracker account of its own.
 */
class MangaTrackers(private val server: MangaServer) {
    private val api: SuwayomiApi get() = server.api()

    private val _revision = MutableStateFlow(0L)

    /** Changes when a sign-in or a series' entries changed. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun changed() = _revision.update { it + 1 }

    fun list(): List<Tracker> = api.trackers().map { it.toTracker() }.sortedBy { it.name.lowercase() }

    /** Signs in to a tracker that takes a user name and password. Throws with the reason when it is refused. */
    fun signIn(trackerId: Int, username: String, password: String) {
        require(username.isNotBlank() && password.isNotEmpty()) { "Enter the user name and the password." }
        val accepted = api.loginTrackerCredentials(trackerId, username.trim(), password)
        changed()
        if (!accepted) throw SuwayomiException(SuwayomiException.Kind.UNAUTHORIZED, "The tracker did not accept that user name and password.")
    }

    /**
     * Finishes a browser sign-in from the address the browser ended up at (the fallback when the
     * browser could not be sent back to DebridOne by itself).
     */
    fun completeBrowserSignIn(trackerId: Int, endedAt: String) {
        val address = endedAt.trim()
        require(Regex("[?#&](access_token|code)=").containsMatchIn(address)) {
            "That address carries no sign-in. Copy the whole address the browser shows after you allowed access."
        }
        val accepted = api.loginTrackerOAuth(trackerId, address)
        changed()
        if (!accepted) throw SuwayomiException(SuwayomiException.Kind.UNAUTHORIZED, "The tracker did not accept the sign-in.")
    }

    /**
     * Starts a sign-in on the tracker's own page. The tracker sends the browser to Suwayomi's
     * landing page, which forwards it to the address named in `state`: a listener DebridOne opens
     * for this one sign-in, on this computer only, at an address nobody else knows. Whatever
     * arrives there is passed to the server, which checks it with the tracker.
     */
    fun beginBrowserSignIn(trackerId: Int, timeoutMinutes: Long = 10): BrowserSignIn {
        val tracker = api.trackers().firstOrNull { it.id == trackerId } ?: throw IllegalArgumentException("That tracker is not offered by the manga server.")
        val authUrl = tracker.authUrl ?: throw IllegalArgumentException("${tracker.name} signs in with a user name and password.")
        val secret = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val listener = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4)
        val outcome = CompletableFuture<Unit>()
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "debridone-tracker-sign-in").apply { isDaemon = true } }
        val redirect = "http://127.0.0.1:${listener.address.port}/$secret"
        listener.executor = executor
        listener.createContext("/") { exchange ->
            val asked = exchange.requestURI
            val ours = asked.path == "/$secret"
            val message = if (!ours) {
                "Nothing to see here."
            } else {
                try {
                    // Coming back without a sign-in means access was not allowed there: said plainly, without asking the server.
                    if (!Regex("(^|&)(access_token|code)=").containsMatchIn(asked.rawQuery.orEmpty())) {
                        throw SuwayomiException(SuwayomiException.Kind.UNAUTHORIZED, "${tracker.name} sent the browser back without a sign-in: access was not allowed on its page")
                    }
                    val accepted = api.loginTrackerOAuth(trackerId, redirect + (asked.rawQuery?.let { "?$it" } ?: ""))
                    changed()
                    if (!accepted) throw SuwayomiException(SuwayomiException.Kind.UNAUTHORIZED, "${tracker.name} did not accept the sign-in.")
                    outcome.complete(Unit)
                    "You are signed in to ${tracker.name}. You can close this tab and go back to DebridOne."
                } catch (failure: Exception) {
                    outcome.completeExceptionally(failure)
                    "The sign-in did not work: ${failure.message ?: "unknown reason"}. Go back to DebridOne and try again."
                }
            }
            val body = "<!doctype html><meta charset=\"utf-8\"><title>DebridOne</title><body style=\"font-family:sans-serif;margin:3em\"><p>${escape(message)}</p></body>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.responseHeaders.add("Referrer-Policy", "no-referrer")
            exchange.sendResponseHeaders(if (ours) 200 else 404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        listener.start()
        // Used, abandoned or forgotten, the listener goes. It is stopped from a thread of its own:
        // the outcome completes inside the request that brought it, which still has its answer to send.
        outcome.orTimeout(timeoutMinutes, TimeUnit.MINUTES).whenComplete { _, _ ->
            Thread({
                runCatching { listener.stop(1) }
                executor.shutdown()
            }, "debridone-tracker-sign-in-end").apply { isDaemon = true }.start()
        }
        val state = buildJsonObject {
            put("redirectUrl", redirect)
            put("clientName", "DebridOne")
            put("trackerId", trackerId)
        }.toString()
        val separator = if ('?' in authUrl) '&' else '?'
        return BrowserSignIn(trackerId, authUrl + separator + "state=" + URLEncoder.encode(state, Charsets.UTF_8), outcome, listener)
    }

    fun signOut(trackerId: Int) {
        api.logoutTracker(trackerId)
        changed()
    }

    /** Looks a title up on a tracker the person is signed in to. */
    fun search(trackerId: Int, query: String): List<TrackCandidate> =
        api.searchTracker(trackerId, query.trim()).map {
            TrackCandidate(it.trackerId, it.remoteId, it.title, it.coverUrl, it.summary, it.publishingStatus, it.publishingType, it.startDate, it.totalChapters, it.trackingUrl)
        }

    /** The series' entries, one per tracker it is linked to. */
    fun entries(seriesId: Long): List<TrackEntry> = api.trackRecords(seriesId.toInt()).map { it.toEntry() }

    /** Links the series to an entry of a tracker; the server then keeps that entry up to date as chapters are read. */
    fun bind(seriesId: Long, trackerId: Int, remoteId: String): TrackEntry =
        api.bindTrack(seriesId.toInt(), trackerId, remoteId).toEntry().also { changed() }

    /** Unlinks; with [deleteRemote] the entry is also removed from the tracker's list (where the tracker allows it). */
    fun unbind(recordId: Int, deleteRemote: Boolean = false) {
        api.unbindTrack(recordId, deleteRemote)
        changed()
    }

    fun update(recordId: Int, status: Int? = null, lastChapterRead: Double? = null, score: String? = null): TrackEntry? =
        api.updateTrack(recordId, status, lastChapterRead, score)?.toEntry().also { changed() }

    /** Tells the series' trackers now how far it has been read (the server also does so by itself while reading). */
    fun sendProgress(seriesId: Long) {
        api.trackProgress(seriesId.toInt())
        changed()
    }

    private fun SwTracker.toTracker() = Tracker(
        id = id, name = name, iconPath = icon, signedIn = loggedIn, browserSignInUrl = authUrl?.takeIf { it.isNotBlank() }, signInExpired = tokenExpired,
        statuses = statuses.map { TrackStatus(it.first, it.second) }, scores = scores,
        canDeleteRemoteEntry = supportsTrackDeletion, supportsPrivateEntries = supportsPrivateTracking,
    )

    private fun SwTrackRecord.toEntry() = TrackEntry(
        recordId = id, seriesId = mangaId.toLong(), trackerId = trackerId, title = title, url = remoteUrl, status = status,
        lastChapterRead = lastChapterRead, totalChapters = totalChapters, score = displayScore, private = private,
    )

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
