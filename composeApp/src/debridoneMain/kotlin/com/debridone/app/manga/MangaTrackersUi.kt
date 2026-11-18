package com.debridone.app.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.StatusPill
import com.debridone.manga.library.Series
import com.debridone.manga.tracking.BrowserSignIn
import com.debridone.manga.tracking.TrackCandidate
import com.debridone.manga.tracking.TrackEntry
import com.debridone.manga.tracking.Tracker
import com.nuvio.app.core.ui.PluginConfigurationPanel
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the tracker screens are showing. */
internal object TrackerUi {
    /** The tracker whose sign-in is open in the configuration panel. */
    var signingIn by mutableStateOf<Int?>(null)
}

/** What a list of trackers could not be read for, or the list. */
internal class TrackerList(val trackers: List<Tracker> = emptyList(), val problem: String? = null, val loaded: Boolean = false)

/** The manga server's trackers, read again whenever a sign-in changes or another server is connected. */
@Composable
internal fun rememberTrackers(): State<TrackerList> {
    val manga = LocalManga.current
    val revision by manga.services.trackers.revision.collectAsState()
    val generation by manga.services.server.generation.collectAsState()
    return produceState(TrackerList(), manga.profileId, revision, generation) {
        value = withContext(Dispatchers.IO) {
            runCatching { TrackerList(manga.services.trackers.list(), loaded = true) }
                .getOrElse { TrackerList(problem = it.message ?: "The trackers could not be read.", loaded = true) }
        }
    }
}

/**
 * Trackers: the reading-list services the manga server can keep up to date. The server signs in,
 * links series to entries and reports progress; this page shows where the profile is signed in
 * and opens a tracker's sign-in in the configuration panel.
 */
