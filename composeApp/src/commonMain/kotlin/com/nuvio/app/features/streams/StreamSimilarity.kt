package com.nuvio.app.features.streams

/**
 * DebridOne: picks, among the streams offered for the next episode, the one that most resembles the
 * source that is playing now.
 *
 * Two rules come before any scoring:
 *  1. a stream whose own name says it is a different episode is never a candidate, however similar
 *     the rest of its name is;
 *  2. a stream that cannot be started automatically (see [StreamAutoPlaySelector.playability]) is
 *     never a candidate.
 *
 * What is left is scored with [StreamSimilarityWeights]. Only the properties the current source
 * actually states are counted, so the score is the share of comparable properties that agree
 * (0.0 to 1.0). Nothing here does I/O; the same input always gives the same ranking.
 */

/** What a release name states about the file, with the spelling variants folded together. */
data class ReleaseTraits(
    val resolution: String? = null,
    val releaseType: String? = null,
    val videoCodec: String? = null,
    val hdr: Set<String> = emptySet(),
    val audio: String? = null,
    val channels: String? = null,
    val languages: Set<String> = emptySet(),
    val group: String? = null,
    /** Words that are neither technical tags nor episode markers: mostly the show's name. */
    val nameTokens: Set<String> = emptySet(),
) {
    val technicalTagCount: Int
        get() = listOfNotNull(resolution, releaseType, videoCodec, audio).size + if (hdr.isEmpty()) 0 else 1
}

object ReleaseNameParser {
    private fun token(pattern: String) = Regex("(?<![a-z0-9])(?:$pattern)(?![a-z])")
    private fun word(pattern: String) = Regex("(?<![a-z0-9])(?:$pattern)(?![a-z0-9])")

    private val resolutionP = Regex("(?<![a-z0-9])(4320|2160|1440|1080|720|576|540|480|360)\\s?[pi](?![a-z0-9])")
    private val resolutionWxH = Regex("(?<!\\d)(\\d{3,4})\\s?x\\s?(\\d{3,4})(?!\\d)")
    private val resolution8k = word("8k")
    private val resolution4k = word("4k|uhd")
    private val resolutionFhd = word("fhd")

