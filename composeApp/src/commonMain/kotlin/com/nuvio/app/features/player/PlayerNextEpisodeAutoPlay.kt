package com.nuvio.app.features.player

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.streams.EpisodeTarget
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamSimilarityConfig
import com.nuvio.app.features.streams.StreamSimilarityReference
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun PlayerScreenRuntime.isAtNextEpisodeThreshold(): Boolean {
    if (playbackSnapshotKey != activePlaybackKey || playbackSnapshot.isLoading ||
        !initialSeekApplied || isScrubbingTimeline || errorMessage != null ||
        isShortPlaceholderDuration(playbackSnapshot.durationMs)
    ) return false
    // Preload: trigger source fetch before the button appears
    if (playerSettingsUiState.preloadNextEpisodeSources && !nextEpisodePreloadTriggered && nextEpisodeInfo != null && !isNetworkOffline()) {
        val preloadLeadMs = maxOf(playerSettingsUiState.streamAutoPlayTimeoutSeconds.toLong() * 1_000L, 10_000L)
            .coerceAtMost(30_000L)
        val shouldPreloadByCard = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = playbackSnapshot.positionMs + preloadLeadMs,
            durationMs = playbackSnapshot.durationMs,
            skipIntervals = skipIntervals,
            thresholdMode = playerSettingsUiState.nextEpisodeThresholdMode,
            thresholdPercent = playerSettingsUiState.nextEpisodeThresholdPercent,
            thresholdMinutesBeforeEnd = playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
        )
        val shouldPreloadByEnd = playbackSnapshot.durationMs > 0L &&
            (playbackSnapshot.durationMs - playbackSnapshot.positionMs) <= preloadLeadMs
        if (shouldPreloadByCard || shouldPreloadByEnd) {
            preloadNextEpisodeSources()
        }
    }
    return playbackSnapshot.isEnded || PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = playbackSnapshot.positionMs,
        durationMs = playbackSnapshot.durationMs,
        skipIntervals = skipIntervals,
        thresholdMode = playerSettingsUiState.nextEpisodeThresholdMode,
        thresholdPercent = playerSettingsUiState.nextEpisodeThresholdPercent,
        thresholdMinutesBeforeEnd = playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
    )
}

internal fun PlayerScreenRuntime.cancelNextEpisodeAutoPlay() {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    nextEpisodeAutoPlayAutomatic = false
    nextEpisodeAutoPlaySearching = false
    nextEpisodeAutoPlaySourceName = null
    nextEpisodeAutoPlayCountdown = null
}

/** Why the next episode could not be started for the user and the source list is shown instead. */
internal enum class NextEpisodeManualReason {
    /** Automatic source selection is switched off and nothing resembled the current source. */
    NoAutomaticChoice,

    /** No add-on returned a stream for the next episode. */
    NoSources,

    /** Add-ons did not answer in time and nothing usable had arrived. */
    TimedOut,

    /** The chosen stream could not be turned into a playable address. */
    ResolveFailed,
}

/**
 * The one transition to the next episode. The "Next episode" button, the pop-up near the end of an
 * episode, the keyboard shortcut and automatic playback all end up here.
 *
 * It asks the enabled add-ons for the next episode's streams (never reusing the current address),
 * waits for them, chooses the stream closest to the one playing ([similarityReference]), and hands
 * it to [onEpisodeStreamSelected]. When nothing can be chosen reliably the user is asked.
 *
 * @param userInitiated the user pressed something. The request is then made even when the
 * connectivity probe reports offline (the probe can be wrong, and the user will see the outcome),
 * and playback starts without a countdown.
 */
