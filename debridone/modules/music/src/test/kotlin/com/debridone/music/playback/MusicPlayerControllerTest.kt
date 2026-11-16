package com.debridone.music.playback

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Queue, shuffle, repeat and error logic, with a scripted engine so nothing depends on timing. */
class MusicPlayerControllerTest {
    private val fake = FakePlaybackEngine()

    /** The scripted engine, once the player has applied every command sent so far. */
    private val engine: FakePlaybackEngine
        get() {
            player.awaitIdle()
            return fake
        }
    private val played = mutableListOf<Triple<Long, Long, Boolean>>()
    private val errors = mutableListOf<PlaybackError>()
    private var sessionSaves = 0
    private val player = MusicPlayerController(
        fake,
        random = Random(7),
        observer = object : PlayerObserver {
            override fun onTrackPlayed(item: QueueItem, listenedMs: Long, completed: Boolean) {
                played += Triple(item.trackId, listenedMs, completed)
            }

            override fun onSessionChanged(state: PlayerState) {
                sessionSaves++
            }

            override fun onPlaybackError(error: PlaybackError) {
                errors += error
            }
        },
    )

    private fun tracks(count: Int) = (1..count).map { QueueItem(it.toLong(), "/music/$it.flac", "Track $it", durationMs = 2_000) }
    private val state get() = player.also { it.awaitIdle() }.state.value
    private fun titles(state: PlayerState) = state.order.map { state.queue[it].title }

    @AfterTest
    fun tearDown() = player.close()

    @Test
    fun startsIdleAndEmpty() {
        assertEquals(PlaybackStatus.IDLE, state.status)
        assertNull(state.current)
        assertFalse(state.hasNext)
    }

    @Test
    fun playQueueLoadsTheChosenTrack() {
        player.playQueue(tracks(3), startIndex = 1)
        assertEquals("Track 2", state.current?.title)
        assertEquals(PlaybackStatus.LOADING, state.status)
        assertEquals("/music/2.flac", engine.lastLoad.uri)
        engine.start()
        assertEquals(PlaybackStatus.PLAYING, state.status)
        engine.position(700)
        assertEquals(700, state.positionMs)
        assertEquals(listOf("Track 3"), state.upNext.map { it.title })
    }

    @Test
    fun advancesAutomaticallyAndEndsAfterTheLastTrack() {
        player.playQueue(tracks(3))
        repeat(3) {
            player.awaitIdle()
            engine.playThrough()
        }
        assertEquals(PlaybackStatus.ENDED, state.status)
        assertEquals(3, engine.loads.size)
        assertEquals(listOf(1L, 2L, 3L), played.map { it.first })
        assertTrue(played.all { it.third }, "every track should be reported as completed")
        assertTrue(played.all { it.second == 2_000L }, "listened time: $played")
        // The finished queue is rewound, so Play starts it again from the top.
        assertEquals("Track 1", state.current?.title)
        player.play()
        assertEquals("/music/1.flac", state.let { engine.lastLoad.uri })
    }

    @Test
    fun nextAndPreviousMoveThroughTheQueue() {
        player.playQueue(tracks(3))
        engine.start()
        player.next()
        assertEquals("Track 2", state.current?.title)
        engine.start()
        engine.position(500)
        player.previous()
        assertEquals("Track 1", state.current?.title)
        // "Next" on the last track of a non-repeating queue stays put.
        player.skipTo(2)
        player.next()
        assertEquals("Track 3", state.current?.title)
        assertFalse(state.hasNext)
    }

    @Test
    fun previousRestartsATrackThatIsWellUnderWay() {
        player.playQueue(tracks(2), startIndex = 1)
        engine.start()
        engine.position(4_500)
        player.previous()
        assertEquals("Track 2", state.current?.title)
        assertEquals(listOf(0L), engine.seeks)
        assertEquals(1, engine.loads.size)
    }

    @Test
    fun skippingPartWayReportsAnIncompleteListen() {
        player.playQueue(tracks(2))
        engine.start()
        listOf(0L, 500, 1_000, 1_500).forEach { engine.position(it) }
        player.next()
        assertEquals(listOf(Triple(1L, 1_500L, false)), state.let { played })
    }

    @Test
    fun seeksDoNotCountAsListening() {
        player.playQueue(tracks(1))
        engine.start()
        engine.position(0)
        engine.position(500)
        player.seekTo(1_800)
        player.awaitIdle()
        engine.position(1_800)
        engine.position(2_000)
        engine.finish()
        assertEquals(listOf(Triple(1L, 700L, true)), state.let { played })
    }