@Composable
internal fun TrackersTab() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val list by rememberTrackers()
    val legacy = remember(manga.profileId) { manga.services.metadata.hasLegacyTrackerSignIn(manga.profileId) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 14.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Trackers", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
            Text(
                "Reading lists on other services. The manga server signs in for this profile, links a series to its entry there, and tells the service how far you have read as you read. " +
                    "A series is linked from its own page, with \"Track Series\".",
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
            )
        }
        list.problem?.let { problem -> item { Notice(problem, PillTone.DANGER) } }
        if (!list.loaded) {
            item { CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp)) }
        }
        items(list.trackers, key = { it.id }) { tracker ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ServerIcon(tracker.iconPath, Modifier.size(38.dp))
                Column(Modifier.weight(1f)) {
                    Text(tracker.name, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
                    Text(
                        if (tracker.signsInWithBrowser) "Signs in on ${tracker.name}'s own page, in your browser" else "Signs in with your ${tracker.name} user name and password",
                        style = small(), color = tokens.colors.textMuted,
                    )
                }
                when {
                    tracker.signedIn && tracker.signInExpired -> StatusPill("Sign-in ran out", PillTone.WARNING)
                    tracker.signedIn -> StatusPill("Signed in", PillTone.SUCCESS)
                    else -> StatusPill("Not signed in")
                }
                if (tracker.signedIn && !tracker.signInExpired) {
                    MangaButton("Sign out", {
                        scope.launch(Dispatchers.IO) {
                            runCatching { manga.services.trackers.signOut(tracker.id) }
                                .onSuccess { MangaNav.message = "Signed out of ${tracker.name}. Series stay linked, and are updated again once you sign in." }
                                .onFailure { MangaNav.message = it.message ?: "Signing out did not work." }
                        }
                    }, primary = false)
                } else {
                    MangaButton(if (tracker.signedIn) "Sign in again" else "Sign in", { TrackerUi.signingIn = tracker.id }, primary = false)
                }
            }
        }
        item {
            Text(
                "Sign-ins are kept by this profile's manga server, in the profile's own folder; other profiles have their own. " +
                    "DebridOne adds no tracker of its own and sees a password only to pass it to the server.",
                style = small(), color = tokens.colors.textMuted,
            )
            if (legacy) {
                Text(
                    "The earlier version of Manga had its own AniList or MyAnimeList sign-in for this profile. It is no longer used: sign in above instead. " +
                        "The old sign-in stays in the credential store until this profile is deleted.",
                    style = small(), color = tokens.colors.textMuted, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** A tracker's sign-in, in the configuration panel over wherever "Sign in" was clicked. */
@Composable
internal fun MangaTrackerSignInPanel(modifier: Modifier = Modifier) {
    val list by rememberTrackers()
    val id = TrackerUi.signingIn
    // Kept while the panel slides away, so it does not blank out.
    var shown by remember { mutableStateOf<Tracker?>(null) }
    list.trackers.firstOrNull { it.id == id }?.let { shown = it }
    val close = { TrackerUi.signingIn = null }
    PluginConfigurationPanel(
        open = id != null,
        title = shown?.let { "Sign in to ${it.name}" } ?: "Sign in",
        kind = "Manga tracker · the sign-in is kept by this profile's manga server",
        onClose = close,
        modifier = modifier,
    ) { shown?.let { tracker -> if (tracker.signsInWithBrowser) BrowserSignInForm(tracker, close) else CredentialsSignInForm(tracker, close) } }
}

@Composable
private fun CredentialsSignInForm(tracker: Tracker, onDone: () -> Unit) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var username by remember(tracker.id) { mutableStateOf("") }
    var password by remember(tracker.id) { mutableStateOf("") }
    var busy by remember(tracker.id) { mutableStateOf(false) }
    var problem by remember(tracker.id) { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy || username.isBlank() || password.isEmpty()) return
        busy = true
        problem = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { manga.services.trackers.signIn(tracker.id, username, password) } }
            busy = false
            outcome.onSuccess {
                password = ""
                MangaNav.message = "Signed in to ${tracker.name}."
                onDone()
            }.onFailure { problem = it.message ?: "${tracker.name} did not accept the sign-in." }
        }
    }

    Text(
        "Your ${tracker.name} user name and password. They are sent to this profile's manga server, which signs in to ${tracker.name} and keeps the sign-in; DebridOne does not store them.",
        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
    )
    MangaTextField(username, { username = it; problem = null }, "User name", Modifier.fillMaxWidth(), onSubmit = ::submit)
    MangaTextField(password, { password = it; problem = null }, "Password", Modifier.fillMaxWidth(), onSubmit = ::submit, secret = true)
    problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        MangaButton(if (busy) "Signing in…" else "Sign in", ::submit, enabled = !busy && username.isNotBlank() && password.isNotEmpty())
        MangaButton("Cancel", onDone, primary = false, enabled = !busy)
    }
}

