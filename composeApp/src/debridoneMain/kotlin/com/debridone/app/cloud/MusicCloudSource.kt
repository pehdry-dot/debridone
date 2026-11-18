package com.debridone.app.cloud

import com.debridone.app.DebridOneRuntime
import com.debridone.app.music.MusicCloudBridge
import com.debridone.app.shell.SettingsPage
import com.debridone.app.shell.ShellSection
import com.debridone.app.shell.ShellState
import com.debridone.cloud.CloudFileRow
import com.debridone.cloud.CloudServices
import com.debridone.cloud.Download
import com.debridone.cloud.ItemKind
import com.debridone.cloud.MediaClass
import com.debridone.music.sources.CloudAudioSource
import java.io.IOException
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Cloud audio in Music, both ways.
 *
 * Towards Music it is the [CloudAudioSource] the engine was built with: which accounts the active
 * profile may use (by a name, never a sign-in), which finished audio files they hold, one file's
 * address at the moment it plays, and "save these". Music cannot ask for anything else, and its
 * addons cannot ask for anything at all.
 *
 * Towards the cloud pages it is the [MusicBridge]: play this file, add it to the library, and what
 * to do when a download of an audio file finishes or its copy is deleted.
 */
internal class MusicCloudSource(private val cloud: CloudServices) : CloudAudioSource, MusicBridge {
    override val sectionName = "Music"
    override val sectionKey = "music"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = changes.asStateFlow()

    /** Downloads the listener asked for from inside Music: their songs join the library when they arrive. */
    private val wantedByMusic = ConcurrentHashMap.newKeySet<Long>()

    init {
        // Music reads its listing again when the accounts or their files change.
        scope.launch { cloud.files.revision.collect { changes.update { it + 1 } } }
        scope.launch { cloud.accounts.accounts.collect { changes.update { it + 1 } } }
    }

    fun install() {
        CloudBridges.music = this
        MusicCloudBridge.openConnectedAccounts = { ShellState.showInPlace(SettingsPage.CONNECTED_ACCOUNTS) }
        MusicCloudBridge.openCloudFiles = {
            DownloadsUi.showShelf(MediaClass.MUSIC)
            ShellState.select(ShellSection.DOWNLOADS)
        }
        MusicCloudBridge.refreshAccounts = {
            val profileId = DebridOneRuntime.activeProfileId.value
            cloud.accounts.visibleTo(profileId).forEach { CloudApp.refresh(profileId, it, fresh = true, announce = false) }
        }
    }

    // ---------------------------------------------------------------- what Music may ask

    override fun accounts(profileId: Long): List<CloudAudioSource.Account> {
        val visible = cloud.accounts.visibleTo(profileId)
        return visible.map { account ->
            // The provider's name is enough unless the profile has two accounts there.
            val name = if (visible.count { it.provider == account.provider } > 1) "${account.provider.displayName} (${account.label})" else account.provider.displayName
            CloudAudioSource.Account(account.id.toString(), name)
        }
    }

    override fun audioFiles(profileId: Long, accountKey: String): List<CloudAudioSource.File> =
        rows(profileId, accountKey).map { CloudAudioSource.File(fileKey(it.kind, it.itemRemoteId, it.ref), musicPath(it), it.sizeBytes, it.mimeType) }

    override fun address(profileId: Long, accountKey: String, fileKey: String): String {
        val row = row(profileId, accountKey, fileKey) ?: throw IOException("This file is no longer in your account.")
        cloud.downloads.localCopy(profileId, row)?.takeIf { it.fileExists }?.let { return it.destination }
        return cloud.files.resolve(profileId, row.id).url
    }

    override fun download(profileId: Long, accountKey: String, fileKeys: List<String>): String {
        val rows = fileKeys.mapNotNull { row(profileId, accountKey, it) }
        if (rows.isEmpty()) throw IOException("These files are no longer in your account.")
        val (present, missing) = rows.partition { cloud.downloads.localCopy(profileId, it)?.fileExists == true }
        // A copy that is already here only has to be made known to Music.
        present.forEach { row ->
            val copy = cloud.downloads.localCopy(profileId, row) ?: return@forEach
            scope.launch { adopt(copy, addToLibrary = true) }
        }
        val result = cloud.downloads.enqueue(profileId, missing.map { it.id })
        wantedByMusic += result.queued.map { it.id }
        return listOfNotNull(
            if (result.queued.isNotEmpty()) "${plural(result.queued.size, "download")} started; ${if (result.queued.size == 1) "it is" else "they are"} listed under Downloads." else null,
            if (present.isNotEmpty()) "${plural(present.size, "song")} already on this computer." else null,
            result.problems.firstOrNull(),
        ).joinToString(" ").ifEmpty { "Nothing to download." }
    }

    // ---------------------------------------------------------------- what the cloud pages may ask

    override fun play(profileId: Long, files: List<CloudFileRow>) {
        CloudApp.background { MusicCloudBridge.play(profileId, files.map(::key))?.let(CloudApp::say) }
    }