internal fun CoroutineScope.launchPlayerNextEpisodeAutoPlay(
    previousJob: Job?,
    nextEpisodeInfo: NextEpisodeInfo?,
    allEpisodes: List<MetaVideo>,
    parentMetaId: String,
    parentMetaType: String,
    contentType: String?,
    settings: PlayerSettingsUiState,
    currentStreamBingeGroup: String?,
    onDownloadedEpisodeSelected: (DownloadItem, MetaVideo) -> Unit,
    onEpisodeStreamSelected: (StreamItem, MetaVideo) -> Unit,
    onManualSelectionRequired: (MetaVideo, NextEpisodeManualReason) -> Unit,
    onSearchingChanged: (Boolean) -> Unit,
    onSourceNameChanged: (String?) -> Unit,
    onCountdownChanged: (Int?) -> Unit,
    onNextEpisodeCardVisibleChanged: (Boolean) -> Unit,
    userInitiated: Boolean = true,
    similarityReference: StreamSimilarityReference? = null,
    similarityConfig: StreamSimilarityConfig = StreamSimilarityConfig(),
    hardTimeoutMs: Long = NEXT_EPISODE_HARD_TIMEOUT_MS,
): Job? {
    val nextVideoId = nextEpisodeInfo?.videoId ?: return null
    val nextVideo = allEpisodes.firstOrNull { video -> video.id == nextVideoId } ?: return null
    if (nextEpisodeInfo.hasAired != true) return null

    val downloadedNextEpisode = DownloadsRepository.findPlayableDownload(
        parentMetaId = parentMetaId,
        seasonNumber = nextVideo.season,
        episodeNumber = nextVideo.episode,
        videoId = nextVideo.id,
    )
    if (downloadedNextEpisode != null) {
        onDownloadedEpisodeSelected(downloadedNextEpisode, nextVideo)
        return null
    }

    // Offline: automatic playback is a network search, so skip it. A press by the user is tried
    // regardless; if the network really is down the source list says so.
    if (!userInitiated && isNetworkOffline()) return null

    previousJob?.cancel()
    onSearchingChanged(true)
    onSourceNameChanged(null)
    onCountdownChanged(null)

    val type = contentType ?: parentMetaType
    val shouldAutoSelectInManualMode =
        settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
            (
                settings.streamAutoPlayNextEpisodeEnabled ||
                    settings.streamAutoPlayPreferBingeGroup
                )

    val bingeGroupOnlyManualMode =
        shouldAutoSelectInManualMode &&
            (!settings.streamAutoPlayNextEpisodeEnabled ||
                !settings.streamAutoPlayNextEpisodeFallbackEnabled) &&
            settings.streamAutoPlayPreferBingeGroup

    val effectiveMode = if (shouldAutoSelectInManualMode) {
        StreamAutoPlayMode.FIRST_STREAM
    } else {
        settings.streamAutoPlayMode
    }
    val effectiveSource = if (shouldAutoSelectInManualMode) {
        StreamAutoPlaySource.ALL_SOURCES
    } else {
        settings.streamAutoPlaySource
    }
    val effectiveSelectedAddons = if (shouldAutoSelectInManualMode) {
        emptySet()
    } else {
        settings.streamAutoPlaySelectedAddons
    }
    val effectiveSelectedPlugins = if (shouldAutoSelectInManualMode) {
        emptySet()
    } else {
        settings.streamAutoPlaySelectedPlugins
    }
    val effectiveRegex = if (shouldAutoSelectInManualMode) {
        ""
    } else {
        settings.streamAutoPlayRegex
    }
    val preferredBingeGroup = if (settings.streamAutoPlayPreferBingeGroup) {
        currentStreamBingeGroup
    } else {
        null
    }
    val matchCurrentSource = settings.nextEpisodeMatchCurrentSource && similarityReference != null

    return launch {
        // Connectivity may have dropped between the request and the actual search.
        if (!userInitiated && isNetworkOffline()) {
            onSearchingChanged(false)
            return@launch
        }

        // A fresh request for the next episode. The repository ignores it only when the very same
        // request is already running or answered (the preload), so add-ons are not asked twice.
        PlayerStreamsRepository.loadEpisodeStreams(
            type = type,
            videoId = nextVideo.id,
            season = nextVideo.season,
            episode = nextVideo.episode,
        )

        if (effectiveMode == StreamAutoPlayMode.MANUAL && !matchCurrentSource) {
            onSearchingChanged(false)
            onNextEpisodeCardVisibleChanged(false)
            onManualSelectionRequired(nextVideo, NextEpisodeManualReason.NoAutomaticChoice)
            return@launch
        }

        val installedAddonNames = AddonRepository.uiState.value.addons
            .enabledAddons()
            .map { it.displayTitle }
            .toSet()
        val debridSettings = DebridSettingsRepository.snapshot()

        val timeoutSeconds = settings.streamAutoPlayTimeoutSeconds
        var autoSelectTriggered = false
        var selectedStream: StreamItem? = null
        val autoSelectSettled = CompletableDeferred<Unit>()

        fun settleAutoSelect() {
            if (!autoSelectSettled.isCompleted) {
                autoSelectSettled.complete(Unit)
            }
        }

        fun selectStream(stream: StreamItem) {
            autoSelectTriggered = true
            selectedStream = stream
            settleAutoSelect()
        }

        fun finishWithoutSelection() {
            autoSelectTriggered = true
            settleAutoSelect()
        }

        fun trySelectStream(streams: List<StreamItem>): StreamItem? =
            StreamAutoPlaySelector.selectAutoPlayStream(
                streams = streams,
                mode = effectiveMode,
                regexPattern = effectiveRegex,
                source = effectiveSource,
                installedAddonNames = installedAddonNames,
                selectedAddons = effectiveSelectedAddons,
                selectedPlugins = effectiveSelectedPlugins,
                preferredBingeGroup = preferredBingeGroup,
                preferBingeGroupInSelection = settings.streamAutoPlayPreferBingeGroup,
                bingeGroupOnly = bingeGroupOnlyManualMode,
                debridEnabled = debridSettings.canResolvePlayableLinks,
                activeResolverProviderId = debridSettings.activeResolverProviderId,
            )

        fun tryBingeGroupOnly(streams: List<StreamItem>): StreamItem? {
            if (preferredBingeGroup == null || !settings.streamAutoPlayPreferBingeGroup) return null
            return StreamAutoPlaySelector.selectAutoPlayStream(
                streams = streams,
                mode = effectiveMode,
                regexPattern = effectiveRegex,
                source = effectiveSource,
                installedAddonNames = installedAddonNames,
                selectedAddons = effectiveSelectedAddons,
                selectedPlugins = effectiveSelectedPlugins,
                preferredBingeGroup = preferredBingeGroup,
                preferBingeGroupInSelection = true,
                bingeGroupOnly = true,
                debridEnabled = debridSettings.canResolvePlayableLinks,
                activeResolverProviderId = debridSettings.activeResolverProviderId,
            )
        }

        val episodeTarget = EpisodeTarget(nextVideo.season, nextVideo.episode)
        val selectionCoordinator = NextEpisodeStreamSelectionCoordinator(
            selectAfterDelay = ::trySelectStream,
            selectPreferred = ::tryBingeGroupOnly,
            similarity = similarityReference?.takeIf { matchCurrentSource }?.let { reference ->
                NextEpisodeSimilaritySelector(
                    reference = reference,
                    target = episodeTarget,
                    nextEpisodeTitle = nextVideo.title,
                    config = similarityConfig,
                    playability = { stream ->
                        StreamAutoPlaySelector.playability(
                            stream = stream,
                            debridEnabled = debridSettings.canResolvePlayableLinks,
                            activeResolverProviderId = debridSettings.activeResolverProviderId,
                        )
                    },
                )
            },
            episodeTarget = episodeTarget,
            currentEpisode = similarityReference?.let { EpisodeTarget(it.season, it.episode) },
        )

        fun applySelectionDecision(decision: NextEpisodeStreamSelectionDecision) {
            if (autoSelectTriggered) return
            when (decision) {
                is NextEpisodeStreamSelectionDecision.Selected -> selectStream(decision.stream)
                NextEpisodeStreamSelectionDecision.ManualSelection -> finishWithoutSelection()
                NextEpisodeStreamSelectionDecision.Waiting -> Unit
            }
        }

        val innerJob = launch {
            PlayerStreamsRepository.episodeStreamsState.collectLatest { state ->
                applySelectionDecision(selectionCoordinator.onStreamsChanged(state))
            }
        }

        val timeoutMs = timeoutSeconds * 1_000L
        val isBoundedTimeout = timeoutSeconds in 1..30

        if (isBoundedTimeout) {
            withTimeoutOrNull(timeoutMs) { autoSelectSettled.await() }
        }
        applySelectionDecision(
            selectionCoordinator.onSelectionDelayElapsed(PlayerStreamsRepository.episodeStreamsState.value),
        )
        val completed = withTimeoutOrNull(hardTimeoutMs) { autoSelectSettled.await() }
        innerJob.cancel()
        val timedOut = completed == null && !autoSelectTriggered
        if (timedOut) {
            when (val decision = selectionCoordinator.onSearchTimedOut(PlayerStreamsRepository.episodeStreamsState.value)) {
                is NextEpisodeStreamSelectionDecision.Selected -> selectStream(decision.stream)
                else -> finishWithoutSelection()
            }
        }

        var resolveFailed = false
        val selected = selectedStream?.let { stream ->
            when (val result = DirectDebridPlaybackResolver.resolveToPlayableStream(stream, nextVideo.season, nextVideo.episode)) {
                is DirectDebridPlayableResult.Success -> result.stream
                else -> {
                    resolveFailed = true
                    result.toastMessage()?.let { NuvioToastController.show(it) }
                    PlayerStreamsRepository.loadEpisodeStreams(
                        type = type,
                        videoId = nextVideo.id,
                        season = nextVideo.season,
                        episode = nextVideo.episode,
                        forceRefresh = true,
                    )
                    null
                }
            }
        }
        onSearchingChanged(false)
        if (selected != null) {
            onSourceNameChanged((selected.name?.takeIf { it.isNotBlank() } ?: selected.addonName).trim())
            val countdownSeconds = if (userInitiated) 0 else settings.nextEpisodeCountdownSeconds.coerceIn(0, 30)
            for (i in countdownSeconds downTo 1) {
                onCountdownChanged(i)
                delay(1000)
            }
            onEpisodeStreamSelected(selected, nextVideo)
            onNextEpisodeCardVisibleChanged(false)
            onCountdownChanged(null)
            onSourceNameChanged(null)
        } else {
            val hasAnyStream = PlayerStreamsRepository.episodeStreamsState.value.groups.any { it.streams.isNotEmpty() }
            val reason = when {
                resolveFailed -> NextEpisodeManualReason.ResolveFailed
                !hasAnyStream && timedOut -> NextEpisodeManualReason.TimedOut
                !hasAnyStream -> NextEpisodeManualReason.NoSources
                else -> NextEpisodeManualReason.NoAutomaticChoice
            }
            onManualSelectionRequired(nextVideo, reason)
            onNextEpisodeCardVisibleChanged(false)
        }
    }
}

internal fun PlayerScreenRuntime.preloadNextEpisodeSources() {
    if (nextEpisodePreloadTriggered) return
    val nextEp = nextEpisodeInfo ?: return
    if (nextEp.hasAired != true) return
    val type = contentType ?: return
    // Offline: preloading streams is a network search; retry once connectivity returns.
    if (isNetworkOffline()) return

    nextEpisodePreloadTriggered = true
    nextEpisodePreloadJob?.cancel()
    nextEpisodePreloadJob = scope.launch {
        PlayerStreamsRepository.loadEpisodeStreams(
            type = type,
            videoId = nextEp.videoId,
            season = nextEp.season,
            episode = nextEp.episode
        )
    }
}

internal fun PlayerScreenRuntime.cancelNextEpisodePreload() {
    nextEpisodePreloadJob?.cancel()
    nextEpisodePreloadJob = null
    nextEpisodePreloadTriggered = false
}
