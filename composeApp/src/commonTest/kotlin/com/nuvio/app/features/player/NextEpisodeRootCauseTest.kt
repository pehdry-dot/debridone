package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.network.classifyNetworkCondition
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reason "Next episode" did nothing in DebridOne: with no account backend configured the app
 * classified a working connection as "servers unreachable", and the next-episode code treated that
 * as offline and returned before asking any add-on for streams.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NextEpisodeRootCauseTest {
    @AfterTest
    fun reset() {
        NetworkStatusRepository.setConditionForTest(NetworkCondition.Unknown)
        PlayerStreamsRepository.clearAll()
    }

    @Test
    fun `a working connection without an account backend is online`() {
        assertEquals(
            NetworkCondition.Online,
            classifyNetworkCondition(internetReachable = true, backendEndpointCount = 0, backendReachable = false),
        )
        assertEquals(
            NetworkCondition.ServersUnreachable,
            classifyNetworkCondition(internetReachable = true, backendEndpointCount = 1, backendReachable = false),
        )
        assertEquals(
            NetworkCondition.NoInternet,
            classifyNetworkCondition(internetReachable = false, backendEndpointCount = 0, backendReachable = false),
        )
    }

    @Test
    fun `pressing next requests the next episode's streams even when the probe says servers are unreachable`() = runTest {
        NetworkStatusRepository.setConditionForTest(NetworkCondition.ServersUnreachable)
        PlayerStreamsRepository.clearAll()
        val runtime = PlayerScreenRuntime(seriesPlayerArgs())
        runtime.scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        runtime.playerMetaVideos = listOf(
            MetaVideo(id = "tt1:1:4", title = "Four", season = 1, episode = 4),
            MetaVideo(id = "tt1:1:5", title = "Five", season = 1, episode = 5),
        )
        runtime.nextEpisodeInfo = NextEpisodeInfo(
            videoId = "tt1:1:5",
            season = 1,
            episode = 5,
            title = "Five",
            thumbnail = null,
            overview = null,
            released = null,
            hasAired = true,
            isWatched = false,
            unairedMessage = null,
        )

        runtime.playNextEpisode()
        advanceUntilIdle()

        // No add-on is installed in the test, so the search ends at once and the source list for
        // the next episode is shown. Before the fix nothing happened at all.
        assertTrue(runtime.showEpisodesPanel, "the request for the next episode was never made")
        assertEquals("tt1:1:5", runtime.episodeStreamsPanelState.selectedEpisode?.id)
        runtime.nextEpisodeAutoPlayJob?.cancel()
    }
}

internal fun seriesPlayerArgs(
    season: Int = 1,
    episode: Int = 4,
    providerAddonId: String? = "addon:one",
    providerName: String = "Addon One",
    streamTitle: String = "Source",
    bingeGroup: String? = null,
) = PlayerScreenArgs(
    profileId = 1,
    title = "Show Name",
    sourceUrl = "https://example.com/show.s01e04.mkv",
    sourceAudioUrl = null,
    sourceHeaders = emptyMap(),
    sourceResponseHeaders = emptyMap(),
    streamType = null,
    providerName = providerName,
    streamTitle = streamTitle,
    streamSubtitle = null,
    initialBingeGroup = bingeGroup,
    pauseDescription = null,
    onBack = {},
    onOpenInExternalPlayer = null,
    onOpenExternalUrl = null,
    modifier = Modifier,
    logo = null,
    poster = null,
    background = null,
    seasonNumber = season,
    episodeNumber = episode,
    episodeTitle = "Four",
    episodeThumbnail = null,
    contentType = "series",
    videoId = "tt1:$season:$episode",
    parentMetaId = "tt1",
    parentMetaType = "series",
    providerAddonId = providerAddonId,
    torrentInfoHash = null,
    torrentFileIdx = null,
    torrentFilename = null,
    torrentTrackers = emptyList(),
    initialPositionMs = 0L,
    initialProgressFraction = null,
    subtitleExtras = null,
)