    override fun playLocal(profileId: Long, download: Download) {
        CloudApp.background {
            if (!download.fileExists) return@background CloudApp.say("${download.fileName} is no longer on this computer.")
            val key = key(download)
            if (MusicCloudBridge.fileSaved(profileId, key, Paths.get(download.destination), addToLibrary = false) && MusicCloudBridge.playSaved(profileId, key)) return@background
            // Not (or no longer) listed in an account: it is played as the file it is, which puts it in the library.
            val problem = MusicCloudBridge.playFile(profileId, Paths.get(download.destination))
            if (problem == null) cloud.downloads.markImported(download.id, sectionKey, IMPORTED_AS_FILE) else CloudApp.say(problem)
        }
    }

    override suspend fun addCloudFilesToLibrary(profileId: Long, files: List<CloudFileRow>): String = MusicCloudBridge.addToLibrary(profileId, files.map(::key))

    override suspend fun addToLibrary(profileId: Long, download: Download): String {
        if (!download.fileExists) return "${download.fileName} is no longer on this computer."
        return if (adopt(download, addToLibrary = true)) "${download.fileName} is in your Music library." else "${download.fileName} is not an audio file Music can read."
    }

    override suspend fun removeFromLibrary(profileId: Long, download: Download) {
        // As the cloud song it was added as, and as the file it may have become since (its cloud entry or its account went away).
        if (download.importRef != IMPORTED_AS_FILE) MusicCloudBridge.removeFromLibrary(profileId, key(download))
        MusicCloudBridge.removeFile(profileId, Paths.get(download.destination))
    }

    override suspend fun downloadFinished(download: Download, addToLibrary: Boolean): String? {
        val wanted = wantedByMusic.remove(download.id)
        val added = adopt(download, addToLibrary = wanted || addToLibrary)
        return if (added && (wanted || addToLibrary)) "${download.fileName} is saved and in your Music library." else null
    }

    override suspend fun localCopyDeleted(profileId: Long, download: Download) {
        MusicCloudBridge.fileSaved(profileId, key(download), null, addToLibrary = false)
    }

    /**
     * Makes a finished download known to Music: as the saved copy of the cloud song Music already
     * lists, or (with [addToLibrary], when Music lists no such song) as a file on this computer.
     * True when the song is in the library afterwards or Music now plays it from the file.
     */
    private suspend fun adopt(download: Download, addToLibrary: Boolean): Boolean {
        val path = Paths.get(download.destination)
        if (MusicCloudBridge.fileSaved(download.profileId, key(download), path, addToLibrary)) {
            if (addToLibrary) cloud.downloads.markImported(download.id, sectionKey, IMPORTED_AS_CLOUD_SONG)
            return true
        }
        if (!addToLibrary || MusicCloudBridge.importFile(download.profileId, path) == null) return false
        cloud.downloads.markImported(download.id, sectionKey, IMPORTED_AS_FILE)
        return true
    }

    // ---------------------------------------------------------------- names

    private fun rows(profileId: Long, accountKey: String): List<CloudFileRow> {
        val accountId = accountKey.toLongOrNull() ?: return emptyList()
        return cloud.files.filesOf(profileId, setOf(MediaClass.MUSIC), accountId = accountId)
    }

    private fun row(profileId: Long, accountKey: String, fileKey: String): CloudFileRow? {
        val kind = fileKey.substringBefore(':')
        val itemRemoteId = fileKey.substringAfter(':').substringBeforeLast(':')
        return rows(profileId, accountKey).firstOrNull { it.kind.key == kind && it.itemRemoteId == itemRemoteId && fileKey(it.kind, it.itemRemoteId, it.ref) == fileKey }
    }

    private fun key(file: CloudFileRow) = MusicCloudBridge.Key(file.accountId.toString(), fileKey(file.kind, file.itemRemoteId, file.ref))

    private fun key(download: Download) = MusicCloudBridge.Key(download.accountId.toString(), fileKey(download.kind, download.itemRemoteId, download.fileRef))

    /**
     * The name Music knows a file by: its entry and a short fingerprint of the provider's own
     * reference to the file. The reference itself can be a private link and is not handed over.
     */
    private fun fileKey(kind: ItemKind, itemRemoteId: String, ref: String): String =
        "${kind.key}:$itemRemoteId:" + MessageDigest.getInstance("SHA-1").digest(ref.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** A file's path as Music reads names from it: a file alone in its entry gets the entry's name as its folder. */
    private fun musicPath(file: CloudFileRow): String {
        val path = file.path.trim('/')
        return if ('/' in path || file.kind == ItemKind.LINK || file.itemName.equals(file.name, ignoreCase = true)) path else "${file.itemName}/$path"
    }

    private companion object {
        const val IMPORTED_AS_CLOUD_SONG = "cloud"
        const val IMPORTED_AS_FILE = "file"
    }
}
