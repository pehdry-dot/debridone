package com.debridone.app.manga

import com.debridone.app.DebridOneRuntime
import com.debridone.app.shell.ShellSection
import com.debridone.app.shell.ShellState
import com.debridone.manga.local.LocalFiles
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Manga offers the rest of the application for files that arrive from outside it (a comic
 * archive downloaded from the user's cloud), and the one thing it asks in return.
 *
 * Manga knows no account and no cloud: it is handed a file that is already on this computer and
 * treats it like any file the user added by hand, through the same [LocalFiles] (the manga
 * server's Local source) and the same reader. The file is read where it is, so it has to stay
 * there while the series is in the library.
 */
object MangaCloudBridge {
    /** Set by the application: shows the comic archives of the user's connected accounts. Null: no such place. */
    @Volatile
    var openCloudBrowser: (() -> Unit)? = null

    /** True for a file name the reader opens (CBZ, ZIP, CBR, RAR, EPUB). */
    fun canRead(fileName: String): Boolean = LocalFiles.canRead(fileName)

    /** Why a file of this name cannot be read, in words; null when it can. */
    fun unsupportedReason(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when {
            LocalFiles.canRead(fileName) -> null
            extension == "cb7" || extension == "7z" -> "\"$fileName\" is a 7-Zip archive. The reader opens CBZ, ZIP, CBR, RAR and EPUB files; 7-Zip archives are not supported."
            else -> "\"$fileName\" is not a comic archive the reader opens (CBZ, ZIP, CBR, RAR or EPUB)."
        }
    }

    /**
     * Adds a downloaded archive (or folder) to the active profile's library and opens its page, or
     * the reader at the place the user stopped when [read] is set. Returns the series' id.
     */
    suspend fun importAndOpen(path: Path, read: Boolean, open: Boolean = true): Result<Long> = runCatching {
        val module = DebridOneRuntime.modules.manga
        val services = module.services.value ?: throw IOException("Manga is not running.")
        val profile = module.activeProfile.value ?: throw IOException("Choose a profile first.")
        // Manga's server is only started when Manga is first needed; this may be that moment.
        module.awaitServer()
        val (series, chapter) = withContext(Dispatchers.IO) {
            val series = services.local.import(profile, path, null)
            series to (services.repository.resumeChapter(series) ?: services.repository.chapters(series.id).firstOrNull())
        }
        if (open) {
            withContext(Dispatchers.Main) {
                // Before anything is opened: the first time Manga is shown for a profile it starts from Home.
                MangaNav.onProfile(profile)
                MangaNav.root(MangaRoute.Library)
                MangaNav.open(MangaRoute.SeriesPage(series.id))
                if (read && chapter != null) MangaNav.open(MangaRoute.Reader(chapter.id))
                ShellState.select(ShellSection.MANGA)
            }
        }
        series.id
    }

    /** Takes a series that came in through [importAndOpen] out of the library. Its file is the caller's business. */
    suspend fun removeImported(seriesId: Long) = withContext(Dispatchers.IO) {
        val services = DebridOneRuntime.modules.manga.services.value ?: return@withContext
        // The link DebridOne made in the server's local folder goes with it; the downloaded file is the caller's.
        runCatching { services.repository.series(seriesId)?.let { services.local.remove(it) } }
        Unit
    }
}
