package com.debridone.app.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.debridone.manga.library.Series
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.debridone.platform.media.SystemImageDecoder
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/**
 * A page ready to be drawn: its picture in one or more horizontal pieces, top to bottom, none
 * taller than [MangaDecoder.PIECE_HEIGHT]. [width] × [height] is the size of the whole.
 */
internal class DecodedPage(val pieces: List<ImageBitmap>, val width: Int, val height: Int, val blank: Boolean) {
    val ratio: Float get() = height.toFloat() / width.coerceAtLeast(1)
}

/** What became of a page's bytes. */
internal sealed interface Decoding {
    class Done(val page: DecodedPage) : Decoding
    class Failed(val message: String) : Decoding
}

/** Turns image bytes into bitmaps, scaled down to what the screen needs. */
internal object MangaDecoder {
    /**
     * The tallest piece a page is drawn in. Long webtoon strips (often 20,000 pixels and more) are
     * cut into pieces of this height, well under what any graphics card takes in one texture.
     */
    const val PIECE_HEIGHT = 4096

    /**
     * Decodes a page completely before anything is drawn. Skia reads only the header of encoded
     * bytes at first and the rest when it draws; a picture that cannot be decoded in full then
     * draws as nothing, which on the reader's dark background is a black page with no error. Here
     * the whole picture is decoded first and a failure is reported as one.
     *
     * The result is scaled down to [maxWidth] × [maxHeight] (whichever binds), a picture with
     * transparent parts is laid on white (manga pages are drawn for paper: on a dark background,
     * black ink on transparency would not show), and a tall result is cut into pieces.
     * Formats Skia does not read (AVIF, HEIF, JPEG XL) go to the operating system's decoder.
     */
    fun decodePage(bytes: ByteArray, maxWidth: Int, maxHeight: Int = Int.MAX_VALUE): Decoding {
        val decoded = fullImage(bytes) ?: return Decoding.Failed(undecodable(bytes))
        val full = decoded.first
        try {
            val opaque = full.isOpaque
            val scale = minOf(1f, maxWidth.toFloat() / full.width, maxHeight.toFloat() / full.height)
            val width = (full.width * scale).toInt().coerceAtLeast(1)
            val height = (full.height * scale).toInt().coerceAtLeast(1)
            val pieces = ArrayList<ImageBitmap>()
            var top = 0
            while (top < height) {
                val pieceHeight = minOf(PIECE_HEIGHT, height - top)
                val surface = Surface.makeRasterN32Premul(width, pieceHeight)
                try {
                    if (!opaque) surface.canvas.clear(0xFFFFFFFF.toInt())
                    surface.canvas.drawImageRect(
                        full,
                        Rect.makeLTRB(0f, top / scale, full.width.toFloat(), minOf(full.height.toFloat(), (top + pieceHeight) / scale)),
                        Rect.makeWH(width.toFloat(), pieceHeight.toFloat()),
                        if (scale >= 0.999f) SamplingMode.DEFAULT else SamplingMode.MITCHELL,
                        null,
                        true,
                    )
                    pieces += surface.makeImageSnapshot().toComposeImageBitmap()
                } finally {
                    surface.close()
                }
                top += pieceHeight
            }
            return Decoding.Done(DecodedPage(pieces, width, height, blank = decoded.second))
        } catch (failure: Throwable) {
            return Decoding.Failed("The page could not be drawn: ${failure.message ?: failure.javaClass.simpleName}.")
        } finally {
            full.close()
        }
    }

    /**
     * Decodes [bytes] into one bitmap (covers, icons, pictures in dialogs). An image wider than
     * [maxWidth] or taller than [maxHeight] is scaled down, so a 4000-pixel scan shown in a
     * 160-pixel card does not hold 60 MB of memory. Returns null when the bytes are not a picture
     * that can be decoded in full.
     */
    fun decode(bytes: ByteArray, maxWidth: Int, maxHeight: Int = Int.MAX_VALUE): ImageBitmap? {
        val done = decodePage(bytes, maxWidth, maxHeight) as? Decoding.Done ?: return null
        return done.page.pieces.singleOrNull() ?: run {
            // A cover is never tall enough to be cut; should one be, its first piece stands in.
            done.page.pieces.firstOrNull()
        }
    }

