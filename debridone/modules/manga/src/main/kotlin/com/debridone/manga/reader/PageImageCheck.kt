package com.debridone.manga.reader

/** The picture formats a page can come in, told apart by their first bytes. */
enum class ImageFormat(val extension: String, val label: String) {
    JPEG("jpg", "JPEG"),
    PNG("png", "PNG"),
    GIF("gif", "GIF"),
    WEBP("webp", "WebP"),
    BMP("bmp", "BMP"),
    AVIF("avif", "AVIF"),
    HEIF("heic", "HEIF"),
    JXL("jxl", "JPEG XL"),
}

/** What the bytes of a page turned out to be, before anything tries to draw them. */
sealed interface PageBytes {
    /** A picture in [format] that is whole as far as its own structure says. */
    data class Whole(val format: ImageFormat) : PageBytes

    /**
     * A picture that stops before its end: it was cut off on its way, or kept while it was still
     * arriving. Drawn, it shows as a page that is partly or entirely black.
     */
    data class Cut(val format: ImageFormat, val size: Int, val expected: Int?) : PageBytes

    /** Not a picture at all. [hint] is a short, safe excerpt of what came instead (a page title, an error text). */
    data class NotImage(val what: Content, val hint: String) : PageBytes

    enum class Content { NOTHING, WEB_PAGE, BROWSER_CHECK, SVG, JSON, TEXT, UNKNOWN }
}

/**
 * Looks at a page's bytes the way the reader cannot afford to after the fact: a picture decoder
 * reads the header first and the rest only when it draws, so bytes that stop short, or a web page
 * where a picture should be, come out as a black or empty page instead of an error. This finds
 * those cases from the bytes alone, by each format's own structure.
 */
object PageImageCheck {
    fun inspect(bytes: ByteArray): PageBytes {
        if (bytes.isEmpty()) return PageBytes.NotImage(PageBytes.Content.NOTHING, "")
        val format = format(bytes) ?: return notImage(bytes)
        val expected = when (format) {
            ImageFormat.JPEG -> return if (jpegWhole(bytes)) PageBytes.Whole(format) else PageBytes.Cut(format, bytes.size, null)
            ImageFormat.PNG -> return pngCheck(bytes)
            ImageFormat.GIF -> return if (gifWhole(bytes)) PageBytes.Whole(format) else PageBytes.Cut(format, bytes.size, null)
            ImageFormat.WEBP -> le32(bytes, 4)?.let { it + 8 }
            ImageFormat.BMP -> le32(bytes, 2)
            ImageFormat.AVIF, ImageFormat.HEIF -> return boxesCheck(bytes, format)
            ImageFormat.JXL -> return if (bytes.size >= 12 && bytes[4] == 'J'.code.toByte()) boxesCheck(bytes, format) else PageBytes.Whole(format)
        }
        return if (expected == null || expected <= 0 || bytes.size >= expected) PageBytes.Whole(format) else PageBytes.Cut(format, bytes.size, expected)
    }

