package com.debridone.app.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.DebridOneRuntime
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.ShellCard
import com.debridone.app.shell.ShellPage
import com.debridone.app.shell.StatusPill
import com.debridone.cloud.AccountStatus
import com.debridone.cloud.CloudAccount
import com.debridone.cloud.CloudProvider
import com.debridone.cloud.CloudServices
import com.debridone.cloud.DeviceSignIn
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Connected Accounts: the user's own accounts at the debrid services. One card per
 * service; both can be connected at the same time, and each account is either this profile's or
 * shared with every profile.
 */
@Composable
internal fun ConnectedAccountsPage() {
    val tokens = MaterialTheme.nuvio
    val cloud = CloudApp.services
    val profileId by DebridOneRuntime.activeProfileId.collectAsStateWithLifecycle()

    ShellPage(
        title = "Connected Accounts",
        subtitle = "Debrid services. Connect your own Real-Debrid and TorBox accounts to browse, play and download the files that are already in them, " +
            "from the Downloads section and from Streaming, Music and Manga.",
    ) {
        if (cloud == null) {
            Notice("The connected accounts could not be opened on this computer: ${DebridOneRuntime.cloudError ?: "unknown reason"}", PillTone.DANGER)
            return@ShellPage
        }
        val accounts by cloud.accounts.accounts.collectAsStateWithLifecycle()
        val refreshing by cloud.files.refreshing.collectAsStateWithLifecycle()
        var connecting by remember { mutableStateOf<CloudProvider?>(null) }
        var disconnecting by remember { mutableStateOf<CloudAccount?>(null) }

        cloud.accounts.providers.forEach { provider ->
            ProviderCard(
                cloud, provider,
                accounts = accounts.filter { it.provider == provider.id && it.visibleTo(profileId) },
                refreshing = refreshing,
                profileId = profileId,
                onConnect = { connecting = provider },
                onDisconnect = { disconnecting = it },
            )
        }
        Text(
            (if (cloud.accounts.credentialStoreIsProtected) "Sign-ins and keys are kept in the ${cloud.accounts.credentialStoreName}, protected by the system."
            else "Sign-ins and keys are kept in an encrypted file, because this system has no keychain.") +
                " They are never written to DebridOne's databases or logs, and no plugin or addon of Streaming, Music or Manga can read them. " +
                "A subscription to these services is between you and them; DebridOne itself is free and has no paid features.",
            style = small(), color = tokens.colors.textMuted,
        )

        connecting?.let { provider -> ConnectDialog(cloud, provider, profileId, onDismiss = { connecting = null }) }
        disconnecting?.let { account ->
            CloudConfirmDialog(
                title = "Disconnect ${account.provider.displayName}?",
                message = "DebridOne forgets the sign-in of ${account.label} and what it listed from it, and stops its unfinished downloads. " +
                    "Nothing changes in your ${account.provider.displayName} account, and files you already downloaded stay on this computer.",
                confirm = "Disconnect", danger = true, onDismiss = { disconnecting = null },
            ) {
                CloudApp.background {
                    cloud.disconnect(profileId, account.id)
                    CloudApp.say("${account.provider.displayName} is disconnected.")
                }
            }
        }
    }
    CloudMessage()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    cloud: CloudServices,
    provider: CloudProvider,
    accounts: List<CloudAccount>,
    refreshing: Set<Long>,
    profileId: Long?,
    onConnect: () -> Unit,
    onDisconnect: (CloudAccount) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    ShellCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProviderMark(provider.id)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(provider.id.displayName, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
                    when {
                        accounts.isEmpty() -> StatusPill("Not connected")
                        accounts.any { it.status == AccountStatus.NEEDS_SIGN_IN } -> StatusPill("Sign in again", PillTone.DANGER)
                        accounts.any { it.status == AccountStatus.ERROR } -> StatusPill("Not answering", PillTone.WARNING)
                        else -> StatusPill("Connected", PillTone.SUCCESS)
                    }
                    if (provider.isTestServer) StatusPill("Test server", PillTone.DANGER)
                }
                Text(
                    when {
                        provider.isTestServer -> "Not the real service: a test server at ${provider.address} is in use."
                        accounts.isEmpty() -> "Shows what is in your ${provider.id.displayName} account: ${provider.kinds.joinToString(", ") { it.plural.lowercase() }}."
                        else -> provider.kinds.joinToString(" · ") { it.plural }
                    },
                    style = small(), color = if (provider.isTestServer) tokens.colors.danger else tokens.colors.textMuted,
                )
            }
            if (accounts.isEmpty()) CloudButton("Connect", onConnect, icon = Icons.Rounded.Link, primary = true)
        }

        accounts.forEach { account ->
            val busy = account.id in refreshing
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.background.copy(alpha = 0.5f)).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(14.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(account.label, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
                    account.plan?.let { StatusPill(it, if (account.premium) PillTone.ACCENT else PillTone.NEUTRAL) }
                    if (busy) CircularProgressIndicator(color = tokens.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (account.username != null && account.email != null) Fact("E-mail", account.email!!)
                    Fact("Subscription", if (account.premium) "Active" else if (account.plan != null) "Not active" else "Unknown")
                    Fact(if (account.premium) "Valid until" else "Expired on", formatDate(account.premiumUntil))
                    account.details.forEach { (label, value) -> Fact(label, value) }
                    Fact("Connected with", if (account.authKind == "code") "a code on ${account.provider.displayName}'s page" else provider.keyName)
                    Fact("Last read", formatDateTime(account.lastListedAt ?: account.lastRefreshAt))
                }
                if (account.status != AccountStatus.CONNECTED) {
                    Text(account.lastError ?: "The last attempt to reach ${account.provider.displayName} failed.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Available to", style = small(), color = tokens.colors.textMuted)
                    CloudChip("This profile only", selected = !account.shared) {
                        if (account.shared) CloudApp.background { cloud.accounts.setShared(profileId, account.id, false); CloudApp.say("${account.provider.displayName} is now this profile's only.") }
                    }
                    CloudChip("All profiles", selected = account.shared) {
                        if (!account.shared) CloudApp.background { cloud.accounts.setShared(profileId, account.id, true); CloudApp.say("${account.provider.displayName} is now shared with all profiles.") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (account.status == AccountStatus.NEEDS_SIGN_IN) CloudButton("Sign in again", onConnect, icon = Icons.Rounded.Link, primary = true)
                    CloudButton("Refresh", { CloudApp.refresh(profileId, account) }, icon = Icons.Rounded.Refresh, enabled = !busy)
                    CloudButton("Manage account", { openInBrowser(provider.id.manageUrl) }, icon = Icons.Rounded.OpenInNew)
                    Box(Modifier.weight(1f))
                    CloudButton("Disconnect", { onDisconnect(account) }, icon = Icons.Rounded.LinkOff, danger = true)
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = small(), color = tokens.colors.textMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
    }
}

/**
 * Connecting an account. Where the service offers it (Real-Debrid), the usual way is a code typed
 * on the service's own page, so the password never passes through DebridOne; the other way is the
 * key the service gives its users. Nothing is saved unless the service accepts the sign-in.
 */
@Composable
private fun ConnectDialog(cloud: CloudServices, provider: CloudProvider, profileId: Long?, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val name = provider.id.displayName
    var shared by remember { mutableStateOf(false) }
    var withKey by remember { mutableStateOf(!provider.supportsCodeSignIn) }
    var attempt by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<DeviceSignIn?>(null) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var expired by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    fun connected(account: CloudAccount) {
        onDismiss()
        CloudApp.say("Connected to $name as ${account.label}. Reading the account…")
        CloudApp.refresh(profileId, account, fresh = false)
    }

    // One code at a time: ask for it, then look every few seconds whether it was allowed.
    LaunchedEffect(attempt, withKey) {
        if (withKey) return@LaunchedEffect
        pending = null
        error = null
        expired = false
        copied = false
        val signIn = withContext(Dispatchers.IO) { runCatching { cloud.accounts.beginSignIn(provider.id) } }.getOrElse {
            error = it.message ?: "$name could not be reached."
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
            val looked = withContext(Dispatchers.IO) { runCatching { cloud.accounts.finishSignIn(signIn, profileId, shared) } }
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

    CloudDialog("Connect $name", onDismiss = { if (!working) onDismiss() }, width = 560) {
        Text(
            "DebridOne shows the files that are already in your $name account, plays them and downloads them. " +
                "It does not add or look for anything there, and it removes something only when you choose \"Delete from $name\" and confirm.",
            style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
        )
        if (provider.isTestServer) Text("Test server in use: ${provider.address}. This is not $name.", style = small(), color = tokens.colors.danger)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Available to", style = small(), color = tokens.colors.textMuted)
            CloudChip("This profile only", selected = !shared) { shared = false }
            CloudChip("All profiles", selected = shared) { shared = true }
        }

        if (!withKey) {
            val signIn = pending
            when {
                signIn != null -> {
                    Text("1.  Open $name's page and sign in there if asked.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
                    CloudButton("Open ${signIn.verificationUrl.removePrefix("https://")}", { openInBrowser(signIn.verificationUrl) }, icon = Icons.Rounded.OpenInNew, primary = true)
                    Text("2.  Type this code and allow the app.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer {
                            Text(
                                signIn.userCode,
                                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp),
                                color = tokens.colors.textPrimary,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderDefault, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
                            )
                        }
                        CloudButton(if (copied) "Copied" else "Copy", { copied = copyToClipboard(signIn.userCode) }, icon = Icons.Rounded.ContentCopy)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = tokens.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text("Waiting for you to allow it on $name. This window closes by itself. The code is good for ${minutes(secondsLeft)}.", style = small(), color = tokens.colors.textSecondary)
                    }
                }
                expired -> Text("The code expired before it was allowed.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger)
                error != null -> Text(error!!, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger)
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = tokens.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Text("Asking $name for a code…", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                }
            }
            Text(
                "Your $name password is never typed into DebridOne. What $name hands over is kept in the ${cloud.accounts.credentialStoreName}, and you can withdraw it on $name's website at any time.",
                style = small(), color = tokens.colors.textMuted,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CloudButton("Use an ${provider.keyName} instead", { withKey = true; error = null })
                Box(Modifier.weight(1f))
                if (signIn == null && (expired || error != null)) CloudButton("Get a new code", { attempt++ }, primary = true)
                CloudButton("Cancel", onDismiss)
            }
        } else {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            val submit = submit@{
                if (working || key.isBlank()) return@submit
                working = true
                error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { cloud.accounts.connectWithKey(provider.id, key, profileId, shared) } }
                    working = false
                    result.fold({ key = ""; connected(it) }, { error = it.message ?: "The connection could not be made." })
                }
            }
            CloudTextField(key, { key = it; error = null }, provider.keyName, focusRequester = focus, onSubmit = submit, secret = true)
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger) }
            Text(
                provider.keyHelp + " It is checked with $name now and saved only if $name accepts it, in the ${cloud.accounts.credentialStoreName}, never in a database or a log.",
                style = small(), color = tokens.colors.textMuted,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (provider.supportsCodeSignIn) CloudButton("Sign in with a code instead", { withKey = false; key = ""; error = null }, enabled = !working)
                Box(Modifier.weight(1f))
                if (working) CircularProgressIndicator(color = tokens.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                CloudButton("Cancel", onDismiss, enabled = !working)
                CloudButton(if (working) "Checking…" else "Connect", submit, primary = true, enabled = !working && key.isNotBlank())
            }
        }
    }
}

private fun minutes(seconds: Int): String = if (seconds >= 120) "${seconds / 60} more minutes" else if (seconds >= 60) "one more minute" else "$seconds more seconds"
