package com.debridone.mangayomi.bridge

import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Settings kept in memory, as the manga server keeps a source's settings on disk. */
class MemoryPreferences : SharedPreferences {
    val values = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String, default: String?): String? = values[key] as? String ?: default
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: MutableSet<String>?): MutableSet<String>? = (values[key] as? Set<String>)?.toMutableSet() ?: default
    override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default
    override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
    override fun getFloat(key: String, default: Float): Float = values[key] as? Float ?: default
    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    override fun contains(key: String): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { values[key] = value }
        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { values[key] = value?.toSet() }
        override fun putInt(key: String, value: Int) = apply { values[key] = value }
        override fun putLong(key: String, value: Long) = apply { values[key] = value }
        override fun putFloat(key: String, value: Float) = apply { values[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
        override fun remove(key: String) = apply { values.remove(key) }
        override fun clear() = apply { values.clear() }
        override fun commit() = true
        override fun apply() = Unit
    }
}

/** One source's code, run by the bridge exactly as the extension runs it, outside the server. */
class LoadedSource(code: String, entry: JsonObject, client: OkHttpClient = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()) : AutoCloseable {
    val preferences = MemoryPreferences()
    private val meta = SourceMeta(JsonObject(entry + ("debridoneSourceId" to JsonPrimitive(1L))), code)
    private var definitions: JsonArray? = null
    private var headers: Headers? = null
    internal val runtime: JsRuntime = JsRuntime(
        meta,
        HostCalls({ client }, { headers ?: Headers.Builder().build() }, { preferences }, { definitions ?: JsonArray(emptyList()) }, meta.name),
    )

    init {
        definitions = runtime.callOptional("getSourcePreferences()")?.let { Json.parseToJsonElement(it) as? JsonArray } ?: JsonArray(emptyList())
        headers = Headers.Builder().apply {
            runtime.callOptional("getHeaders(${JsonPrimitive(meta.baseUrl)})")?.let { Json.parseToJsonElement(it) as? JsonObject }
                ?.forEach { (k, v) -> set(k, (v as? JsonPrimitive)?.content ?: v.toString()) }
        }.build()
    }

    fun call(expression: String) = Json.parseToJsonElement(runtime.call(expression))

    override fun close() = runtime.close()
}
