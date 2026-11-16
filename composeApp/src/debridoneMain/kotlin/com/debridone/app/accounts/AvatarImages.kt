package com.debridone.app.accounts

import com.debridone.accounts.AccountsDatabase
import com.debridone.platform.AppPaths
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Profile pictures imported from this computer, stored under `accounts/avatars`. */
internal object AvatarImages {
    private const val MAX_SOURCE_BYTES = 20L * 1024 * 1024
    private const val OUTPUT_SIZE = 512
    private val EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp")

    private fun directory(paths: AppPaths): Path = paths.accountsDir.resolve("avatars")

    /** Shows the system file chooser and returns the `file:` URL of the stored copy, or null. */
    suspend fun pickAndImport(paths: AppPaths): String? = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, "Choose a profile picture", FileDialog.LOAD).apply {
            setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in EXTENSIONS }
            isMultipleMode = false
            isVisible = true
        }
        val name = dialog.file ?: return@withContext null
        import(paths, File(dialog.directory, name).toPath())
    }

    /**
     * Decodes [source] and stores a square PNG copy. Re-encoding means only pixels are kept: the
     * original file, its metadata and anything else it may contain never enter the app's data.
     */
    fun import(paths: AppPaths, source: Path): String? {
        if (!source.isRegularFile() || Files.size(source) > MAX_SOURCE_BYTES) return null
        val decoded = runCatching { ImageIO.read(source.toFile()) }.getOrNull() ?: return null
        val side = minOf(decoded.width, decoded.height)
        if (side <= 0) return null
        val x = (decoded.width - side) / 2
        val y = (decoded.height - side) / 2
        val output = BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(decoded, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE, x, y, x + side, y + side, null)
        } finally {
            graphics.dispose()
        }
        val dir = directory(paths)
        Files.createDirectories(dir)
        val target = dir.resolve("${UUID.randomUUID()}.png")
        if (!ImageIO.write(output, "png", target.toFile())) return null
        return target.toUri().toString()
    }

    /** Deletes imported pictures that no profile uses any more. */
    fun sweepUnused(paths: AppPaths, accounts: AccountsDatabase) {
        val dir = directory(paths)
        if (!Files.isDirectory(dir)) return
        val inUse = accounts.listAccounts()
            .flatMap { accounts.listProfiles(it.id) }
            .mapNotNull { it.avatarUrl }
            .toSet()
        val cutoff = System.currentTimeMillis() - 10 * 60 * 1000
        dir.listDirectoryEntries("*.png").forEach { file ->
            // Keep fresh files: a picture is imported before its profile is saved.
            val fresh = Files.getLastModifiedTime(file).toMillis() > cutoff
            if (!fresh && file.toUri().toString() !in inUse) runCatching { Files.deleteIfExists(file) }
        }
    }
}
