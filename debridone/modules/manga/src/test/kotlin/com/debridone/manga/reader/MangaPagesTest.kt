package com.debridone.manga.reader

import com.debridone.manga.suwayomi.SampleComics
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Telling pictures from other bytes, and whole pictures from ones that stop short. */
class MangaPagesTest {
    private fun jpeg(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(64, 96, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()

    @Test
    fun picturesAreRecognisedByTheirFirstBytes() {
        assertEquals("png", MangaPages.imageExtension(SampleComics.page(1)))
        assertEquals("jpg", MangaPages.imageExtension(jpeg()))
        assertNull(MangaPages.imageExtension("<html>not a picture</html>".toByteArray()))
        assertNull(MangaPages.imageExtension(ByteArray(4)))
        assertFalse(MangaPages.looksLikeImage("PK\u0003\u0004 an archive, not a page".toByteArray()))
    }

    /** The server can hand out a cover while it is still writing it; such a picture must not be shown as if it were whole. */
    @Test
    fun aPictureThatStopsShortIsNotTakenForAWholeOne() {
        val png = SampleComics.page(2)
        assertTrue(MangaPages.isComplete(png))
        assertFalse(MangaPages.isComplete(png.copyOf(png.size - 40)))
        assertFalse(MangaPages.isComplete(png.copyOf(png.size / 2)))

        val jpeg = jpeg()
        assertTrue(MangaPages.isComplete(jpeg))
        assertFalse(MangaPages.isComplete(jpeg.copyOf(jpeg.size - 2)), "without its end marker")
        assertFalse(MangaPages.isComplete(jpeg.copyOf(jpeg.size * 2 / 3)))
        // Some encoders pad after the end marker; that is still a whole picture.
        assertTrue(MangaPages.isComplete(jpeg + ByteArray(8)))
    }
}
