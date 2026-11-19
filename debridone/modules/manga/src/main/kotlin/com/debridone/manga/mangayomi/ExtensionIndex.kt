package com.debridone.manga.mangayomi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/** The kinds of extension lists DebridOne recognises, whether or not it can use them. */
enum class IndexFormat(val label: String) {
    /** Mangayomi's index: a list of sources, each pointing to its code (JavaScript or Dart). */
    MANGAYOMI("Mangayomi index"),

    /** A Mihon/Tachiyomi store's list (index.min.json or index.json): packages the manga server installs itself. */
    SUWAYOMI_LIST("Mihon/Tachiyomi store"),

    /** A Mihon/Tachiyomi store's description (repo.json), pointing to its list. */
    SUWAYOMI_REPO("Mihon/Tachiyomi store"),

    /** A store's list in the current binary format (index.pb). */
    SUWAYOMI_BINARY("Mihon/Tachiyomi store"),

    /** LNReader's plugins: light novels, not manga. */
    LNREADER("LNReader plugin list"),

    /** Aidoku's sources: WebAssembly packages for iOS. */
    AIDOKU("Aidoku source list"),

    UNKNOWN("Not an extension list"),
}

/** How a source of an index can be used in Manga. */
enum class Support(val label: String) {
    /** The manga server installs and runs it as it is (a Mihon/Tachiyomi extension). */
    DIRECT("Runs directly"),

    /** It runs through DebridOne's Mangayomi bridge (its JavaScript, in the manga server). */
    ADAPTER("Runs through the Mangayomi bridge"),

    /** It cannot run in Manga; the reason says why. */
    NONE("Cannot run here"),
}

/** One source of a Mangayomi index, with the index's own fields kept as they are. */
class MangayomiEntry(val json: JsonObject) {
    private fun text(name: String) = (json[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

    val id: Long = (json["id"] as? JsonPrimitive)?.longOrNull ?: 0
    val name: String = text("name")
    val lang: String = text("lang")
    val baseUrl: String = text("baseUrl")
    val iconUrl: String = text("iconUrl")
    val version: String = text("version")
    val codeUrl: String = text("sourceCodeUrl")
    val typeSource: String = text("typeSource")
    val nsfw: Boolean = (json["isNsfw"] as? JsonPrimitive)?.booleanOrNull ?: false
    val cloudflare: Boolean = (json["hasCloudflare"] as? JsonPrimitive)?.booleanOrNull ?: false

    /** 0: manga, 1: anime, 2: novel (Mangayomi's itemType; older indexes say isManga). */
    val itemType: Int = (json["itemType"] as? JsonPrimitive)?.intOrNull ?: if ((json["isManga"] as? JsonPrimitive)?.booleanOrNull == false) 1 else 0

    /** 0: Dart, 1: JavaScript (Mangayomi's sourceCodeLanguage). */
    val codeLanguage: Int = (json["sourceCodeLanguage"] as? JsonPrimitive)?.intOrNull ?: if (codeUrl.endsWith(".js")) 1 else 0

    val language: String get() = if (codeLanguage == 1) "JavaScript" else "Dart"

    /** The id the manga server knows this source by: fixed, and different from every Mihon source's. */
    val sourceId: Long get() = idOf("debridone/mangayomi/$id/$lang")

    /** The package name of the extension DebridOne builds for it. */
    val packageName: String get() = "debridone.mangayomi.m$id." + lang.lowercase().replace(Regex("[^a-z0-9]"), "_").ifBlank { "all" }

    /** The source's version as the server's version number. */
    val versionCode: Int get() {
        val parts = version.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        return (parts.getOrElse(0) { 0 } * 1_000_000 + parts.getOrElse(1) { 0 } * 1_000 + parts.getOrElse(2) { 0 }).coerceIn(1, 999_999_999)
    }

    companion object {
        /** The first eight bytes of the MD5 of [text], without the sign bit: how Mihon makes source ids too. */
        fun idOf(text: String): Long =
            MessageDigest.getInstance("MD5").digest(text.toByteArray()).take(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) } and Long.MAX_VALUE
    }
}

/** What an index turned out to be. */
class DetectedIndex(val format: IndexFormat, val mangayomi: List<MangayomiEntry> = emptyList(), val note: String = "")

object ExtensionIndexDetector {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Recognises the list in [bytes], read from [address] (a web address or a file name). */
    fun detect(bytes: ByteArray, address: String): DetectedIndex {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        if (head.isEmpty()) return DetectedIndex(IndexFormat.UNKNOWN, note = "The file is empty.")
        if (head.startsWith("<")) return DetectedIndex(IndexFormat.UNKNOWN, note = "This is a web page, not a list of extensions. For a file on GitHub, use its \"Raw\" address.")
        if (!head.startsWith("{") && !head.startsWith("[")) {
            return if (address.substringBefore('?').endsWith(".pb", ignoreCase = true) || bytes.any { it.toInt() == 0 }) {
                DetectedIndex(IndexFormat.SUWAYOMI_BINARY)
            } else {
                DetectedIndex(IndexFormat.UNKNOWN, note = "This is neither JSON nor a store's binary list.")
            }
        }
        val root: JsonElement = try {
            json.parseToJsonElement(String(bytes, Charsets.UTF_8))
        } catch (failure: Exception) {
            return DetectedIndex(IndexFormat.UNKNOWN, note = "The JSON could not be read: ${failure.message?.take(160)}")
        }
        if (root is JsonObject) {
            return when {
                "index_v2" in root || (root["meta"] as? JsonObject)?.containsKey("signingKeyFingerprint") == true -> DetectedIndex(IndexFormat.SUWAYOMI_REPO)
                root["sources"] is JsonArray -> fromList(root["sources"] as JsonArray, address)
                "extensions" in root -> DetectedIndex(IndexFormat.SUWAYOMI_BINARY)
                else -> DetectedIndex(IndexFormat.UNKNOWN, note = "A JSON object, but not a store description or a list of sources.")
            }
        }
        return fromList(root as JsonArray, address)
    }

