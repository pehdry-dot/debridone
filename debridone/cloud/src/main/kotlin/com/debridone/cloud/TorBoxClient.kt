package com.debridone.cloud

import com.debridone.cloud.CloudException.Reason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The user's own TorBox account, through TorBox's public API (api-docs.torbox.app).
 *
 * ```
 * GET  /v1/api/user/me                           who is signed in, and their plan
 * GET  /v1/api/torrents/mylist                   the account's torrents, with their files
 * GET  /v1/api/usenet/mylist                     its usenet downloads
 * GET  /v1/api/webdl/mylist                      its web downloads
 * GET  /v1/api/{torrents|usenet|webdl}/requestdl a download address for one file (good for starting
 *                                                a download for about three hours)
 * POST /v1/api/torrents/controltorrent           } operation "delete": remove an entry, only when
 * POST /v1/api/usenet/controlusenetdownload      } the user asks and confirms
 * POST /v1/api/webdl/controlwebdownload          }
 * ```
 *
 * The account is identified by its API key (`Authorization: Bearer`). The address that asks for a
 * download link carries that key as a parameter, as TorBox requires; it is built and used inside
 * this class only and never handed to anything else. Only the address TorBox answers with (which
 * has no key in it) leaves this class.
 *
 * TorBox allows 300 requests a minute per key; requests are spaced to stay under that. Its lists
 * are cached on its side for up to ten minutes unless a fresh one is asked for.
 */
