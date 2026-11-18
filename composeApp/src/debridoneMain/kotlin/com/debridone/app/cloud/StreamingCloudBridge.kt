package com.debridone.app.cloud

import com.debridone.app.DebridOneRuntime
import com.debridone.app.shell.ShellSection
import com.debridone.app.shell.ShellState
import com.debridone.cloud.CloudFileRow
import com.debridone.cloud.CloudQuery
import com.debridone.cloud.CloudServices
import com.debridone.cloud.Download
import com.debridone.cloud.ItemKind
import com.debridone.cloud.ProviderId
import com.nuvio.app.AppShellHost
import com.nuvio.app.ShellPlaybackRequest
import java.io.File
import java.security.MessageDigest

/**
 * Cloud video in the Streaming player. Streaming is handed one file at a time: an address made
 * for the moment (or the local copy), a title, and ids by which it recognises the same file again.
 * The player, the watch progress and Continue Watching are Streaming's own; no account, key or
 * listing crosses over, and Streaming's own debrid settings are not involved.
 */
internal class StreamingCloudBridge(private val cloud: CloudServices) : VideoBridge {
    fun install() {
        CloudBridges.video = this
        AppShellHost.resumeShellContent = ::resume
    }

    override fun play(profileId: Long, file: CloudFileRow, local: Download?) = play(profileId, file, local, fromBeginning = false)

    private fun play(profileId: Long, file: CloudFileRow, local: Download?, fromBeginning: Boolean) {
        CloudApp.background {
            val address = local?.takeIf { it.fileExists }?.let { File(it.destination).toURI().toString() } ?: cloud.files.resolve(profileId, file.id).url
            val contentId = contentId(file.provider, file.kind, file.itemRemoteId)
            request(file.name, file.itemName, address, file.provider, contentId, videoId(contentId, file.ref), fromBeginning)
        }
    }

    override fun playLocal(profileId: Long, download: Download) {
        if (!download.fileExists) return CloudApp.say("${download.fileName} is no longer on this computer.")
        val contentId = contentId(download.provider, download.kind, download.itemRemoteId)
        request(download.fileName, download.itemName, File(download.destination).toURI().toString(), download.provider, contentId, videoId(contentId, download.fileRef), fromBeginning = false)
    }

    private fun request(title: String, entry: String, address: String, provider: ProviderId, contentId: String, videoId: String, fromBeginning: Boolean) {
        AppShellHost.requestPlayback(
            ShellPlaybackRequest(
                title = title, subtitle = entry.takeIf { it != title }, sourceUrl = address,
                providerName = provider.displayName, providerId = provider.key,
                contentId = contentId, videoId = videoId, startFromBeginning = fromBeginning,
            ),
        )
        ShellState.select(ShellSection.STREAMING)
    }

    /**
     * Continue Watching in Streaming, on a file played from here: it is found again among the
     * active profile's accounts (or its downloads) and played with a new address.
     */
    private fun resume(contentId: String, videoId: String, fromBeginning: Boolean): Boolean {
        val profileId = DebridOneRuntime.activeProfileId.value ?: return false
        val parts = contentId.removePrefix(AppShellHost.SHELL_CONTENT_PREFIX).split(':', limit = 3)
        val provider = ProviderId.of(parts.getOrNull(0).orEmpty()) ?: return false
        val kind = ItemKind.of(parts.getOrNull(1).orEmpty()) ?: return false
        val remoteId = parts.getOrNull(2) ?: return false
        val downloads = cloud.downloads.forProfile(profileId)
        val item = cloud.files.items(profileId, CloudQuery(provider = provider, kinds = setOf(kind))).firstOrNull { it.remoteId == remoteId }
        val file = item?.let { cloud.files.files(profileId, it.id).firstOrNull { file -> videoId(contentId, file.ref) == videoId } }
        if (file != null) {
            play(profileId, file, cloud.downloads.localCopy(profileId, file), fromBeginning)
            return true
        }
        // Gone from the cloud, but perhaps still on this computer.
        val local = downloads.firstOrNull { it.fileExists && it.provider == provider && it.kind == kind && it.itemRemoteId == remoteId && videoId(contentId, it.fileRef) == videoId } ?: return false
        playLocal(profileId, local)
        return true
    }

    private fun contentId(provider: ProviderId, kind: ItemKind, itemRemoteId: String) = "${AppShellHost.SHELL_CONTENT_PREFIX}${provider.key}:${kind.key}:$itemRemoteId"

    /** The file inside its entry, as a short fingerprint: the provider's own reference is private and is not handed over. */
    private fun videoId(contentId: String, ref: String): String =
        contentId + ":" + MessageDigest.getInstance("SHA-1").digest(ref.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
