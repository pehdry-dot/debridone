package com.debridone.music

import com.debridone.module.storage.ProfileSettingsTableStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small per-profile choices of the Music section that are not appearance: the last library tab,
 * sort orders, which Home sections are shown, start-up behaviour, the output device.
 */
class MusicPreferences internal constructor(private val store: () -> ProfileSettingsTableStore) {
    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The active profile's preferences. Empty while no profile is active. */
    val values: StateFlow<Map<String, String>> = _values.asStateFlow()

    @Volatile
    private var profileId: Long? = null

    internal fun load(profile: Long?) {
        profileId = profile
        _values.value = if (profile == null) emptyMap() else store().all(profile, PREFIX).mapKeys { it.key.removePrefix(PREFIX) }
    }

    operator fun get(key: String): String? = _values.value[key]

    fun boolean(key: String, default: Boolean): Boolean = get(key)?.toBooleanStrictOrNull() ?: default

    /** Saves [value] for the active profile; null removes the preference. */
    fun set(key: String, value: String?) {
        val profile = profileId ?: return
        store().set(profile, PREFIX + key, value)
        _values.value = if (value == null) _values.value - key else _values.value + (key to value)
    }

    companion object {
        private const val PREFIX = "pref."

        const val LIBRARY_TAB = "library_tab"
        const val SONG_SORT = "song_sort"
        const val SONG_SORT_DESCENDING = "song_sort_descending"
        const val ALBUM_SORT = "album_sort"
        const val ALBUM_SORT_DESCENDING = "album_sort_descending"
        const val HOME_HIDDEN_SECTIONS = "home_hidden_sections"
        const val DEFAULT_VIEW = "default_view"
        const val RESCAN_ON_START = "rescan_on_start"
        const val RESUME_ON_START = "resume_on_start"
        const val AUDIO_DEVICE = "audio_device"
    }
}
