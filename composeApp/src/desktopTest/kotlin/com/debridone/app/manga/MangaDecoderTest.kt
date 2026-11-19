package com.debridone.app.manga

import androidx.compose.ui.graphics.asSkiaBitmap
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The reader's decoder with the real drawing library: pages are decoded in full before they are
 * drawn, a damaged picture is an error and never a black page, transparency is laid on white, and
 * a long strip is cut into pieces that join without a seam.
 */
class MangaDecoderTest {
    private fun encode(image: BufferedImage, format: String): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()

    /** Rows coloured by band: band k (k * 1000 .. k * 1000 + 999) has a colour of its own. */
    private fun bandColor(band: Int) = Color((band * 37) % 256, (band * 91 + 40) % 256, (band * 53 + 90) % 256)

    @Test
    fun aLongStripIsCutIntoPiecesThatJoinExactly() {
        val width = 400
        val height = 30_000
        val strip = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = strip.createGraphics()
        for (band in 0 until height / 1000) {
            g.color = bandColor(band)
            g.fillRect(0, band * 1000, width, 1000)
        }
        g.dispose()

        val done = assertIs<Decoding.Done>(MangaDecoder.decodePage(encode(strip, "png"), maxWidth = 2400, maxHeight = 40_000))
        val page = done.page
        assertEquals(width, page.width)
        assertEquals(height, page.height)
        assertEquals((height + MangaDecoder.PIECE_HEIGHT - 1) / MangaDecoder.PIECE_HEIGHT, page.pieces.size)
        assertTrue(page.pieces.all { it.height <= MangaDecoder.PIECE_HEIGHT && it.width == width })
        assertEquals(height, page.pieces.sumOf { it.height })

        // Every row of the whole strip, read back from the pieces, is the row of the original.
        var top = 0
        for (piece in page.pieces) {
            val pixels = piece.asSkiaBitmap()
            for (y in listOf(0, 1, piece.height / 2, piece.height - 2, piece.height - 1)) {
                val row = top + y
                val expected = bandColor(row / 1000)
                val actual = pixels.getColor(width / 2, y)
                assertEquals(expected.rgb or (0xFF shl 24), actual, "row $row (piece starting at $top)")
            }
            top += piece.height
        }
    }

    @Test
    fun aDamagedPictureIsAnErrorAndNeverAnEmptyPage() {
        val webp = Base64.getDecoder().decode("UklGRigAAABXRUJQVlA4TBwAAAAvH8ADAA9Qjwtx38N3/qOCQCDJX3qABSL6H9QL")
        assertIs<Decoding.Done>(MangaDecoder.decodePage(webp, 2400))
        // Cut, it used to come out as a fully transparent page whenever it had to be scaled.
        for (keep in listOf(webp.size - 6, webp.size / 2)) {
            assertIs<Decoding.Failed>(MangaDecoder.decodePage(webp.copyOf(keep), maxWidth = 16), "cut to $keep bytes")
        }
        val failed = assertIs<Decoding.Failed>(MangaDecoder.decodePage("<html><title>Just a moment...</title></html>".toByteArray(), 2400))
        assertTrue(failed.message.isNotBlank())
    }

    @Test
    fun transparencyIsLaidOnWhite() {
        val picture = BufferedImage(200, 300, BufferedImage.TYPE_INT_ARGB)
        val g = picture.createGraphics()
        g.color = Color.BLACK
        g.fillRect(90, 0, 20, 300) // a black stroke on an otherwise transparent page
        g.dispose()
        val page = assertIs<Decoding.Done>(MangaDecoder.decodePage(encode(picture, "png"), 2400)).page
        val pixels = page.pieces.single().asSkiaBitmap()
        assertEquals(0xFFFFFFFF.toInt(), pixels.getColor(20, 150), "transparent parts are white")
        assertEquals(0xFF000000.toInt(), pixels.getColor(100, 150), "the ink stays black")
    }

    @Test
    fun aLargePageIsScaledToWhatTheScreenNeeds() {
        val scan = BufferedImage(1600, 2400, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply { color = Color(200, 40, 40); fillRect(0, 0, 1600, 2400); dispose() }
        }
        val page = assertIs<Decoding.Done>(MangaDecoder.decodePage(encode(scan, "jpg"), maxWidth = 800, maxHeight = 6000)).page
        assertEquals(800, page.width)
        assertEquals(1200, page.height)
        assertTrue(page.blank, "a page of one colour is noticed (a placeholder rather than a page)")
    }
}
