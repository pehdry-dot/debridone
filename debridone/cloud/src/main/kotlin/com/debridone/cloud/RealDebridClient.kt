package com.debridone.cloud

import com.debridone.cloud.CloudException.Reason
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The user's own Real-Debrid account, through Real-Debrid's public REST API (api.real-debrid.com).
 *
 * ```
 * GET    /oauth/v2/device/code, /device/credentials; POST /oauth/v2/token   sign-in with a code
 * GET    /rest/1.0/user                     who is signed in, and their subscription
 * GET    /rest/1.0/torrents                 the account's torrents
 * GET    /rest/1.0/torrents/info/{id}       the files of one of them
 * GET    /rest/1.0/downloads                the links the account generated before
 * POST   /rest/1.0/unrestrict/link          a download address for a file of the account's own cloud
 * DELETE /rest/1.0/torrents/delete/{id}     remove a torrent        } only when the user asks,
 * DELETE /rest/1.0/downloads/delete/{id}    remove a generated link } and confirms
 * ```
 *
 * Sign-in is Real-Debrid's documented "OAuth2 for opensource apps": the user allows DebridOne on
 * Real-Debrid's own page with a short code and DebridOne never sees the password. What comes back
 * is limited to the scopes `unrestrict`, `torrents`, `downloads`, `user`. A private API token is
 * accepted as the alternative for those who prefer it.
 *
 * Real-Debrid allows 250 requests a minute; requests are spaced to stay well under that.
 */