@Composable
private fun BrowserSignInForm(tracker: Tracker, onDone: () -> Unit) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var pending by remember(tracker.id) { mutableStateOf<BrowserSignIn?>(null) }
    var problem by remember(tracker.id) { mutableStateOf<String?>(null) }
    var pasted by remember(tracker.id) { mutableStateOf("") }
    var finishing by remember(tracker.id) { mutableStateOf(false) }

    // Leaving the panel gives the sign-in up: nothing keeps listening for it.
    DisposableEffect(tracker.id) { onDispose { pending?.close() } }

    fun begin() {
        problem = null
        pending?.close()
        scope.launch {
            val started = withContext(Dispatchers.IO) { runCatching { manga.services.trackers.beginBrowserSignIn(tracker.id) } }
            started.onFailure { problem = it.message ?: "The sign-in could not be started." }
            val signIn = started.getOrNull() ?: return@launch
            pending = signIn
            openInBrowser(signIn.address)
            val outcome = withContext(Dispatchers.IO) { runCatching { signIn.outcome.get() } }
            if (pending !== signIn) return@launch
            pending = null
            outcome.onSuccess {
                MangaNav.message = "Signed in to ${tracker.name}."
                onDone()
            }.onFailure { failure ->
                val cause = (failure as? java.util.concurrent.ExecutionException)?.cause ?: failure
                problem = when (cause) {
                    is java.util.concurrent.TimeoutException -> "Nothing came back from the browser in time. Start the sign-in again."
                    is java.util.concurrent.CancellationException -> null
                    else -> cause.message ?: "${tracker.name} did not accept the sign-in."
                }
            }
        }
    }

    Text(
        "${tracker.name} signs you in on its own page, in your browser: DebridOne never sees your password. When you allow access there, the browser is sent back to DebridOne " +
            "(through Suwayomi's own sign-in page), and this profile's manga server finishes the sign-in.",
        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
    )
    val waiting = pending
    if (waiting == null) {
        MangaButton("Sign in with my browser", ::begin, icon = Icons.Rounded.OpenInNew)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Text("Waiting for you to allow access in your browser…", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
            MangaButton("Cancel", { pending = null; waiting.close() }, primary = false)
        }
        Text("If the browser did not open, open this address in it:", style = small(), color = tokens.colors.textMuted)
        SelectionContainer {
            Text(
                waiting.address, style = small().copy(fontFamily = FontFamily.Monospace), color = tokens.colors.textMuted,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(tokens.colors.surface).padding(10.dp),
            )
        }
    }
    problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("If the browser ends on a page that does not load", style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
        Text(
            "Copy the whole address the browser shows after you allowed access and paste it here. It contains the sign-in, so treat it like a password.",
            style = small(), color = tokens.colors.textMuted,
        )
        MangaTextField(pasted, { pasted = it; problem = null }, "The address the browser ended at", Modifier.fillMaxWidth(), secret = true)
        MangaButton(if (finishing) "Finishing…" else "Finish the sign-in", {
            finishing = true
            problem = null
            scope.launch {
                val outcome = withContext(Dispatchers.IO) { runCatching { manga.services.trackers.completeBrowserSignIn(tracker.id, pasted) } }
                finishing = false
                outcome.onSuccess {
                    pasted = ""
                    pending?.close()
                    pending = null
                    MangaNav.message = "Signed in to ${tracker.name}."
                    onDone()
                }.onFailure { problem = it.message ?: "${tracker.name} did not accept the sign-in." }
            }
        }, primary = false, enabled = pasted.isNotBlank() && !finishing)
    }
}

// ---------------------------------------------------------------------------- one series

