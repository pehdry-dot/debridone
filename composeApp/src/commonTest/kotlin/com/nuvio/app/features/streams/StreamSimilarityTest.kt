package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamSimilarityTest {
    private fun stream(
        filename: String? = null,
        addonId: String = "addon:one",
        addonName: String = "Addon One",
        title: String? = null,
        name: String? = null,
        url: String? = "https://cdn.example/${filename ?: "file"}",
        bingeGroup: String? = null,
        externalUrl: String? = null,
    ) = StreamItem(
        name = name,
        title = title,
        url = url,
        externalUrl = externalUrl,
        addonName = addonName,
        addonId = addonId,
        behaviorHints = StreamBehaviorHints(bingeGroup = bingeGroup, filename = filename),
    )

    private val current = stream("Show.Name.S01E04.1080p.WEB-DL.x265.GroupA.mkv")
    private val reference = StreamSimilarityReference.of(current, season = 1, episode = 4, episodeTitle = "Four")
    private val next = EpisodeTarget(season = 1, episode = 5)

    // --- release names -------------------------------------------------------------------

    @Test
    fun `reads the properties a release name states`() {
        val traits = ReleaseNameParser.parse("Show.Name.S01E04.2160p.AMZN.WEB-DL.DDP5.1.Atmos.DV.HDR10+.H.265-FLUX.mkv")
        assertEquals("2160p", traits.resolution)
        assertEquals("web-dl", traits.releaseType)
        assertEquals("hevc", traits.videoCodec)
        assertEquals(setOf("dv", "hdr10+", "hdr"), traits.hdr)
        assertEquals("eac3", traits.audio)
        assertEquals("5.1", traits.channels)
        assertEquals("flux", traits.group)
        assertTrue("show" in traits.nameTokens && "name" in traits.nameTokens)
        assertFalse(traits.nameTokens.any { it.startsWith("s01") }, "episode markers are not part of the name")
    }

    @Test
    fun `folds spelling variants together`() {
        assertEquals("hevc", ReleaseNameParser.parse("Show S01E01 720p HDTV x265").videoCodec)
        assertEquals("hevc", ReleaseNameParser.parse("Show.S01E01.HEVC.1080p").videoCodec)
        assertEquals("avc", ReleaseNameParser.parse("Show.S01E01.1080p.BluRay.H264").videoCodec)
        assertEquals("bluray", ReleaseNameParser.parse("Show.S01E01.1080p.BDRip.x264").releaseType)
        assertEquals("webrip", ReleaseNameParser.parse("Show.S01E01.720p.WEBRip.x264").releaseType)
        assertEquals("2160p", ReleaseNameParser.parse("Show S01E01 4K UHD BluRay").resolution)
        assertEquals("1080p", ReleaseNameParser.parse("Show - 1x01 - 1920x1080 x264").resolution)
    }

    @Test
    fun `finds the release group in the usual places`() {
        assertEquals("ntb", ReleaseNameParser.parse("Show.S01E04.1080p.WEB-DL.x264-NTb.mkv").group)
        assertEquals("groupa", ReleaseNameParser.parse("Show.Name.S01E04.1080p.WEB-DL.x265.GroupA").group)
        assertEquals("subsplease", ReleaseNameParser.parse("[SubsPlease] Show - 104 (1080p) [ABCD1234].mkv").group)
        assertNull(ReleaseNameParser.parse("Show.S01E04.1080p.WEB-DL").group, "WEB-DL is not a group called DL")
        assertNull(ReleaseNameParser.parse("Show S01E04").group, "a name without technical tags has no trailing group")
    }

    @Test
    fun `reads languages from words and flags`() {
        assertEquals(setOf("pt"), ReleaseNameParser.parse("Show.S01E04.1080p.WEB-DL.DUBLADO.x264").languages)
        assertEquals(setOf("dual", "pt"), ReleaseNameParser.parse("Show S01E04 1080p Dual Audio PT-BR").languages)
        assertEquals(setOf("en", "it"), ReleaseNameParser.parse("Show S01E04 1080p 🇬🇧 / 🇮🇹").languages)
    }

    // --- episode statements ----------------------------------------------------------------

    @Test
    fun `finds episode statements in their common forms`() {
        assertEquals(listOf(EpisodeMarker(1, 5)), EpisodeMarkers.find("Show.S01E05.1080p"))
        assertEquals(listOf(EpisodeMarker(1, 5, 6)), EpisodeMarkers.find("Show.S01E05E06.1080p"))
        assertEquals(listOf(EpisodeMarker(1, 5, 6)), EpisodeMarkers.find("Show.S01E05-E06.1080p"))
        assertEquals(listOf(EpisodeMarker(1, 5, 8)), EpisodeMarkers.find("Show S01E05-08 720p"))
        assertEquals(listOf(EpisodeMarker(1, 5)), EpisodeMarkers.find("Show.S01E05-720p.WEB"), "a resolution is not a range end")
        assertEquals(listOf(EpisodeMarker(2, 3)), EpisodeMarkers.find("Show - 2x03 - Title"))
        assertEquals(listOf(EpisodeMarker(1, 5)), EpisodeMarkers.find("Show Season 1 Episode 5"))
        assertEquals(listOf(EpisodeMarker(null, 7)), EpisodeMarkers.find("Show Ep 07 [720p]"))
        assertEquals(listOf(EpisodeMarker(null, 105)), EpisodeMarkers.find("[Group] Show - 105 [1080p]"))
        assertTrue(EpisodeMarkers.find("Show 1920x1080 x264").isEmpty(), "a frame size is not an episode")
        assertTrue(EpisodeMarkers.find("Show.S01.COMPLETE.1080p.BluRay").isEmpty(), "a season pack names no episode")
        assertTrue(EpisodeMarkers.find("Show - 2019 [1080p]").isEmpty(), "a year is not an episode")
    }

    @Test
    fun `validates the episode before anything else`() {
        fun check(vararg texts: String) = EpisodeValidator.check(texts.toList(), next, EpisodeTarget(1, 4))
        assertEquals(EpisodeCheck.MATCH, check("Show.S01E05.1080p.mkv"))
        assertEquals(EpisodeCheck.MATCH, check("Show.S01.1080p.BluRay", "Show.S01E05.1080p.BluRay.mkv"))
        assertEquals(EpisodeCheck.MATCH, check("Show.S01E04-E06.1080p.mkv"), "a multi-episode file that contains it")
        assertEquals(EpisodeCheck.MISMATCH, check("Show.S01E04.1080p.mkv"), "the episode that is playing now")
        assertEquals(EpisodeCheck.MISMATCH, check("Show.S02E05.1080p.mkv"), "the right number in the wrong season")
        assertEquals(EpisodeCheck.UNVERIFIED, check("Server 2 - 1080p"))
        assertEquals(EpisodeCheck.UNVERIFIED, check("Show.S01.COMPLETE.1080p"))
    }

    @Test
    fun `follows absolute numbering used by the current file`() {
        val referenceMarkers = EpisodeMarkers.find("[Group] Show - 104 [1080p].mkv")
        fun check(text: String) = EpisodeValidator.check(listOf(text), EpisodeTarget(5, 3), EpisodeTarget(5, 2), referenceMarkers)
        assertEquals(EpisodeCheck.MATCH, check("[Group] Show - 105 [1080p].mkv"))
        assertEquals(EpisodeCheck.MISMATCH, check("[Group] Show - 104 [1080p].mkv"))
        assertEquals(EpisodeCheck.MISMATCH, check("[Group] Show - 106 [1080p].mkv"))
    }

    // --- ranking ---------------------------------------------------------------------------

    @Test
    fun `prefers the source closest to the one playing`() {
        val same = stream("Show.Name.S01E05.1080p.WEB-DL.x265.GroupA.mkv")
        val lower = stream("Show.Name.S01E05.720p.WEBRip.GroupB.mkv")
        val bigger = stream("Show.Name.S01E05.2160p.BluRay.GroupC.mkv")

        val ranking = StreamSimilarityRanker.rank(reference, listOf(lower, bigger, same), next, nextEpisodeTitle = "Five")

        assertEquals(same, ranking.ranked.first().stream)
        assertTrue(ranking.ranked.first().score > 0.95, "score ${ranking.ranked.first().score}")
        val config = StreamSimilarityConfig()
        assertEquals(same, ranking.best(config)?.stream)
        assertEquals(same, ranking.immediate(config)?.stream)
        ranking.ranked.drop(1).forEach { other ->
            assertTrue(other.score < config.minimumScore, "${other.stream.behaviorHints.filename} scored ${other.score}")
        }
    }

    @Test
    fun `never offers the previous episode however similar its name`() {
        val previous = stream("Show.Name.S01E04.1080p.WEB-DL.x265.GroupA.mkv")
        val earlier = stream("Show.Name.S01E03.1080p.WEB-DL.x265.GroupA.mkv")
        val different = stream("Show.Name.S01E05.720p.HDTV.x264-OTHER.mkv", addonId = "addon:two", addonName = "Addon Two")

        val ranking = StreamSimilarityRanker.rank(reference, listOf(previous, earlier, different), next)

        assertEquals(2, ranking.rejectedWrongEpisode)
        assertEquals(listOf(different), ranking.ranked.map { it.stream })
        assertNull(ranking.best(StreamSimilarityConfig()), "a dissimilar stream is not chosen for the user either")
    }

    @Test
    fun `a playable stream outranks an unplayable twin`() {
        val externalOnly = stream("Show.Name.S01E05.1080p.WEB-DL.x265.GroupA.mkv", url = null, externalUrl = "https://site.example/watch")
        val playable = stream("Show.Name.S01E05.1080p.WEB-DL.x264.GroupA.mkv")

        val ranking = StreamSimilarityRanker.rank(
            reference = reference,
            candidates = listOf(externalOnly, playable),
            target = next,
            playability = { if (it.url == null) StreamPlayability.NOT_PLAYABLE else StreamPlayability.READY },
        )

        assertEquals(1, ranking.rejectedNotPlayable)
        assertEquals(playable, ranking.best(StreamSimilarityConfig())?.stream)
    }

    @Test
    fun `the same release from the same add-on wins over the same release elsewhere`() {
        val elsewhere = stream("Show.Name.S01E05.1080p.WEB-DL.x265.GroupA.mkv", addonId = "addon:two", addonName = "Addon Two")
        val sameAddon = stream("Show.Name.S01E05.1080p.WEB-DL.x265.GroupA.mkv")

        val ranking = StreamSimilarityRanker.rank(reference, listOf(elsewhere, sameAddon), next)

        assertEquals(listOf(sameAddon, elsewhere), ranking.ranked.map { it.stream })
        assertTrue(ranking.ranked[1].score >= StreamSimilarityConfig().minimumScore, "another add-on with the same release is still a good match")
    }

    @Test
    fun `a wrong language counts against a stream`() {
        val dubbedReference = StreamSimilarityReference.of(
            stream("Show.Name.S01E04.1080p.WEB-DL.DUBLADO.x264-GRP.mkv"), season = 1, episode = 4,
        )
        val english = stream("Show.Name.S01E05.1080p.WEB-DL.ENG.x264-GRP.mkv")
        val dubbed = stream("Show.Name.S01E05.1080p.WEB-DL.DUBLADO.x264-GRP.mkv")

        val ranking = StreamSimilarityRanker.rank(dubbedReference, listOf(english, dubbed), next)

        assertEquals(dubbed, ranking.ranked.first().stream)
        assertTrue(ranking.ranked.first().score - ranking.ranked.last().score > 0.2)
    }

    @Test
    fun `dolby vision without a fallback is not preferred over a plain twin`() {
        val dolbyOnly = stream("Show.Name.S01E05.1080p.WEB-DL.DV.x265.GroupA.mkv")
        val plain = stream("Show.Name.S01E05.1080p.WEB-DL.x265.GroupA.mkv")

        val ranking = StreamSimilarityRanker.rank(reference, listOf(dolbyOnly, plain), next)

        assertEquals(plain, ranking.ranked.first().stream)
    }

    @Test
    fun `binge group and the expected file name are strong signals`() {
        val bingeReference = StreamSimilarityReference.of(
            stream("Show.Name.S01E04.1080p.WEB-DL.x265.GroupA.mkv", bingeGroup = "one|1080p|GroupA"), season = 1, episode = 4,
        )
        val sibling = stream("Show.Name.S01E05.1080p.WEB-DL.x265.GroupA.mkv", bingeGroup = "one|1080p|GroupA")
        val lookalike = stream("Show.Name.S01E05.1080p.WEB-DL.x265.GroupA.PROPER.mkv", bingeGroup = "one|1080p|other")

        val ranking = StreamSimilarityRanker.rank(bingeReference, listOf(lookalike, sibling), next)

        assertEquals(sibling, ranking.ranked.first().stream)
        assertTrue("binge group" in ranking.ranked.first().agreements && "file name" in ranking.ranked.first().agreements)
    }

    @Test
    fun `works across a season boundary`() {
        val finale = stream("Show.Name.S01E10.1080p.WEB-DL.x265.GroupA.mkv")
        val finaleReference = StreamSimilarityReference.of(finale, season = 1, episode = 10)
        val premiere = stream("Show.Name.S02E01.1080p.WEB-DL.x265.GroupA.mkv")
        val firstEver = stream("Show.Name.S01E01.1080p.WEB-DL.x265.GroupA.mkv")

        val ranking = StreamSimilarityRanker.rank(finaleReference, listOf(firstEver, premiere), EpisodeTarget(2, 1))

        assertEquals(listOf(premiere), ranking.ranked.map { it.stream })
        assertEquals(1, ranking.rejectedWrongEpisode)
        assertTrue("file name" in ranking.ranked.first().agreements)
    }

    @Test
    fun `streams without file names are compared by what their labels state`() {
        val labelled = StreamSimilarityReference(
            addonId = "addon:one", addonName = "Addon One", bingeGroup = null, filename = null,
            texts = listOf("Server 2 - 1080p"), season = 1, episode = 4,
        )
        val sameServer = stream(title = "Server 2 - 1080p", url = "https://a.example/2")
        val otherServer = stream(title = "Server 5 - 480p", url = "https://a.example/5")

        val ranking = StreamSimilarityRanker.rank(labelled, listOf(otherServer, sameServer), next)

        assertEquals(sameServer, ranking.best(StreamSimilarityConfig())?.stream)
    }

    @Test
    fun `gives no answer when the current source states nothing to compare`() {
        val bare = StreamSimilarityReference(
            addonId = "addon:one", addonName = "Addon One", bingeGroup = null, filename = null,
            texts = listOf("Play"), season = 1, episode = 4,
        )
        val ranking = StreamSimilarityRanker.rank(bare, listOf(stream(title = "Play")), next)

        assertFalse(ranking.referenceIsUsable)
        assertNull(ranking.best(StreamSimilarityConfig()))
    }

    @Test
    fun `ranking is deterministic and keeps the add-ons' order between equals`() {
        val first = stream("Show.Name.S01E05.1080p.WEB-DL.x265.GroupA.mkv", url = "https://a.example/1")
        val second = stream("Show.Name.S01E05.1080p.WEB-DL.x265.GroupA.mkv", url = "https://a.example/2")
        val candidates = listOf(first, second)

        val once = StreamSimilarityRanker.rank(reference, candidates, next).ranked.map { it.stream.url }
        val again = StreamSimilarityRanker.rank(reference, candidates, next).ranked.map { it.stream.url }

        assertEquals(listOf("https://a.example/1", "https://a.example/2"), once)
        assertEquals(once, again)
    }

    @Test
    fun `weights are configurable`() {
        val sameGroupLowerResolution = stream("Show.Name.S01E05.720p.WEB-DL.x265.GroupA.mkv")
        val sameResolutionOtherGroup = stream("Show.Name.S01E05.1080p.WEB-DL.x265.GroupB.mkv")
        val candidates = listOf(sameGroupLowerResolution, sameResolutionOtherGroup)

        val groupFirst = StreamSimilarityConfig(weights = StreamSimilarityWeights(releaseGroup = 10.0, resolution = 0.5))
        val resolutionFirst = StreamSimilarityConfig(weights = StreamSimilarityWeights(releaseGroup = 0.5, resolution = 10.0))

        assertEquals(sameGroupLowerResolution, StreamSimilarityRanker.rank(reference, candidates, next, config = groupFirst).ranked.first().stream)
        assertEquals(sameResolutionOtherGroup, StreamSimilarityRanker.rank(reference, candidates, next, config = resolutionFirst).ranked.first().stream)
    }

    @Test
    fun `thresholds decide whether a match is reliable enough`() {
        val close = stream("Show.Name.S01E05.1080p.WEB-DL.x264.GroupB.mkv")
        val ranking = StreamSimilarityRanker.rank(reference, listOf(close), next)
        val score = assertNotNull(ranking.ranked.firstOrNull()).score

        assertNull(ranking.best(StreamSimilarityConfig(minimumScore = score + 0.01)))
        assertNotNull(ranking.best(StreamSimilarityConfig(minimumScore = score - 0.01)))
        assertNull(ranking.immediate(StreamSimilarityConfig(minimumScore = score - 0.01, immediateScore = score + 0.01)))
    }
}
