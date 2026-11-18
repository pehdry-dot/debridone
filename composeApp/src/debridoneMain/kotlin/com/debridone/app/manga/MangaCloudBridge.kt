package com.debridone.app.manga

import com.debridone.app.DebridOneRuntime
import com.debridone.app.shell.ShellSection
import com.debridone.app.shell.ShellState
import com.debridone.manga.local.LocalLibrary
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Manga offers the rest of the application for files that arrive from outside it (a comic
 * archive downloaded from the user's cloud), and the one thing it asks in return.
 *
 * Manga knows no account and no cloud: it is handed a file that is already on this computer and
 * treats it like any file the user added by hand, through the same [LocalLibrary] and the same
 * reader. The file is read where it is, so it has to stay there while the series is in the library.
 */
object MangaCloudBridge {
    /** Set by the application: shows the comic archives of the user's connected accounts. Null: no such place. */
    @Volatile
    var openCloudBrowser: (() -> Unit)? = null

    /** True for a file name the reader opens (CBZ, ZIP). */
    fun canRead(fileName: String): Boolean = LocalLibrary.extension(fileName) in LocalLibrary.ARCHIVE_EXTENSIONS

    /** Why a file of this name cannot be read, in words; null when it can. */
    fun unsupportedReason(fileName: String): String? {
        val extension = LocalLibrary.extension(fileName)
        return when {
            extension in LocalLibrary.ARCHIVE_EXTENSIONS -> null
            extension in LocalLibrary.UNSUPPORTED_ARCHIVE_EXTENSIONS ->
                "\"$fileName\" is a ${extension.uppercase()} archive. DebridOne reads CBZ and ZIP; RAR and 7-Zip archives (CBR, CB7) are not supported. " +
                    "A .cbr file that is really a ZIP archive can be read once it is downloaded."
            else -> "\"$fileName\" is not a CBZ or ZIP archive."
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
        val (series, chapter) = withContext(Dispatchers.IO) {
            if (Files.isRegularFile(path) && !LocalLibrary.isReadableArchive(path)) throw IOException(LocalLibrary.unsupportedMessage(path))
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
        DebridOneRuntime.modules.manga.services.value?.repository?.deleteSeries(seriesId)
        Unit
    }
}