class RealDebridClient(
    baseUrl: String = DEFAULT_BASE_URL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    spacingMs: Long = 350,
) : CloudProvider {
    private val base = baseUrl.trimEnd('/')
    private val throttle = Throttle(spacingMs, clock, pause)

    override val id = ProviderId.REAL_DEBRID
    override val kinds = listOf(ItemKind.TORRENT, ItemKind.LINK)
    override val supportsCodeSignIn = true
    override val keyName = "API token"
    override val keyHelp = "The private API token of your Real-Debrid account (real-debrid.com/apitoken). It opens everything in the account, which is more than DebridOne uses; Real-Debrid itself advises the code sign-in for apps."
    override val deletableKinds = setOf(ItemKind.TORRENT, ItemKind.LINK)
    override val address: String get() = base
    override val isTestServer: Boolean get() = base != DEFAULT_BASE_URL

    // ---------------------------------------------------------------- sign-in

    override fun beginSignIn(): DeviceSignIn {
        val root = json(request("$base$OAUTH/device/code?" + CloudHttp.query(listOf("client_id" to OPEN_SOURCE_CLIENT_ID, "new_credentials" to "yes"))))
        return DeviceSignIn(
            provider = id,
            deviceCode = root.string("device_code") ?: throw CloudException(Reason.PROTOCOL, "Real-Debrid did not start the sign-in."),
            userCode = root.string("user_code") ?: throw CloudException(Reason.PROTOCOL, "Real-Debrid did not give a code to enter."),
            verificationUrl = root.string("verification_url")?.takeIf { it.startsWith("https://") } ?: "https://real-debrid.com/device",
            intervalSeconds = (root.long("interval") ?: 5).toInt().coerceIn(2, 60),
            expiresInSeconds = (root.long("expires_in") ?: 1800).toInt().coerceIn(60, 3600),
        )
    }

    override fun pollSignIn(signIn: DeviceSignIn): CloudCredentials? {
        val pending = request("$base$OAUTH/device/credentials?" + CloudHttp.query(listOf("client_id" to OPEN_SOURCE_CLIENT_ID, "code" to signIn.deviceCode)))
        // Until the code is allowed Real-Debrid answers with an error; that is the normal "not yet".
        if (pending.status >= 400) return null
        val granted = pending.json().obj() ?: return null
        val clientId = granted.string("client_id") ?: return null
        val clientSecret = granted.string("client_secret") ?: return null
        return CloudCredentials(token(clientId, clientSecret, signIn.deviceCode).encode())
    }

    override fun credentialsFromKey(key: String): CloudCredentials {
        val token = key.trim()
        if (token.length < 16 || token.any { it.isWhitespace() }) throw CloudException(Reason.PROTOCOL, "That does not look like a Real-Debrid API token.")
        return CloudCredentials(Tokens(null, null, token, null, Long.MAX_VALUE).encode())
    }

    /** A new access token when the saved one is about to expire. An API token is never renewed. */
    override fun renew(credentials: CloudCredentials): CloudCredentials? {
        val tokens = Tokens.decode(credentials.secret) ?: return null
        val clientId = tokens.clientId ?: return null
        val clientSecret = tokens.clientSecret ?: return null
        val refreshToken = tokens.refreshToken ?: return null
        if (tokens.expiresAtMs - clock() > RENEW_BEFORE_MS) return null
        return CloudCredentials(token(clientId, clientSecret, refreshToken).encode(), credentials.cacheKey)
    }

    private fun token(clientId: String, clientSecret: String, code: String): Tokens {
        val answer = request(
            "$base$OAUTH/token",
            form = listOf("client_id" to clientId, "client_secret" to clientSecret, "code" to code, "grant_type" to DEVICE_GRANT),
        )
        if (answer.status >= 400) {
            throw CloudException(Reason.AUTH_EXPIRED, "Real-Debrid no longer accepts this sign-in (${error(answer) ?: answer.status}). Sign in again.")
        }
        val root = json(answer)
        return Tokens(
            clientId = clientId,
            clientSecret = clientSecret,
            accessToken = root.string("access_token") ?: throw CloudException(Reason.PROTOCOL, "Real-Debrid gave no access token."),
            refreshToken = root.string("refresh_token") ?: code,
            expiresAtMs = clock() + (root.long("expires_in") ?: 3600).coerceAtLeast(60) * 1000,
        )
    }

    // ---------------------------------------------------------------- account

    override fun account(credentials: CloudCredentials): AccountInfo {
        val user = json(api(credentials, "/user"))
        val type = user.string("type")
        val points = user.long("points")
        return AccountInfo(
            remoteUserId = user.id("id") ?: user.string("username") ?: throw CloudException(Reason.PROTOCOL, "Real-Debrid did not say which account this is."),
            username = user.string("username"),
            email = user.string("email"),
            plan = type?.replaceFirstChar { it.uppercase() },
            premium = type == "premium",
            premiumUntil = parseTime(user.string("expiration")),
            details = buildMap { points?.let { put("Fidelity points", it.toString()) } },
        )
    }

    // ---------------------------------------------------------------- what is in the account

    override fun list(credentials: CloudCredentials, kind: ItemKind, fresh: Boolean): List<CloudItem> = when (kind) {
        ItemKind.TORRENT -> pages(credentials, "/torrents").map(::torrent)
        ItemKind.LINK -> generated(credentials, fresh = true).map { it.item }
        else -> emptyList()
    }

    private fun pages(credentials: CloudCredentials, path: String): List<JsonObject> {
        val all = ArrayList<JsonObject>()
        var page = 1
        while (all.size < MAX_ENTRIES) {
            val answer = patiently { api(credentials, path, listOf("page" to page.toString(), "limit" to PAGE_SIZE.toString())) }
            if (answer.status >= 400) throw failure(answer)
            // An empty list is answered with 204 and no body.
            if (answer.status == 204 || answer.body.isEmpty()) break
            val list = (answer.json() as? JsonArray ?: throw CloudException(Reason.PROTOCOL, "Real-Debrid's answer could not be read.")).mapNotNull { it.obj() }
            all += list
            if (list.size < PAGE_SIZE) break
            page++
        }
        return all
    }

    private fun torrent(node: JsonObject): CloudItem {
        val links = node["links"].array().mapNotNull { (it as? JsonPrimitive)?.content }
        val raw = node.string("status")
        val status = when (raw) {
            "downloaded" -> ItemStatus.READY
            "downloading", "compressing", "uploading", "magnet_conversion" -> ItemStatus.DOWNLOADING
            "queued", "waiting_files_selection" -> ItemStatus.QUEUED
            "error", "magnet_error", "virus", "dead" -> ItemStatus.ERROR
            else -> ItemStatus.UNKNOWN
        }
        return CloudItem(
            kind = ItemKind.TORRENT,
            remoteId = node.string("id").orEmpty(),
            name = node.string("filename") ?: "Torrent",
            sizeBytes = node.long("bytes") ?: 0,
            status = status,
            rawStatus = raw?.replace('_', ' '),
            progress = node.double("progress")?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
            createdAt = parseTime(node.string("added")),
            completedAt = parseTime(node.string("ended")),
            expiresAt = null,
            // The files of a finished torrent are asked for separately; an unfinished one has none to hand out.
            files = if (status == ItemStatus.READY) null else emptyList(),
            signature = signature(links),
        )
    }

    /**
     * The files of one finished torrent. Real-Debrid lists one link per selected file, in the
     * files' order. A torrent it packed into fewer archives than it has files cannot be mapped file
     * by file, and is never guessed at: each link is then offered as one opaque download.
     */
    override fun files(credentials: CloudCredentials, item: CloudItem): List<CloudFile> {
        if (item.kind != ItemKind.TORRENT) return item.files.orEmpty()
        val info = json(patiently { api(credentials, "/torrents/info/${CloudHttp.encode(item.remoteId)}") })
        val selected = info["files"].array().mapNotNull { it.obj() }.filter { (it.long("selected") ?: 0) == 1L }
        val links = info["links"].array().mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { link -> link.startsWith("https://") } }
        if (selected.size == links.size) {
            return selected.zip(links).mapNotNull { (file, link) ->
                val path = file.string("path")?.trim('/')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                CloudFile(ref = LINK_PREFIX + link, path = path, sizeBytes = file.long("bytes") ?: 0)
            }
        }
        return links.mapIndexed { index, link ->
            CloudFile(
                ref = LINK_PREFIX + link, sizeBytes = 0, opaque = true,
                path = if (links.size == 1) "${item.name} (packed by Real-Debrid)" else "${item.name} (packed, part ${index + 1} of ${links.size})",
            )
        }
    }

    private class Generated(val id: String, val link: String?, val download: String?, val item: CloudItem)

    private val generatedCache = ConcurrentHashMap<String, Pair<Long, List<Generated>>>()

    /** The links the account generated before, newest first; remembered for a few minutes for [resolve]. */
    private fun generated(credentials: CloudCredentials, fresh: Boolean): List<Generated> {
        if (!fresh) generatedCache[credentials.cacheKey]?.takeIf { clock() - it.first < GENERATED_TTL_MS }?.let { return it.second }
        val list = pages(credentials, "/downloads").mapNotNull { node ->
            val entryId = node.string("id") ?: return@mapNotNull null
            val name = node.string("filename") ?: return@mapNotNull null
            val size = node.long("filesize") ?: 0
            Generated(
                id = entryId,
                link = node.string("link"),
                download = node.string("download"),
                item = CloudItem(
                    kind = ItemKind.LINK,
                    remoteId = entryId,
                    name = name,
                    sizeBytes = size,
                    status = ItemStatus.READY,
                    rawStatus = null,
                    progress = null,
                    createdAt = parseTime(node.string("generated")),
                    completedAt = null,
                    expiresAt = null,
                    files = listOf(CloudFile(ref = GENERATED_PREFIX + entryId, path = name, sizeBytes = size, mimeType = node.string("mimeType"))),
                    origin = node.string("link"),
                ),
            )
        }
        generatedCache[credentials.cacheKey] = clock() to list
        return list
    }

    // ---------------------------------------------------------------- download addresses

    /**
     * A file of a torrent is turned into an address by "unrestricting" its own Real-Debrid link,
     * which also adds a record to the account's generated links; when such a record already exists
     * its address is used instead, unless [fresh] says that address no longer works. A generated
     * link is looked up in the account's list.
     */
    override fun resolve(credentials: CloudCredentials, kind: ItemKind, itemId: String, fileRef: String, fresh: Boolean): ResolvedLink {
        if (fileRef.startsWith(LINK_PREFIX)) {
            val link = fileRef.removePrefix(LINK_PREFIX)
            if (!fresh) {
                runCatching { generated(credentials, fresh = false) }.getOrNull()?.firstOrNull { it.link == link }?.let { known ->
                    address(known.download)?.let { return ResolvedLink(it, known.item.name, known.item.sizeBytes.takeIf { size -> size > 0 }) }
                }
            }
            val answer = patiently { api(credentials, "/unrestrict/link", form = listOf("link" to link)) }
            val root = json(answer)
            generatedCache.remove(credentials.cacheKey)
            return ResolvedLink(
                address(root.string("download")) ?: throw CloudException(Reason.UNAVAILABLE, "Real-Debrid gave no address for this file."),
                root.string("filename"), root.long("filesize")?.takeIf { it > 0 },
            )
        }
        val entryId = fileRef.removePrefix(GENERATED_PREFIX)
        val known = generated(credentials, fresh).firstOrNull { it.id == entryId }
            ?: throw CloudException(Reason.NOT_FOUND, "This file is no longer among your Real-Debrid downloads.")
        return ResolvedLink(
            address(known.download) ?: throw CloudException(Reason.UNAVAILABLE, "Real-Debrid lists this file without an address."),
            known.item.name, known.item.sizeBytes.takeIf { it > 0 },
        )
    }

    private fun address(text: String?): String? = text?.takeIf { it.startsWith("https://") || (isTestServer && it.startsWith("http://")) }

    // ---------------------------------------------------------------- removing (explicit and confirmed only)

    override fun delete(credentials: CloudCredentials, kind: ItemKind, itemId: String) {
        val path = when (kind) {
            ItemKind.TORRENT -> "/torrents/delete/"
            ItemKind.LINK -> "/downloads/delete/"
            else -> throw CloudException(Reason.UNSUPPORTED, "Real-Debrid has no such kind of entry.")
        }
        val answer = api(credentials, path + CloudHttp.encode(itemId), method = "DELETE")
        if (answer.status >= 400) throw failure(answer)
        generatedCache.remove(credentials.cacheKey)
    }

    // ---------------------------------------------------------------- requests

    private fun api(credentials: CloudCredentials, path: String, parameters: List<Pair<String, String>> = emptyList(), form: List<Pair<String, String>>? = null, method: String = "GET"): HttpAnswer {
        val tokens = Tokens.decode(credentials.secret) ?: throw CloudException(Reason.AUTH_EXPIRED, "The saved Real-Debrid sign-in cannot be read. Sign in again.")
        return request(
            base + REST + path + if (parameters.isEmpty()) "" else "?" + CloudHttp.query(parameters),
            mapOf("Authorization" to "Bearer ${tokens.accessToken}", "Accept" to "application/json"), form, method,
        )
    }

    private fun request(url: String, headers: Map<String, String> = mapOf("Accept" to "application/json"), form: List<Pair<String, String>>? = null, method: String = "GET"): HttpAnswer {
        throttle.await()
        return CloudHttp.exchange("Real-Debrid", url, if (form != null) "POST" else method, headers, form = form)
    }

    /** One more try, after a real pause, when Real-Debrid says requests are coming too fast. */
    private fun patiently(ask: () -> HttpAnswer): HttpAnswer {
        val first = ask()
        if (first.status != 429) return first
        pause((first.retryAfterSeconds?.coerceIn(1, 120) ?: 20) * 1000L)
        return ask()
    }

    private fun json(answer: HttpAnswer): JsonObject {
        if (answer.status >= 400) throw failure(answer)
        return answer.json().obj() ?: throw CloudException(Reason.PROTOCOL, "Real-Debrid's answer could not be read.")
    }

    /** Real-Debrid's own error word (`{"error": "...", "error_code": n}`), when it sent one. */
    private fun error(answer: HttpAnswer): String? = answer.json().obj()?.string("error")?.replace('_', ' ')?.take(80)

    private fun failure(answer: HttpAnswer): CloudException {
        val code = answer.json().obj()?.long("error_code")
        val words = error(answer)
        return when {
            answer.status == 401 || code == 8L -> CloudException(Reason.AUTH_EXPIRED, "Real-Debrid no longer accepts the saved sign-in. Sign in again.")
            answer.status == 403 -> CloudException(Reason.FORBIDDEN, "Real-Debrid refused the request (${words ?: "permission denied"}). The account may be locked or not premium.")
            answer.status == 404 -> CloudException(Reason.NOT_FOUND, "Real-Debrid no longer has this (${words ?: "not found"}).")
            answer.status == 429 || code == 34L -> CloudException(Reason.RATE_LIMITED, "Real-Debrid is limiting requests right now. Try again in a minute.", answer.retryAfterSeconds)
            answer.status == 503 -> CloudException(Reason.UNAVAILABLE, "Real-Debrid cannot hand this out right now (${words ?: "service unavailable"}).")
            else -> CloudException(Reason.PROTOCOL, "Real-Debrid answered with an error (${words ?: answer.status}).")
        }
    }

    private fun signature(links: List<String>): String =
        MessageDigest.getInstance("SHA-1").digest(links.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }.take(16) + ":" + links.size

    /** What a sign-in is made of. Stored as one JSON text in the credential store; never anywhere else. */
    private class Tokens(val clientId: String?, val clientSecret: String?, val accessToken: String, val refreshToken: String?, val expiresAtMs: Long) {
        fun encode(): String = buildJsonObject {
            clientId?.let { put("client_id", it) }
            clientSecret?.let { put("client_secret", it) }
            put("access_token", accessToken)
            refreshToken?.let { put("refresh_token", it) }
            put("expires_at", expiresAtMs)
        }.toString()

        companion object {
            fun decode(text: String): Tokens? {
                val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
                return Tokens(root.string("client_id"), root.string("client_secret"), root.string("access_token") ?: return null, root.string("refresh_token"), root.long("expires_at") ?: 0)
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.real-debrid.com"

        /** The client id Real-Debrid publishes for open-source apps (scopes: unrestrict, torrents, downloads, user). */
        const val OPEN_SOURCE_CLIENT_ID = "X245A4XAIBGVM"

        const val LINK_PREFIX = "l:"
        const val GENERATED_PREFIX = "d:"
        private const val REST = "/rest/1.0"
        private const val OAUTH = "/oauth/v2"
        private const val DEVICE_GRANT = "http://oauth.net/grant_type/device/1.0"

        /**
         * Real-Debrid documents up to 5000 entries a request, but (seen on the real service, not in
         * its documentation) it leaves the links of torrents out of any answer asked with a limit
         * above 100. So pages are 100 entries.
         */
        private const val PAGE_SIZE = 100
        private const val MAX_ENTRIES = 10_000
        private const val RENEW_BEFORE_MS = 2 * 60 * 1000L
        private const val GENERATED_TTL_MS = 5 * 60 * 1000L
    }
}
