package com.debridone.platform.media

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The system decoder is macOS's ImageIO. On a Mac (the platform tests run on one in CI) it must
 * decode the formats the drawing library cannot, AVIF above all, to the right pixels; elsewhere it
 * must say it has nothing rather than fail.
 */
class SystemImageDecoderTest {
    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    // 32 × 16: the left half red (220, 30, 30), the right half blue (30, 60, 220).
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAACAAAAAQCAIAAAD4YuoOAAAAJ0lEQVR4nGO8IyfHQApwkttHknomklSTAUYtGLVg1IJRC0YtoAYAAJVpAlbb8qgDAAAAAElFTkSuQmCC")
    private val avif = Base64.getDecoder().decode(
        "AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUIAAADrbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAcGljdAAAAAAAAAAAAAAAAAAAAAAOcGl0bQAAAAAAAQAAAB5pbG9jAAAAAEQAAAEAAQAAAAEAAAETAAAARgAAAChpaW5mAAAAAAABAAAAGmluZmUCAAAAAAEAAGF2MDFDb2xvcgAAAABqaXBycAAAAEtpcGNvAAAAFGlzcGUAAAAAAAAAIAAAABAAAAAQcGl4aQAAAAADCAgIAAAADGF2MUOBAAwAAAAAE2NvbHJuY2x4AAEADQAGgAAAABdpcG1hAAAAAAAAAAEAAQQBAoMEAAAATm1kYXQSAAoJGBD/7RAQ0GhAMjcSB/f2qQCCCD5AAADyK9F9i3qiB7xI37cNf9Cxjg1JrAaTB9smg9v59/EhWDE6Lgu+XU9+e5Ie",
    )

    @Test
    fun decodesWithTheSystemOnAMacAndSaysNothingElsewhere() {
        if (!isMac) {
            assertNull(SystemImageDecoder.decode(png))
            return
        }
        assertTrue(SystemImageDecoder.available)
        for ((name, bytes) in listOf("PNG" to png, "AVIF" to avif)) {
            val pixels = assertNotNull(SystemImageDecoder.decode(bytes), "$name was not decoded")
            assertEquals(32, pixels.width, name)
            assertEquals(16, pixels.height, name)
            fun pixel(x: Int, y: Int) = (0 until 4).map { pixels.bytes[(y * pixels.width + x) * 4 + it].toInt() and 0xff }
            val left = pixel(4, 8)
            val right = pixel(28, 8)
            assertTrue(left[0] > 150 && left[2] < 90 && left[3] == 255, "$name left half should be red, was $left")
            assertTrue(right[2] > 150 && right[0] < 90 && right[3] == 255, "$name right half should be blue, was $right")
            // Rows run top to bottom: the top-left and bottom-left corners are both red.
            assertTrue(pixel(1, 0)[0] > 150 && pixel(1, 15)[0] > 150, name)
        }
    }

    @Test
    fun bytesThatAreNotAPictureGiveNothing() {
        assertNull(SystemImageDecoder.decode("<html>not a picture</html>".toByteArray()))
        assertNull(SystemImageDecoder.decode(ByteArray(0)))
    }
}