class TorBoxClient(
    baseUrl: String = DEFAULT_BASE_URL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    spacingMs: Long = 250,
) : CloudProvider {
    private val base = baseUrl.trimEnd('/')
    private val throttle = Throttle(spacingMs, clock, pause)

    override val id = ProviderId.TORBOX
    override val kinds = listOf(ItemKind.TORRENT, ItemKind.USENET, ItemKind.WEB)
    override val supportsCodeSignIn = false
    override val keyName = "API key"
    override val keyHelp = "The API key of your TorBox account (torbox.app › Settings). It identifies your account to TorBox; DebridOne keeps it in the system's credential store."
    override val deletableKinds = setOf(ItemKind.TORRENT, ItemKind.USENET, ItemKind.WEB)
    override val address: String get() = base
    override val isTestServer: Boolean get() = base != DEFAULT_BASE_URL

    override fun beginSignIn(): DeviceSignIn = throw CloudException(Reason.UNSUPPORTED, "TorBox is connected with an API key.")

    override fun pollSignIn(signIn: DeviceSignIn): CloudCredentials? = throw CloudException(Reason.UNSUPPORTED, "TorBox is connected with an API key.")

    override fun credentialsFromKey(key: String): CloudCredentials {
        val trimmed = key.trim()
        if (trimmed.length < 8 || trimmed.any { it.isWhitespace() }) throw CloudException(Reason.PROTOCOL, "That does not look like a TorBox API key.")
        return CloudCredentials(trimmed)
    }

    /** An API key does not expire; it stops working only when the user replaces it on TorBox. */
    override fun renew(credentials: CloudCredentials): CloudCredentials? = null

    // ---------------------------------------------------------------- account

    override fun account(credentials: CloudCredentials): AccountInfo {
        val user = data(get(credentials, "/user/me", listOf("settings" to "false"))).obj()
            ?: throw CloudException(Reason.PROTOCOL, "TorBox did not say which account this is.")
        val plan = user.long("plan")
        val until = parseTime(user.string("premium_expires_at"))
        val downloaded = user.long("total_downloaded")
        return AccountInfo(
            remoteUserId = user.id("id") ?: user.string("email") ?: throw CloudException(Reason.PROTOCOL, "TorBox did not say which account this is."),
            username = null,
            email = user.string("email"),
            plan = when (plan) {
                0L -> "Free"
                1L -> "Essential"
                2L -> "Pro"
                3L -> "Standard"
                null -> null
                else -> "Plan $plan"
            },
            premium = plan != null && plan != 0L && (until == null || until > clock()),
            premiumUntil = until,
            details = buildMap {
                user.bool("is_subscribed")?.let { put("Renews automatically", if (it) "Yes" else "No") }
                downloaded?.let { put("Downloads so far", it.toString()) }
                parseTime(user.string("cooldown_until"))?.takeIf { it > clock() }?.let { put("Download cooldown until", java.time.Instant.ofEpochMilli(it).toString()) }
            },
        )
    }

    // ---------------------------------------------------------------- what is in the account

    override fun list(credentials: CloudCredentials, kind: ItemKind, fresh: Boolean): List<CloudItem> {
        val segment = segment(kind) ?: return emptyList()
        val all = ArrayList<CloudItem>()
        var offset = 0
        while (offset < MAX_ENTRIES) {
            val parameters = buildList {
                add("offset" to offset.toString())
                add("limit" to PAGE_SIZE.toString())
                if (fresh) add("bypass_cache" to "true")
            }
            val page = (data(get(credentials, "/$segment/mylist", parameters)) as? JsonArray).array().mapNotNull { it.obj() }
            all += page.mapNotNull { item(kind, it) }
            if (page.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return all
    }

    private fun item(kind: ItemKind, node: JsonObject): CloudItem? {
        val remoteId = node.id("id") ?: return null
        val finished = node.bool("download_finished") == true
        val present = node.bool("download_present") == true
        val raw = node.string("download_state")
        val state = raw.orEmpty().lowercase()
        val status = when {
            finished && present -> ItemStatus.READY
            "error" in state || "failed" in state || "missing" in state -> ItemStatus.ERROR
            "paused" in state -> ItemStatus.PAUSED
            "queued" in state || "metadl" in state || "checking" in state -> ItemStatus.QUEUED
            state.isNotEmpty() -> ItemStatus.DOWNLOADING
            // Finished on TorBox's side but no longer held there.
            finished -> ItemStatus.ERROR
            else -> ItemStatus.UNKNOWN
        }
        val files = node["files"].array().mapNotNull { it.obj() }.mapNotNull { file ->
            val fileId = file.id("id") ?: return@mapNotNull null
            // "name" is the path inside the transfer; "short_name" the file's own name.
            val path = (file.string("name") ?: file.string("short_name") ?: return@mapNotNull null).trim('/')
            CloudFile(ref = fileId, path = path, sizeBytes = file.long("size") ?: 0, mimeType = file.string("mimetype"), md5 = file.string("md5")?.takeIf { MD5.matches(it) })
        }
        val updated = parseTime(node.string("updated_at"))
        return CloudItem(
            kind = kind,
            remoteId = remoteId,
            name = node.string("name") ?: kind.label,
            sizeBytes = node.long("size") ?: files.sumOf { it.sizeBytes },
            status = status,
            rawStatus = raw ?: node.string("error"),
            progress = node.double("progress")?.let { (if (it > 1.0) it / 100.0 else it).toFloat().coerceIn(0f, 1f) },
            createdAt = parseTime(node.string("created_at")),
            completedAt = parseTime(node.string("cached_at")) ?: updated?.takeIf { finished },
            expiresAt = parseTime(node.string("expires_at")),
            files = files,
            signature = "${files.size}:${updated ?: 0}",
        )
    }

    override fun files(credentials: CloudCredentials, item: CloudItem): List<CloudFile> = item.files.orEmpty()

    // ---------------------------------------------------------------- download addresses

    /** Always asks TorBox anew: its addresses are made to start a download soon, not to be kept. */
    override fun resolve(credentials: CloudCredentials, kind: ItemKind, itemId: String, fileRef: String, fresh: Boolean): ResolvedLink {
        val segment = segment(kind) ?: throw CloudException(Reason.UNSUPPORTED, "TorBox has no such kind of entry.")
        val idParameter = when (kind) {
            ItemKind.TORRENT -> "torrent_id"
            ItemKind.USENET -> "usenet_id"
            else -> "web_id"
        }
        val link = data(get(credentials, "/$segment/requestdl", listOf("token" to credentials.secret, idParameter to itemId, "file_id" to fileRef, "redirect" to "false")))
        val url = (link as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.startsWith("https://") || (isTestServer && it.startsWith("http://")) }
            ?: throw CloudException(Reason.UNAVAILABLE, "TorBox gave no address for this file.")
        return ResolvedLink(url)
    }

    // ---------------------------------------------------------------- removing (explicit and confirmed only)

    override fun delete(credentials: CloudCredentials, kind: ItemKind, itemId: String) {
        val (path, body) = when (kind) {
            ItemKind.TORRENT -> "/torrents/controltorrent" to buildJsonObject { putId("torrent_id", itemId); put("operation", "delete") }
            // TorBox documents this one id as a text.
            ItemKind.USENET -> "/usenet/controlusenetdownload" to buildJsonObject { put("usenet_id", itemId); put("operation", "delete") }
            ItemKind.WEB -> "/webdl/controlwebdownload" to buildJsonObject { putId("webdl_id", itemId); put("operation", "delete") }
            else -> throw CloudException(Reason.UNSUPPORTED, "TorBox has no such kind of entry.")
        }
        throttle.await()
        data(CloudHttp.exchange("TorBox", "$base$API$path", "POST", bearer(credentials), json = body.toString()))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putId(name: String, value: String) {
        val number = value.toLongOrNull()
        if (number != null) put(name, number) else put(name, value)
    }

    // ---------------------------------------------------------------- requests

    private fun segment(kind: ItemKind): String? = when (kind) {
        ItemKind.TORRENT -> "torrents"
        ItemKind.USENET -> "usenet"
        ItemKind.WEB -> "webdl"
        ItemKind.LINK -> null
    }

    private fun bearer(credentials: CloudCredentials) = mapOf("Authorization" to "Bearer ${credentials.secret}", "Accept" to "application/json")

    private fun get(credentials: CloudCredentials, path: String, parameters: List<Pair<String, String>>): HttpAnswer {
        val url = "$base$API$path" + if (parameters.isEmpty()) "" else "?" + CloudHttp.query(parameters)
        throttle.await()
        val first = CloudHttp.exchange("TorBox", url, headers = bearer(credentials))
        if (first.status != 429) return first
        // Told to slow down: one more try after a real pause.
        pause((first.retryAfterSeconds?.coerceIn(1, 120) ?: 20) * 1000L)
        return CloudHttp.exchange("TorBox", url, headers = bearer(credentials))
    }

    /** The `data` of TorBox's envelope `{success, error, detail, data}`, or the failure it describes. */
    private fun data(answer: HttpAnswer): kotlinx.serialization.json.JsonElement? {
        val root = answer.json().obj()
        val code = root?.string("error")
        val detail = root?.string("detail")?.take(160)
        if (answer.status < 400 && root != null && root.bool("success") != false) return root["data"]
        throw when {
            answer.status == 401 || code in AUTH_CODES -> CloudException(Reason.AUTH_EXPIRED, "TorBox no longer accepts the saved API key. Connect again with a current key.")
            answer.status == 429 -> CloudException(Reason.RATE_LIMITED, "TorBox is limiting requests right now. Try again in a minute.", answer.retryAfterSeconds)
            code == "ITEM_NOT_FOUND" || answer.status == 404 -> CloudException(Reason.NOT_FOUND, "TorBox no longer has this" + (detail?.let { " ($it)" } ?: "") + ".")
            code in LIMIT_CODES || answer.status == 403 -> CloudException(Reason.FORBIDDEN, "TorBox refused the request" + (detail?.let { ": $it" } ?: "."))
            code in SERVER_CODES || answer.status >= 500 -> CloudException(Reason.UNAVAILABLE, "TorBox cannot do this right now" + (detail?.let { ": $it" } ?: "."))
            root == null -> CloudException(Reason.PROTOCOL, "TorBox's answer could not be read.")
            else -> CloudException(Reason.PROTOCOL, "TorBox answered with an error" + (detail?.let { ": $it" } ?: " (${code ?: answer.status})."))
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.torbox.app"
        private const val API = "/v1/api"
        private const val PAGE_SIZE = 1000
        private const val MAX_ENTRIES = 20_000
        private val MD5 = Regex("^[0-9a-fA-F]{32}$")
        private val AUTH_CODES = setOf("BAD_TOKEN", "NO_AUTH", "AUTH_ERROR", "OAUTH_VERIFICATION_ERROR")
        private val LIMIT_CODES = setOf("PLAN_RESTRICTED_FEATURE", "MONTHLY_LIMIT", "COOLDOWN_LIMIT", "ACTIVE_LIMIT", "DOWNLOAD_TOO_LARGE", "INVALID_DEVICE", "VENDOR_DISABLED")
        private val SERVER_CODES = setOf("DATABASE_ERROR", "UNKNOWN_ERROR", "NO_SERVERS_AVAILABLE_ERROR", "DOWNLOAD_SERVER_ERROR", "LINK_OFFLINE")
    }
}
