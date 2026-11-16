package com.debridone.music.playback

import com.debridone.music.Fixtures
import com.debridone.music.awaitTrue
import com.debridone.music.requireLibmpv
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Drives the real libmpv with its silent (but real-time) audio output. */
class MpvAudioEngineTest {
    private sealed interface Event {
        data class Started(val token: Long) : Event
        data class Ended(val token: Long, val reason: TrackEndReason, val error: String?) : Event
        data class Position(val token: Long, val ms: Long) : Event
        data class Duration(val token: Long, val ms: Long) : Event
        data class Paused(val paused: Boolean) : Event
    }

    private val events = CopyOnWriteArrayList<Event>()
    private lateinit var engine: MpvAudioEngine

    @BeforeTest
    fun setUp() {
        requireLibmpv()
        engine = MpvAudioEngine(audioOutput = "null")
        engine.setListener(object : PlaybackEngineListener {
            override fun onTrackStarted(token: Long) { events += Event.Started(token) }
            override fun onTrackEnded(token: Long, reason: TrackEndReason, error: String?) { events += Event.Ended(token, reason, error) }
            override fun onPosition(token: Long, positionMs: Long) { events += Event.Position(token, positionMs) }
            override fun onDuration(token: Long, durationMs: Long) { events += Event.Duration(token, durationMs) }
            override fun onPaused(paused: Boolean) { events += Event.Paused(paused) }
        })
    }

    @AfterTest
    fun tearDown() {
        if (::engine.isInitialized) engine.close()
    }

    private fun positions(token: Long) = events.filterIsInstance<Event.Position>().filter { it.token == token }.map { it.ms }
    private fun lastPosition(token: Long) = positions(token).lastOrNull() ?: -1
    private fun ended(token: Long) = events.filterIsInstance<Event.Ended>().firstOrNull { it.token == token }
    private fun awaitStarted(token: Long) = awaitTrue(describe = { "track $token to start; events: ${events.takeLast(8)}" }) {
        ended(token)?.let { fail("track ended before starting: $it") }
        events.any { it is Event.Started && it.token == token }
    }

    @Test
    fun reportsItsVersion() {
        assertTrue(engine.description.contains("mpv"), engine.description)
    }

    @Test
    fun playsEveryClaimedFormatFromStartToEnd() {
        val failures = mutableListOf<String>()
        for ((label, file) in Fixtures.formats) {
            events.clear()
            val token = engine.load(file.toString())
            try {
                assertTrue(token > 0, "$label: no load token")
                awaitStarted(token)
                val startedAt = System.nanoTime()
                awaitTrue(describe = { "$label to finish; events: ${events.takeLast(6)}" }) { ended(token) != null }
                val playedForMs = (System.nanoTime() - startedAt) / 1_000_000
                val end = ended(token)!!
                assertEquals(TrackEndReason.FINISHED, end.reason, "$label ended with ${end.error}")
                val duration = events.filterIsInstance<Event.Duration>().lastOrNull { it.token == token }?.ms
                assertNotNull(duration, "$label: no duration reported")
                assertTrue(duration in 1_900..2_200, "$label: duration $duration ms")
                val seen = positions(token)
                assertTrue(seen.size >= 5, "$label: only ${seen.size} position updates")
                // mpv reports the position once per audio buffer, so the last report is a little
                // before the end. The silent output also counts its last buffers as played at once,
                // so a 2 s track ends after about 1.6 s of wall-clock time: real time, not a skip.
                assertTrue(seen.max() >= 1_400, "$label: playback only reached ${seen.max()} ms")
                assertTrue(playedForMs in 1_200..3_500, "$label: a 2 s track took $playedForMs ms")
                assertTrue(seen.zipWithNext().count { (a, b) -> b >= a } >= seen.size - 2, "$label: position did not advance steadily")
            } catch (e: AssertionError) {
                failures += "$label: ${e.message}"
            }
        }
        assertTrue(failures.isEmpty(), "Formats that did not play:\n" + failures.joinToString("\n"))
    }

    @Test
    fun aMissingFileEndsWithAnError() {
        val token = engine.load(Fixtures.root.resolve("does-not-exist.mp3").toString())
        awaitTrue(describe = { "error for a missing file; events: $events" }) { ended(token) != null }
        val end = ended(token)!!
        assertEquals(TrackEndReason.ERROR, end.reason)
        assertNotNull(end.error)
        assertTrue(events.none { it is Event.Started })
    }

