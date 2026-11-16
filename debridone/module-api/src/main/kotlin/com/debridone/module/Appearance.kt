package com.debridone.module

import kotlinx.coroutines.flow.StateFlow

/** One customisable visual setting of a module. Rendered generically by the Customization Studio. */
sealed interface AppearanceOption {
    val key: String
    val label: String
    val description: String?

    data class Choice(
        override val key: String,
        override val label: String,
        val choices: List<ChoiceValue>,
        val defaultValue: String,
        override val description: String? = null,
    ) : AppearanceOption {
        init {
            require(choices.any { it.value == defaultValue }) { "Default '$defaultValue' is not a choice of '$key'" }
        }
    }

    data class Toggle(
        override val key: String,
        override val label: String,
        val defaultValue: Boolean,
        override val description: String? = null,
    ) : AppearanceOption

    /** A colour as `#RRGGBB`; null means "follow the global accent". */
    data class Colour(
        override val key: String,
        override val label: String,
        override val description: String? = null,
    ) : AppearanceOption
}

data class ChoiceValue(val value: String, val label: String)

/** Keys every module understands, so the shell can theme the module's surface consistently. */
object CommonAppearanceKeys {
    const val ACCENT = "accent"
    const val CARD_SIZE = "card_size"
    const val DENSITY = "density"
    const val LAYOUT = "layout"
}

/** Resolved values for one profile. Unknown keys fall back to the option's default. */
data class ModuleAppearance(val values: Map<String, String>) {
    fun choice(option: AppearanceOption.Choice): String =
        values[option.key]?.takeIf { stored -> option.choices.any { it.value == stored } } ?: option.defaultValue

    fun toggle(option: AppearanceOption.Toggle): Boolean =
        values[option.key]?.toBooleanStrictOrNull() ?: option.defaultValue

    fun colour(option: AppearanceOption.Colour): String? = values[option.key]?.takeIf(::isHexColour)

    val accentHex: String? get() = values[CommonAppearanceKeys.ACCENT]?.takeIf(::isHexColour)

    companion object {
        val Empty = ModuleAppearance(emptyMap())
        private val HEX = Regex("#[0-9A-Fa-f]{6}")

        fun isHexColour(value: String): Boolean = HEX.matches(value)
    }
}

/**
 * Per-profile appearance of ONE module, stored in that module's own database. Changing Music's
 * appearance cannot affect Games because they are different stores over different files.
 */
interface ModuleAppearanceStore {
    val moduleId: ModuleId

    /** The settings this module offers. */
    val options: List<AppearanceOption>

    /** Appearance of the currently active profile. */
    val current: StateFlow<ModuleAppearance>

    suspend fun set(key: String, value: String?)

    /** Replaces all values for the active profile (theme import). Unknown/invalid entries are dropped. */
    suspend fun replaceAll(values: Map<String, String>)

    suspend fun resetToDefaults()
}