    /**
     * The whole picture, decoded into memory, and whether it is one colour throughout; null when
     * neither Skia nor the system can decode it in full.
     */
    private fun fullImage(bytes: ByteArray): Pair<org.jetbrains.skia.Image, Boolean>? {
        val encoded = runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes) }.getOrNull()
        if (encoded != null) {
            try {
                // Reading the pixels decodes the picture in full, and fails for one that stops short.
                val bitmap = runCatching { Bitmap.makeFromImage(encoded) }.getOrNull()
                if (bitmap != null) {
                    val blank = uniform(bitmap.width, bitmap.height) { x, y -> bitmap.getColor(x, y) }
                    bitmap.setImmutable()
                    return org.jetbrains.skia.Image.makeFromBitmap(bitmap) to blank
                }
            } finally {
                encoded.close()
            }
        }
        val pixels = SystemImageDecoder.decode(bytes) ?: return null
        val blank = uniform(pixels.width, pixels.height) { x, y ->
            val at = (y * pixels.width + x) * 4
            (0 until 4).fold(0) { acc, i -> (acc shl 8) or (pixels.bytes[at + i].toInt() and 0xff) }
        }
        return runCatching {
            org.jetbrains.skia.Image.makeRaster(
                ImageInfo(pixels.width, pixels.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL), pixels.bytes, pixels.width * 4,
            ) to blank
        }.getOrNull()
    }

    /** True when a grid of samples across the picture is one colour: a placeholder rather than a page. */
    private inline fun uniform(width: Int, height: Int, color: (Int, Int) -> Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val first = color(0, 0)
        for (gx in 0..15) for (gy in 0..15) {
            if (color(((width - 1) * gx) / 15, ((height - 1) * gy) / 15) != first) return false
        }
        return true
    }

    private fun undecodable(bytes: ByteArray): String {
        val format = com.debridone.manga.reader.PageImageCheck.format(bytes)
        return when {
            format == null -> "This page is not a picture DebridOne can show."
            format.label in setOf("AVIF", "HEIF", "JPEG XL") && !SystemImageDecoder.available ->
                "This page is a ${format.label} picture; on this computer DebridOne cannot decode that format."
            format.label in setOf("AVIF", "HEIF", "JPEG XL") ->
                "This page is a ${format.label} picture that macOS could not decode (AVIF needs macOS 13, JPEG XL macOS 14)."
            else -> "This ${format.label} picture could not be decoded in full: it is damaged."
        }
    }
}

/** Decoded covers, least recently used first out. */
internal object MangaCovers {
    private const val MAX = 220
    private const val COVER_WIDTH = 420
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > MAX
    }

    /** Covers that could not be loaded, so a grid does not ask for them again on every scroll. */
    private val missing = HashSet<String>()
    private val loading = Semaphore(6)

    fun cached(key: String): ImageBitmap? = synchronized(this) { cache[key] }

    fun clear() = synchronized(this) {
        cache.clear()
        missing.clear()
    }

    fun forget(key: String) = synchronized(this) {
        cache.remove(key)
        missing.remove(key)
    }

    suspend fun load(key: String, width: Int = COVER_WIDTH, bytes: suspend () -> ByteArray?): ImageBitmap? {
        cached(key)?.let { return it }
        if (synchronized(this) { key in missing }) return null
        return loading.withPermit {
            cached(key) ?: withContext(Dispatchers.IO) {
                val bitmap = runCatching { bytes() }.getOrNull()?.let { MangaDecoder.decode(it, width) }
                synchronized(this@MangaCovers) { if (bitmap != null) cache[key] = bitmap else missing += key }
                bitmap
            }
        }
    }

    /** The profile is part of the key: two profiles' servers number their series independently. */
    fun key(series: Series): String = "s:${series.profileId}:${series.id}:${series.coverFile ?: series.coverUrl}"
}

/** The cover of a library series, or a quiet placeholder when it has none. [large] is for the series page. */
@Composable
internal fun SeriesCover(series: Series, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp), large: Boolean = false) {
    val manga = LocalManga.current
    val key = MangaCovers.key(series) + if (large) ":L" else ""
    val bitmap by produceState(MangaCovers.cached(key), key) {
        value = MangaCovers.cached(key) ?: MangaCovers.load(key, if (large) 900 else 420) { manga.services.pages.cover(series) }
    }
    CoverBox(bitmap, modifier, shape)
}

/** The icon of a source or an extension, as the manga server serves it. */
@Composable
internal fun ServerIcon(path: String, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    val manga = LocalManga.current
    val key = "i:${manga.profileId}:$path"
    val bitmap by produceState(MangaCovers.cached(key), key) {
        value = if (path.isBlank()) null else MangaCovers.cached(key) ?: MangaCovers.load(key, 128) { manga.services.pages.icon(path) }
    }
    val tokens = MaterialTheme.nuvio
    Box(modifier.clip(shape).background(tokens.colors.surfaceElevated), contentAlignment = Alignment.Center) {
        val shown = bitmap
        if (shown != null) {
            Image(bitmap = shown, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Icon(androidx.compose.material.icons.Icons.Rounded.Extension, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.fillMaxSize(0.5f))
        }
    }
}

/** A cover by plain address (metadata providers). */
@Composable
internal fun UrlCover(url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp), width: Int = 420) {
    val manga = LocalManga.current
    val key = "u:$width:${url.orEmpty()}"
    val bitmap by produceState(MangaCovers.cached(key), key) {
        value = if (url.isNullOrBlank()) null else MangaCovers.cached(key) ?: MangaCovers.load(key, width) { manga.services.pages.remoteCover(url) }
    }
    CoverBox(bitmap, modifier, shape)
}

@Composable
private fun CoverBox(bitmap: ImageBitmap?, modifier: Modifier, shape: Shape) {
    val tokens = MaterialTheme.nuvio
    Box(modifier.clip(shape).background(tokens.colors.surfaceElevated), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(BookIcon, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.fillMaxSize(0.34f))
        }
    }
}
