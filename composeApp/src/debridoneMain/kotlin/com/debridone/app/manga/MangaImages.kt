package com.debridone.app.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.debridone.manga.host.RemoteSeries
import com.debridone.manga.library.Series
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/** Turns image bytes into bitmaps, scaled down to what the screen needs. */
internal object MangaDecoder {
    /**
     * Decodes [bytes]. An image wider than [maxWidth] or taller than [maxHeight] is scaled down, so a
     * 4000-pixel scan shown in a 160-pixel card does not hold 60 MB of memory. Returns null when the
     * bytes are not an image Skia can read.
     */
    fun decode(bytes: ByteArray, maxWidth: Int, maxHeight: Int = Int.MAX_VALUE): ImageBitmap? = runCatching {
        val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        try {
            val scale = minOf(1f, maxWidth.toFloat() / image.width, maxHeight.toFloat() / image.height)
            if (scale >= 0.999f) return@runCatching image.toComposeImageBitmap()
            val width = (image.width * scale).toInt().coerceAtLeast(1)
            val height = (image.height * scale).toInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(width, height)
            try {
                surface.canvas.drawImageRect(
                    image,
                    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    Rect.makeWH(width.toFloat(), height.toFloat()),
                    SamplingMode.MITCHELL,
                    null,
                    true,
                )
                surface.makeImageSnapshot().toComposeImageBitmap()
            } finally {
                surface.close()
            }
        } finally {
            image.close()
        }
    }.getOrNull()
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

    fun key(series: Series): String = "s:${series.id}:${series.coverFile ?: series.coverUrl}"

    fun key(remote: RemoteSeries): String = "r:${remote.extensionId}:${remote.coverUrl}"
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

/** The cover of a series as a source lists it (no row yet). */
@Composable
internal fun RemoteCover(remote: RemoteSeries, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    val manga = LocalManga.current
    val key = MangaCovers.key(remote)
    val bitmap by produceState(MangaCovers.cached(key), key) {
        value = MangaCovers.cached(key)
            ?: MangaCovers.load(key) { manga.services.pages.remoteCover(remote.coverUrl, extensionId = remote.extensionId, raw = remote.raw) }
    }
    CoverBox(bitmap, modifier, shape)
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
