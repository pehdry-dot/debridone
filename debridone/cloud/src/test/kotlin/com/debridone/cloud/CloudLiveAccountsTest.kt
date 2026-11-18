package com.debridone.cloud

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * Read-only checks against the **real** services, with a real account. Off by default; run with
 *
 * ```
 * DEBRIDONE_LIVE_CLOUD=1 DEBRIDONE_LIVE_RD_TOKEN=… DEBRIDONE_LIVE_TORBOX_KEY=… ./gradlew :debridone:cloud:test --tests "*CloudLiveAccountsTest"
 * ```
 *
 * (Where a network proxy adds the credentials itself, the two variables can be left out.) They
 * read the account, list what is in it and ask for one download address; nothing is added or
 * removed. What they print is counts and kinds only: no file name, user name or address.
 */
class CloudLiveAccountsTest {
    private fun enabled() = Assume.assumeTrue("live cloud checks are off", System.getenv("DEBRIDONE_LIVE_CLOUD") == "1")

    private fun extensions(files: List<CloudFile>) = files.groupingBy { MediaFileClassifier.extension(it.path).ifEmpty { "-" } }.eachCount().entries.sortedByDescending { it.value }.take(8)

    @Test
    fun realDebridAccountIsReadAsDocumented() {
        enabled()
        val client = RealDebridClient()
        val credentials = client.credentialsFromKey(System.getenv("DEBRIDONE_LIVE_RD_TOKEN") ?: PLACEHOLDER)

        val account = client.account(credentials)
        println("RD account: id=${account.remoteUserId.isNotEmpty()} user=${account.username != null} email=${account.email != null} plan=${account.plan} premium=${account.premium} until known=${account.premiumUntil != null} details=${account.details.keys}")
        assertTrue(account.remoteUserId.isNotEmpty())

        val torrents = client.list(credentials, ItemKind.TORRENT)
        println("RD torrents: ${torrents.size}, by status ${torrents.groupingBy { it.status }.eachCount()}, raw ${torrents.groupingBy { it.rawStatus }.eachCount()}, dates known ${torrents.count { it.createdAt != null }}/${torrents.count { it.completedAt != null }}, progress ${torrents.mapNotNull { it.progress }.toSet()}")
        val listed = torrents.filter { it.files == null }.associateWith { client.files(credentials, it) }
        val files = listed.values.flatten()
        println("RD torrent files: ${files.size} in ${listed.size} finished torrents (${listed.count { (_, list) -> list.any { it.path.contains("packed") } }} packed), by shelf ${files.groupingBy { MediaFileClassifier.classify(it.path, it.mimeType) }.eachCount()}, extensions ${extensions(files)}, sizes known ${files.count { it.sizeBytes > 0 }}")
        assertTrue(files.all { it.ref.startsWith(RealDebridClient.LINK_PREFIX + "https://") })

        val links = client.list(credentials, ItemKind.LINK)
        val linkFiles = links.flatMap { it.files.orEmpty() }
        val fromTorrents = links.count { link -> files.any { it.ref == RealDebridClient.LINK_PREFIX + link.origin } }
        println("RD generated links: ${links.size} ($fromTorrents of them are files of the torrents above), by shelf ${linkFiles.groupingBy { MediaFileClassifier.classify(it.path, it.mimeType) }.eachCount()}, mime ${linkFiles.groupingBy { it.mimeType?.substringBefore('/') }.eachCount()}")

        // One address. By default for a file that already has a generated link, so that the check
        // leaves the account exactly as it found it; with DEBRIDONE_LIVE_CLOUD_UNRESTRICT=1 for a
        // file that has none, which adds one "generated link" record, as playing the file would.
        val unrestrict = System.getenv("DEBRIDONE_LIVE_CLOUD_UNRESTRICT") == "1"
        val hasLink = { file: CloudFile -> links.any { it.origin == file.ref.removePrefix(RealDebridClient.LINK_PREFIX) } }
        val candidate = if (unrestrict) files.firstOrNull { !hasLink(it) } else files.firstOrNull(hasLink)
        if (candidate != null) {
            val resolved = client.resolve(credentials, ItemKind.TORRENT, "", candidate.ref)
            val host = URI(resolved.url).host.orEmpty()
            println("RD address (${if (unrestrict) "new" else "reused"}): https=${resolved.url.startsWith("https://")} host=*.${host.substringAfter('.')} name known=${resolved.fileName != null} size matches=${resolved.sizeBytes == candidate.sizeBytes}")
            assertTrue(resolved.url.startsWith("https://"))
        }
        links.firstOrNull()?.let { link ->
            val resolved = client.resolve(credentials, ItemKind.LINK, link.remoteId, link.files!!.single().ref)
            println("RD generated link address: https=${resolved.url.startsWith("https://")}")
        }
    }

    @Test
    fun torBoxAccountIsReadAsDocumented() {
        enabled()
        val client = TorBoxClient()
        val credentials = client.credentialsFromKey(System.getenv("DEBRIDONE_LIVE_TORBOX_KEY") ?: PLACEHOLDER)

        val account = client.account(credentials)
        println("TorBox account: id=${account.remoteUserId.isNotEmpty()} email=${account.email != null} plan=${account.plan} premium=${account.premium} until known=${account.premiumUntil != null} details=${account.details.keys}")
        assertNotNull(account.plan)

        for (kind in client.kinds) {
            val items = client.list(credentials, kind)
            val files = items.flatMap { it.files.orEmpty() }
            println("TorBox ${kind.plural}: ${items.size}, by status ${items.groupingBy { it.status }.eachCount()}, raw ${items.groupingBy { it.rawStatus }.eachCount()}, files ${files.size} by shelf ${files.groupingBy { MediaFileClassifier.classify(it.path, it.mimeType) }.eachCount()}, md5 known ${files.count { it.md5 != null }}, extensions ${extensions(files)}")
            val ready = items.firstOrNull { it.status == ItemStatus.READY && !it.files.isNullOrEmpty() }
            if (ready != null) {
                val outcome = runCatching { client.resolve(credentials, kind, ready.remoteId, ready.files!!.first().ref) }
                println("TorBox ${kind.label} address: " + outcome.fold({ "https=${it.url.startsWith("https://")} host=*.${URI(it.url).host.orEmpty().substringAfter('.')}" }, { "failed: ${(it as? CloudException)?.reason} ${it.message}" }))
            }
        }
    }

    private companion object {
        /** Shaped like a key; replaced on the way out where a proxy holds the real one. */
        const val PLACEHOLDER = "proxy-injected-placeholder-credential"
    }
}
