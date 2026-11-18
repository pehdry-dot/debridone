package com.debridone.app.cloud

import com.debridone.cloud.CloudFileRow
import com.debridone.cloud.CloudServices
import com.debridone.cloud.Download
import com.debridone.cloud.DownloadState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What happens by itself when a download finishes: a comic the user asked to read is opened, and
 * a file whose shelf the profile set to "add to the library" joins that section's library.
 * Nothing else is ever done without being asked.
 */
internal object CloudAutomation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Downloads the user started in order to read the file: opened in Manga when they finish. */
    private val readWhenDone = ConcurrentHashMap.newKeySet<Long>()
    private val seen = ConcurrentHashMap<Long, DownloadState>()

    fun start(cloud: CloudServices) {
        // Each section is reached through its own narrow bridge; none of them sees the accounts.
        StreamingCloudBridge(cloud).install()
        MangaBridgeImpl(cloud).install()
        scope.launch {
            cloud.downloads.downloads.collect { downloads ->
                for (download in downloads) {
                    val before = seen.put(download.id, download.state)
                    // Only a download that finished while DebridOne was watching: not the history read at start-up.
                    if (download.state == DownloadState.DONE && before != null && before != DownloadState.DONE) finished(cloud, download)
                }
            }
        }
    }

    private suspend fun finished(cloud: CloudServices, download: Download) {
        val manga = CloudBridges.manga
        if (readWhenDone.remove(download.id) && manga != null) {
            runCatching { manga.read(download.profileId, download) }.fold({ it?.let(CloudApp::say) }, { CloudApp.say("${download.fileName} was downloaded but could not be opened: ${it.message}") })
            return
        }
        val importer = CloudBridges.importer(download.mediaClass, download.fileName) ?: return
        if (!cloud.downloads.settings.autoImport(download.profileId, if (importer === manga) com.debridone.cloud.MediaClass.COMIC else download.mediaClass)) return
        runCatching { importer.addToLibrary(download.profileId, download) }.onSuccess(CloudApp::say)
    }

    /**
     * "Read" on a comic of the cloud. With a copy on this computer it opens at once. Without one
     * the user is told first that the whole archive is downloaded, and it opens when that is done.
     */
    fun read(cloud: CloudServices, profileId: Long, file: CloudFileRow, copy: Download?) {
        val manga = CloudBridges.manga ?: return
        manga.unsupportedReason(file.name)?.let { return CloudApp.say(it) }
        if (copy != null) {
            CloudApp.background { manga.read(profileId, copy)?.let(CloudApp::say) }
        } else {
            DownloadsUi.dialog = DownloadsDialog.DownloadToRead(file)
        }
    }

    /** The user agreed to the download that reading needs. */
    fun downloadAndRead(cloud: CloudServices, profileId: Long, file: CloudFileRow) {
        val result = cloud.downloads.enqueue(profileId, listOf(file.id))
        val queued = result.queued.firstOrNull()
        if (queued == null) {
            CloudApp.say(result.problems.firstOrNull() ?: "The download could not be started.")
        } else {
            readWhenDone += queued.id
            CloudApp.say("Downloading ${file.name}. It opens in Manga when the download is complete.")
        }
    }
}
