package com.debridone.manga.suwayomi

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The settings of Suwayomi-Server that matter to someone reading in DebridOne. They are the
 * server's own (kept in its `server.conf`), read and written through its API; DebridOne keeps no
 * copy. What DebridOne itself decides for a server it starts (address, port, sign-in, no web
 * interface) is not here and cannot be changed from here.
 */
data class ServerOptions(
    /** Downloaded chapters are kept as one CBZ file each rather than a folder of images. */
    val downloadAsCbz: Boolean = false,
    /** New chapters found when the library is checked are downloaded by themselves. */
    val autoDownloadNewChapters: Boolean = false,
    /** At most this many new chapters per series; 0 means no limit. */
    val autoDownloadLimit: Int = 0,
    /** New chapters are not downloaded by themselves for a series that still has unread chapters. */
    val autoDownloadSkipsSeriesWithUnread: Boolean = true,
    /** Hours between the server's own checks for new chapters; 0 switches them off. Suwayomi accepts 6 or more. */
    val updateIntervalHours: Double = 12.0,
    /** A check for new chapters also asks each source for the series' details again. */
    val updateRefreshesDetails: Boolean = false,
    val updateSkipsUnread: Boolean = true,
    val updateSkipsNotStarted: Boolean = true,
    val updateSkipsCompleted: Boolean = true,
    /** How many sources are asked at the same time (1–20). */
    val sourcesInParallel: Int = 6,
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: String = "",
    /** 4 or 5. */
    val proxyVersion: Int = 5,
    val proxyUsername: String = "",
    /** Whether a proxy password is saved; the password itself is never read back. */
    val proxyHasPassword: Boolean = false,
    val flareSolverrEnabled: Boolean = false,
    val flareSolverrUrl: String = "",
    val flareSolverrTimeoutSeconds: Int = 60,
    /** Days between the server's own backups of the library; 0 switches them off. */
    val backupIntervalDays: Int = 1,
    /** Days a backup is kept. */
    val backupKeepDays: Int = 14,
)

/** Reads and changes [ServerOptions] on the connected server. */
class MangaServerSettings(private val server: MangaServer) {
    private val api: SuwayomiApi get() = server.api()

