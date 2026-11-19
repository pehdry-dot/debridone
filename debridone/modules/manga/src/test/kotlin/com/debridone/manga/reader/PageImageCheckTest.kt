package com.debridone.manga.reader

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bytes that reach the reader as a page are a picture that is whole, a picture that stops short,
 * or something else entirely. The last two must never be handed to the decoder as a page: it would
 * draw them as a black page without an error.
 */
class PageImageCheckTest {
    private fun picture(format: String, width: Int = 300, height: Int = 420): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        for (y in 0 until height step 20) {
            g.color = if ((y / 20) % 2 == 0) Color(30, 110, 230) else Color(250, 140, 0)
            g.fillRect(0, y, width, 20)
        }
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    // 32 × 16 pictures made with Pillow: lossless WebP and AVIF.
    private val webp = Base64.getDecoder().decode("UklGRigAAABXRUJQVlA4TBwAAAAvH8ADAA9Qjwtx38N3/qOCQCDJX3qABSL6H9QL")
    private val avif = Base64.getDecoder().decode(
        "AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUIAAADrbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAcGljdAAAAAAAAAAAAAAAAAAAAAAOcGl0bQAAAAAAAQAAAB5pbG9jAAAAAEQAAAEAAQAAAAEAAAETAAAARgAAAChpaW5mAAAAAAABAAAAGmluZmUCAAAAAAEAAGF2MDFDb2xvcgAAAABqaXBycAAAAEtpcGNvAAAAFGlzcGUAAAAAAAAAIAAAABAAAAAQcGl4aQAAAAADCAgIAAAADGF2MUOBAAwAAAAAE2NvbHJuY2x4AAEADQAGgAAAABdpcG1hAAAAAAAAAAEAAQQBAoMEAAAATm1kYXQSAAoJGBD/7RAQ0GhAMjcSB/f2qQCCCD5AAADyK9F9i3qiB7xI37cNf9Cxjg1JrAaTB9smg9v59/EhWDE6Lgu+XU9+e5Ie",
    )

    @Test
    fun wholePicturesAreWhole() {
        for ((bytes, format) in listOf(
            picture("jpg") to ImageFormat.JPEG, picture("png") to ImageFormat.PNG, picture("gif") to ImageFormat.GIF,
            picture("bmp") to ImageFormat.BMP, webp to ImageFormat.WEBP, avif to ImageFormat.AVIF,
        )) {
            assertEquals(PageBytes.Whole(format), PageImageCheck.inspect(bytes), format.label)
            assertEquals(format, PageImageCheck.format(bytes))
            assertNull(PageImageCheck.problem(PageImageCheck.inspect(bytes), 1))
        }
        // Padding after a JPEG's end, as some servers add, is not a cut.
        assertIs<PageBytes.Whole>(PageImageCheck.inspect(picture("jpg") + ByteArray(5000)))
    }

    @Test
    fun picturesThatStopShortAreCaught() {
        for ((bytes, label) in listOf(picture("jpg") to "JPEG", picture("png") to "PNG", picture("gif") to "GIF", webp to "WebP", avif to "AVIF", picture("bmp") to "BMP")) {
            for (keep in listOf(bytes.size - 3, bytes.size * 2 / 3, bytes.size / 2)) {
                val cut = PageImageCheck.inspect(bytes.copyOf(keep))
                assertIs<PageBytes.Cut>(cut, "$label cut to $keep of ${bytes.size} bytes")
                assertTrue(PageImageCheck.problem(cut, 7)!!.startsWith("Page 7 arrived cut off"))
            }
        }
    }

    @Test
    fun aThumbnailInsideAJpegDoesNotMakeACutPageLookWhole() {
        // A JPEG whose EXIF block holds a whole thumbnail (with its own end marker), cut in the middle of the main picture.
        val thumbnail = picture("jpg", 40, 30)
        val main = picture("jpg")
        val exif = byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + byteArrayOf(((thumbnail.size + 2) shr 8).toByte(), ((thumbnail.size + 2) and 0xff).toByte()) + thumbnail
        val withThumbnail = main.copyOfRange(0, 2) + exif + main.copyOfRange(2, main.size)
        assertIs<PageBytes.Whole>(PageImageCheck.inspect(withThumbnail))
        assertIs<PageBytes.Cut>(PageImageCheck.inspect(withThumbnail.copyOf(withThumbnail.size - main.size / 3)))
    }

