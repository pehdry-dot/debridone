package com.debridone.music.library

import com.debridone.platform.db.SqliteDatabase
import com.debridone.platform.db.getNullableLong
import com.debridone.platform.io.AtomicFiles
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.roundToInt

/**
 * Cover images of one module, stored once per distinct picture.
 *
 * An album's tracks usually embed the same cover; hashing the bytes means it is decoded, scaled and
 * written a single time. Each picture is kept as a display copy and a small thumbnail for grids,
 * together with its dominant colour for artwork-tinted backgrounds.
 */
class ArtworkStore(
    private val database: SqliteDatabase,
    private val directory: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        Files.createDirectories(directory)
        ImageIO.setUseCache(false)
    }

    /**
     * Stores [bytes] for [profileId] and returns the artwork id, or null when the data is not an
     * image this build can decode.
     */
    fun store(profileId: Long, bytes: ByteArray, origin: String): Long? {
        if (bytes.isEmpty() || bytes.size > MAX_SOURCE_BYTES) return null
        val hash = sha256(bytes)
        existingId(profileId, hash)?.let { return it }

        val fileName = "$hash.jpg"
        val thumbName = "$hash.thumb.jpg"
        val file = directory.resolve(fileName)
        val thumb = directory.resolve(thumbName)
        val dimensions: Pair<Int, Int>
        val colour: Int?

        if (Files.isRegularFile(file) && Files.isRegularFile(thumb)) {
            // Another profile already has this picture on disk.
            val known = database.queryOne(
                "SELECT width, height, dominant_color FROM music_artwork WHERE hash = ? LIMIT 1", hash,
            ) { rs -> Triple(rs.getInt(1), rs.getInt(2), rs.getNullableLong("dominant_color")?.toInt()) }
            if (known != null) {
                dimensions = known.first to known.second
                colour = known.third
            } else {
                val image = decode(Files.readAllBytes(file)) ?: return null
                dimensions = image.width to image.height
                colour = dominantColour(image)
            }
        } else {
            val image = decode(bytes) ?: return null
            val display = scaleToFit(image, MAX_DISPLAY_SIZE)
            val small = scaleToFit(display, THUMB_SIZE)
            AtomicFiles.writeBytes(file, encodeJpeg(display, 0.9f), keepBackup = false)
            AtomicFiles.writeBytes(thumb, encodeJpeg(small, 0.85f), keepBackup = false)
            dimensions = display.width to display.height
            colour = dominantColour(small)
        }

        return database.transaction {
            existingId(profileId, hash) ?: insert(
                "INSERT INTO music_artwork (profile_id, hash, file, thumb, width, height, dominant_color, origin, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                profileId, hash, fileName, thumbName, dimensions.first, dimensions.second, colour?.toLong(), origin, clock(),
            )
        }
    }

    fun storeFile(profileId: Long, image: Path, origin: String): Long? {
        if (!Files.isRegularFile(image) || Files.size(image) > MAX_SOURCE_BYTES) return null
        return store(profileId, Files.readAllBytes(image), origin)
    }

    fun find(profileId: Long, id: Long): ArtworkRef? = database.queryOne(
        "SELECT id, file, thumb, dominant_color FROM music_artwork WHERE profile_id = ? AND id = ?", profileId, id,
    ) { rs -> ref(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getNullableLong("dominant_color")?.toInt()) }

    /** Builds the reference from columns selected by a joined query. */
    fun ref(id: Long, file: String, thumb: String, dominantColor: Int?): ArtworkRef =
        ArtworkRef(id, directory.resolve(thumb).toString(), directory.resolve(file).toString(), dominantColor)

    /** Removes artwork rows nothing points to any more, and image files no profile uses. */
    fun deleteUnreferenced(profileId: Long): Int {
        val removed = database.execute(
            """
            DELETE FROM music_artwork WHERE profile_id = ?1
              AND id NOT IN (SELECT artwork_id FROM music_tracks WHERE profile_id = ?1 AND artwork_id IS NOT NULL)
              AND id NOT IN (SELECT artwork_id FROM music_albums WHERE profile_id = ?1 AND artwork_id IS NOT NULL)
              AND id NOT IN (SELECT artwork_id FROM music_artists WHERE profile_id = ?1 AND artwork_id IS NOT NULL)
              AND id NOT IN (SELECT artwork_id FROM music_playlists WHERE profile_id = ?1 AND artwork_id IS NOT NULL)
            """.trimIndent(),
            profileId,
        )
        if (removed > 0) deleteOrphanFiles()
        return removed
    }

    fun deleteProfile(profileId: Long) {
        database.execute("DELETE FROM music_artwork WHERE profile_id = ?", profileId)
        deleteOrphanFiles()
    }

    private fun deleteOrphanFiles() {
        val used = database.query("SELECT DISTINCT hash FROM music_artwork") { it.getString(1) }.toSet()
        runCatching {
            Files.newDirectoryStream(directory, "*.jpg").use { files ->
                for (file in files) {
                    val hash = file.fileName.toString().substringBefore('.')
                    if (hash !in used) runCatching { Files.deleteIfExists(file) }
                }
            }
        }
    }

    private fun existingId(profileId: Long, hash: String): Long? = database.queryOne(
        "SELECT id FROM music_artwork WHERE profile_id = ? AND hash = ?", profileId, hash,
    ) { it.getLong(1) }

    private fun decode(bytes: ByteArray): BufferedImage? = runCatching {
        ImageIO.read(ByteArrayInputStream(bytes))?.takeIf { it.width > 0 && it.height > 0 }
    }.getOrNull()

    /** Returns an opaque RGB copy no larger than [maxSide] on its longest side. */
    private fun scaleToFit(source: BufferedImage, maxSide: Int): BufferedImage {
        val longest = maxOf(source.width, source.height)
        val scale = if (longest > maxSide) maxSide.toDouble() / longest else 1.0
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        if (scale == 1.0 && source.type == BufferedImage.TYPE_INT_RGB) return source
        // Halve repeatedly before the final step: one big bilinear jump aliases badly.
        var current = source
        var currentWidth = source.width
        var currentHeight = source.height
        while (currentWidth / 2 >= width && currentHeight / 2 >= height) {
            currentWidth /= 2
            currentHeight /= 2
            current = draw(current, currentWidth, currentHeight)
        }
        return draw(current, width, height)
    }

    private fun draw(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.color = java.awt.Color.BLACK
            graphics.fillRect(0, 0, width, height)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val output = ByteArrayOutputStream()
        try {
            val parameters = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            MemoryCacheImageOutputStream(output).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), parameters)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }

    /**
     * The colour a background should take from this cover: the average of its pixels, weighted
     * towards saturated ones so a mostly black or white sleeve still yields its accent colour.
     */
    private fun dominantColour(image: BufferedImage): Int? {
        val sample = scaleToFit(image, 24)
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var weightSum = 0.0
        for (y in 0 until sample.height) {
            for (x in 0 until sample.width) {
                val rgb = sample.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val saturation = if (max == 0) 0.0 else (max - min).toDouble() / max
                val weight = 0.05 + saturation * (max / 255.0)
                red += r * weight
                green += g * weight
                blue += b * weight
                weightSum += weight
            }
        }
        if (weightSum <= 0.0) return null
        val r = (red / weightSum).roundToInt().coerceIn(0, 255)
        val g = (green / weightSum).roundToInt().coerceIn(0, 255)
        val b = (blue / weightSum).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val ORIGIN_EMBEDDED = "embedded"
        const val ORIGIN_FOLDER = "folder"
        const val ORIGIN_CUSTOM = "custom"
        const val ORIGIN_ONLINE = "online"
        const val ORIGIN_SOURCE = "source"

        private const val MAX_SOURCE_BYTES = 40L * 1024 * 1024
        private const val MAX_DISPLAY_SIZE = 1200
        private const val THUMB_SIZE = 320
    }
}
