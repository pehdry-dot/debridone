package com.debridone.app.music

import com.debridone.music.MusicModule
import com.debridone.music.MusicPlayer
import com.debridone.music.library.Track
import com.debridone.music.playback.PlayerState
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Connects the Music player to macOS: the Now Playing widget, Control Centre, the lock screen and
 * the media keys. It goes through DebridOne's music bridge library, which only exists inside the
 * packaged macOS app; everywhere else this object does nothing.
 */
object MusicSystemIntegration {
    @Suppress("FunctionName")
    private interface Bridge : Library {
        fun debridone_music_mpv_api_version(): NativeLong
        fun debridone_music_set_command_handler(handler: CommandHandler?)
        fun debridone_music_activate_commands()
        fun debridone_music_deactivate_commands()
        fun debridone_music_set_now_playing(
            title: String?,
            artist: String?,
            album: String?,
            durationSeconds: Double,
            positionSeconds: Double,
            playing: Int,
            artworkPath: String?,
        )
        fun debridone_music_clear_now_playing()
    }

    private fun interface CommandHandler : Callback {
        fun invoke(command: Int, value: Double)
    }

    private const val COMMAND_PLAY = 1
    private const val COMMAND_PAUSE = 2
    private const val COMMAND_TOGGLE = 3
    private const val COMMAND_NEXT = 4
    private const val COMMAND_PREVIOUS = 5
    private const val COMMAND_SEEK = 6
    private const val COMMAND_STOP = 7

    private val bridge: Bridge? by lazy {
        val library = MusicAudio.bundledBridge() ?: return@lazy null
        runCatching {
            Native.load(library.toString(), Bridge::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        }.getOrNull()
    }

    /** Whether Now Playing and the media keys are connected in this build. */
    val isAvailable: Boolean get() = bridge != null

    /** libmpv's client API version as seen through the bridge, or null when there is no bridge. */
    fun bridgedMpvApiVersion(): Long? = runCatching { bridge?.debridone_music_mpv_api_version()?.toLong() }.getOrNull()

    @Volatile
    private var player: MusicPlayer? = null

    /** True while a video owns the media keys. */
    @Volatile
    private var videoActive = false

    // Kept in a field: the native side holds a pointer to it for the life of the process.
    private val handler = CommandHandler { command, value ->
        val target = player ?: return@CommandHandler
        if (videoActive) return@CommandHandler
        runCatching {
            when (command) {
                COMMAND_PLAY -> target.resume()
                COMMAND_PAUSE, COMMAND_STOP -> target.pause()
                COMMAND_TOGGLE -> target.togglePlayPause()
                COMMAND_NEXT -> target.next()
                COMMAND_PREVIOUS -> target.previous()
                COMMAND_SEEK -> target.seekTo((value * 1000).toLong())
            }
        }
    }

    private class Published(val trackId: Long, val playing: Boolean, val durationMs: Long, val positionMs: Long, val atNanos: Long, val title: String, val artworkPath: String?)

    private var published: Published? = null

    /** Starts following [module]'s player. Call once. */
    fun attach(module: MusicModule, scope: CoroutineScope) {
        val api = bridge ?: return
        runCatching { api.debridone_music_set_command_handler(handler) }
        scope.launch(Dispatchers.Default) {
            module.services.collectLatest { services ->
                player = services?.player
                val current = services?.player ?: return@collectLatest
                combine(current.state, current.nowPlaying) { state, track -> state to track }.collect { (state, track) ->
                    runCatching { publish(api, state, track) }
                }
            }
        }
    }

    private fun publish(api: Bridge, state: PlayerState, track: Track?) {
        val item = state.current
        if (item == null) {
            if (published != null) {
                published = null
                api.debridone_music_clear_now_playing()
            }
            return
        }
        if (videoActive) return
        val now = System.nanoTime()
        val last = published
        val artwork = track?.takeIf { it.id == item.trackId }?.artwork?.filePath ?: item.artworkPath
        // macOS advances the position itself from the playback rate, so it only needs to be told
        // again when something changes: another track, play/pause, a seek, or new details.
        val expected = last?.let { if (it.playing) it.positionMs + (now - it.atNanos) / 1_000_000 else it.positionMs }
        val changed = last == null || last.trackId != item.trackId || last.playing != state.isPlaying ||
            last.durationMs != state.durationMs || last.title != item.title || last.artworkPath != artwork ||
            (expected != null && abs(expected - state.positionMs) > 1_500)
        if (!changed) return
        if (last == null || (!last.playing && state.isPlaying)) api.debridone_music_activate_commands()
        api.debridone_music_set_now_playing(
            item.title, item.artist, item.album,
            state.durationMs / 1000.0, state.positionMs / 1000.0, if (state.isPlaying) 1 else 0, artwork,
        )
        published = Published(item.trackId, state.isPlaying, state.durationMs, state.positionMs, now, item.title, artwork)
    }

    /** A video is starting in Streaming: Music lets go of the media keys. */
    fun onVideoStarted() {
        videoActive = true
        published = null
        runCatching { bridge?.debridone_music_deactivate_commands() }
    }

    /** The video is over. If music is still queued it takes the media keys back. */
    fun onVideoEnded() {
        videoActive = false
        val api = bridge ?: return
        val current = player ?: return
        published = null
        if (current.state.value.current != null) {
            runCatching {
                api.debridone_music_activate_commands()
                publish(api, current.state.value, current.nowPlaying.value)
            }
        }
    }

    fun shutdown() {
        runCatching { bridge?.debridone_music_clear_now_playing() }
    }
}
