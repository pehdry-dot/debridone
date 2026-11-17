package com.debridone.manga.reader

import com.debridone.manga.library.MediaType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ReadingMode(val label: String) {
    /** Paged, pages advance to the left: the way Japanese manga is printed. */
    RIGHT_TO_LEFT("Right to left"),

    /** Paged, pages advance to the right. */
    LEFT_TO_RIGHT("Left to right"),

    /** All pages in one column with a gap between them, scrolled. */
    VERTICAL("Vertical scrolling"),

    /** One continuous strip without gaps, at an adjustable width: for webtoons and manhwa. */
    WEBTOON("Continuous webtoon");

    val isPaged: Boolean get() = this == RIGHT_TO_LEFT || this == LEFT_TO_RIGHT
}

@Serializable
enum class PageLayout(val label: String) {
    SINGLE("Single page"),
    DOUBLE("Double page"),
}

@Serializable
enum class PageFit(val label: String) {
    /** The whole page is always visible. */
    SCREEN("Fit screen"),
    WIDTH("Fit width"),
    HEIGHT("Fit height"),
    ORIGINAL("Original size"),
}

@Serializable
enum class ReaderBackground(val label: String, val argb: Long) {
    BLACK("Black", 0xFF000000),
    DARK("Dark grey", 0xFF17171A),
    SEPIA("Sepia", 0xFFF1E7D0),
    WHITE("White", 0xFFFFFFFF),
}

/** How a chapter is laid out and navigated. One preset per shelf (Manga, Manhwa) and profile; a series can carry its own. */
@Serializable
data class ReaderSettings(
    val mode: ReadingMode = ReadingMode.RIGHT_TO_LEFT,
    val layout: PageLayout = PageLayout.SINGLE,
    /** In double-page layout: show the first page alone, so that spreads line up. */
    val offsetFirstPage: Boolean = true,
    val fit: PageFit = PageFit.SCREEN,
    val background: ReaderBackground = ReaderBackground.BLACK,
    /** Width of the column in the scrolling modes, as a share of the window (30..100). */
    val widthPercent: Int = 60,
    /** Space between pages in vertical scrolling, in dp. Webtoon mode always uses none. */
    val pageGap: Int = 12,
    /** Pages fetched ahead of the one being read. */
    val preloadPages: Int = 3,
    val showPageNumber: Boolean = true,
    /** Slide between pages in the paged modes. */
    val animatePages: Boolean = false,
    /** Pixels per arrow-key press in the scrolling modes. */
    val scrollStep: Int = 160,
) {
    fun normalized(): ReaderSettings = copy(
        widthPercent = widthPercent.coerceIn(30, 100),
        pageGap = pageGap.coerceIn(0, 80),
        preloadPages = preloadPages.coerceIn(0, 8),
        scrollStep = scrollStep.coerceIn(40, 800),
    )

    fun encode(): String = codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Manga: right to left, one whole page at a time. Manhwa: a continuous strip. */
        fun defaults(type: MediaType): ReaderSettings = when (type) {
            MediaType.MANGA -> ReaderSettings()
            MediaType.MANHWA -> ReaderSettings(mode = ReadingMode.WEBTOON, fit = PageFit.WIDTH, pageGap = 0)
        }

        fun decode(text: String?, fallback: ReaderSettings): ReaderSettings =
            if (text.isNullOrBlank()) fallback else runCatching { codec.decodeFromString(serializer(), text).normalized() }.getOrDefault(fallback)
    }
}
