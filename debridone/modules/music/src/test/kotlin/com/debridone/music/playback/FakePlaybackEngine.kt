package com.debridone.music.playback

/** A scripted engine for queue logic tests: nothing plays until the test says so. */
class FakePlaybackEngine : MusicPlaybackEngine {
    data class Load(val token: Long, val uri: String, val startPositionMs: Long, val startPaused: Boolean)

    override val description = "fake"
    val loads = mutableListOf<Load>()
    val seeks = mutableListOf<Long>()
    var pausedNow = false
    var volumeLevel = 100
    var mutedNow = false
    var stops = 0
    var closed = false
    private var listener: PlaybackEngineListener? = null
    private var nextToken = 1L

    val lastLoad: Load get() = loads.last()

    override fun setListener(listener: PlaybackEngineListener?) {
        this.listener = listener
    }

    @Synchronized
    override fun load(uri: String, startPositionMs: Long, startPaused: Boolean): Long {
        val token = nextToken++
        loads += Load(token, uri, startPositionMs, startPaused)
        pausedNow = startPaused
        return token
    }

    override fun setPaused(paused: Boolean) {
        pausedNow = paused
        listener?.onPaused(paused)
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
    }

    override fun stop() {
        stops++
    }

    override fun setVolume(percent: Int) {
        volumeLevel = percent
    }

    override fun setMuted(muted: Boolean) {
        mutedNow = muted
    }

    override fun audioDevices() = listOf(AudioDevice("auto", "Autoselect device"))

    override fun setAudioDevice(id: String) = Unit

    override fun close() {
        closed = true
    }

    // --- what the test makes the "engine" report -------------------------------------------

    fun start(token: Long = lastLoad.token) = listener!!.onTrackStarted(token)

    fun position(ms: Long, token: Long = lastLoad.token) = listener!!.onPosition(token, ms)

    fun finish(token: Long = lastLoad.token) = listener!!.onTrackEnded(token, TrackEndReason.FINISHED, null)

    fun fail(message: String = "cannot open", token: Long = lastLoad.token) = listener!!.onTrackEnded(token, TrackEndReason.ERROR, message)

    /** Plays the current load from start to end, reporting progress every 500 ms of track time. */
    fun playThrough(durationMs: Long = 2_000) {
        val token = lastLoad.token
        start(token)
        var at = 0L
        while (at <= durationMs) {
            position(at, token)
            at += 500
        }
        finish(token)
    }
}