    private val releaseTypes = listOf(
        "remux" to word("remux|bdremux"),
        "bluray" to word("blu ?-?ray|bd ?-?rip|br ?-?rip|bdmv"),
        "web-dl" to word("web ?-?dl"),
        "webrip" to word("web ?-?rip"),
        "hdrip" to word("hd ?-?rip"),
        "hdtv" to word("hdtv|pdtv|tvrip|dsr"),
        "dvd" to word("dvd ?-?rip|dvd ?-?r|dvd"),
        "cam" to word("cam ?-?rip|hd ?-?cam|cam|telesync|hd ?-?ts|telecine"),
        "web-dl" to word("web"),
    )
    private val codecs = listOf(
        "hevc" to token("x ?265|h ?265|hevc"),
        "avc" to token("x ?264|h ?264|avc"),
        "av1" to word("av1"),
        "vp9" to word("vp9"),
        "xvid" to word("xvid|divx"),
        "mpeg2" to word("mpeg ?-?2"),
    )
    private val dolbyVision = word("dv|dovi|dolby ?vision")
    private val hdr10Plus = Regex("(?<![a-z0-9])hdr10(?:\\+| ?plus)")
    private val hdrPlain = token("hdr10|hdr|hlg")
    private val audios = listOf(
        "truehd" to token("true ?-?hd"),
        "dts-hd" to token("dts ?-?hd(?: ?ma)?|dts ?-?x|dts ?-?ma"),
        "dts" to token("dts"),
        "eac3" to token("ddp|dd\\+|e ?-?ac ?-?3"),
        "ac3" to token("dd|ac ?-?3|dolby ?digital"),
        "aac" to token("aac"),
        "opus" to token("opus"),
        "flac" to token("flac"),
        "mp3" to token("mp3"),
    )
    private val atmos = word("atmos")
    private val channelsPattern = Regex("(?<![\\d.])([1257])\\.([01])(?![\\d])")
    private val languageWords = listOf(
        "multi" to word("multi"),
        "dual" to word("dual(?: ?-?audio)?"),
        "en" to word("english|eng"),
        "pt" to word("portuguese|portugues|por|pt ?-?br|ptbr|dublado|nacional"),
        "es" to word("spanish|espanol|español|spa|latino|castellano"),
        "fr" to word("french|francais|français|fre|fra|vff|vfq|truefrench"),
        "de" to word("german|deutsch|ger|deu"),
        "it" to word("italian|italiano|ita"),
        "ja" to word("japanese|jpn|jap"),
        "ko" to word("korean|kor"),
        "ru" to word("russian|rus"),
        "hi" to word("hindi|hin"),
    )
    private val flagLanguages = mapOf(
        "gb" to "en", "us" to "en", "au" to "en", "ca" to "en",
        "br" to "pt", "pt" to "pt",
        "es" to "es", "mx" to "es", "ar" to "es",
        "fr" to "fr", "de" to "de", "it" to "it", "jp" to "ja", "kr" to "ko", "ru" to "ru", "in" to "hi",
    )
    private val containerExtensions = setOf("mkv", "mp4", "avi", "m4v", "webm", "mov", "wmv", "mpg", "mpeg", "ts", "m2ts")
    private val fileExtension = Regex("\\.([a-z0-9]{2,4})$")
    private val hyphenGroup = Regex("-([A-Za-z0-9]{2,})$")
    private val bracketGroup = Regex("^\\[([^\\]]{2,24})\\]")
    private val hexHash = Regex("^(?=.*\\d)(?=.*[a-f])[0-9a-f]{8,}$")
    private val statsStart = Regex("[\\uD83D\\uD83E\\u2699\\u23F1\\u2B07\\u2B06]")
    private val splitter = Regex("[^a-z0-9+]+")

    /**
     * @param ignoredTitles episode titles to leave out of [ReleaseTraits.nameTokens]; they differ
     * from one episode to the next and say nothing about the release.
     */
    fun parse(text: String, ignoredTitles: Collection<String> = emptyList()): ReleaseTraits {
        val original = text.trim()
        if (original.isEmpty()) return ReleaseTraits()
        val lower = original.lowercase()
        val flat = lower.replace(Regex("[._]"), " ")
        val consumed = mutableListOf<IntRange>()

        fun first(regex: Regex): MatchResult? = regex.find(flat)?.also { consumed += it.range }

        val resolution = resolutionP.find(flat)?.also { consumed += it.range }?.let { "${it.groupValues[1]}p" }
            ?: resolutionWxH.find(flat)?.also { consumed += it.range }?.let { heightToResolution(it.groupValues[2].toIntOrNull()) }
            ?: first(resolution8k)?.let { "4320p" }
            ?: first(resolution4k)?.let { "2160p" }
            ?: first(resolutionFhd)?.let { "1080p" }
        val releaseType = releaseTypes.firstNotNullOfOrNull { (name, regex) -> first(regex)?.let { name } }
        val videoCodec = codecs.firstNotNullOfOrNull { (name, regex) -> first(regex)?.let { name } }
        val hdr = buildSet {
            if (first(dolbyVision) != null) add("dv")
            if (first(hdr10Plus) != null) {
                add("hdr10+")
                add("hdr")
            }
            if (first(hdrPlain) != null) add("hdr")
        }
        val hasAtmos = first(atmos) != null
        val audio = audios.firstNotNullOfOrNull { (name, regex) -> first(regex)?.let { name } }
            ?: if (hasAtmos) "atmos" else null
        val channels = channelsPattern.find(lower)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
        val languages = buildSet {
            languageWords.forEach { (code, regex) -> if (first(regex) != null) add(code) }
            addAll(flagLanguages(original))
        }

        val ignored = ignoredTitles.flatMap { it.lowercase().split(splitter) }.filter { it.length > 1 }.toSet()
        val withoutEpisodeMarkers = EpisodeMarkers.strip(blank(flat, consumed))
        val nameTokens = withoutEpisodeMarkers.split(splitter)
            .filter { it.length > 1 }
            .filterNot { it in containerExtensions || it in ignored || hexHash.matches(it) || (it.all(Char::isDigit) && it.length > 4) }
            .toSet()

        return ReleaseTraits(
            resolution = resolution,
            releaseType = releaseType,
            videoCodec = videoCodec,
            hdr = hdr,
            audio = audio,
            channels = channels,
            languages = languages,
            group = group(original, technicalTags = listOfNotNull(resolution, releaseType, videoCodec, audio).size),
            nameTokens = nameTokens,
        )
    }