    private fun fromList(list: JsonArray, address: String): DetectedIndex {
        val objects = list.filterIsInstance<JsonObject>()
        if (objects.isEmpty()) return DetectedIndex(IndexFormat.UNKNOWN, note = "The list is empty.")
        fun share(vararg keys: String) = objects.count { o -> keys.all { it in o } } * 2 >= objects.size
        return when {
            share("sourceCodeUrl") -> DetectedIndex(IndexFormat.MANGAYOMI, objects.filter { "sourceCodeUrl" in it }.map(::MangayomiEntry))
            share("pkg", "apk") -> DetectedIndex(IndexFormat.SUWAYOMI_LIST)
            share("site", "url", "iconUrl") -> DetectedIndex(IndexFormat.LNREADER)
            share("file", "id", "lang") -> DetectedIndex(IndexFormat.AIDOKU)
            else -> DetectedIndex(IndexFormat.UNKNOWN, note = "A list, but not of extensions DebridOne knows (from $address).")
        }
    }
}

/** Whether and how a Mangayomi source can run in Manga, judged from its index entry and, when read, its code. */
data class Compatibility(val support: Support, val reason: String, val warnings: List<String> = emptyList())

object MangayomiCompatibility {
    /** APIs of Mangayomi's that the bridge does not provide, and what that means for a source that calls them. */
    private val UNSUPPORTED_CALLS = linkedMapOf(
        "evaluateJavascriptViaWebview" to "needs a web browser for part of its work, which the manga server does not have: those parts will fail",
        "deobfuscateJsPassword" to "uses a password deobfuscator the bridge does not have",
        "parseEpub" to "reads EPUB books (a novel source)",
        "createHlsProxyUrl" to "plays video (an anime source)",
    )

    fun judge(entry: MangayomiEntry, code: String?): Compatibility {
        if (entry.itemType == 1) return Compatibility(Support.NONE, "An anime source: Manga reads manga.")
        if (entry.itemType == 2) return Compatibility(Support.NONE, "A novel source: Manga reads manga.")
        if (entry.codeLanguage != 1) {
            return Compatibility(
                Support.NONE,
                "Written in Dart, which only Mangayomi's own Dart interpreter runs; DebridOne does not include one" +
                    (if (entry.typeSource.isNotBlank() && entry.typeSource != "single") " (it is built on Mangayomi's \"${entry.typeSource}\" template)" else "") + ".",
            )
        }
        val warnings = ArrayList<String>()
        if (entry.cloudflare) warnings += "The site may put up a browser check (Cloudflare); then only a FlareSolverr of your own (Manga settings › Server) gets past it."
        if (code != null) {
            if ("class DefaultExtension" !in code) return Compatibility(Support.NONE, "Its code does not define a Mangayomi source (no DefaultExtension class).")
            if ("getPageList" !in code && "getVideoList" in code) return Compatibility(Support.NONE, "Its code lists videos, not pages: an anime source.")
            UNSUPPORTED_CALLS.forEach { (call, meaning) -> if (Regex("\\b$call\\s*\\(").containsMatchIn(code)) warnings += "It $meaning." }
            if (warnings.any { it.contains("anime") || it.contains("novel") }) return Compatibility(Support.NONE, warnings.first { it.contains("anime") || it.contains("novel") })
        }
        return Compatibility(Support.ADAPTER, "JavaScript: runs through DebridOne's Mangayomi bridge, inside the manga server.", warnings)
    }
}
