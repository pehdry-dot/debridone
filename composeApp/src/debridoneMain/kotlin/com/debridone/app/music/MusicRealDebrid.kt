package com.debridone.app.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debridone.app.DebridOneRuntime
import com.debridone.music.sources.RealDebridAdapter
import com.debridone.music.sources.RealDebridSignIn
import com.debridone.music.sources.SourceConnection
import com.nuvio.app.core.ui.nuvio
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where Real-Debrid's API is. Always the real service, except when a developer points DebridOne
 * at a test server on this same computer with `DEBRIDONE_REAL_DEBRID_API` (see TESTING.md); any
 * other address in that variable is ignored, and the dialog says when a test server is in use.
 */
internal object RealDebridEndpoint {
    private val testServer: String? = System.getenv("DEBRIDONE_REAL_DEBRID_API")?.trim()?.trimEnd('/')
        ?.takeIf { Regex("^http://(127\\.0\\.0\\.1|localhost):\\d{1,5}$").matches(it) }

    val baseUrl: String get() = testServer ?: RealDebridAdapter.DEFAULT_BASE_URL
    val isTestServer: Boolean get() = testServer != null
}

/**
 * Signing in to the user's own Real-Debrid account. The usual way is a code: Real-Debrid shows a
 * page where the user types it and allows DebridOne, and this dialog notices. The password never
 * passes through DebridOne. The other way, for those who prefer it, is the account's API token.
 */
@Composable
internal fun RealDebridSignInDialog(onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val sources = music.services.sources
    val scope = rememberCoroutineScope()

    var attempt by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<RealDebridSignIn?>(null) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var expired by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var withToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    fun connected(connection: SourceConnection) {
        onDismiss()
        MusicNav.message = "Connected to Real-Debrid as ${connection.username}. Reading your cloud…"
        DebridOneRuntime.modules.music.syncSource(connection.id) { synced ->
            MusicNav.message = synced.fold(
                { if (it.total == 0) "Real-Debrid: no audio files found in your cloud yet." else "Real-Debrid: ${plural(it.total, "song")} added to your library" },
                { "Real-Debrid: ${it.message}" },
            )
        }
    }

    // One code at a time: ask for it, then look every few seconds whether it was allowed.
    LaunchedEffect(attempt, withToken) {
        if (withToken) return@LaunchedEffect
        pending = null
        error = null
        expired = false
        copied = false
        val started = withContext(Dispatchers.IO) { runCatching { sources.beginRealDebridSignIn(RealDebridEndpoint.baseUrl) } }
        val signIn = started.getOrElse {
            error = it.message ?: "Real-Debrid could not be reached."
            return@LaunchedEffect
        }
        pending = signIn
        secondsLeft = signIn.expiresInSeconds
        var sinceLastLook = 0
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
            if (++sinceLastLook < signIn.intervalSeconds) continue
            sinceLastLook = 0
            val looked = withContext(Dispatchers.IO) { runCatching { sources.finishRealDebridSignIn(music.profileId, signIn) } }
            looked.exceptionOrNull()?.let {
                error = it.message ?: "The sign-in could not be completed."
                pending = null
                return@LaunchedEffect
            }
            looked.getOrNull()?.let { return@LaunchedEffect connected(it) }
        }
        pending = null
        expired = true
    }

    MusicDialogFrame("Connect Real-Debrid", onDismiss = { if (!working) onDismiss() }, width = 540) {
        Text(
            "DebridOne lists the audio files that are already in your own Real-Debrid cloud (finished torrents and links you generated), " +
                "plays them and lets you save them. It never adds, deletes or searches anything there, and addons have no access to it.",
            style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
        )
        if (RealDebridEndpoint.isTestServer) {
            Text("Test server in use: ${RealDebridEndpoint.baseUrl} (DEBRIDONE_REAL_DEBRID_API). This is not Real-Debrid.", style = small(), color = tokens.colors.danger)
        }

        if (!withToken) {
            val signIn = pending
            when {
                signIn != null -> {
                    Text("1.  Open Real-Debrid's page and sign in there if asked.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        MusicButton("Open ${signIn.verificationUrl.removePrefix("https://")}", { openPage(signIn.verificationUrl) }, icon = Icons.Rounded.OpenInNew)
                    }
                    Text("2.  Type this code and allow the app.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer {
                            Text(
                                signIn.userCode,
                                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp),
                                color = tokens.colors.textPrimary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(tokens.colors.surface)
                                    .border(1.dp, tokens.colors.borderDefault, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                            )
                        }
                        MusicButton(if (copied) "Copied" else "Copy", { copied = copy(signIn.userCode) }, icon = Icons.Rounded.ContentCopy, primary = false)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = music.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text(
                            "Waiting for you to allow it on Real-Debrid. This window closes by itself. The code is good for ${minutes(secondsLeft)}.",
                            style = small(), color = tokens.colors.textSecondary,
                        )
                    }
                }
                expired -> Text("The code expired before it was allowed.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger)
                error != null -> Text(error!!, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger)
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = music.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Text("Asking Real-Debrid for a code…", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                }
            }
            Text(
                "Your Real-Debrid password is never typed into DebridOne. What Real-Debrid hands over lets DebridOne read your cloud and nothing more; " +
                    "it is kept in the ${sources.credentialStoreName} for this profile only, and you can withdraw it on Real-Debrid at any time.",
                style = small(), color = tokens.colors.textMuted,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                MusicButton("Use an API token instead", { withToken = true; error = null }, primary = false)
                Box(Modifier.weight(1f))
                if (signIn == null && (expired || error != null)) {
                    MusicButton("Get a new code", { attempt++ })
                    Box(Modifier.size(10.dp))
                }
                MusicButton("Cancel", onDismiss, primary = false)
            }
        } else {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            val submit = submit@{
                if (working || token.isBlank()) return@submit
                working = true
                error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { sources.addRealDebridWithToken(music.profileId, token, RealDebridEndpoint.baseUrl) } }
                    working = false
                    result.fold({ token = ""; connected(it) }, { error = it.message ?: "The connection could not be made." })
                }
            }
            DialogTextField(token, { token = it; error = null }, "API token (from your Real-Debrid account page)", focusRequester = focus, onSubmit = submit, secret = true)
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger) }
            Text(
                "A private API token opens everything in your account, which is more than DebridOne needs or uses; Real-Debrid itself advises the code sign-in for apps. " +
                    "The token is checked now and kept in the ${sources.credentialStoreName} for this profile only, never in the database.",
                style = small(), color = tokens.colors.textMuted,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                MusicButton("Sign in with a code instead", { withToken = false; token = ""; error = null }, primary = false, enabled = !working)
                Box(Modifier.weight(1f))
                if (working) {
                    CircularProgressIndicator(color = music.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Box(Modifier.size(10.dp))
                }
                MusicButton("Cancel", onDismiss, primary = false, enabled = !working)
                Box(Modifier.size(10.dp))
                MusicButton(if (working) "Checking…" else "Connect", submit, enabled = !working && token.isNotBlank())
            }
        }
    }
}

private fun minutes(seconds: Int): String = if (seconds >= 120) "${seconds / 60} more minutes" else if (seconds >= 60) "one more minute" else "$seconds more seconds"

/** Opens Real-Debrid's own page in the user's browser. Only https pages are ever opened. */
private fun openPage(url: String) {
    if (!url.startsWith("https://")) return
    runCatching {
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }
    }
}

private fun copy(text: String): Boolean = runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }.isSuccess
