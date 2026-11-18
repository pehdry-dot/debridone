package com.debridone.cloud

import com.debridone.platform.AppPaths
import com.debridone.platform.db.OpenOutcome
import com.debridone.platform.secrets.ScopedSecretStore
import com.debridone.platform.secrets.SecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The cloud layer of the application: the connected accounts, what is in them, and the download
 * manager. One instance for the whole application; every call names the profile it is for.
 *
 * It is application-level infrastructure. It does not know Streaming, Music or Manga, and their
 * engines do not know it: the application hands each of them only its own kind of file.
 */
class CloudServices private constructor(
    private val store: CloudStore,
    val accounts: DebridAccountManager,
    val files: CloudFileRepository,
    val downloads: DownloadManager,
    private val scope: CoroutineScope,
) : AutoCloseable {
    /** How the database was opened (created, recovered from a backup, ...), for the storage notice. */
    val outcome: OpenOutcome get() = store.outcome

    /** Disconnects an account: credentials erased, cache dropped, unfinished transfers of it stopped. */
    fun disconnect(profileId: Long?, accountId: Long) {
        accounts.disconnect(profileId, accountId)
        downloads.accountRemoved(accountId)
        files.changed()
    }

    /** A profile was deleted: its own accounts, queue, history, settings and partial files go with it. */
    fun deleteProfile(profileId: Long) {
        accounts.accounts.value.filter { it.scopeProfileId == profileId }.forEach { downloads.accountRemoved(it.id) }
        accounts.deleteProfile(profileId)
        downloads.deleteProfile(profileId)
        files.changed()
    }

    override fun close() {
        downloads.close()
        scope.cancel()
        store.close()
    }

    companion object {
        /** The secret-store namespace of the cloud layer. No module can read it. */
        const val SECRET_NAMESPACE = "app.cloud"

        fun open(
            paths: AppPaths,
            secrets: SecretStore,
            endpoints: CloudEndpoints = CloudEndpoints.fromEnvironment(),
            clock: () -> Long = System::currentTimeMillis,
            /** Where downloads go until the user chooses otherwise. */
            defaultDownloadFolder: Path = Paths.get(System.getProperty("user.home"), "Downloads", "DebridOne"),
            providers: List<CloudProvider> = listOf(RealDebridClient(endpoints.realDebrid, clock), TorBoxClient(endpoints.torBox, clock)),
            freeSpace: (Path) -> Long = { Files.getFileStore(it).usableSpace },
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            retryDelayMs: ((Int) -> Long)? = null,
        ): CloudServices {
            val store = CloudStore.open(paths)
            val accounts = DebridAccountManager(store, ScopedSecretStore(secrets, SECRET_NAMESPACE), providers, clock)
            val files = CloudFileRepository(store, accounts, clock)
            val settings = DownloadSettings(store, defaultDownloadFolder)
            val downloads = if (retryDelayMs != null) DownloadManager(store, files, settings, scope, clock, freeSpace, retryDelayMs) else DownloadManager(store, files, settings, scope, clock, freeSpace)
            downloads.start()
            return CloudServices(store, accounts, files, downloads, scope)
        }
    }
}
