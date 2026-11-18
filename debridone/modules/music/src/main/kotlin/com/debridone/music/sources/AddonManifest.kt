package com.debridone.music.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** One field of an addon's `settings` schema. Its value is sent to the addon as a query parameter named [key]. */
data class AddonSetting(
    val key: String,
    /** `select`, `toggle`, `text` or `number`. */
    val type: String,
    val label: String,
    val help: String?,
    val default: String,
    /** For `select`: value to label. */
    val options: List<Pair<String, String>>,
    val min: Double?,
    val max: Double?,
    val step: Double?,
    val maxLength: Int?,
    val placeholder: String?,
)

/** A row an addon offers for browsing (`catalogs` in the manifest, served at `/catalog/{id}`). */
data class AddonRow(val id: String, val type: String, val name: String)

/** What DebridOne can and cannot do with an addon, in words for the person installing it. */
data class AddonCompatibility(
    val usable: Boolean,
    /** Parts of the addon DebridOne uses. */
    val uses: List<String>,
    /** Parts the addon offers that DebridOne does not use. */
    val ignores: List<String>,
    /** Things worth knowing before installing. */
    val notes: List<String>,
)

/**
 * The manifest of a music addon in the format shared by Eclipse Music and BeatBoss
 * (`GET {base}/manifest.json`): an id, a name, a version and the list of `resources` the addon
 * serves. See debridone/docs/MUSIC_ADDONS.md for the endpoints behind each resource.
 *
 * Only what is listed here is read. A manifest is data; nothing in it is executed.
 */
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val icon: String?,
    /** `music` (default), `audiobook` or `podcast`. */
    val contentType: String,
    val resources: Set<String>,
    val types: Set<String>,
    val settings: List<AddonSetting>,
    val rows: List<AddonRow>,
) {
    val searches: Boolean get() = RESOURCE_SEARCH in resources
    val resolvesStreams: Boolean get() = RESOURCE_STREAM in resources
    val hasDetailPages: Boolean get() = RESOURCE_CATALOG in resources
    val hasLyrics: Boolean get() = RESOURCE_LYRICS in resources
    val sendsNotices: Boolean get() = RESOURCE_NOTIFICATIONS in resources

    fun compatibility(): AddonCompatibility {
        val uses = ArrayList<String>()
        val ignores = ArrayList<String>()
        val notes = ArrayList<String>()
        if (searches) uses += "Search"
        if (resolvesStreams) uses += "Playback (the addon resolves each song's address)"
        if (hasDetailPages) uses += "Album, artist and playlist pages"
        if (rows.isNotEmpty()) uses += if (rows.size == 1) "1 browsing row" else "${rows.size} browsing rows"
        if (RESOURCE_SETTINGS in resources && settings.isNotEmpty()) uses += if (settings.size == 1) "1 setting" else "${settings.size} settings"
        if (hasLyrics) uses += "Lyrics"
        if (sendsNotices) uses += "Messages from the addon (shown when something fails)"
        for (resource in resources.sorted()) {
            when (resource) {
                RESOURCE_SEARCH, RESOURCE_STREAM, RESOURCE_CATALOG, RESOURCE_SETTINGS, RESOURCE_LYRICS, RESOURCE_NOTIFICATIONS -> Unit
                "library" -> ignores += "Library sync (BeatBoss): your DebridOne library stays on this computer"
                "isrc" -> ignores += "ISRC lookup"
                "resolve" -> ignores += "Resolve (matching songs chosen elsewhere)"
                else -> ignores += "\"$resource\""
            }
        }
        when (contentType) {
            "audiobook" -> notes += "This is an audiobook addon. It plays in the music player: there is no chapter list, speed control or sleep timer."
            "podcast" -> notes += "This is a podcast addon. It plays in the music player: there is no speed control or sleep timer."
        }
        if (!resolvesStreams) notes += "The addon does not resolve stream addresses; only songs that carry a direct address can be played."
        val usable = searches || rows.isNotEmpty() || hasLyrics
        if (!usable) notes += "It offers neither search nor browsing rows nor lyrics, so there is nothing DebridOne could show."
        return AddonCompatibility(usable, uses, ignores, notes)
    }

    /** The manifest as the non-secret settings kept with a connection. */
    fun toConfig(): Map<String, String> = buildMap {
        put(KEY_ID, id)
        put(KEY_VERSION, version)
        description?.let { put(KEY_DESCRIPTION, it) }
        icon?.let { put(KEY_ICON, it) }
        put(KEY_CONTENT_TYPE, contentType)
        put(KEY_RESOURCES, resources.sorted().joinToString(","))
        put(KEY_TYPES, types.sorted().joinToString(","))
        if (settings.isNotEmpty()) put(KEY_SETTINGS, settingsJson())
        if (rows.isNotEmpty()) put(KEY_ROWS, rowsJson())
    }

    private fun settingsJson(): String = buildJsonArray {
        settings.forEach { setting ->
            add(
                buildJsonObject {
                    put("key", setting.key)
                    put("type", setting.type)
                    put("label", setting.label)
                    setting.help?.let { put("help", it) }
                    put("default", setting.default)
                    setting.min?.let { put("min", it) }
                    setting.max?.let { put("max", it) }
                    setting.step?.let { put("step", it) }
                    setting.maxLength?.let { put("maxLength", it) }
                    setting.placeholder?.let { put("placeholder", it) }
                    if (setting.options.isNotEmpty()) {
                        put("options", buildJsonArray { setting.options.forEach { (value, label) -> add(buildJsonObject { put("value", value); put("label", label) }) } })
                    }
                },
            )
        }
    }.toString()

    private fun rowsJson(): String = buildJsonArray {
        rows.forEach { row -> add(buildJsonObject { put("id", row.id); put("type", row.type); put("name", row.name) }) }
    }.toString()

    companion object {
        const val RESOURCE_SEARCH = "search"
        const val RESOURCE_STREAM = "stream"
        const val RESOURCE_CATALOG = "catalog"
        const val RESOURCE_SETTINGS = "settings"
        const val RESOURCE_LYRICS = "lyrics"
        const val RESOURCE_NOTIFICATIONS = "notifications"

        const val KEY_ID = "addon.id"
        const val KEY_VERSION = "addon.version"
        const val KEY_DESCRIPTION = "addon.description"
        const val KEY_ICON = "addon.icon"
        const val KEY_CONTENT_TYPE = "addon.contentType"
        const val KEY_RESOURCES = "addon.resources"
        const val KEY_TYPES = "addon.types"
        const val KEY_SETTINGS = "addon.settings"
        const val KEY_ROWS = "addon.rows"

        /** Prefix of a setting's saved value in a connection's settings. */
        const val VALUE_PREFIX = "set."

        const val MAX_BYTES = 256 * 1024
        private const val MAX_SETTINGS = 40
        private const val MAX_ROWS = 40
        private val SETTING_TYPES = setOf("select", "toggle", "text", "number")
        private val ROW_TYPES = setOf("track", "album", "artist", "playlist")
        private val VIDEO_TYPES = setOf("movie", "series", "channel", "tv", "anime")
        private val SETTING_KEY = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")

        /**
         * Reads and checks a manifest. Every problem is a sentence for the user; a manifest of
         * another kind of add-on is named for what it is.
         */
        fun parse(text: String): Result<AddonManifest> = runCatching {
            if (text.toByteArray().size > MAX_BYTES) fail("The manifest is larger than ${MAX_BYTES / 1024} KB.")
            val trimmed = text.trim()
            if (trimmed.startsWith("<")) fail("That address returned a web page, not an addon manifest. The address of an addon ends in /manifest.json.")
            val root = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
                ?: fail("That is not an addon manifest: it is not a JSON object.")
            if (root.containsKey(MusicPluginManifest.FORMAT_KEY)) fail("That is a DebridOne Music plugin manifest, not an Eclipse Music / BeatBoss addon.")
            val types = root["types"].strings().map { it.lowercase() }.toSet()
            val resources = root["resources"].resourceNames()
            if (looksLikeVideoAddon(root, types, resources)) {
                fail("That is a Streaming (video) add-on manifest. Streaming add-ons are installed under Plugins › Streaming; Music cannot use them.")
            }
            val id = root.string("id")?.take(120) ?: fail("The manifest has no \"id\".")
            val name = root.string("name")?.take(80) ?: fail("The manifest has no \"name\".")
            val version = root.string("version")?.take(32) ?: fail("The manifest has no \"version\".")
            if (root["resources"] !is JsonArray) fail("The manifest has no \"resources\" list, so it does not say what the addon provides.")
            val contentType = when (val value = root.string("contentType")?.lowercase()) {
                null, "music" -> "music"
                "audiobook", "podcast" -> value
                else -> fail("The addon's content type \"$value\" is not one DebridOne knows (music, audiobook, podcast).")
            }
            AddonManifest(
                id = id,
                name = name,
                version = version,
                description = root.string("description")?.take(500),
                icon = root.string("icon")?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }?.take(1000),
                contentType = contentType,
                resources = resources,
                types = types,
                settings = if (RESOURCE_SETTINGS in resources) settings(root["settings"]) else emptyList(),
                rows = rows(root["catalogs"]),
            )
        }

        /** Rebuilds the manifest kept with a connection; null when the connection is not an addon. */
        fun fromConfig(name: String, config: Map<String, String>): AddonManifest? {
            val id = config[KEY_ID] ?: return null
            return AddonManifest(
                id = id,
                name = name,
                version = config[KEY_VERSION].orEmpty(),
                description = config[KEY_DESCRIPTION],
                icon = config[KEY_ICON],
                contentType = config[KEY_CONTENT_TYPE] ?: "music",
                resources = config[KEY_RESOURCES].orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
                types = config[KEY_TYPES].orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
                settings = config[KEY_SETTINGS]?.let { runCatching { settings(Json.parseToJsonElement(it)) }.getOrNull() }.orEmpty(),
                rows = config[KEY_ROWS]?.let { runCatching { rows(Json.parseToJsonElement(it)) }.getOrNull() }.orEmpty(),
            )
        }

        /** A Stremio-style video add-on: the same idea of a manifest, a different protocol. */
        private fun looksLikeVideoAddon(root: JsonObject, types: Set<String>, resources: Set<String>): Boolean =
            root.containsKey("idPrefixes") || types.any { it in VIDEO_TYPES } || "meta" in resources || "subtitles" in resources || "addon_catalog" in resources ||
                (root["catalogs"] as? JsonArray).orEmpty().any { (it as? JsonObject)?.string("type")?.lowercase() in VIDEO_TYPES }

        private fun kotlinx.serialization.json.JsonElement?.strings(): List<String> =
            (this as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }

        /** Resource names; a Stremio manifest may list objects (`{"name": "stream", ...}`) instead of strings. */
        private fun kotlinx.serialization.json.JsonElement?.resourceNames(): Set<String> =
            (this as? JsonArray).orEmpty().mapNotNull { entry ->
                when (entry) {
                    is JsonPrimitive -> entry.contentOrNull
                    is JsonObject -> entry.string("name")
                    else -> null
                }?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
            }.toSet()

        private fun settings(node: kotlinx.serialization.json.JsonElement?): List<AddonSetting> =
            (node as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(MAX_SETTINGS).mapNotNull { field ->
                val key = field.string("key")?.takeIf { SETTING_KEY.matches(it) && it !in RESERVED_PARAMETERS } ?: return@mapNotNull null
                val type = field.string("type")?.lowercase()?.takeIf { it in SETTING_TYPES } ?: return@mapNotNull null
                val options = (field["options"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(60).mapNotNull { option ->
                    val value = option.primitiveText("value") ?: return@mapNotNull null
                    value.take(200) to (option.string("label") ?: value).take(120)
                }
                if (type == "select" && options.isEmpty()) return@mapNotNull null
                val default = field.primitiveText("default") ?: when (type) {
                    "toggle" -> "false"
                    "select" -> options.first().first
                    else -> ""
                }
                AddonSetting(
                    key = key,
                    type = type,
                    label = (field.string("label") ?: key).take(120),
                    help = field.string("help")?.take(300),
                    default = default.take(500),
                    options = options,
                    min = (field["min"] as? JsonPrimitive)?.doubleOrNull,
                    max = (field["max"] as? JsonPrimitive)?.doubleOrNull,
                    step = (field["step"] as? JsonPrimitive)?.doubleOrNull,
                    maxLength = field.int("maxLength")?.coerceIn(1, 500),
                    placeholder = field.string("placeholder")?.take(120),
                )
            }.distinctBy { it.key }

        private fun rows(node: kotlinx.serialization.json.JsonElement?): List<AddonRow> =
            (node as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(MAX_ROWS).mapNotNull { row ->
                AddonRow(
                    id = row.primitiveText("id")?.take(200) ?: return@mapNotNull null,
                    type = row.string("type")?.lowercase()?.takeIf { it in ROW_TYPES } ?: return@mapNotNull null,
                    name = (row.string("name") ?: return@mapNotNull null).take(120),
                )
            }.distinctBy { it.id }

        /** A value that may be written as a string, a number or a boolean. */
        private fun JsonObject.primitiveText(key: String): String? {
            val primitive = this[key] as? JsonPrimitive ?: return null
            primitive.booleanOrNull?.let { return it.toString() }
            return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }

        /** Parameters the protocol itself uses; an addon's setting cannot take their names. */
        private val RESERVED_PARAMETERS = setOf("q", "limit", "skip", "artist", "title", "isrc")

        private fun fail(message: String): Nothing = throw SourceException(message)
    }
}