    @Test
    fun whatIsNotAPictureIsNamed() {
        val challenge = "<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>cf-browser-verification</body></html>".toByteArray()
        val page = "<html><head><title>404 Not Found</title></head><body>nothing</body></html>".toByteArray()
        val json = """{"error":true,"message":"Token expired"}""".toByteArray()
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"/>""".toByteArray()

        assertEquals(PageBytes.Content.BROWSER_CHECK, (PageImageCheck.inspect(challenge) as PageBytes.NotImage).what)
        assertEquals(PageBytes.NotImage(PageBytes.Content.WEB_PAGE, "404 Not Found"), PageImageCheck.inspect(page))
        assertEquals(PageBytes.NotImage(PageBytes.Content.JSON, "Token expired"), PageImageCheck.inspect(json))
        assertEquals(PageBytes.Content.SVG, (PageImageCheck.inspect(svg) as PageBytes.NotImage).what)
        assertEquals(PageBytes.Content.NOTHING, (PageImageCheck.inspect(ByteArray(0)) as PageBytes.NotImage).what)
        assertEquals(PageBytes.Content.UNKNOWN, (PageImageCheck.inspect(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)) as PageBytes.NotImage).what)

        assertEquals("The source sent a web page instead of page 3 (\"404 Not Found\").", PageImageCheck.problem(PageImageCheck.inspect(page), 3))
        assertEquals("The source sent an error instead of page 3: Token expired.", PageImageCheck.problem(PageImageCheck.inspect(json), 3))
        assertTrue(PageImageCheck.problem(PageImageCheck.inspect(challenge), 3)!!.contains("browser check"))
        assertFalse(MangaPages.looksLikeImage(page))
    }

    @Test
    fun onlyTheBrokenCopyOfOnePageIsRemovedFromTheServersCache() {
        val profile = Files.createTempDirectory("page-cache")
        val cache = ServerPageCache { profile }
        val root = profile.resolve("tmp/Tachidesk/manga-cache")
        val chapter = Files.createDirectories(root.resolve("Some Source (EN)/A Series/Ch. 3"))
        val other = Files.createDirectories(root.resolve("Some Source (EN)/A Series/Ch. 4"))
        val good = picture("jpg")
        val broken = picture("png").let { it.copyOf(it.size / 2) }
        Files.write(chapter.resolve("001.jpg"), good)
        Files.write(chapter.resolve("002.png"), broken)
        Files.write(other.resolve("002.png"), broken.copyOf(broken.size - 1))

        // Page 2 (index 1) with exactly the broken bytes: that file goes, nothing else.
        assertFalse(cache.forgetPage(1, good), "bytes that match no copy remove nothing")
        assertTrue(cache.forgetPage(1, broken, "Ch. 3"))
        assertFalse(Files.exists(chapter.resolve("002.png")))
        assertTrue(Files.exists(chapter.resolve("001.jpg")))
        assertTrue(Files.exists(other.resolve("002.png")))

        // A whole chapter, found by the server's own folder naming.
        assertEquals(1, cache.forgetChapter("Some Source (EN)", "A Series", "Ch. 3", null))
        assertFalse(Files.exists(chapter))
        assertTrue(Files.exists(other.resolve("002.png")))
        assertEquals(0, cache.forgetChapter("Some Source (EN)", "A Series", "Ch. 9", null))

        // Names are made safe the way the server makes them.
        assertEquals("Vol_ 1_ Ch_ 2", ServerPageCache.safeName("Vol: 1/ Ch? 2"))
        assertNotNull(ServerPageCache { null }.let { it.forgetChapter("x", "y", "z", null) })
    }
}
