package com.debridone.manga.reader

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * The copies of source pages that the manga server DebridOne runs keeps for itself
 * (`<profile>/tmp/Tachidesk/manga-cache/<source>/<series>/<chapter>/<page>.<ext>`).
 *
 * The server keeps a page once it has fetched it and hands that copy out from then on. If the
 * source closed the connection early, the copy is cut off and every later read shows the same
 * broken page. This removes such a copy, and nothing else: one page whose bytes are exactly the
 * broken ones, or one chapter's folder when asked to. The library, downloads and other chapters are
 * never touched. A server of the person's own is another computer's disk and is left alone.
 */
class ServerPageCache(private val profileDirectory: () -> Path?) {
    private val root: Path? get() = profileDirectory()?.resolve("tmp")?.resolve("Tachidesk")?.resolve("manga-cache")

    /**
     * Deletes the server's copy of page [index] (zero-based) when its bytes are exactly [bytes].
     * Comparing the bytes means only that copy can be the one removed, whatever the server named
     * the folders. Returns true when a copy was removed.
     */
    fun forgetPage(index: Int, bytes: ByteArray, chapterName: String? = null): Boolean {
        val base = root?.takeIf { it.isDirectory() } ?: return false
        val prefixes = (3..5).map { (index + 1).toString().padStart(it, '0') + "." }.toSet()
        val candidates = chapterFolders(base)
            .sortedByDescending { chapterName != null && it.name.contains(safeName(chapterName), ignoreCase = true) }
        for (folder in candidates) {
            val files = runCatching { Files.list(folder).use { stream -> stream.toList() } }.getOrDefault(emptyList())
            for (file in files) {
                if (!file.isRegularFile() || prefixes.none { file.name.startsWith(it) }) continue
                if (runCatching { Files.size(file) }.getOrDefault(-1) != bytes.size.toLong()) continue
                val same = runCatching { Files.readAllBytes(file).contentEquals(bytes) }.getOrDefault(false)
                if (same) return runCatching { Files.deleteIfExists(file) }.getOrDefault(false)
            }
        }
        return false
    }

    /**
     * Deletes the server's copies of one chapter's pages, found by the way the server names its
     * folders (source, series and chapter names made safe for a file system). Returns how many page
     * files were removed; 0 when the server has none for that chapter.
     */
    fun forgetChapter(sourceDisplayName: String, seriesTitle: String, chapterName: String, scanlator: String?): Int {
        val base = root?.takeIf { it.isDirectory() } ?: return 0
        val folderName = safeName(if (!scanlator.isNullOrBlank()) "${scanlator}_$chapterName" else chapterName)
        val series = base.resolve(safeName(sourceDisplayName)).resolve(safeName(seriesTitle))
        val exact = series.resolve(folderName)
        val folder = when {
            exact.isDirectory() -> exact
            // The source's display name can differ in how its language is written; the series and
            // chapter names are what identifies the folder.
            else -> runCatching { Files.list(base).use { it.toList() } }.getOrDefault(emptyList())
                .map { it.resolve(safeName(seriesTitle)).resolve(folderName) }
                .firstOrNull { it.isDirectory() }
        } ?: return 0
        if (!folder.normalize().startsWith(base.normalize())) return 0
        val files = runCatching { Files.list(folder).use { it.toList() } }.getOrDefault(emptyList()).filter { it.isRegularFile() }
        var removed = 0
        for (file in files) if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) removed++
        runCatching { Files.deleteIfExists(folder) }
        return removed
    }

    private fun chapterFolders(base: Path): List<Path> = runCatching {
        Files.walk(base, 3).use { stream -> stream.filter { it.isDirectory() && base.relativize(it).nameCount == 3 }.toList() }
    }.getOrDefault(emptyList())

    companion object {
        /** The server's rule for turning a name into a folder name (Tachiyomi's `buildValidFilename`). */
        fun safeName(name: String): String {
            val trimmed = name.trim('.', ' ')
            if (trimmed.isEmpty()) return "(invalid)"
            val out = StringBuilder(trimmed.length)
            for (c in trimmed) out.append(if (c.code <= 0x1f || c.code == 0x7f || c in "\"*/:<>?\\|") '_' else c)
            return out.toString().take(240)
        }
    }
}