    /** The line of a stream's text that reads like a release name: the one with most technical tags. */
    fun releaseLine(texts: List<String?>): String? =
        texts.asSequence()
            .filterNotNull()
            .flatMap { it.lineSequence() }
            .map { line -> statsStart.find(line)?.let { line.substring(0, it.range.first) } ?: line }
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .map { it to parse(it).technicalTagCount }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

    private fun heightToResolution(height: Int?): String? = when (height) {
        null -> null
        in 2000..2400 -> "2160p"
        in 1000..1200 -> "1080p"
        in 700..800 -> "720p"
        in 400..600 -> "480p"
        else -> "${height}p"
    }

    private fun blank(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        val chars = text.toCharArray()
        ranges.forEach { range -> for (index in range) if (index in chars.indices) chars[index] = ' ' }
        return chars.concatToString()
    }

    /**
     * The release group: the scene suffix (`...x265-GROUP`), the fansub prefix (`[Group] Show`), or
     * the last word of a name that already carried its technical tags (`...WEB-DL.x265.GROUP`).
     */
    private fun group(original: String, technicalTags: Int): String? {
        var name = original.trim()
        fileExtension.find(name.lowercase())?.let { match ->
            if (match.groupValues[1] in containerExtensions) name = name.dropLast(match.value.length)
        }
        name = name.trimEnd(']', ')', ' ', '.')
        hyphenGroup.find(name)?.groupValues?.get(1)?.let { candidate ->
            if (!isTechnicalWord(candidate)) return candidate.lowercase()
        }
        bracketGroup.find(original.trim())?.groupValues?.get(1)?.trim()?.let { candidate ->
            if (candidate.isNotEmpty() && !isTechnicalWord(candidate)) return candidate.lowercase()
        }
        if (technicalTags >= 2) {
            val last = name.split('.', ' ', '_').lastOrNull { it.isNotBlank() } ?: return null
            if (last.length in 2..24 && last.all { it.isLetterOrDigit() } && last.any(Char::isLetter) &&
                !isTechnicalWord(last) && EpisodeMarkers.find(last).isEmpty()
            ) {
                return last.lowercase()
            }
        }
        return null
    }

    private fun isTechnicalWord(word: String): Boolean {
        val flat = word.lowercase().replace(Regex("[._]"), " ")
        if (flat.all(Char::isDigit)) return true
        if (flat in containerExtensions) return true
        if (flat in setOf("dl", "hd", "ma", "rip", "x", "h", "dual", "multi", "audio", "subs", "sub", "proper", "repack", "extended", "uncut", "internal", "complete")) return true
        val probes = listOf(resolutionP, resolution4k, resolution8k, resolutionFhd, dolbyVision, hdrPlain, atmos, channelsPattern) +
            releaseTypes.map { it.second } + codecs.map { it.second } + audios.map { it.second } + languageWords.map { it.second }
        return probes.any { regex -> regex.find(flat)?.let { it.range.first == 0 && it.range.last == flat.lastIndex } == true }
    }

    private fun flagLanguages(text: String): Set<String> {
        val found = mutableSetOf<String>()
        var index = 0
        val letters = StringBuilder()
        while (index < text.length - 1) {
            val high = text[index]
            val low = text[index + 1]
            if (high == '\uD83C' && low in '\uDDE6'..'\uDDFF') {
                letters.append('a' + (low - '\uDDE6'))
                index += 2
                if (letters.length == 2) {
                    flagLanguages[letters.toString()]?.let(found::add)
                    letters.clear()
                }
            } else {
                letters.clear()
                index += 1
            }
        }
        return found
    }
}

