package com.debridone.music.playback

import com.debridone.music.Fixtures
import com.debridone.music.awaitState
import com.debridone.music.requireLibmpv
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The player on top of the real libmpv engine (silent output, real time). */
class MusicPlayerPlaybackTest {
    private val played = CopyOnWriteArrayList<Pair<String, Boolean>>()
    private lateinit var player: MusicPlayerController

    @BeforeTest
    fun setUp() {
        requireLibmpv()
        player = MusicPlayerController(
            MpvAudioEngine(audioOutput = "null"),
            observer = object : PlayerObserver {
                override fun onTrackPlayed(item: QueueItem, listenedMs: Long, completed: Boolean) {
                    played += item.title to completed
                }
            },
        )
    }

    @AfterTest
    fun tearDown() {
        if (::player.isInitialized) player.close()
    }

    @Test
    fun playsAWholeAlbumAcrossFormatsInOrder() {
        val album = Fixtures.albumFiles.map { Fixtures.item(it) }
        val seen = CopyOnWriteArrayList<String>()
        player.playQueue(album)
        player.awaitState(timeoutMs = 30_000, what = "the album to end") { state ->
            state.current?.title?.let { if (state.status == PlaybackStatus.PLAYING && seen.lastOrNull() != it) seen += it }
            state.status == PlaybackStatus.ENDED
        }
        val expected = album.map { it.title }
        assertEquals(expected, seen.toList(), "tracks observed playing")
        assertEquals(expected.map { it to true }, played.toList(), "history reports")
    }

    @Test
    fun pauseSeekAndResumeOnARealTrack() {
        player.playQueue(listOf(Fixtures.item(Fixtures.flac)))
        player.awaitState(what = "playback to pass 300 ms") { it.status == PlaybackStatus.PLAYING && it.positionMs >= 300 }
        assertTrue(player.state.value.durationMs in 1_900..2_200, "duration ${player.state.value.durationMs}")

        player.pause()
        player.awaitState(what = "pause") { it.status == PlaybackStatus.PAUSED }
        Thread.sleep(150)
        val held = player.state.value.positionMs
        Thread.sleep(400)
        assertTrue(player.state.value.positionMs - held <= 60, "moved while paused")

        player.seekTo(1_500)
        player.awaitState(what = "seek to 1500 ms") { it.positionMs in 1_400..1_700 }

        player.play()
        player.awaitState(what = "the track to finish") { it.status == PlaybackStatus.ENDED }
        assertEquals(listOf("Second Light" to true), played.toList())
    }

    @Test
    fun skipsAMissingFileAndPlaysTheNextOne() {
        val missing = QueueItem(99, Fixtures.root.resolve("gone.mp3").toString(), "Gone")
        player.playQueue(listOf(missing, Fixtures.item(Fixtures.ogg)))
        val state = player.awaitState(what = "the next track to play") { it.status == PlaybackStatus.PLAYING && it.current?.title == "Sixth Sense" }
        assertEquals("Gone", state.lastError?.title)
        player.awaitState(what = "the queue to end") { it.status == PlaybackStatus.ENDED }
    }

    @Test
    fun repeatOneLoopsARealTrack() {
        player.setRepeat(RepeatMode.ONE)
        player.playQueue(listOf(Fixtures.item(Fixtures.wav), Fixtures.item(Fixtures.mp3)))
        com.debridone.music.awaitTrue(timeoutMs = 12_000, describe = { "two full plays; got $played" }) { played.size >= 2 }
        assertEquals(listOf("Fifth Element" to true, "Fifth Element" to true), played.take(2))
        assertEquals("Fifth Element", player.state.value.current?.title)
    }

    @Test
    fun resumesARestoredSessionAtItsSavedPosition() {
        val queue = listOf(Fixtures.item(Fixtures.mp3), Fixtures.item(Fixtures.opus))
        player.restore(queue, order = listOf(0, 1), orderPosition = 1, positionMs = 1_000, shuffle = false, repeat = RepeatMode.OFF, volume = 80, muted = false)
        player.awaitIdle()
        assertEquals(PlaybackStatus.IDLE, player.state.value.status)
        Thread.sleep(300)
        assertEquals(1_000, player.state.value.positionMs)

        player.play()
        val playing = player.awaitState(what = "playback from the saved position") { it.status == PlaybackStatus.PLAYING && it.positionMs > 0 }
        assertEquals("Seventh Heaven", playing.current?.title)
        assertTrue(playing.positionMs >= 900, "started at ${playing.positionMs} ms instead of the saved position")
    }
}
