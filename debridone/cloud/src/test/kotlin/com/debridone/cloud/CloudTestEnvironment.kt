package com.debridone.cloud

import com.debridone.cloud.testing.StandInRealDebrid
import com.debridone.cloud.testing.StandInTorBox
import com.debridone.platform.AppPaths
import com.debridone.platform.secrets.FileSecretStore
import com.debridone.platform.secrets.ScopedSecretStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * A throwaway DebridOne data directory, a stand-in Real-Debrid and a stand-in TorBox, and the
 * cloud layer opened on them. Opening it again simulates restarting the application.
 */
class CloudTestEnvironment : AutoCloseable {
    val root: Path = Files.createTempDirectory("debridone-cloud-test")
    private val paths = AppPaths(root.resolve("data"), root.resolve("cache"))
    private val secrets = FileSecretStore(root.resolve("secrets"))
    val realDebrid = StandInRealDebrid()
    val torBox = StandInTorBox()
    val downloadFolder: Path = root.resolve("Downloads")

    /** What the disk reports as free. */
    @Volatile
    var freeBytes: Long = 500L * 1024 * 1024 * 1024

    private var services: CloudServices? = null

    fun open(): CloudServices {
        services?.close()
        return CloudServices.open(
            paths, secrets,
            defaultDownloadFolder = downloadFolder,
            // No spacing between requests and no real pauses: the stand-ins are on this machine.
            providers = listOf(RealDebridClient(realDebrid.origin, pause = {}, spacingMs = 0), TorBoxClient(torBox.origin, pause = {}, spacingMs = 0)),
            freeSpace = { freeBytes },
            retryDelayMs = { 20 },
        ).also { services = it }
    }

    /** Signs in to the stand-in Real-Debrid with a code, the way a user does. */
    fun connectRealDebrid(cloud: CloudServices, profileId: Long? = PROFILE_A, shared: Boolean = false): CloudAccount {
        val pending = cloud.accounts.beginSignIn(ProviderId.REAL_DEBRID)
        realDebrid.approved = true
        return checkNotNull(cloud.accounts.finishSignIn(pending, profileId, shared))
    }

    fun connectTorBox(cloud: CloudServices, profileId: Long? = PROFILE_A, shared: Boolean = false): CloudAccount =
        cloud.accounts.connectWithKey(ProviderId.TORBOX, StandInTorBox.API_KEY, profileId, shared)

    /** Reads a secret of the cloud layer straight from the secret store. */
    fun storedSecret(accountId: Long): String? = ScopedSecretStore(secrets, CloudServices.SECRET_NAMESPACE).get("account-$accountId")

    /** Every credential the stand-ins have handed out or accept. */
    fun secretTexts(): List<String> = listOfNotNull(
        realDebrid.accessToken, realDebrid.refreshToken, StandInRealDebrid.CLIENT_SECRET, StandInRealDebrid.DEVICE_CODE, StandInRealDebrid.PRIVATE_TOKEN, StandInTorBox.API_KEY,
    )

    /** Every byte written under the data folder, to prove a credential or an address is not among them. */
    fun dataFolderContains(text: String): Boolean {
        val needle = text.toByteArray()
        return Files.walk(root.resolve("data")).use { files ->
            files.filter { Files.isRegularFile(it) }.anyMatch { file ->
                val bytes = Files.readAllBytes(file)
                (0..bytes.size - needle.size).any { start -> needle.indices.all { bytes[start + it] == needle[it] } }
            }
        }
    }

    override fun close() {
        services?.close()
        services = null
        realDebrid.close()
        torBox.close()
        root.toFile().deleteRecursively()
    }

    companion object {
        const val PROFILE_A = 1001L
        const val PROFILE_B = 1002L
    }
}

/** Polls until [condition] holds; fails with [describe] after [timeoutMs]. */
fun awaitTrue(timeoutMs: Long = 10_000, describe: () -> String = { "condition" }, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(10)
    }
    throw AssertionError("Timed out after $timeoutMs ms waiting for ${describe()}")
}