/** A season/episode statement found in a name. [season] is null when only an episode is given. */
data class EpisodeMarker(val season: Int?, val firstEpisode: Int, val lastEpisode: Int = firstEpisode) {
    fun covers(episode: Int): Boolean = episode in firstEpisode..lastEpisode
}

object EpisodeMarkers {
    private val seasonEpisode = Regex(
        "(?i)(?<![a-z0-9])s(\\d{1,3})[ ._-]?e(\\d{1,4})((?:[ ._-]?(?:e|-e?)\\d{1,4}(?![0-9pi]))*)",
    )
    private val rangeNumber = Regex("\\d{1,4}")
    private val crossForm = Regex("(?i)(?<![0-9a-z])(\\d{1,2})x(\\d{1,4})(?![0-9a-z])")
    private val worded = Regex("(?i)season[ ._-]?(\\d{1,3})[ ._,-]*episode[ ._-]?(\\d{1,4})")
    private val episodeOnly = Regex("(?i)(?<![a-z0-9])(?:episode|ep|e)[ ._-]?(\\d{1,4})(?![0-9a-z])")
    private val fansubAbsolute = Regex(" - (\\d{2,4})(?:v\\d)?(?=$|[ .\\[(])")

    /** Every episode statement in [text], most specific forms first. */
    fun find(text: String): List<EpisodeMarker> {
        if (text.isBlank()) return emptyList()
        val markers = mutableListOf<EpisodeMarker>()
        var rest = text
        fun consume(regex: Regex, build: (MatchResult) -> EpisodeMarker?) {
            val matches = regex.findAll(rest).toList()
            if (matches.isEmpty()) return
            matches.forEach { match -> build(match)?.let(markers::add) }
            rest = regex.replace(rest, " ")
        }
        consume(seasonEpisode) { match ->
            val season = match.groupValues[1].toIntOrNull() ?: return@consume null
            val first = match.groupValues[2].toIntOrNull() ?: return@consume null
            val last = rangeNumber.findAll(match.groupValues[3])
                .mapNotNull { it.value.toIntOrNull() }
                .filter { it > first && it - first <= 60 }
                .maxOrNull() ?: first
            EpisodeMarker(season, first, last)
        }
        consume(worded) { match ->
            val season = match.groupValues[1].toIntOrNull() ?: return@consume null
            val episode = match.groupValues[2].toIntOrNull() ?: return@consume null
            EpisodeMarker(season, episode)
        }
        consume(crossForm) { match ->
            val season = match.groupValues[1].toIntOrNull() ?: return@consume null
            val episode = match.groupValues[2].toIntOrNull() ?: return@consume null
            EpisodeMarker(season, episode)
        }
        consume(episodeOnly) { match -> match.groupValues[1].toIntOrNull()?.let { EpisodeMarker(null, it) } }
        consume(fansubAbsolute) { match ->
            match.groupValues[1].toIntOrNull()?.takeUnless { it in 1900..2099 }?.let { EpisodeMarker(null, it) }
        }
        return markers
    }

    /** [text] with its episode statements blanked out, for comparing names across episodes. */
    fun strip(text: String): String =
        listOf(seasonEpisode, worded, crossForm, episodeOnly, fansubAbsolute)
            .fold(text) { current, regex -> regex.replace(current, " ") }
}

enum class EpisodeCheck {
    /** The stream's own name states the wanted episode. */
    MATCH,

    /** The name states no episode; the add-on returned it for the wanted episode. */
    UNVERIFIED,

    /** The name states a different episode. Never selected automatically. */
    MISMATCH,
}

data class EpisodeTarget(val season: Int?, val episode: Int?)

