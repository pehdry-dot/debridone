package com.nuvio.app.features.player

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.EpisodeTarget
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamSimilarityReference
import com.nuvio.app.features.streams.StreamSubtitle
import com.nuvio.app.features.streams.StreamsEmptyStateReason
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transition to the next episode, driven the way the player drives it: the command the native
 * controls send for the "Next episode" button and for the pop-up, and automatic playback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NextEpisodeTransitionTest {
    private val episodes = listOf(
        MetaVideo(id = "tt1:1:3", title = "Three", season = 1, episode = 3),
        MetaVideo(id = "tt1:1:4", title = "Four", season = 1, episode = 4),
        MetaVideo(id = "tt1:1:5", title = "Five", season = 1, episode = 5),
        MetaVideo(id = "tt1:2:1", title = "Again", season = 2, episode = 1),
    )

    private fun stream(
        filename: String,
        addonId: String = "addon:one",
        addonName: String = "Addon One",
        url: String? = "https://cdn.example/$filename",
    ) = StreamItem(
        name = addonName,
        title = filename,
        url = url,
        addonName = addonName,
        addonId = addonId,
        behaviorHints = StreamBehaviorHints(filename = filename),
    )

    private val playing = stream("Show.Name.S01E04.1080p.WEB-DL.x265.GroupA.mkv")
    private val sibling = stream("Show.Name.S01E05.1080p.WEB-DL.x265.GroupA.mkv")
    private val lowerQuality = stream("Show.Name.S01E05.720p.WEBRip.GroupB.mkv", addonId = "addon:two", addonName = "Addon Two")
    private val bluRay = stream("Show.Name.S01E05.2160p.BluRay.GroupC.mkv", addonId = "addon:two", addonName = "Addon Two")
    private val previousEpisodeLookalike = stream("Show.Name.S01E04.1080p.WEB-DL.x265.GroupA.mkv", url = "https://cdn.example/again")

    @BeforeTest
    fun prepare() {
        NetworkStatusRepository.setConditionForTest(NetworkCondition.Online)
        PlayerStreamsRepository.clearAll()
    }

    @AfterTest
    fun reset() {
        NetworkStatusRepository.setConditionForTest(NetworkCondition.Unknown)
        PlayerStreamsRepository.clearAll()
    }

    private fun TestScope.runtime(
        season: Int = 1,
        episode: Int = 4,
        current: StreamItem? = playing,
        videos: List<MetaVideo> = episodes,
    ): PlayerScreenRuntime {
        val runtime = PlayerScreenRuntime(
            seriesPlayerArgs(season = season, episode = episode).copy(
                stream = current,
                sourceUrl = current?.url ?: "https://cdn.example/unknown",
                externalSubtitles = listOf(StreamSubtitle(url = "https://subs.example/e$episode.srt", language = "en")),
            ),
        )
        runtime.scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        runtime.playerMetaVideos = videos
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, season, episode)
        runtime.nextEpisodeInfo = next?.let {
            NextEpisodeInfo(
                videoId = it.id,
                season = it.season ?: 0,
                episode = it.episode ?: 0,
                title = it.title,
                thumbnail = null,
                overview = null,
                released = null,
                hasAired = true,
                isWatched = false,
                unairedMessage = null,
            )
        }
        return runtime
    }

    private fun answer(video: MetaVideo, vararg groups: AddonStreamGroup, loading: Boolean = groups.any { it.isLoading }) {
        PlayerStreamsRepository.setEpisodeStreamsForTest(
            type = "series",
            videoId = video.id,
            season = video.season,
            episode = video.episode,
            state = StreamsUiState(
                groups = groups.toList(),
                activeAddonIds = groups.map { it.addonId }.toSet(),
                isAnyLoading = loading,
                emptyStateReason = if (groups.isEmpty() && !loading) StreamsEmptyStateReason.NoStreamsFound else null,
            ),
        )
    }

    private fun group(vararg streams: StreamItem, id: String = streams.first().addonId, name: String = streams.first().addonName) =
        AddonStreamGroup(addonName = name, addonId = id, streams = streams.toList())

    private fun loadingGroup(id: String, name: String) =
        AddonStreamGroup(addonName = name, addonId = id, streams = emptyList(), isLoading = true)

    // --- identifying the next episode ---------------------------------------------------------

    @Test
    fun `the next episode is the following one, across a season boundary too`() {
        assertEquals("tt1:1:5", PlayerNextEpisodeRules.resolveNextEpisode(episodes, 1, 4)?.id)
        assertEquals("tt1:2:1", PlayerNextEpisodeRules.resolveNextEpisode(episodes, 1, 5)?.id)
        assertNull(PlayerNextEpisodeRules.resolveNextEpisode(episodes, 2, 1), "nothing follows the last episode")
    }

    // --- the manual button ----------------------------------------------------------------------

    @Test
    fun `the next button asks add-ons for the next episode, not the current one`() = runTest {
        val runtime = runtime()
        val before = PlayerStreamsRepository.episodeStreamsRequestsStarted

        assertTrue(runtime.handlePlayerControlsEvent("playNextEpisode", 0.0))
        advanceUntilIdle()

        assertEquals(before + 1, PlayerStreamsRepository.episodeStreamsRequestsStarted)
        val request = assertNotNull(PlayerStreamsRepository.lastEpisodeStreamsRequest)
        assertTrue(request.startsWith("series::tt1:1:5::1::5"), request)
        runtime.nextEpisodeAutoPlayJob?.cancel()
    }

    @Test
    fun `the next button starts the source closest to the one playing`() = runTest {
        val runtime = runtime()
        answer(episodes[2], group(lowerQuality, bluRay), group(previousEpisodeLookalike, sibling))

        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        advanceUntilIdle()

        assertEquals("tt1:1:5", runtime.activeVideoId)
        assertEquals(1, runtime.activeSeasonNumber)
        assertEquals(5, runtime.activeEpisodeNumber)
        assertEquals("Five", runtime.activeEpisodeTitle)
        assertEquals(sibling.url, runtime.activeSourceUrl)
        assertEquals(sibling, runtime.activeStream)
        assertNotEquals(playing.url, runtime.activeSourceUrl, "the next episode never reuses the current address")
        assertFalse(runtime.nextEpisodeAutoPlaySearching)
        assertNull(runtime.nextEpisodeAutoPlayCountdown)
        assertFalse(runtime.showEpisodesPanel)
    }

    @Test
    fun `subtitles of the previous episode are not carried over`() = runTest {
        val runtime = runtime()
        assertEquals(1, runtime.externalSubtitles.size)
        answer(episodes[2], group(sibling))

        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        advanceUntilIdle()

        assertEquals("tt1:1:5", runtime.activeVideoId)
        assertTrue(runtime.externalSubtitles.isEmpty(), "the new stream brought none, so none are attached")
    }

    @Test
    fun `progress of the episode being left is saved`() = runTest {
        val runtime = runtime()
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, durationMs = 1_500_000L, positionMs = 1_260_000L)
        answer(episodes[2], group(sibling))

        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        advanceUntilIdle()

        assertEquals("tt1:1:5", runtime.activeVideoId)
        val saved = WatchProgressRepository.progressForVideo(
            videoId = "tt1:1:4",
            parentMetaId = "tt1",
            seasonNumber = 1,
            episodeNumber = 4,
        )
        assertEquals(1_260_000L, assertNotNull(saved, "episode 4 has no saved progress").lastPositionMs)
        assertEquals(4, saved.episodeNumber)
    }

    // --- the pop-up -----------------------------------------------------------------------------

    @Test
    fun `the pop-up does the same as the button`() = runTest {
        val runtime = runtime()
        runtime.showNextEpisodeCard = true
        answer(episodes[2], group(lowerQuality, bluRay), group(sibling))

        // The card in the native controls sends this command; the Compose card calls the same function.
        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        advanceUntilIdle()

        assertEquals("tt1:1:5", runtime.activeVideoId)
        assertEquals(sibling.url, runtime.activeSourceUrl)
        assertFalse(runtime.showNextEpisodeCard)
    }

    @Test
    fun `the pop-up shows the episode that will play and that sources are being found`() = runTest {
        val runtime = runtime()
        runtime.showNextEpisodeCard = true
        answer(episodes[2], loadingGroup("addon:one", "Addon One"))

        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        runCurrent()

        assertEquals(5, runtime.nextEpisodeInfo?.episode)
        assertEquals(1, runtime.nextEpisodeInfo?.season)
        assertTrue(runtime.nextEpisodeAutoPlaySearching, "the card shows a loading state while add-ons answer")
        assertEquals("tt1:1:4", runtime.activeVideoId, "the current episode keeps playing meanwhile")

        answer(episodes[2], group(sibling))
        advanceUntilIdle()
        assertEquals("tt1:1:5", runtime.activeVideoId)
    }

    @Test
    fun `pressing again while sources are being found does not start a second search`() = runTest {
        val runtime = runtime()
        answer(episodes[2], loadingGroup("addon:one", "Addon One"))
        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        runCurrent()
        val job = runtime.nextEpisodeAutoPlayJob
        val requests = PlayerStreamsRepository.episodeStreamsRequestsStarted

        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        runtime.playNextEpisode()
        runCurrent()

        assertTrue(job === runtime.nextEpisodeAutoPlayJob)
        assertEquals(requests, PlayerStreamsRepository.episodeStreamsRequestsStarted)
        job?.cancel()
    }

    @Test
    fun `sources preloaded for the pop-up are not requested again`() = runTest {
        val runtime = runtime()
        runtime.preloadNextEpisodeSources()
        runCurrent()
        val afterPreload = PlayerStreamsRepository.episodeStreamsRequestsStarted

        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        advanceUntilIdle()

        assertEquals(afterPreload, PlayerStreamsRepository.episodeStreamsRequestsStarted)
        runtime.nextEpisodeAutoPlayJob?.cancel()
    }

    // --- season boundary ------------------------------------------------------------------------

    @Test
    fun `the last episode of a season continues with the first of the next`() = runTest {
        val finale = stream("Show.Name.S01E05.1080p.WEB-DL.x265.GroupA.mkv")
        val premiere = stream("Show.Name.S02E01.1080p.WEB-DL.x265.GroupA.mkv")
        val pilot = stream("Show.Name.S01E01.1080p.WEB-DL.x265.GroupA.mkv")
        val runtime = runtime(season = 1, episode = 5, current = finale)
        assertEquals("tt1:2:1", runtime.nextEpisodeInfo?.videoId)
        answer(episodes[3], group(pilot, premiere))

        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        advanceUntilIdle()

        assertEquals("tt1:2:1", runtime.activeVideoId)
        assertEquals(2, runtime.activeSeasonNumber)
        assertEquals(1, runtime.activeEpisodeNumber)
        assertEquals(premiere.url, runtime.activeSourceUrl)
    }

    @Test
    fun `there is nothing to do after the last episode`() = runTest {
        val runtime = runtime(season = 2, episode = 1, current = stream("Show.Name.S02E01.1080p.WEB-DL.x265.GroupA.mkv"))
        assertNull(runtime.nextEpisodeInfo)

        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        advanceUntilIdle()

        assertEquals("tt1:2:1", runtime.activeVideoId)
        assertNull(runtime.nextEpisodeAutoPlayJob)
    }

    // --- missing sources, wrong episodes, weak matches ---------------------------------------------

    @Test
    fun `missing sources leave the episode playing and open the source list`() = runTest {
        val runtime = runtime()
        answer(episodes[2])

        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        advanceUntilIdle()

        assertEquals("tt1:1:4", runtime.activeVideoId)
        assertEquals(playing.url, runtime.activeSourceUrl)
        assertTrue(runtime.showEpisodesPanel)
        assertEquals("tt1:1:5", runtime.episodeStreamsPanelState.selectedEpisode?.id)
        assertEquals(NextEpisodeManualReason.NoSources, runtime.nextEpisodeManualReason)
        assertFalse(runtime.nextEpisodeAutoPlaySearching)
    }

    @Test
    fun `a stream for the previous episode is never started, even as the only offer`() = runTest {
        val runtime = runtime()
        answer(episodes[2], group(previousEpisodeLookalike))

        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        advanceUntilIdle()

        assertEquals("tt1:1:4", runtime.activeVideoId)
        assertTrue(runtime.showEpisodesPanel, "the user is asked instead")
    }

    @Test
    fun `no sufficiently similar source means the user chooses`() = runTest {
        val runtime = runtime()
        answer(episodes[2], group(lowerQuality, bluRay))

        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        advanceUntilIdle()

        assertEquals("tt1:1:4", runtime.activeVideoId)
        assertTrue(runtime.showEpisodesPanel)
        assertEquals(NextEpisodeManualReason.NoAutomaticChoice, runtime.nextEpisodeManualReason)
    }

    @Test
    fun `the user's own automatic choice applies when nothing is similar, minus wrong episodes`() = runTest {
        val runtime = runtime()
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(
            streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
            streamAutoPlayTimeoutSeconds = 0,
        )
        answer(episodes[2], group(previousEpisodeLookalike), group(lowerQuality, bluRay))

        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        advanceUntilIdle()

        assertEquals("tt1:1:5", runtime.activeVideoId)
        assertEquals(lowerQuality.url, runtime.activeSourceUrl, "first stream that is for episode 5")
    }

    @Test
    fun `matching can be switched off`() = runTest {
        val runtime = runtime()
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(
            nextEpisodeMatchCurrentSource = false,
            streamAutoPlayPreferBingeGroup = false,
        )
        answer(episodes[2], group(sibling))

        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        advanceUntilIdle()

        assertEquals("tt1:1:4", runtime.activeVideoId)
        assertTrue(runtime.showEpisodesPanel, "manual source selection, as configured")
    }

    // --- timeouts --------------------------------------------------------------------------------

    @Test
    fun `an add-on that never answers ends in the source list, not in a wait without end`() = runTest {
        val manual = mutableListOf<NextEpisodeManualReason>()
        val searching = mutableListOf<Boolean>()
        answer(episodes[2], loadingGroup("addon:slow", "Slow"))

        val job = launchTransition(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            hardTimeoutMs = 20_000L,
            onManual = { _, reason -> manual += reason },
            onSearching = { searching += it },
        )
        advanceTimeBy(19_000L)
        assertTrue(manual.isEmpty())
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(listOf(NextEpisodeManualReason.TimedOut), manual)
        assertEquals(listOf(true, false), searching)
        assertTrue(job?.isCompleted == true)
    }

    @Test
    fun `when the usual add-on hangs a good match from another add-on is used at the timeout`() = runTest {
        val selected = mutableListOf<StreamItem>()
        val elsewhere = stream("Show.Name.S01E05.1080p.WEB-DL.x265.GroupA.mkv", addonId = "addon:two", addonName = "Addon Two")
        answer(episodes[2], group(elsewhere), loadingGroup("addon:one", "Addon One"))

        launchTransition(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            hardTimeoutMs = 20_000L,
            onSelected = { stream, _ -> selected += stream },
        )
        advanceTimeBy(15_000L)
        assertTrue(selected.isEmpty(), "still waiting for the add-on the current source came from")
        // The selection delay (3 s by default) plus the 20 s limit set above.
        advanceTimeBy(9_000L)
        runCurrent()

        assertEquals(listOf(elsewhere), selected)
    }

    @Test
    fun `a close match is taken at once while slower add-ons are still answering`() = runTest {
        val selected = mutableListOf<StreamItem>()
        answer(episodes[2], group(sibling), loadingGroup("addon:slow", "Slow"))

        launchTransition(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            onSelected = { stream, _ -> selected += stream },
        )
        runCurrent()

        assertEquals(listOf(sibling), selected)
    }

    // --- cancelling ------------------------------------------------------------------------------

    @Test
    fun `cancelling while sources are being found keeps the current episode`() = runTest {
        val runtime = runtime()
        runtime.showNextEpisodeCard = true
        answer(episodes[2], loadingGroup("addon:one", "Addon One"))
        runtime.handlePlayerControlsEvent("playNextEpisode", 0.0)
        runCurrent()
        assertTrue(runtime.nextEpisodeAutoPlaySearching)

        assertTrue(runtime.handlePlayerControlsEvent("cancelNextEpisode", 0.0))
        answer(episodes[2], group(sibling))
        advanceUntilIdle()

        assertEquals("tt1:1:4", runtime.activeVideoId)
        assertEquals(playing.url, runtime.activeSourceUrl)
        assertFalse(runtime.nextEpisodeAutoPlaySearching)
        assertNull(runtime.nextEpisodeAutoPlayJob)
        assertFalse(runtime.showNextEpisodeCard)
        assertTrue(runtime.nextEpisodeCardDismissed, "the pop-up does not come straight back")
    }

    // --- automatic playback ----------------------------------------------------------------------

    @Test
    fun `automatic playback counts down, can be cancelled, and otherwise starts the episode`() = runTest {
        val selected = mutableListOf<StreamItem>()
        val countdown = mutableListOf<Int?>()
        val sourceNames = mutableListOf<String?>()
        answer(episodes[2], group(sibling))

        val job = launchTransition(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            userInitiated = false,
            settings = PlayerSettingsUiState(streamAutoPlayNextEpisodeEnabled = true, nextEpisodeCountdownSeconds = 5),
            onSelected = { stream, _ -> selected += stream },
            onCountdown = { countdown += it },
            onSourceName = { sourceNames += it },
        )
        advanceTimeBy(3_500L)
        assertEquals(listOf(null, 5, 4, 3, 2), countdown)
        assertTrue("Addon One" in sourceNames, "the card names the source that was chosen")
        assertTrue(selected.isEmpty(), "nothing starts before the countdown ends")
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(listOf(sibling), selected)
        assertTrue(job?.isCompleted == true)

        // Cancelled during the countdown: nothing starts.
        selected.clear()
        val cancelled = launchTransition(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            userInitiated = false,
            settings = PlayerSettingsUiState(streamAutoPlayNextEpisodeEnabled = true, nextEpisodeCountdownSeconds = 5),
            onSelected = { stream, _ -> selected += stream },
        )
        advanceTimeBy(2_000L)
        cancelled?.cancel()
        advanceTimeBy(10_000L)
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `a press by the user starts without a countdown`() = runTest {
        val countdown = mutableListOf<Int?>()
        val selected = mutableListOf<StreamItem>()
        answer(episodes[2], group(sibling))

        launchTransition(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            userInitiated = true,
            settings = PlayerSettingsUiState(nextEpisodeCountdownSeconds = 10),
            onSelected = { stream, _ -> selected += stream },
            onCountdown = { countdown += it },
        )
        runCurrent()

        assertEquals(listOf(sibling), selected)
        assertTrue(countdown.filterNotNull().isEmpty())
    }

    @Test
    fun `automatic playback stays quiet without a connection, a press does not`() = runTest {
        NetworkStatusRepository.setConditionForTest(NetworkCondition.NoInternet)
        answer(episodes[2], group(sibling))
        val selected = mutableListOf<StreamItem>()

        val automatic = launchTransition(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            userInitiated = false,
            onSelected = { stream, _ -> selected += stream },
        )
        assertNull(automatic)
        assertTrue(selected.isEmpty())

        launchTransition(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            userInitiated = true,
            onSelected = { stream, _ -> selected += stream },
        )
        advanceUntilIdle()
        assertEquals(listOf(sibling), selected)
    }

    private fun launchTransition(
        scope: CoroutineScope,
        userInitiated: Boolean = true,
        settings: PlayerSettingsUiState = PlayerSettingsUiState(),
        hardTimeoutMs: Long = NEXT_EPISODE_HARD_TIMEOUT_MS,
        onSelected: (StreamItem, MetaVideo) -> Unit = { _, _ -> },
        onManual: (MetaVideo, NextEpisodeManualReason) -> Unit = { _, _ -> },
        onSearching: (Boolean) -> Unit = {},
        onCountdown: (Int?) -> Unit = {},
        onSourceName: (String?) -> Unit = {},
    ): Job? = scope.launchPlayerNextEpisodeAutoPlay(
        previousJob = null,
        nextEpisodeInfo = NextEpisodeInfo(
            videoId = "tt1:1:5", season = 1, episode = 5, title = "Five", thumbnail = null, overview = null,
            released = null, hasAired = true, isWatched = false, unairedMessage = null,
        ),
        allEpisodes = episodes,
        parentMetaId = "tt1",
        parentMetaType = "series",
        contentType = "series",
        settings = settings,
        currentStreamBingeGroup = null,
        onDownloadedEpisodeSelected = { _, _ -> },
        onEpisodeStreamSelected = onSelected,
        onManualSelectionRequired = onManual,
        onSearchingChanged = onSearching,
        onSourceNameChanged = onSourceName,
        onCountdownChanged = onCountdown,
        onNextEpisodeCardVisibleChanged = {},
        userInitiated = userInitiated,
        similarityReference = StreamSimilarityReference.of(playing, season = 1, episode = 4, episodeTitle = "Four"),
        hardTimeoutMs = hardTimeoutMs,
    )
}
