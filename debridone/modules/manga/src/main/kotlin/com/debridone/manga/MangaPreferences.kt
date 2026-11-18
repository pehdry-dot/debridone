package com.debridone.manga

import com.debridone.manga.library.MediaType
import com.debridone.manga.reader.ReaderSettings
import com.debridone.module.storage.ProfileSettingsTableStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-profile choices of the Manga section that are not appearance: reading presets for Manga and
 * for Manhwa, source languages, the last library view and sort order, and which server the
 * profile reads from.
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

    /**
     * Language codes of the sources that are switched on unless the person decided for a source
     * itself. An extension often brings one source per language; without this, installing one
     * would add dozens of sources to every search. "*" means every language.
     */
    fun sourceLanguages(): Set<String> {
        val stored = get(SOURCE_LANGUAGES) ?: return com.debridone.manga.sources.MangaSources.defaultLanguages()
        return if (stored == ALL_LANGUAGES) emptySet() else stored.split(',').filter { it.isNotBlank() }.toSet()
    }

    /** An empty set means every language. */
    fun setSourceLanguages(languages: Set<String>) = set(SOURCE_LANGUAGES, if (languages.isEmpty()) ALL_LANGUAGES else languages.sorted().joinToString(","))

    private fun readerKey(type: MediaType) = "reader.${type.key}"

    companion object {
        private const val PREFIX = "pref."
        const val SOURCE_LANGUAGES = "source_languages"
        private const val ALL_LANGUAGES = "*"

        /** `managed` (the server DebridOne starts, the default) or `remote`. */
        const val SERVER_MODE = "server.mode"
        const val SERVER_MODE_REMOTE = "remote"
        const val SERVER_URL = "server.url"
        const val SERVER_USERNAME = "server.username"
        const val SERVER_TIMEOUT = "server.timeout"
        const val SERVER_REQUIRE_HTTPS = "server.require_https"
        const val LIBRARY_SORT = "library_sort"
        const val LIBRARY_SORT_DESCENDING = "library_sort_descending"
        const val DISCOVER_SOURCE = "discover_source"
        const val CHAPTERS_DESCENDING = "chapters_descending"
    }
}

/**
 * Settings of the Manga section that belong to the app rather than to a profile. Stored under the
 * reserved profile id 0 of Manga's own settings table. (How sources are reached, how many chapters
 * download at once and where they are kept are settings of the Suwayomi server itself.)
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

    /** Megabytes of covers from metadata providers kept on disk. */
    val cacheMegabytes: Int get() = get(CACHE_MEGABYTES)?.toIntOrNull()?.coerceIn(50, 5_000) ?: 300

    /** True once the person has read what an extension is allowed to do (shown when the first store is added). */
    val extensionNoticeSeen: Boolean get() = get(EXTENSION_NOTICE_SEEN) == "true"

    companion object {
        private const val APP_SCOPE = 0L
        private const val PREFIX = "app."
        const val CACHE_MEGABYTES = "cache_megabytes"
        const val EXTENSION_NOTICE_SEEN = "extension_notice_seen"
    }
}
