package com.debridone.manga

import com.debridone.manga.library.MediaType
import com.debridone.manga.reader.ReaderSettings
import com.debridone.module.storage.ProfileSettingsTableStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-profile choices of the Manga section that are not appearance: reading presets for Manga and
 * for Manhwa, chapter languages, the last library view and sort order.
 */
class MangaPreferences internal constructor(private val store: () -> ProfileSettingsTableStore) {
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

    fun int(key: String, default: Int): Int = get(key)?.toIntOrNull() ?: default

    /** Saves [value] for the active profile; null removes the preference. */
    fun set(key: String, value: String?) {
        val profile = profileId ?: return
        store().set(profile, PREFIX + key, value)
        _values.value = if (value == null) _values.value - key else _values.value + (key to value)
    }

    /** The reading preset of a shelf. Manga and Manhwa each have their own. */
    fun readerSettings(type: MediaType): ReaderSettings = ReaderSettings.decode(get(readerKey(type)), ReaderSettings.defaults(type))

    fun setReaderSettings(type: MediaType, settings: ReaderSettings) = set(readerKey(type), settings.normalized().encode())

    /** Tiyo language keys of the chapters to list and count. Empty: every language. */
    fun chapterLanguages(): Set<String> = get(CHAPTER_LANGUAGES)?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    fun setChapterLanguages(languages: Set<String>) = set(CHAPTER_LANGUAGES, languages.sorted().joinToString(",").ifEmpty { null })

    private fun readerKey(type: MediaType) = "reader.${type.key}"

    companion object {
        private const val PREFIX = "pref."
        const val CHAPTER_LANGUAGES = "chapter_languages"
        const val LIBRARY_SORT = "library_sort"
        const val LIBRARY_SORT_DESCENDING = "library_sort_descending"
        const val LIBRARY_SHELF = "library_shelf"
        const val DISCOVER_SOURCE = "discover_source"
        const val DISCOVER_TYPE = "discover_type"
        const val CHAPTERS_DESCENDING = "chapters_descending"
        const val REFRESH_ON_START = "refresh_on_start"
        const val HOME_HIDDEN_SECTIONS = "home_hidden_sections"
    }
}

/**
 * Settings of the Manga section that belong to the app rather than to a profile: how the extension
 * host reaches the network, time limits, and the size of the page cache. Stored under the reserved
 * profile id 0 of Manga's own settings table.
 */
class MangaAppSettings internal constructor(private val store: () -> ProfileSettingsTableStore) {
    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())
    val values: StateFlow<Map<String, String>> = _values.asStateFlow()

    internal fun load() {
        _values.value = store().all(APP_SCOPE, PREFIX).mapKeys { it.key.removePrefix(PREFIX) }
    }

    operator fun get(key: String): String? = _values.value[key]

    fun set(key: String, value: String?) {
        store().set(APP_SCOPE, PREFIX + key, value)
        _values.value = if (value == null) _values.value - key else _values.value + (key to value)
    }

    /** Seconds a source may take to answer one request. */
    val timeoutSeconds: Int get() = get(TIMEOUT_SECONDS)?.toIntOrNull()?.coerceIn(10, 180) ?: 45

    /** Megabytes of pages kept on disk for re-reading without downloading again. */
    val cacheMegabytes: Int get() = get(CACHE_MEGABYTES)?.toIntOrNull()?.coerceIn(100, 20_000) ?: 1_000

    /** `http://host:port` for the extensions' requests, or null to connect directly. */
    val proxy: String? get() = get(PROXY)?.trim()?.takeIf { it.isNotEmpty() }

    val showLegacyPackages: Boolean get() = get(SHOW_LEGACY) == "true"

    /** Chapters downloaded at the same time. */
    val parallelDownloads: Int get() = get(PARALLEL_DOWNLOADS)?.toIntOrNull()?.coerceIn(1, 3) ?: 1

    companion object {
        private const val APP_SCOPE = 0L
        private const val PREFIX = "app."
        const val TIMEOUT_SECONDS = "timeout_seconds"
        const val CACHE_MEGABYTES = "cache_megabytes"
        const val PROXY = "proxy"
        const val SHOW_LEGACY = "show_legacy"
        const val PARALLEL_DOWNLOADS = "parallel_downloads"
    }
}
