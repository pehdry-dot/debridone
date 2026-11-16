package com.nuvio.app.features.player

import com.nuvio.app.features.streams.EpisodeCheck
import com.nuvio.app.features.streams.EpisodeMarkers
import com.nuvio.app.features.streams.EpisodeTarget
import com.nuvio.app.features.streams.EpisodeValidator
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamPlayability
import com.nuvio.app.features.streams.StreamRanking
import com.nuvio.app.features.streams.StreamSimilarityConfig
import com.nuvio.app.features.streams.StreamSimilarityRanker
import com.nuvio.app.features.streams.StreamSimilarityReference
import com.nuvio.app.features.streams.StreamsUiState

internal sealed interface NextEpisodeStreamSelectionDecision {
    data class Selected(val stream: StreamItem) : NextEpisodeStreamSelectionDecision
    data object Waiting : NextEpisodeStreamSelectionDecision
    data object ManualSelection : NextEpisodeStreamSelectionDecision
}

/**
 * DebridOne: ranks the next episode's streams against the source playing now.
 * See [StreamSimilarityRanker] for the rules.
 */
internal class NextEpisodeSimilaritySelector(
    val reference: StreamSimilarityReference,
    val target: EpisodeTarget,
    val nextEpisodeTitle: String? = null,
    val config: StreamSimilarityConfig = StreamSimilarityConfig(),
    val playability: (StreamItem) -> StreamPlayability = { StreamPlayability.READY },
) {
    fun rank(streams: List<StreamItem>): StreamRanking =
        StreamSimilarityRanker.rank(
            reference = reference,
            candidates = streams,
            target = target,
            nextEpisodeTitle = nextEpisodeTitle,
            config = config,
            playability = playability,
        )
}

/**
 * Decides, each time the list of streams for the next episode changes, whether one can be chosen
 * for the user, whether to keep waiting, or whether the user has to choose.
 *
 * Order of preference:
 *  1. a stream that closely matches the current source ([similarity]), taken as soon as it shows up;
 *  2. once the wait is over (or every add-on has answered), the best sufficiently similar stream;
 *  3. the user's own automatic-playback preferences ([selectAfterDelay], [selectPreferred]);
 *  4. otherwise the source list.
 *
 * A stream whose name states another episode is removed before any of these look at the list.
 */
internal class NextEpisodeStreamSelectionCoordinator(
    private val selectAfterDelay: (List<StreamItem>) -> StreamItem?,
    private val selectPreferred: (List<StreamItem>) -> StreamItem?,
    private val similarity: NextEpisodeSimilaritySelector? = null,
    private val episodeTarget: EpisodeTarget? = similarity?.target,
    private val currentEpisode: EpisodeTarget? = similarity?.let { EpisodeTarget(it.reference.season, it.reference.episode) },
) {
    private var selectionDelayElapsed = false
    private val referenceMarkers = similarity?.reference?.let { reference ->
        (listOfNotNull(reference.filename) + reference.texts)
            .flatMap { it.lineSequence().flatMap { line -> EpisodeMarkers.find(line) } }
    }.orEmpty()

    /** The ranking behind the most recent decision; null when similarity matching is off. */
    var lastRanking: StreamRanking? = null
        private set

    /** True when the most recent [NextEpisodeStreamSelectionDecision.Selected] came from similarity. */
    var selectedBySimilarity: Boolean = false
        private set

    fun onStreamsChanged(state: StreamsUiState): NextEpisodeStreamSelectionDecision = decide(state, final = false)

    fun onSelectionDelayElapsed(state: StreamsUiState): NextEpisodeStreamSelectionDecision {
        selectionDelayElapsed = true
        return decide(state, final = false)
    }

    /** The search ran out of time: choose from what has arrived, or hand over to the user. */
    fun onSearchTimedOut(state: StreamsUiState): NextEpisodeStreamSelectionDecision {
        selectionDelayElapsed = true
        return decide(state, final = true)
    }

    private fun decide(state: StreamsUiState, final: Boolean): NextEpisodeStreamSelectionDecision {
        val allStreams = state.groups.flatMap { it.streams }
        val streams = allStreams.filter(::isForWantedEpisode)
        val isLoading = !final && (state.isAnyLoading || state.groups.any { it.isLoading })
        selectedBySimilarity = false

        val ranking = similarity?.rank(streams)
        lastRanking = ranking
        if (similarity != null && ranking != null) {
            ranking.immediate(similarity.config)?.let {
                selectedBySimilarity = true
                return NextEpisodeStreamSelectionDecision.Selected(it.stream)
            }
            // The add-on the current source came from is the likeliest place for its sibling:
            // do not settle for less while it is still answering.
            val referenceAddonLoading = !final && state.groups.any {
                it.isLoading && it.addonId == similarity.reference.addonId
            }
            val mayFallBack = !isLoading || (selectionDelayElapsed && !referenceAddonLoading)
            if (mayFallBack) {
                ranking.best(similarity.config)?.let {
                    selectedBySimilarity = true
                    return NextEpisodeStreamSelectionDecision.Selected(it.stream)
                }
            } else if (selectionDelayElapsed) {
                selectPreferred(streams)?.let { return NextEpisodeStreamSelectionDecision.Selected(it) }
                return NextEpisodeStreamSelectionDecision.Waiting
            }
        }

        val selected = if (selectionDelayElapsed || !isLoading) {
            selectAfterDelay(streams)
        } else {
            selectPreferred(streams)
        }
        return when {
            selected != null -> NextEpisodeStreamSelectionDecision.Selected(selected)
            isLoading -> NextEpisodeStreamSelectionDecision.Waiting
            else -> NextEpisodeStreamSelectionDecision.ManualSelection
        }
    }

    private fun isForWantedEpisode(stream: StreamItem): Boolean {
        val target = episodeTarget ?: return true
        val texts = listOf(stream.behaviorHints.filename, stream.title, stream.description, stream.name)
        return EpisodeValidator.check(texts, target, currentEpisode, referenceMarkers) != EpisodeCheck.MISMATCH
    }
}
