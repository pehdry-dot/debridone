package com.debridone.music.playback

/** Why the engine stopped playing a track. */
enum class TrackEndReason {
    /** Played to the end. */
    FINISHED,

    /** Replaced or stopped on request. */
    STOPPED,

    /** Could not be opened or decoded; see the accompanying message. */
    ERROR,
}

data class AudioDevice(val id: String, val description: String)

/**
 * Callbacks arrive on the engine's own thread. [token] identifies the `load` call the event belongs
 * to, so a late event of a track that was already replaced can be told apart from the current one.
 */
interface PlaybackEngineListener {
    fun onTrackStarted(token: Long)

    fun onTrackEnded(token: Long, reason: TrackEndReason, error: String?)

    fun onPosition(token: Long, positionMs: Long)

    fun onDuration(token: Long, durationMs: Long)

    fun onPaused(paused: Boolean)
}

/**
 * Plays one audio stream at a time. Queueing, shuffle and repeat live in [MusicPlayerController];
 * the engine only knows how to load, pause, seek and report.
 */
interface MusicPlaybackEngine : AutoCloseable {
    /** Human-readable engine name and version for diagnostics. */
    val description: String

    fun setListener(listener: PlaybackEngineListener?)

    /**
     * Replaces whatever is playing with [uri] (a file path or a URL). Returns the token that
     * later [PlaybackEngineListener.onTrackStarted] / [PlaybackEngineListener.onTrackEnded] calls
     * for this load will carry.
     */
    fun load(uri: String, startPositionMs: Long = 0, startPaused: Boolean = false): Long

    fun setPaused(paused: Boolean)

    fun seekTo(positionMs: Long)

    fun stop()

    /** 0-100. */
    fun setVolume(percent: Int)

    fun setMuted(muted: Boolean)

    fun audioDevices(): List<AudioDevice>

    /** `auto` follows the system default output. */
    fun setAudioDevice(id: String)
}