    @Test
    fun repeatOneReplaysTheSameTrack() {
        player.playQueue(tracks(2))
        player.setRepeat(RepeatMode.ONE)
        player.awaitIdle()
        engine.playThrough()
        assertEquals("Track 1", state.current?.title)
        assertEquals(2, engine.loads.size)
        assertEquals("/music/1.flac", engine.lastLoad.uri)
        engine.playThrough()
        assertEquals(listOf(1L, 1L), state.let { played.map { it.first } })
        // A manual skip still leaves the repeated track.
        player.next()
        assertEquals("Track 2", state.current?.title)
    }

    @Test
    fun repeatAllWrapsAround() {
        player.playQueue(tracks(2), startIndex = 1)
        player.setRepeat(RepeatMode.ALL)
        assertTrue(state.hasNext)
        engine.playThrough()
        assertEquals("Track 1", state.current?.title)
        assertEquals(PlaybackStatus.LOADING, state.status)
        player.previous()
        assertEquals("Track 2", state.current?.title)
    }

    @Test
    fun cycleRepeatGoesThroughAllModes() {
        player.cycleRepeat()
        assertEquals(RepeatMode.ALL, state.repeat)
        player.cycleRepeat()
        assertEquals(RepeatMode.ONE, state.repeat)
        player.cycleRepeat()
        assertEquals(RepeatMode.OFF, state.repeat)
    }

    @Test
    fun shuffleKeepsTheCurrentTrackAndPlaysEveryTrackOnce() {
        player.playQueue(tracks(8), startIndex = 3)
        engine.start()
        player.setShuffle(true)
        val shuffled = state
        assertEquals("Track 4", shuffled.current?.title)
        assertEquals(0, shuffled.orderPosition)
        assertEquals((0..7).toSet(), shuffled.order.toSet())
        assertTrue(shuffled.order != (0..7).toList(), "order was not shuffled: ${shuffled.order}")
        assertEquals(1, engine.loads.size, "turning shuffle on must not restart the track")

        val heard = mutableListOf(shuffled.current!!.trackId)
        repeat(7) {
            engine.playThrough()
            heard += state.current!!.trackId
        }
        assertEquals((1L..8L).toSet(), heard.toSet())

        player.setShuffle(false)
        assertEquals((0..7).toList(), state.order)
        assertEquals(heard.last(), state.current?.trackId)
    }

    @Test
    fun playQueueCanStartShuffled() {
        player.playQueue(tracks(6), startIndex = 2, shuffle = true)
        assertTrue(state.shuffle)
        assertEquals("Track 3", state.current?.title)
        assertEquals(0, state.orderPosition)
    }

    @Test
    fun queueEditing() {
        player.playQueue(tracks(3))
        engine.start()
        player.addToQueue(listOf(QueueItem(10, "/music/10.flac", "Added last")))
        player.playNext(listOf(QueueItem(11, "/music/11.flac", "Added next")))
        assertEquals(listOf("Track 1", "Added next", "Track 2", "Track 3", "Added last"), titles(state))

        player.moveInQueue(4, 1)
        assertEquals(listOf("Track 1", "Added last", "Added next", "Track 2", "Track 3"), titles(state))
        assertEquals("Track 1", state.current?.title)

        player.removeFromQueue(2)
        assertEquals(listOf("Track 1", "Added last", "Track 2", "Track 3"), titles(state))
        assertEquals(1, engine.loads.size, "editing upcoming tracks must not interrupt playback")

        // Removing the playing track moves on to the one that took its place.
        player.removeFromQueue(0)
        assertEquals(listOf("Added last", "Track 2", "Track 3"), titles(state))
        assertEquals("Added last", state.current?.title)
        assertEquals("/music/10.flac", engine.lastLoad.uri)
    }

    @Test
    fun movingTheCurrentTrackKeepsItCurrent() {
        player.playQueue(tracks(4), startIndex = 1)
        player.moveInQueue(1, 3)
        assertEquals("Track 2", state.current?.title)
        assertEquals(3, state.orderPosition)
        assertEquals(1, engine.loads.size)
    }

    @Test
    fun addingToAnEmptyQueueStartsPlayback() {
        player.addToQueue(tracks(2))
        assertEquals("Track 1", state.current?.title)
        assertEquals(1, engine.loads.size)
    }

    @Test
    fun clearingTheQueueStopsAndKeepsSettings() {
        player.setVolume(40)
        player.setRepeat(RepeatMode.ALL)
        player.playQueue(tracks(2))
        engine.start()
        player.clearQueue()
        assertTrue(state.queue.isEmpty())
        assertEquals(PlaybackStatus.IDLE, state.status)
        assertEquals(40, state.volume)
        assertEquals(RepeatMode.ALL, state.repeat)
        assertEquals(1, engine.stops)
    }

