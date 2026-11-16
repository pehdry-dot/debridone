package com.debridone.music.sources

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A stream listed by a "streams" plugin: an internet radio station or another direct address. */
data class ManifestStream(val name: String, val url: String, val genre: String?)

/**
 * A Music plugin, as its manifest describes it.
 *
 * A manifest is data: a name, which of DebridOne's adapters it uses, and that adapter's settings.
 * It never carries code. What a plugin can do is exactly what the adapter it names can do.
 *
 * ```json
 * {
 *   "debridoneMusicPlugin": 1,
 *   "id": "org.example.radio",
 *   "name": "Example Radio",
 *   "version": "1.0.0",
 *   "description": "Three stations.",
 *   "adapter": "streams",
 *   "streams": [ { "name": "Station One", "url": "https://radio.example.org/one.mp3", "genre": "Jazz" } ]
 * }
 * ```
 *
 * Adapters: `streams` (a list of direct addresses), `debridone-catalog` (a server speaking the
 * Music Catalog protocol), `subsonic`, `jellyfin` (a preset for a personal server; the user still
 * signs in), `audius`.
 */
data class MusicPluginManifest(
    val id: String,
    val name: String,
    val version: String?,
    val description: String?,
    val adapter: String,
    val baseUrl: String?,
    /** `none`, `token` or `password`: what the user is asked for when connecting. */
    val auth: String,
    val streams: List<ManifestStream>,
    val homepage: String?,
) {
    val kind: IntegrationKind
        get() = when (adapter) {
            SubsonicAdapter.ID, JellyfinAdapter.ID -> IntegrationKind.PERSONAL_SERVER
            else -> IntegrationKind.PLAYBACK_SOURCE
        }

    /** True when installing needs nothing more from the user. */
    val connectsWithoutSignIn: Boolean
        get() = adapter == ADAPTER_STREAMS || adapter == AudiusAdapter.ID || (adapter == HttpCatalogAdapter.ID && auth == AUTH_NONE)

    fun toJson(): String = buildJsonObject {
        put(FORMAT_KEY, FORMAT_VERSION)
        put("id", id)
        put("name", name)
        version?.let { put("version", it) }
        description?.let { put("description", it) }
        put("adapter", adapter)
        baseUrl?.let { put("baseUrl", it) }
        put("auth", auth)
        homepage?.let { put("homepage", it) }
        if (streams.isNotEmpty()) {
            put(
                "streams",
                buildJsonArray {
                    streams.forEach { stream ->
                        add(
                            buildJsonObject {
                                put("name", stream.name)
                                put("url", stream.url)
                                stream.genre?.let { put("genre", it) }
                            },
                        )
                    }
                },
            )
        }
    }.toString()

    companion object {
        const val FORMAT_KEY = "debridoneMusicPlugin"
        const val FORMAT_VERSION = 1
        const val ADAPTER_STREAMS = "streams"
        const val AUTH_NONE = "none"
        const val AUTH_TOKEN = "token"
        const val AUTH_PASSWORD = "password"
        const val MAX_BYTES = 256 * 1024
        const val MAX_STREAMS = 500

        val adapters = setOf(ADAPTER_STREAMS, HttpCatalogAdapter.ID, SubsonicAdapter.ID, JellyfinAdapter.ID, AudiusAdapter.ID)
        private val idPattern = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")

        /**
         * Reads and checks a manifest. Every problem comes back as a sentence for the user; a
         * manifest of another kind of plugin is named for what it is.
         */
        fun parse(text: String): Result<MusicPluginManifest> = runCatching {
            if (text.toByteArray().size > MAX_BYTES) fail("The manifest is larger than ${MAX_BYTES / 1024} KB.")
            val trimmed = text.trim()
            if (trimmed.startsWith("<")) fail("That address returned a web page, not a Music plugin manifest.")
            val root = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
                ?: fail("That is not a Music plugin manifest: it is not a JSON object.")
            if (!root.containsKey(FORMAT_KEY)) {
                when {
                    root.containsKey("resources") || root.containsKey("catalogs") || root.containsKey("idPrefixes") ->
                        fail("That is a Streaming add-on manifest. Streaming add-ons are installed under Plugins › Streaming; Music cannot use them.")
                    root.containsKey("subsonic-response") ->
                        fail("That is a Subsonic server, not a manifest. Use \"Connect\" on Navidrome / OpenSubsonic and enter its address there.")
                    else -> fail("That is not a Music plugin manifest: it has no \"$FORMAT_KEY\" entry.")
                }
            }
            val format = (root[FORMAT_KEY] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            if (format != FORMAT_VERSION) fail("This manifest is written for plugin format $format; this version of DebridOne reads format $FORMAT_VERSION.")
            val id = root.string("id")?.lowercase() ?: fail("The manifest has no \"id\".")
            if (!idPattern.matches(id)) fail("The plugin id \"$id\" is not valid: use 3 to 64 lower-case letters, digits, dots, dashes or underscores.")
            val name = root.string("name")?.take(80) ?: fail("The manifest has no \"name\".")
            val adapter = root.string("adapter")?.lowercase() ?: fail("The manifest does not say which adapter it uses.")
            if (adapter !in adapters) {
                fail("This plugin needs the \"$adapter\" adapter, which this version of DebridOne does not have. Supported: ${adapters.sorted().joinToString(", ")}.")
            }
            val auth = (root.string("auth") ?: defaultAuth(adapter)).lowercase()
            if (auth !in setOf(AUTH_NONE, AUTH_TOKEN, AUTH_PASSWORD)) fail("\"auth\" must be none, token or password.")
            val baseUrl = root.string("baseUrl")?.let { address(it, "baseUrl") }
            if (baseUrl == null && adapter in setOf(HttpCatalogAdapter.ID, SubsonicAdapter.ID, JellyfinAdapter.ID)) fail("A \"$adapter\" plugin needs a \"baseUrl\".")
            val streams = root["streams"].array().mapNotNull { it.obj() }.map { node ->
                ManifestStream(
                    name = node.string("name")?.take(120) ?: fail("Every stream needs a \"name\"."),
                    url = address(node.string("url") ?: fail("Every stream needs a \"url\"."), "stream url"),
                    genre = node.string("genre")?.take(60),
                )
            }
            if (adapter == ADAPTER_STREAMS && streams.isEmpty()) fail("A \"streams\" plugin needs at least one stream.")
            if (streams.size > MAX_STREAMS) fail("A plugin can list at most $MAX_STREAMS streams.")
            MusicPluginManifest(
                id = id,
                name = name,
                version = root.string("version")?.take(32),
                description = root.string("description")?.take(500),
                adapter = adapter,
                baseUrl = baseUrl,
                auth = auth,
                streams = streams,
                homepage = root.string("homepage")?.let { runCatching { address(it, "homepage") }.getOrNull() },
            )
        }

        private fun defaultAuth(adapter: String) = when (adapter) {
            SubsonicAdapter.ID, JellyfinAdapter.ID -> AUTH_PASSWORD
            else -> AUTH_NONE
        }

        /** An http(s) address; plain http only for this computer and the local network. */
        private fun address(text: String, what: String): String {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: fail("The $what is not a valid address.")
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https" && scheme != "http") fail("The $what must be an http or https address.")
            val host = uri.host?.lowercase() ?: fail("The $what is not a valid address.")
            if (scheme == "http" && !isLocal(host)) fail("The $what must use https (plain http is accepted only on this computer or the local network).")
            if (uri.userInfo != null) fail("The $what must not contain a user name or password.")
            return text.trim().trimEnd('/')
        }

        private fun isLocal(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host == "::1" || host == "[::1]") return true
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return false
            return parts[0] == 127 || parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31)
        }

        private fun fail(message: String): Nothing = throw SourceException(message)
    }
}

/** Downloads a manifest. Only its text is read, and only up to [MusicPluginManifest.MAX_BYTES]. */
internal fun manifestFromAddress(address: String): String {
    val url = address.trim()
    if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) throw SourceException("A manifest address starts with https://.")
    val response = try {
        MusicHttp.get(url, mapOf("Accept" to "application/json"), maxBytes = MusicPluginManifest.MAX_BYTES + 1)
    } catch (e: HttpStatusException) {
        throw SourceException(if (e.status == 404) "There is no manifest at that address." else "That address answered with an error (${e.status}).", e)
    } catch (e: java.io.IOException) {
        throw SourceException("That address could not be reached: ${e.message ?: e::class.simpleName}", e)
    } catch (e: IllegalArgumentException) {
        throw SourceException(e.message ?: "That address is not valid.", e)
    }
    return response.text()
}
