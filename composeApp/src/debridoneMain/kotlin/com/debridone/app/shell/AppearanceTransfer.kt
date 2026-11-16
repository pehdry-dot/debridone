package com.debridone.app.shell

import com.debridone.app.DebridOneRuntime
import com.debridone.module.ModuleId
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.features.settings.ThemeSettingsRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Import/export of the active profile's appearance as a small JSON document.
 *
 * Each module's values are written to and read from that module's own store; an imported file
 * cannot make one module's settings apply to another, and unknown or invalid values are dropped
 * by the module itself.
 */
internal object AppearanceTransfer {
    private const val FORMAT = "debridone-appearance"
    private const val VERSION = 1
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val engineModules = listOf(ModuleId.MUSIC, ModuleId.GAMING, ModuleId.MANGA)

    fun export(): String {
        val document = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            putJsonObject("shell") {
                put("sidebar_style", ShellState.sidebarStyle.value.key)
                put("reduce_motion", ShellState.reduceMotion.value)
            }
            putJsonObject("streaming") {
                put("theme", ThemeSettingsRepository.selectedTheme.value.name)
                put("amoled", ThemeSettingsRepository.amoledEnabled.value)
            }
            putJsonObject("modules") {
                engineModules.forEach { id ->
                    val values = DebridOneRuntime.modules.module(id)?.appearance?.current?.value?.values.orEmpty()
                    putJsonObject(id.key) { values.forEach { (key, value) -> put(key, value) } }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), document)
    }

    /** Applies [text]; returns a message describing the outcome. */
    suspend fun import(text: String): Result<String> = runCatching {
        val document = json.parseToJsonElement(text).jsonObject
        require(document["format"]?.jsonPrimitive?.content == FORMAT) { "This is not a DebridOne appearance file." }
        val version = document["version"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        require(version in 1..VERSION) { "This appearance file needs a newer DebridOne." }

        (document["shell"] as? JsonObject)?.let { shell ->
            shell.string("sidebar_style")?.let { ShellState.setSidebarStyle(SidebarStyle.fromKey(it)) }
            shell.string("reduce_motion")?.toBooleanStrictOrNull()?.let(ShellState::setReduceMotion)
        }
        (document["streaming"] as? JsonObject)?.let { streaming ->
            streaming.string("theme")
                ?.let { name -> AppTheme.entries.firstOrNull { it.name == name && it != AppTheme.CUSTOM } }
                ?.let(ThemeSettingsRepository::setTheme)
            streaming.string("amoled")?.toBooleanStrictOrNull()?.let(ThemeSettingsRepository::setAmoled)
        }
        var applied = 0
        (document["modules"] as? JsonObject)?.let { modules ->
            engineModules.forEach { id ->
                val values = (modules[id.key] as? JsonObject)
                    ?.mapNotNull { (key, element) -> (element as? JsonPrimitive)?.content?.let { key to it } }
                    ?.toMap()
                    ?: return@forEach
                DebridOneRuntime.modules.module(id)?.appearance?.replaceAll(values)
                applied++
            }
        }
        "Appearance imported for $applied sections."
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
