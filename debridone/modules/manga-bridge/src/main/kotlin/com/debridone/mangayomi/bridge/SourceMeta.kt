package com.debridone.mangayomi.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * One Mangayomi source, as DebridOne packed it into this extension's jar:
 * `debridone-mangayomi/source.json` (the index entry, plus the id the server knows the source by)
 * and `debridone-mangayomi/source.js` (its code, unchanged).
 *
 * Every extension jar has a class loader of its own in the manga server, so the resources found
 * through this class are always this jar's.
 */
internal class SourceMeta(val json: JsonObject, val code: String) {
    private fun text(name: String): String = json[name]?.jsonPrimitive?.contentOrNull.orEmpty()

    /** The id the manga server knows the source by: fixed by DebridOne when it built the jar. */
    val sourceId: Long = json["debridoneSourceId"]?.jsonPrimitive?.longOrNull ?: error("source.json has no debridoneSourceId")
    val mangayomiId: Long = json["id"]?.jsonPrimitive?.longOrNull ?: 0
    val name: String = text("name")
    val baseUrl: String = text("baseUrl").trimEnd('/')
    val lang: String = normalizeLanguage(text("lang"))
    val hasCloudflare: Boolean = json["hasCloudflare"]?.jsonPrimitive?.booleanOrNull ?: false

    /** What `this.source` gives the code: Mangayomi's MSource fields. */
    fun sourceObject(): String {
        val fields = linkedMapOf(
            "apiUrl" to json["apiUrl"], "baseUrl" to json["baseUrl"], "dateFormat" to json["dateFormat"], "dateFormatLocale" to json["dateFormatLocale"],
            "hasCloudflare" to json["hasCloudflare"], "id" to json["id"], "isFullData" to json["isFullData"], "lang" to json["lang"],
            "name" to json["name"], "additionalParams" to json["additionalParams"], "notes" to json["notes"],
        )
        return JsonObject(fields.mapValues { it.value ?: kotlinx.serialization.json.JsonNull }).toString()
    }

    companion object {
        private val loaded = ConcurrentHashMap<ClassLoader, SourceMeta>()

        fun of(type: Class<*>): SourceMeta = loaded.computeIfAbsent(type.classLoader) { loader ->
            fun resource(name: String): String =
                loader.getResourceAsStream("debridone-mangayomi/$name")?.use { String(it.readBytes(), Charsets.UTF_8) }
                    ?: error("This extension is incomplete: debridone-mangayomi/$name is missing.")
            SourceMeta(Json.parseToJsonElement(resource("source.json")).jsonObject, resource("source.js"))
        }

        /** Mangayomi writes "pt-br" and "zh-hans"; the manga server expects "pt-BR". */
        fun normalizeLanguage(lang: String): String {
            val parts = lang.trim().replace('_', '-').split('-').filter { it.isNotBlank() }
            if (parts.isEmpty()) return "all"
            val head = parts[0].lowercase()
            return if (parts.size == 1) head else head + "-" + parts.drop(1).joinToString("-") { if (it.length == 2) it.uppercase() else it.lowercase().replaceFirstChar { c -> c.uppercase() } }
        }
    }
}