object EpisodeValidator {
    /**
     * @param referenceMarkers the episode statements of the source playing now. They reveal
     * absolute numbering (a file called "- 104" while episode 2 of season 5 plays), in which case
     * the next file is expected to say 105.
     */
    fun check(
        candidateTexts: List<String?>,
        target: EpisodeTarget,
        current: EpisodeTarget? = null,
        referenceMarkers: List<EpisodeMarker> = emptyList(),
    ): EpisodeCheck {
        val wantedEpisode = target.episode ?: return EpisodeCheck.UNVERIFIED
        val markers = candidateTexts.filterNotNull().flatMap { it.lineSequence().flatMap { line -> EpisodeMarkers.find(line) } }
        if (markers.isEmpty()) return EpisodeCheck.UNVERIFIED

        val seasoned = markers.filter { it.season != null }
        if (seasoned.isNotEmpty()) {
            val wantedSeason = target.season
            return if (seasoned.any { (wantedSeason == null || it.season == wantedSeason) && it.covers(wantedEpisode) }) {
                EpisodeCheck.MATCH
            } else {
                EpisodeCheck.MISMATCH
            }
        }

        val unseasoned = markers.filter { it.season == null }
        if (unseasoned.any { it.covers(wantedEpisode) }) return EpisodeCheck.MATCH
        val currentEpisode = current?.episode
        val absoluteReference = referenceMarkers
            .filter { it.season == null && it.firstEpisode == it.lastEpisode }
            .map { it.firstEpisode }
            .firstOrNull { currentEpisode != null && it != currentEpisode }
        if (absoluteReference != null && unseasoned.any { it.covers(absoluteReference + 1) }) return EpisodeCheck.MATCH
        return EpisodeCheck.MISMATCH
    }
}

enum class StreamPlayability {
    /** Can start now: a direct address, a cached debrid file, a prepared server stream. */
    READY,

    /** Needs a resolving step that DebridOne performs itself (for example torrent playback). */
    RESOLVABLE,

    /** Cannot be started without the user: external page, disabled peer-to-peer, no debrid account. */
    NOT_PLAYABLE,
}

/** Relative importance of each property. Raise a weight to make that property matter more. */
data class StreamSimilarityWeights(
    val sameAddon: Double = 3.0,
    val bingeGroup: Double = 4.0,
    val expectedFilename: Double = 4.0,
    val name: Double = 2.5,
    val releaseGroup: Double = 2.5,
    val resolution: Double = 2.5,
    val videoCodec: Double = 1.0,
    val releaseType: Double = 1.5,
    val hdr: Double = 1.0,
    val audio: Double = 0.75,
    val channels: Double = 0.25,
    val language: Double = 1.5,
    val episodeVerified: Double = 1.5,
    val availability: Double = 1.0,
)

data class StreamSimilarityConfig(
    val weights: StreamSimilarityWeights = StreamSimilarityWeights(),
    /** A stream scoring below this is not similar enough to be chosen for the user. */
    val minimumScore: Double = 0.6,
    /** A stream scoring at least this is taken at once, without waiting for slower add-ons. */
    val immediateScore: Double = 0.85,
    /** How many properties, besides the add-on, must agree before a stream may be chosen. */
    val minimumAgreements: Int = 2,
)

/** What is known about the source that is playing now. */
data class StreamSimilarityReference(
    val addonId: String?,
    val addonName: String?,
    val bingeGroup: String?,
    val filename: String?,
    /** Name, title and description of the playing stream, as the add-on gave them. */
    val texts: List<String>,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String? = null,
) {
    companion object {
        fun of(stream: StreamItem, season: Int?, episode: Int?, episodeTitle: String? = null) = StreamSimilarityReference(
            addonId = stream.addonId,
            addonName = stream.addonName,
            bingeGroup = stream.behaviorHints.bingeGroup,
            filename = stream.behaviorHints.filename,
            texts = listOfNotNull(stream.title, stream.description, stream.name),
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
        )
    }
}

data class RankedStream(
    val stream: StreamItem,
    val score: Double,
    val episodeCheck: EpisodeCheck,
    val playability: StreamPlayability,
    /** The properties that agreed with the current source, for logs and tests. */
    val agreements: List<String>,
)