    /** The picture format by the first bytes, or null when they are not a picture's. */
    fun format(bytes: ByteArray): ImageFormat? {
        fun at(index: Int) = if (index < bytes.size) bytes[index].toInt() and 0xff else -1
        fun ascii(index: Int, text: String) = text.indices.all { at(index + it) == text[it].code }
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> ImageFormat.JPEG
            at(0) == 0x89 && ascii(1, "PNG") -> ImageFormat.PNG
            ascii(0, "GIF8") -> ImageFormat.GIF
            ascii(0, "RIFF") && ascii(8, "WEBP") -> ImageFormat.WEBP
            ascii(0, "BM") && bytes.size >= 26 -> ImageFormat.BMP
            at(0) == 0xFF && at(1) == 0x0A -> ImageFormat.JXL
            ascii(4, "JXL ") -> ImageFormat.JXL
            ascii(4, "ftyp") -> when (String(bytes, 8, minOf(4, bytes.size - 8).coerceAtLeast(0), Charsets.ISO_8859_1)) {
                "avif", "avis" -> ImageFormat.AVIF
                "heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1" -> if (brands(bytes).any { it == "avif" || it == "avis" }) ImageFormat.AVIF else ImageFormat.HEIF
                else -> null
            }
            else -> null
        }
    }

    /** A sentence for the reader about bytes that cannot be shown, or null when they can. */
    fun problem(bytes: PageBytes, page: Int): String? = when (bytes) {
        is PageBytes.Whole -> null
        is PageBytes.Cut -> "Page $page arrived cut off (" + sizeText(bytes.size) +
            (bytes.expected?.let { " of " + sizeText(it) } ?: "") + " of a ${bytes.format.label} picture)."
        is PageBytes.NotImage -> when (bytes.what) {
            PageBytes.Content.NOTHING -> "Page $page arrived empty."
            PageBytes.Content.BROWSER_CHECK -> "The source sent a browser check instead of page $page (it is protected by Cloudflare or similar)."
            PageBytes.Content.WEB_PAGE -> "The source sent a web page instead of page $page" + (bytes.hint.takeIf { it.isNotBlank() }?.let { " (\"$it\")" } ?: "") + "."
            PageBytes.Content.SVG -> "Page $page is an SVG drawing, which the reader does not show."
            PageBytes.Content.JSON -> "The source sent an error instead of page $page" + (bytes.hint.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") + "."
            PageBytes.Content.TEXT -> "The source sent text instead of page $page" + (bytes.hint.takeIf { it.isNotBlank() }?.let { ": \"$it\"" } ?: "") + "."
            PageBytes.Content.UNKNOWN -> "Page $page is not a picture the reader knows (it starts with ${bytes.hint})."
        }
    }

    // ---- formats ---------------------------------------------------------------------------------

    /**
     * A JPEG is whole when, after the start of its (first) scan, the end-of-image marker comes.
     * The segments before the scan are walked by their lengths, so a thumbnail inside the EXIF data,
     * which has an end marker of its own, does not count.
     */
    private fun jpegWhole(bytes: ByteArray): Boolean {
        var index = 2
        while (index + 4 <= bytes.size) {
            if (bytes[index].toInt() and 0xff != 0xFF) return false
            val marker = bytes[index + 1].toInt() and 0xff
            when {
                marker == 0xFF -> { index += 1; continue }
                marker == 0xD9 -> return true
                marker == 0x01 || marker in 0xD0..0xD7 -> { index += 2; continue }
            }
            val length = ((bytes[index + 2].toInt() and 0xff) shl 8) or (bytes[index + 3].toInt() and 0xff)
            if (length < 2) return false
            if (marker == 0xDA) {
                // The scan's data: an FF in it is always followed by 00 or a restart marker, so the
                // first FF D9 after this point is the end of the picture.
                var scan = index + 2 + length
                while (scan + 1 < bytes.size) {
                    if (bytes[scan].toInt() and 0xff == 0xFF && bytes[scan + 1].toInt() and 0xff == 0xD9) return true
                    scan++
                }
                return false
            }
            index += 2 + length
        }
        return false
    }

    /** A PNG is walked chunk by chunk up to its IEND chunk. */
    private fun pngCheck(bytes: ByteArray): PageBytes {
        var index = 8
        while (index + 8 <= bytes.size) {
            val length = be32(bytes, index) ?: break
            if (length < 0) break
            val type = String(bytes, index + 4, 4, Charsets.ISO_8859_1)
            val end = index.toLong() + 12 + length
            if (type == "IEND") return if (end <= bytes.size) PageBytes.Whole(ImageFormat.PNG) else PageBytes.Cut(ImageFormat.PNG, bytes.size, end.toInt())
            if (end > bytes.size) return PageBytes.Cut(ImageFormat.PNG, bytes.size, null)
            index = end.toInt()
        }
        return PageBytes.Cut(ImageFormat.PNG, bytes.size, null)
    }

    /** A GIF ends with its trailer byte; a few bytes of padding after it are allowed. */
    private fun gifWhole(bytes: ByteArray): Boolean {
        var end = bytes.size - 1
        while (end > 0 && bytes[end].toInt() == 0 && bytes.size - end < 16) end--
        return bytes[end].toInt() and 0xff == 0x3B
    }

    /** AVIF, HEIF and boxed JPEG XL are a row of boxes, each saying how long it is. */
    private fun boxesCheck(bytes: ByteArray, format: ImageFormat): PageBytes {
        var index = 0L
        while (index < bytes.size) {
            if (index + 8 > bytes.size) return PageBytes.Cut(format, bytes.size, null)
            var size = (be32(bytes, index.toInt()) ?: return PageBytes.Cut(format, bytes.size, null)).toLong() and 0xffffffffL
            if (size == 1L) {
                if (index + 16 > bytes.size) return PageBytes.Cut(format, bytes.size, null)
                size = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (bytes[(index + 8 + i).toInt()].toLong() and 0xff) }
            } else if (size == 0L) {
                return PageBytes.Whole(format) // the last box runs to the end of the file
            }
            if (size < 8) return PageBytes.Cut(format, bytes.size, null)
            if (index + size > bytes.size) return PageBytes.Cut(format, bytes.size, (index + size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            index += size
        }
        return PageBytes.Whole(format)
    }

    private fun brands(bytes: ByteArray): List<String> {
        val size = (be32(bytes, 0) ?: return emptyList()).coerceAtMost(bytes.size)
        return (16 until size step 4).mapNotNull { if (it + 4 <= bytes.size) String(bytes, it, 4, Charsets.ISO_8859_1) else null }
    }

    // ---- what came instead ------------------------------------------------------------------------

    private fun notImage(bytes: ByteArray): PageBytes.NotImage {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8).trimStart('﻿', ' ', '\t', '\r', '\n')
        val lower = head.lowercase()
        return when {
            lower.startsWith("<") -> when {
                lower.startsWith("<svg") || (lower.startsWith("<?xml") && "<svg" in lower) -> PageBytes.NotImage(PageBytes.Content.SVG, "")
                BROWSER_CHECK.any { it in lower } -> PageBytes.NotImage(PageBytes.Content.BROWSER_CHECK, title(head))
                else -> PageBytes.NotImage(PageBytes.Content.WEB_PAGE, title(head))
            }
            lower.startsWith("{") || lower.startsWith("[") -> PageBytes.NotImage(PageBytes.Content.JSON, jsonMessage(head))
            printable(head) -> PageBytes.NotImage(PageBytes.Content.TEXT, head.lineSequence().first().trim().take(100))
            else -> PageBytes.NotImage(PageBytes.Content.UNKNOWN, bytes.take(8).joinToString(" ") { "%02X".format(it) })
        }
    }

    private val BROWSER_CHECK = listOf("just a moment", "cf-browser-verification", "challenge-platform", "cf_chl_", "attention required! | cloudflare", "ddos-guard", "checking your browser")

    private fun title(html: String): String =
        Regex("<title[^>]*>([^<]{1,160})</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")?.take(100).orEmpty()

    private fun jsonMessage(json: String): String =
        Regex("\"(?:message|error|detail|msg)\"\\s*:\\s*\"([^\"]{1,200})\"", RegexOption.IGNORE_CASE).find(json)?.groupValues?.get(1)?.take(120).orEmpty()

    private fun printable(text: String): Boolean {
        if (text.isEmpty()) return false
        val sample = text.take(512)
        return sample.count { it.isLetterOrDigit() || it.isWhitespace() || it in ".,:;!?'\"()-_/[]{}<>=&%#@+*" } >= sample.length * 0.9
    }

    private fun sizeText(bytes: Int): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes bytes"
    }

    private fun le32(bytes: ByteArray, index: Int): Int? =
        if (index + 4 > bytes.size) null
        else (bytes[index].toInt() and 0xff) or ((bytes[index + 1].toInt() and 0xff) shl 8) or ((bytes[index + 2].toInt() and 0xff) shl 16) or ((bytes[index + 3].toInt() and 0xff) shl 24)

    private fun be32(bytes: ByteArray, index: Int): Int? =
        if (index + 4 > bytes.size) null
        else ((bytes[index].toInt() and 0xff) shl 24) or ((bytes[index + 1].toInt() and 0xff) shl 16) or ((bytes[index + 2].toInt() and 0xff) shl 8) or (bytes[index + 3].toInt() and 0xff)
}