/**
 * A series' entries on the trackers the profile is signed in to: linking it to the right entry,
 * its status, score and chapters read there, and unlinking. Everything is the manga server's; it
 * also reports progress by itself while chapters are read.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrackingDialog(seriesId: Long, onDismiss: () -> Unit) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val list by rememberTrackers()
    val revision by manga.services.trackers.revision.collectAsState()
    val series by rememberMangaQuery(seriesId, initial = null as Series?) { services, _ -> services.repository.series(seriesId) }
    val entries by produceState(emptyList<TrackEntry>(), seriesId, revision) {
        value = withContext(Dispatchers.IO) { runCatching { manga.services.trackers.entries(seriesId) }.getOrDefault(emptyList()) }
    }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun run(done: String? = null, action: () -> Unit) {
        if (busy) return
        busy = true
        problem = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching(action) }
            busy = false
            outcome.onSuccess { if (done != null) MangaNav.message = done }.onFailure { problem = it.message ?: "That did not work." }
        }
    }

    MangaDialogFrame("Track this series", onDismiss, width = 620) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val signedIn = list.trackers.filter { it.signedIn }
            Text(
                "Linked to an entry on a tracker, the series is kept up to date there by the manga server as you read.",
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
            )
            list.problem?.let { Notice(it, PillTone.DANGER) }
            if (list.loaded && signedIn.isEmpty()) {
                Text("This profile is not signed in to any tracker yet.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
                MangaButton("Sign in to a tracker…", { onDismiss(); openMangaPlugins(MangaPluginsTab.TRACKERS) }, primary = false, icon = Icons.Rounded.Sync)
            }
            signedIn.forEach { tracker ->
                val entry = entries.firstOrNull { it.trackerId == tracker.id }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ServerIcon(tracker.iconPath, Modifier.size(26.dp), RoundedCornerShape(7.dp))
                        Text(tracker.name, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
                        if (tracker.signInExpired) StatusPill("Sign-in ran out", PillTone.WARNING)
                    }
                    if (entry == null) {
                        TrackerSearch(tracker, series?.title.orEmpty(), enabled = !busy) { candidate ->
                            run("Linked to \"${candidate.title}\" on ${tracker.name}.") { manga.services.trackers.bind(seriesId, tracker.id, candidate.remoteId) }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (entry.url.isNotBlank()) MangaButton("Open entry", { openInBrowser(entry.url) }, primary = false, icon = Icons.Rounded.OpenInNew)
                        }
                        Text(
                            "Chapters read there: ${trimChapter(entry.lastChapterRead)}" + (if (entry.totalChapters > 0) " of ${entry.totalChapters}" else "") +
                                (if (entry.score.isNotBlank() && entry.score != "-") "  ·  your score: ${entry.score}" else ""),
                            style = small(), color = tokens.colors.textMuted,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            tracker.statuses.forEach { status ->
                                MangaChip(status.label, status.value == entry.status) {
                                    if (status.value != entry.status) run { manga.services.trackers.update(entry.recordId, status = status.value) }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MangaButton("Unlink", {
                                run("Unlinked from ${tracker.name}. The entry there is unchanged.") { manga.services.trackers.unbind(entry.recordId, deleteRemote = false) }
                            }, primary = false, enabled = !busy)
                            if (tracker.canDeleteRemoteEntry) {
                                MangaButton("Unlink and remove it from my ${tracker.name} list", {
                                    run("Unlinked, and removed from your ${tracker.name} list.") { manga.services.trackers.unbind(entry.recordId, deleteRemote = true) }
                                }, primary = false, danger = true, enabled = !busy)
                            }
                        }
                    }
                }
            }
            problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            MangaButton("Send progress now", {
                run("Your progress was sent to the linked trackers.") { manga.services.trackers.sendProgress(seriesId) }
            }, primary = false, enabled = !busy && entries.isNotEmpty())
            Spacer(Modifier.width(10.dp))
            MangaButton("Done", onDismiss)
        }
    }
}

/** Looking a series up on one tracker, to choose the entry to link it to. */
@Composable
private fun TrackerSearch(tracker: Tracker, title: String, enabled: Boolean, onChoose: (TrackCandidate) -> Unit) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var query by remember(tracker.id, title) { mutableStateOf(title) }
    var results by remember(tracker.id) { mutableStateOf<List<TrackCandidate>?>(null) }
    var searching by remember(tracker.id) { mutableStateOf(false) }
    var problem by remember(tracker.id) { mutableStateOf<String?>(null) }

    fun search() {
        if (searching || query.isBlank()) return
        searching = true
        problem = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { manga.services.trackers.search(tracker.id, query) } }
            searching = false
            outcome.onSuccess { results = it }.onFailure { problem = it.message ?: "${tracker.name} could not be searched." }
        }
    }

    Text("Not linked to an entry on ${tracker.name}.", style = small(), color = tokens.colors.textMuted)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MangaTextField(query, { query = it }, "Title to look for on ${tracker.name}", Modifier.weight(1f), onSubmit = ::search)
        MangaButton(if (searching) "Searching…" else "Find", ::search, primary = false, icon = Icons.Rounded.Search, enabled = !searching && query.isNotBlank())
    }
    problem?.let { Text(it, style = small(), color = tokens.colors.danger) }
    results?.let { found ->
        if (found.isEmpty()) Text("${tracker.name} found nothing for that title.", style = small(), color = tokens.colors.textMuted)
        found.take(8).forEach { candidate ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                UrlCover(candidate.coverUrl.takeIf { it.isNotBlank() }, Modifier.width(34.dp).heightIn(min = 48.dp, max = 48.dp), RoundedCornerShape(6.dp), width = 120)
                Column(Modifier.weight(1f)) {
                    Text(candidate.title, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            candidate.type.takeIf { it.isNotBlank() }, candidate.publishingStatus.takeIf { it.isNotBlank() }, candidate.started.takeIf { it.isNotBlank() },
                            candidate.totalChapters.takeIf { it > 0 }?.let { plural(it, "chapter") },
                        ).joinToString("  ·  "),
                        style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                MangaButton("Link", { onChoose(candidate) }, primary = false, enabled = enabled)
            }
        }
    }
}

private fun trimChapter(number: Double): String = if (number == Math.floor(number)) number.toLong().toString() else number.toString()