    fun read(): ServerOptions {
        val v = api.settings(NAMES)
        fun bool(name: String, fallback: Boolean) = (v[name] as? JsonPrimitive)?.booleanOrNull ?: fallback
        fun int(name: String, fallback: Int) = (v[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: fallback
        fun double(name: String, fallback: Double) = (v[name] as? JsonPrimitive)?.doubleOrNull ?: fallback
        fun text(name: String) = (v[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
        val defaults = ServerOptions()
        return ServerOptions(
            downloadAsCbz = bool("downloadAsCbz", defaults.downloadAsCbz),
            autoDownloadNewChapters = bool("autoDownloadNewChapters", defaults.autoDownloadNewChapters),
            autoDownloadLimit = int("autoDownloadNewChaptersLimit", defaults.autoDownloadLimit),
            autoDownloadSkipsSeriesWithUnread = bool("excludeEntryWithUnreadChapters", defaults.autoDownloadSkipsSeriesWithUnread),
            updateIntervalHours = double("globalUpdateInterval", defaults.updateIntervalHours),
            updateRefreshesDetails = bool("updateMangas", defaults.updateRefreshesDetails),
            updateSkipsUnread = bool("excludeUnreadChapters", defaults.updateSkipsUnread),
            updateSkipsNotStarted = bool("excludeNotStarted", defaults.updateSkipsNotStarted),
            updateSkipsCompleted = bool("excludeCompleted", defaults.updateSkipsCompleted),
            sourcesInParallel = int("maxSourcesInParallel", defaults.sourcesInParallel),
            proxyEnabled = bool("socksProxyEnabled", defaults.proxyEnabled),
            proxyHost = text("socksProxyHost"),
            proxyPort = text("socksProxyPort"),
            proxyVersion = int("socksProxyVersion", defaults.proxyVersion),
            proxyUsername = text("socksProxyUsername"),
            proxyHasPassword = text("socksProxyPassword").isNotEmpty(),
            flareSolverrEnabled = bool("flareSolverrEnabled", defaults.flareSolverrEnabled),
            flareSolverrUrl = text("flareSolverrUrl"),
            flareSolverrTimeoutSeconds = int("flareSolverrTimeout", defaults.flareSolverrTimeoutSeconds),
            backupIntervalDays = int("backupInterval", defaults.backupIntervalDays),
            backupKeepDays = int("backupTTL", defaults.backupKeepDays),
        )
    }

    /**
     * Saves what differs between [before] and [after]; values are brought into the ranges the
     * server accepts. [proxyPassword] is written only when given (an empty text removes it).
     * Returns the options as the server then has them.
     */
    fun save(before: ServerOptions, after: ServerOptions, proxyPassword: String? = null): ServerOptions {
        val changes = LinkedHashMap<String, JsonElement>()
        fun put(name: String, old: Any, new: Any) {
            if (old == new) return
            changes[name] = when (new) {
                is Boolean -> JsonPrimitive(new)
                is Int -> JsonPrimitive(new)
                is Double -> JsonPrimitive(new)
                else -> JsonPrimitive(new.toString())
            }
        }
        val interval = after.updateIntervalHours.let { if (it <= 0.0) 0.0 else it.coerceAtLeast(MIN_UPDATE_INTERVAL_HOURS) }
        put("downloadAsCbz", before.downloadAsCbz, after.downloadAsCbz)
        put("autoDownloadNewChapters", before.autoDownloadNewChapters, after.autoDownloadNewChapters)
        put("autoDownloadNewChaptersLimit", before.autoDownloadLimit, after.autoDownloadLimit.coerceAtLeast(0))
        put("excludeEntryWithUnreadChapters", before.autoDownloadSkipsSeriesWithUnread, after.autoDownloadSkipsSeriesWithUnread)
        put("globalUpdateInterval", before.updateIntervalHours, interval)
        put("updateMangas", before.updateRefreshesDetails, after.updateRefreshesDetails)
        put("excludeUnreadChapters", before.updateSkipsUnread, after.updateSkipsUnread)
        put("excludeNotStarted", before.updateSkipsNotStarted, after.updateSkipsNotStarted)
        put("excludeCompleted", before.updateSkipsCompleted, after.updateSkipsCompleted)
        put("maxSourcesInParallel", before.sourcesInParallel, after.sourcesInParallel.coerceIn(1, 20))
        put("socksProxyEnabled", before.proxyEnabled, after.proxyEnabled)
        put("socksProxyHost", before.proxyHost, after.proxyHost.trim())
        put("socksProxyPort", before.proxyPort, after.proxyPort.trim())
        put("socksProxyVersion", before.proxyVersion, if (after.proxyVersion == 4) 4 else 5)
        put("socksProxyUsername", before.proxyUsername, after.proxyUsername.trim())
        if (proxyPassword != null) changes["socksProxyPassword"] = JsonPrimitive(proxyPassword)
        put("flareSolverrEnabled", before.flareSolverrEnabled, after.flareSolverrEnabled)
        put("flareSolverrUrl", before.flareSolverrUrl, after.flareSolverrUrl.trim())
        put("flareSolverrTimeout", before.flareSolverrTimeoutSeconds, after.flareSolverrTimeoutSeconds.coerceIn(5, 600))
        put("backupInterval", before.backupIntervalDays, after.backupIntervalDays.coerceAtLeast(0))
        put("backupTTL", before.backupKeepDays, after.backupKeepDays.coerceAtLeast(0))
        api.setSettings(changes)
        return read()
    }

    companion object {
        const val MIN_UPDATE_INTERVAL_HOURS = 6.0

        private val NAMES = listOf(
            "downloadAsCbz", "autoDownloadNewChapters", "autoDownloadNewChaptersLimit", "excludeEntryWithUnreadChapters",
            "globalUpdateInterval", "updateMangas", "excludeUnreadChapters", "excludeNotStarted", "excludeCompleted", "maxSourcesInParallel",
            "socksProxyEnabled", "socksProxyHost", "socksProxyPort", "socksProxyVersion", "socksProxyUsername", "socksProxyPassword",
            "flareSolverrEnabled", "flareSolverrUrl", "flareSolverrTimeout", "backupInterval", "backupTTL",
        )
    }
}