    @Test
    fun pauseAndResume() {
        player.playQueue(tracks(1))
        engine.start()
        player.togglePlayPause()
        assertEquals(PlaybackStatus.PAUSED, state.status)
        assertTrue(engine.pausedNow)
        player.togglePlayPause()
        assertEquals(PlaybackStatus.PLAYING, state.status)
        assertFalse(engine.pausedNow)
        assertEquals(1, engine.loads.size)
    }

    @Test
    fun volumeAndMute() {
        player.setVolume(130)
        assertEquals(100, state.volume)
        player.setVolume(25)
        assertEquals(25, engine.volumeLevel)
        player.toggleMute()
        assertTrue(state.muted)
        assertTrue(engine.mutedNow)
        player.toggleMute()
        assertFalse(state.muted)
    }

    @Test
    fun aBrokenTrackIsReportedAndSkipped() {
        player.playQueue(tracks(3))
        engine.fail("No such file")
        val afterSkip = player.let {
            com.debridone.music.awaitTrue(describe = { "skip to the next track" }) { it.state.value.current?.title == "Track 2" }
            state
        }
        assertEquals("Track 1", afterSkip.lastError?.title)
        assertEquals("No such file", afterSkip.lastError?.message)
        assertEquals(1, errors.size)
        engine.start()
        // The error stays visible until it is dismissed.
        assertNotNull(state.lastError)
        player.dismissError()
        assertNull(state.lastError)
    }

    @Test
    fun aQueueWhereNothingPlaysStopsInsteadOfLooping() {
        player.playQueue(tracks(3))
        player.setRepeat(RepeatMode.ALL)
        player.awaitIdle()
        repeat(3) { round ->
            com.debridone.music.awaitTrue(describe = { "load ${round + 1}" }) { engine.loads.size == round + 1 }
            player.awaitIdle()
            engine.fail()
        }
        Thread.sleep(700)
        assertEquals(PlaybackStatus.IDLE, state.status)
        assertEquals(3, engine.loads.size)
        assertEquals(3, errors.size)
    }

    @Test
    fun eventsOfAReplacedTrackAreIgnored() {
        player.playQueue(tracks(3))
        val first = engine.lastLoad.token
        engine.start(first)
        player.next()
        player.awaitIdle()
        // Late reports about the first track arrive after the second was requested.
        engine.position(1_900, first)
        engine.finish(first)
        assertEquals("Track 2", state.current?.title)
        assertEquals(0, state.positionMs)
        assertEquals(2, engine.loads.size)
    }

    @Test
    fun restoredSessionIsSilentUntilPlayIsPressed() {
        val queue = tracks(4)
        player.restore(queue, order = listOf(2, 0, 3, 1), orderPosition = 1, positionMs = 1_200, shuffle = true, repeat = RepeatMode.ALL, volume = 55, muted = true)
        val restored = state
        assertEquals("Track 1", restored.current?.title)
        assertEquals(1_200, restored.positionMs)
        assertEquals(PlaybackStatus.IDLE, restored.status)
        assertTrue(restored.shuffle)
        assertEquals(55, engine.volumeLevel)
        assertTrue(engine.mutedNow)
        assertTrue(engine.loads.isEmpty(), "restoring must not start playback")

        player.play()
        player.awaitIdle()
        assertEquals(FakePlaybackEngine.Load(1, "/music/1.flac", 1_200, false), engine.lastLoad)
    }

    @Test
    fun aDamagedSavedOrderFallsBackToQueueOrder() {
        player.restore(tracks(3), order = listOf(0, 0, 7), orderPosition = 9, positionMs = 0, shuffle = false, repeat = RepeatMode.OFF, volume = 100, muted = false)
        assertEquals(listOf(0, 1, 2), state.order)
        assertEquals("Track 3", state.current?.title)
    }

    @Test
    fun sessionChangesAreReported() {
        player.playQueue(tracks(2))
        player.setShuffle(true)
        player.setVolume(10)
        assertTrue(state.let { sessionSaves } >= 3)
    }

    @Test
    fun closingReportsTheInterruptedListenAndClosesTheEngine() {
        player.playQueue(tracks(1))
        engine.start()
        listOf(0L, 500, 1_000, 1_500).forEach { engine.position(it) }
        player.awaitIdle()
        player.close()
        assertEquals(listOf(Triple(1L, 1_500L, false)), played)
        assertTrue(engine.closed)
    }
}
