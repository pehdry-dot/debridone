package com.debridone.app.manga

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.manga.suwayomi.MangaServerSettings
import com.debridone.manga.suwayomi.ServerOptions
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The manga server's own settings: how chapters are downloaded, how often it looks for new ones,
 * how it reaches sources, and its own backups. They are read from and saved to the server; a
 * change is kept only when "Save" is pressed, and a refusal is shown here.
 */
@Composable
internal fun ServerOptionsBlocks(remote: Boolean) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val generation by manga.services.server.generation.collectAsState()
    var saved by remember(manga.profileId, generation) { mutableStateOf<ServerOptions?>(null) }
    var draft by remember(manga.profileId, generation) { mutableStateOf(ServerOptions()) }
    var limit by remember(manga.profileId, generation) { mutableStateOf("") }
    var interval by remember(manga.profileId, generation) { mutableStateOf("") }
    var parallel by remember(manga.profileId, generation) { mutableStateOf("") }
    var solverTimeout by remember(manga.profileId, generation) { mutableStateOf("") }
    var backupEvery by remember(manga.profileId, generation) { mutableStateOf("") }
    var backupKeep by remember(manga.profileId, generation) { mutableStateOf("") }
    var proxyPassword by remember(manga.profileId, generation) { mutableStateOf<String?>(null) }
    var problem by remember(manga.profileId, generation) { mutableStateOf<String?>(null) }
    var notice by remember(manga.profileId, generation) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun show(options: ServerOptions) {
        saved = options
        draft = options
        limit = options.autoDownloadLimit.toString()
        interval = trimHours(options.updateIntervalHours)
        parallel = options.sourcesInParallel.toString()
        solverTimeout = options.flareSolverrTimeoutSeconds.toString()
        backupEvery = options.backupIntervalDays.toString()
        backupKeep = options.backupKeepDays.toString()
        proxyPassword = null
    }

    LaunchedEffect(manga.profileId, generation) {
        withContext(Dispatchers.IO) { runCatching { manga.services.serverSettings.read() } }
            .onSuccess(::show)
            .onFailure { problem = it.message ?: "The server's settings could not be read." }
    }

    val before = saved
    if (before == null) {
        SettingsBlock("The server's own settings") {
            problem?.let { Notice(it, PillTone.DANGER) } ?: CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
        return
    }

    // What the fields say now, as the options they would be saved as.
    val wanted = draft.copy(
        autoDownloadLimit = limit.toIntOrNull() ?: before.autoDownloadLimit,
        updateIntervalHours = interval.replace(',', '.').toDoubleOrNull() ?: before.updateIntervalHours,
        sourcesInParallel = parallel.toIntOrNull() ?: before.sourcesInParallel,
        flareSolverrTimeoutSeconds = solverTimeout.toIntOrNull() ?: before.flareSolverrTimeoutSeconds,
        backupIntervalDays = backupEvery.toIntOrNull() ?: before.backupIntervalDays,
        backupKeepDays = backupKeep.toIntOrNull() ?: before.backupKeepDays,
    )
    val changed = wanted != before || proxyPassword != null
    fun edit(change: ServerOptions.() -> ServerOptions) {
        draft = draft.change()
        notice = null
        problem = null
    }

    if (remote) {
        Notice("These are the settings of your own server: anything else that uses that server sees a change made here.", PillTone.NEUTRAL)
    }

    SettingsBlock("Downloads", "How the manga server keeps the chapters you download.") {
        ServerToggle("Keep each downloaded chapter as one CBZ file", draft.downloadAsCbz) { edit { copy(downloadAsCbz = it) } }
        ServerToggle("Download new chapters by themselves", draft.autoDownloadNewChapters) { edit { copy(autoDownloadNewChapters = it) } }
        if (draft.autoDownloadNewChapters) {
            ServerToggle("…but not for a series that still has unread chapters", draft.autoDownloadSkipsSeriesWithUnread) { edit { copy(autoDownloadSkipsSeriesWithUnread = it) } }
        }
        if (draft.autoDownloadNewChapters) {
            MangaTextField(limit, { limit = it.filter(Char::isDigit).take(4); notice = null }, "At most this many new chapters per series (0 = no limit)", Modifier.width(380.dp))
        }
        Text(
            "New chapters are downloaded by themselves only for series in your library, when the server looks for new chapters. Chapters already downloaded stay as they are.",
            style = small(), color = tokens.colors.textMuted,
        )
    }

    SettingsBlock("Looking for new chapters", "The server asks the sources of the series in your library, by itself and when you ask it to from Library.") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            MangaTextField(interval, { interval = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6); notice = null }, "Hours between checks (0 = never by itself)", Modifier.width(300.dp))
            MangaTextField(parallel, { parallel = it.filter(Char::isDigit).take(2); notice = null }, "Sources asked at the same time (1–20)", Modifier.width(300.dp))
        }
        Text("The server checks no more often than every ${trimHours(MangaServerSettings.MIN_UPDATE_INTERVAL_HOURS)} hours.", style = small(), color = tokens.colors.textMuted)
        ServerToggle("Leave out series that still have unread chapters", draft.updateSkipsUnread) { edit { copy(updateSkipsUnread = it) } }
        ServerToggle("Leave out series you have not started", draft.updateSkipsNotStarted) { edit { copy(updateSkipsNotStarted = it) } }
        ServerToggle("Leave out series their source marks as completed", draft.updateSkipsCompleted) { edit { copy(updateSkipsCompleted = it) } }
        ServerToggle("Also read each series' details again", draft.updateRefreshesDetails) { edit { copy(updateRefreshesDetails = it) } }
    }

    SettingsBlock("How sources are reached", "Only needed when a source cannot be reached directly from this computer.") {
        ServerToggle("Through a SOCKS proxy", draft.proxyEnabled) { edit { copy(proxyEnabled = it) } }
        if (draft.proxyEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                MangaTextField(draft.proxyHost, { value -> edit { copy(proxyHost = value) } }, "Proxy host", Modifier.width(300.dp))
                MangaTextField(draft.proxyPort, { value -> edit { copy(proxyPort = value.filter(Char::isDigit).take(5)) } }, "Port", Modifier.width(120.dp))
                MangaChip("SOCKS 4", draft.proxyVersion == 4, Modifier.border(1.dp, tokens.colors.borderDefault, RoundedCornerShape(50))) { edit { copy(proxyVersion = 4) } }
                MangaChip("SOCKS 5", draft.proxyVersion != 4, Modifier.border(1.dp, tokens.colors.borderDefault, RoundedCornerShape(50))) { edit { copy(proxyVersion = 5) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                MangaTextField(draft.proxyUsername, { value -> edit { copy(proxyUsername = value) } }, "User name (if the proxy asks for one)", Modifier.width(300.dp))
                MangaTextField(
                    proxyPassword.orEmpty(), { proxyPassword = it; notice = null },
                    if (before.proxyHasPassword) "Password (saved; type to replace it)" else "Password", Modifier.width(300.dp), secret = true,
                )
            }
            Text("The proxy's password is kept by the manga server, in this profile's folder.", style = small(), color = tokens.colors.textMuted)
        }
        ServerToggle("Use a FlareSolverr of my own for sources behind a browser check", draft.flareSolverrEnabled) { edit { copy(flareSolverrEnabled = it) } }
        if (draft.flareSolverrEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                MangaTextField(draft.flareSolverrUrl, { value -> edit { copy(flareSolverrUrl = value) } }, "Address of your FlareSolverr", Modifier.width(420.dp), hint = "For example http://localhost:8191")
                MangaTextField(solverTimeout, { solverTimeout = it.filter(Char::isDigit).take(3); notice = null }, "Seconds to wait", Modifier.width(160.dp))
            }
            Text("FlareSolverr is a separate program you run yourself; DebridOne does not include it.", style = small(), color = tokens.colors.textMuted)
        }
    }

    SettingsBlock("The server's own backups", "Besides the backups you export yourself, the server saves the library to its folder on its own.") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            MangaTextField(backupEvery, { backupEvery = it.filter(Char::isDigit).take(3); notice = null }, "Days between backups (0 = never)", Modifier.width(300.dp))
            MangaTextField(backupKeep, { backupKeep = it.filter(Char::isDigit).take(4); notice = null }, "Days a backup is kept", Modifier.width(300.dp))
        }
    }

    problem?.let { Notice(it, PillTone.DANGER) }
    notice?.let { Notice(it, PillTone.SUCCESS) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        MangaButton(if (busy) "Saving…" else "Save the server's settings", {
            busy = true
            problem = null
            notice = null
            scope.launch {
                val outcome = withContext(Dispatchers.IO) { runCatching { manga.services.serverSettings.save(before, wanted, proxyPassword) } }
                busy = false
                outcome.onSuccess { now ->
                    val adjusted = now != wanted.copy(proxyHasPassword = now.proxyHasPassword)
                    show(now)
                    notice = if (adjusted) "Saved. Some values were brought into the range the server accepts." else "Saved."
                }.onFailure { problem = it.message ?: "The server did not accept these settings." }
            }
        }, enabled = changed && !busy)
        MangaButton("Discard changes", { show(before); notice = null; problem = null }, primary = false, enabled = changed && !busy)
        if (!changed && notice == null) Text("Nothing to save.", style = small(), color = tokens.colors.textMuted)
    }
}

/** One yes-or-no setting of the server. */
@Composable
private fun ServerToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val manga = LocalManga.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.nuvio.colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = manga.style.accent, checkedThumbColor = manga.style.onAccent),
        )
    }
}

private fun trimHours(hours: Double): String = if (hours == Math.floor(hours)) hours.toLong().toString() else hours.toString()
