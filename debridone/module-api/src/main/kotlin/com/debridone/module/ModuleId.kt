package com.debridone.module

/**
 * The four entertainment modules. Each is an independent application living in the same window:
 * its own engine, plugins, metadata, library, settings and storage.
 *
 * [key] is the stable identifier used for storage directories and secret namespaces. It must never
 * change once released.
 */
enum class ModuleId(val key: String, val displayName: String) {
    STREAMING("streaming", "Streaming"),
    MUSIC("music", "Music"),
    GAMING("gaming", "Games"),
    MANGA("manga", "Manga"),
    ;

    companion object {
        fun fromKey(key: String?): ModuleId? = entries.firstOrNull { it.key == key }
    }
}
