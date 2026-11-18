package com.debridone.manga.suwayomi

import org.junit.Assume
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** Finds the pinned Suwayomi-Server and Java 21 runtime the build points the tests at. */
object SuwayomiTestRuntime {
    private val required = System.getenv("DEBRIDONE_REQUIRE_SUWAYOMI") == "1"
    val live: Boolean = System.getenv("DEBRIDONE_LIVE_TESTS") == "1"

    fun runtimeOrSkip(): SuwayomiRuntime {
        val runtime = runCatching { SuwayomiRuntimeLocator.locate() }
        if (runtime.isFailure) {
            val why = runtime.exceptionOrNull()?.message
            if (required) throw AssertionError("Suwayomi-Server is required for these tests (run scripts/debridone/fetch-suwayomi-runtime.sh): $why")
            Assume.assumeTrue("No Suwayomi-Server runtime (run scripts/debridone/fetch-suwayomi-runtime.sh): $why", false)
        }
        return runtime.getOrThrow()
    }

    fun assumeLive() {
        Assume.assumeTrue("Live tests are off (set DEBRIDONE_LIVE_TESTS=1)", live)
    }
}

/** Sample comics made on the spot: every page is a small picture with its own colour. */
object SampleComics {
    fun page(index: Int, width: Int = 240, height: Int = 360): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color((40 + index * 53) % 256, (90 + index * 31) % 256, (150 + index * 17) % 256)
        graphics.fillRect(0, 0, width, height)
        graphics.color = Color.WHITE
        graphics.fillRect(20, 20, 30 + index * 12, 30)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    fun cbz(file: Path, pages: Int) {
        Files.createDirectories(file.parent)
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            repeat(pages) { index ->
                zip.putNextEntry(ZipEntry("%03d.png".format(index + 1)))
                zip.write(page(index))
                zip.closeEntry()
            }
        }
    }

    /** A series folder in the form Suwayomi's Local source reads: one archive per chapter. */
    fun series(localSource: Path, title: String, chapters: Int, pages: Int) {
        repeat(chapters) { index -> cbz(localSource.resolve(title).resolve("Chapter %02d.cbz".format(index + 1)), pages) }
    }

    val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
}
