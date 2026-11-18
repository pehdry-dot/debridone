package com.debridone.app.cloud

import com.debridone.app.manga.MangaCloudBridge
import com.debridone.app.shell.ShellSection
import com.debridone.app.shell.ShellState
import com.debridone.cloud.CloudServices
import com.debridone.cloud.Download
import com.debridone.cloud.MediaClass
import java.nio.file.Paths

/**
 * Comic archives of the cloud in Manga. Manga is handed a file that is already on this computer
 * and nothing else: it imports it into its own library with its own reader, details and progress.
 */
internal class MangaBridgeImpl(private val cloud: CloudServices) : MangaBridge {
    override val sectionName = "Manga"
    override val sectionKey = "manga"

    fun install() {
        CloudBridges.manga = this
        // Manga's "From your cloud…" leads to the one file browser, on the comics shelf.
        MangaCloudBridge.openCloudBrowser = {
            DownloadsUi.showShelf(MediaClass.COMIC)
            ShellState.select(ShellSection.DOWNLOADS)
        }
    }

    override fun canRead(fileName: String): Boolean = MangaCloudBridge.canRead(fileName)

    override fun unsupportedReason(fileName: String): String? = MangaCloudBridge.unsupportedReason(fileName)

    override suspend fun read(profileId: Long, download: Download): String? = open(download, read = true, show = true).exceptionOrNull()?.message

    override suspend fun addToLibrary(profileId: Long, download: Download): String =
        open(download, read = false, show = false).fold({ "${download.fileName} is in your Manga library." }, { it.message ?: "${download.fileName} could not be added to Manga." })

    private suspend fun open(download: Download, read: Boolean, show: Boolean): Result<Long> {
        if (!download.fileExists) return Result.failure(java.io.IOException("${download.fileName} is no longer on this computer."))
        return MangaCloudBridge.importAndOpen(Paths.get(download.destination), read, show).onSuccess { seriesId ->
            cloud.downloads.markImported(download.id, sectionKey, seriesId.toString())
        }
    }

    override suspend fun removeFromLibrary(profileId: Long, download: Download) {
        download.importRef?.toLongOrNull()?.let { MangaCloudBridge.removeImported(it) }
    }
}