    @Test
    fun aFileThatIsNotAudioEndsWithAnError() {
        val token = engine.load(Fixtures.firstAlbum.resolve("notes.txt").toString())
        awaitTrue(describe = { "error for a text file; events: $events" }) { ended(token) != null }
        assertEquals(TrackEndReason.ERROR, ended(token)!!.reason)
    }

    @Test
    fun pauseHoldsThePositionAndResumeContinues() {
        val token = engine.load(Fixtures.flac.toString())
        awaitStarted(token)
        awaitTrue(describe = { "playback to reach 300 ms" }) { lastPosition(token) >= 300 }

        engine.setPaused(true)
        awaitTrue(describe = { "pause to be reported" }) { events.lastOrNull { it is Event.Paused } == Event.Paused(true) }
        Thread.sleep(150)
        val heldAt = lastPosition(token)
        Thread.sleep(500)
        assertTrue(lastPosition(token) - heldAt <= 60, "position moved while paused: $heldAt -> ${lastPosition(token)}")
        assertEquals("yes", engine.property("pause"))

        engine.setPaused(false)
        awaitTrue(describe = { "playback to continue after resume" }) { lastPosition(token) >= heldAt + 300 }
        awaitTrue(describe = { "track to finish after resume" }) { ended(token)?.reason == TrackEndReason.FINISHED }
    }

    @Test
    fun seekMovesThePosition() {
        val token = engine.load(Fixtures.wav.toString(), startPaused = true)
        awaitStarted(token)
        engine.seekTo(1_500)
        awaitTrue(describe = { "seek to 1500 ms; positions ${positions(token).takeLast(5)}" }) { lastPosition(token) in 1_400..1_700 }
        engine.seekTo(200)
        awaitTrue(describe = { "seek back to 200 ms; positions ${positions(token).takeLast(5)}" }) { lastPosition(token) in 100..400 }
    }

    @Test
    fun canStartPartWayThroughAndPaused() {
        val token = engine.load(Fixtures.mp3.toString(), startPositionMs = 1_000, startPaused = true)
        awaitStarted(token)
        awaitTrue(describe = { "a first position; events ${events.takeLast(6)}" }) { lastPosition(token) >= 0 }
        Thread.sleep(400)
        assertTrue(lastPosition(token) in 900..1_200, "expected to be held near 1000 ms, was ${lastPosition(token)}")
        engine.setPaused(false)
        awaitTrue(describe = { "track to finish" }) { ended(token)?.reason == TrackEndReason.FINISHED }
        assertTrue(positions(token).min() >= 900, "played audio before the start position: ${positions(token).min()}")
    }

    @Test
    fun volumeAndMuteReachTheEngine() {
        engine.setVolume(35)
        assertEquals(35.0, engine.property("volume")!!.toDouble())
        engine.setVolume(250)
        assertEquals(100.0, engine.property("volume")!!.toDouble())
        engine.setMuted(true)
        assertEquals("yes", engine.property("mute"))
        engine.setMuted(false)
        assertEquals("no", engine.property("mute"))
    }

    @Test
    fun loadingANewTrackReplacesTheCurrentOne() {
        val first = engine.load(Fixtures.ogg.toString())
        awaitStarted(first)
        val second = engine.load(Fixtures.opus.toString())
        assertTrue(second != first)
        awaitStarted(second)
        assertEquals(TrackEndReason.STOPPED, ended(first)?.reason)
        awaitTrue(describe = { "second track to finish" }) { ended(second)?.reason == TrackEndReason.FINISHED }
    }

    @Test
    fun stopEndsPlaybackWithoutAnError() {
        val token = engine.load(Fixtures.aac.toString())
        awaitStarted(token)
        engine.stop()
        awaitTrue(describe = { "stop to end the track" }) { ended(token) != null }
        assertEquals(TrackEndReason.STOPPED, ended(token)!!.reason)
    }

    @Test
    fun listsAnAutomaticOutputDevice() {
        val devices = engine.audioDevices()
        assertTrue(devices.any { it.id == "auto" }, "devices: $devices")
        engine.setAudioDevice("auto")
    }

    @Test
    fun closingTwiceIsHarmless() {
        engine.close()
        engine.close()
        assertTrue(engine.audioDevices().isEmpty())
    }
}