data class StreamRanking(
    /** Candidates, best first. Streams for another episode and unplayable streams are not in it. */
    val ranked: List<RankedStream>,
    val rejectedWrongEpisode: Int,
    val rejectedNotPlayable: Int,
    /** False when the current source states too little to compare anything against. */
    val referenceIsUsable: Boolean,
) {
    fun best(config: StreamSimilarityConfig): RankedStream? =
        ranked.firstOrNull()?.takeIf { it.score >= config.minimumScore && it.agreements.size >= config.minimumAgreements }

    fun immediate(config: StreamSimilarityConfig): RankedStream? =
        best(config)?.takeIf { it.score >= config.immediateScore }
}

object StreamSimilarityRanker {
    fun rank(
        reference: StreamSimilarityReference,
        candidates: List<StreamItem>,
        target: EpisodeTarget,
        nextEpisodeTitle: String? = null,
        config: StreamSimilarityConfig = StreamSimilarityConfig(),
        playability: (StreamItem) -> StreamPlayability = { StreamPlayability.READY },
    ): StreamRanking {
        val weights = config.weights
        val referenceLine = reference.filename?.takeIf { it.isNotBlank() }
            ?: ReleaseNameParser.releaseLine(reference.texts)
            ?: reference.texts.firstOrNull { it.isNotBlank() }?.lineSequence()?.firstOrNull()
        val referenceTraits = referenceLine
            ?.let { ReleaseNameParser.parse(it, listOfNotNull(reference.episodeTitle)) }
            ?: ReleaseTraits()
        val referenceMarkers = (listOfNotNull(reference.filename) + reference.texts)
            .flatMap { it.lineSequence().flatMap { line -> EpisodeMarkers.find(line) } }
        val current = EpisodeTarget(reference.season, reference.episode)
        val expectedFilename = reference.filename?.takeIf { it.isNotBlank() }?.let { filename ->
            val currentEpisode = reference.episode
            val nextEpisode = target.episode
            if (currentEpisode == null || nextEpisode == null) null
            else FilenameEpisodeMatcher.buildNextFilename(filename, reference.season, currentEpisode, target.season, nextEpisode)
        }
        val referenceBingeGroup = reference.bingeGroup?.trim().orEmpty()

        val referenceIsUsable = referenceBingeGroup.isNotEmpty() || expectedFilename != null ||
            referenceTraits.group != null || referenceTraits.resolution != null || referenceTraits.nameTokens.size >= 2
        if (!referenceIsUsable) {
            return StreamRanking(emptyList(), rejectedWrongEpisode = 0, rejectedNotPlayable = 0, referenceIsUsable = false)
        }

        var wrongEpisode = 0
        var notPlayable = 0
        val ranked = candidates.mapIndexedNotNull { index, stream ->
            val texts = listOfNotNull(stream.behaviorHints.filename, stream.title, stream.description, stream.name)
            val episodeCheck = EpisodeValidator.check(texts, target, current, referenceMarkers)
            if (episodeCheck == EpisodeCheck.MISMATCH) {
                wrongEpisode += 1
                return@mapIndexedNotNull null
            }
            val state = playability(stream)
            if (state == StreamPlayability.NOT_PLAYABLE) {
                notPlayable += 1
                return@mapIndexedNotNull null
            }

            val candidateLine = stream.behaviorHints.filename?.takeIf { it.isNotBlank() }
                ?: ReleaseNameParser.releaseLine(listOf(stream.title, stream.description, stream.name))
                ?: listOfNotNull(stream.title, stream.description, stream.name).firstOrNull { it.isNotBlank() }?.lineSequence()?.firstOrNull()
            val traits = candidateLine
                ?.let { ReleaseNameParser.parse(it, listOfNotNull(nextEpisodeTitle)) }
                ?: ReleaseTraits()

            var possible = 0.0
            var earned = 0.0
            val agreements = mutableListOf<String>()
            fun count(weight: Double, share: Double, label: String?) {
                possible += weight
                earned += weight * share
                if (label != null && share >= 0.6) agreements += label
            }

            val sameAddon = (!reference.addonId.isNullOrBlank() && stream.addonId == reference.addonId) ||
                (reference.addonId.isNullOrBlank() && !reference.addonName.isNullOrBlank() && stream.addonName == reference.addonName)
            count(weights.sameAddon, if (sameAddon) 1.0 else 0.0, null)

            if (referenceBingeGroup.isNotEmpty()) {
                count(weights.bingeGroup, if (stream.behaviorHints.bingeGroup?.trim() == referenceBingeGroup) 1.0 else 0.0, "binge group")
            }
            if (expectedFilename != null) {
                val same = FilenameEpisodeMatcher.matches(stream.behaviorHints.filename, expectedFilename)
                count(weights.expectedFilename, if (same) 1.0 else 0.0, "file name")
            }
            if (referenceTraits.nameTokens.isNotEmpty()) {
                count(weights.name, jaccard(referenceTraits.nameTokens, traits.nameTokens), "name")
            }
            referenceTraits.group?.let { group ->
                count(weights.releaseGroup, if (traits.group == group) 1.0 else 0.0, "release group")
            }
            referenceTraits.resolution?.let { resolution ->
                count(weights.resolution, compare(resolution, traits.resolution, mismatch = -0.5), "resolution")
            }
            referenceTraits.videoCodec?.let { codec ->
                count(weights.videoCodec, compare(codec, traits.videoCodec, mismatch = -0.25), "video codec")
            }
            referenceTraits.releaseType?.let { type ->
                count(weights.releaseType, compare(type, traits.releaseType, mismatch = -0.25), "release type")
            }
            if (referenceTraits.hdr.isNotEmpty() || traits.hdr.isNotEmpty()) {
                val share = when {
                    referenceTraits.hdr == traits.hdr -> 1.0
                    // Dolby Vision without an HDR fallback shows wrong colours on a display the
                    // current source did not need.
                    "dv" in traits.hdr && "hdr" !in traits.hdr && "dv" !in referenceTraits.hdr -> -1.0
                    referenceTraits.hdr.intersect(traits.hdr).isNotEmpty() -> 0.5
                    else -> -0.25
                }
                count(weights.hdr, share, "HDR")
            }
            referenceTraits.audio?.let { audio ->
                count(weights.audio, compare(audio, traits.audio, mismatch = 0.0), "audio")
            }
            referenceTraits.channels?.let { channels ->
                count(weights.channels, compare(channels, traits.channels, mismatch = 0.0), null)
            }
            if (referenceTraits.languages.isNotEmpty()) {
                val share = when {
                    traits.languages == referenceTraits.languages -> 1.0
                    traits.languages.isEmpty() -> 0.0
                    traits.languages.intersect(referenceTraits.languages).isNotEmpty() -> 0.5
                    else -> -1.0
                }
                count(weights.language, share, "language")
            }
            count(weights.episodeVerified, if (episodeCheck == EpisodeCheck.MATCH) 1.0 else 0.4, null)
            count(weights.availability, if (state == StreamPlayability.READY) 1.0 else 0.5, null)

            val score = if (possible <= 0.0) 0.0 else (earned / possible).coerceIn(0.0, 1.0)
            index to RankedStream(stream, score, episodeCheck, state, agreements)
        }
            .sortedWith(compareByDescending<Pair<Int, RankedStream>> { it.second.score }.thenBy { it.first })
            .map { it.second }

        return StreamRanking(ranked, wrongEpisode, notPlayable, referenceIsUsable = true)
    }

    private fun compare(reference: String, candidate: String?, mismatch: Double): Double = when (candidate) {
        null -> 0.0
        reference -> 1.0
        else -> mismatch
    }

    private fun jaccard(first: Set<String>, second: Set<String>): Double {
        if (first.isEmpty() || second.isEmpty()) return 0.0
        val common = first.intersect(second).size.toDouble()
        return common / (first.size + second.size - common)
    }
}
