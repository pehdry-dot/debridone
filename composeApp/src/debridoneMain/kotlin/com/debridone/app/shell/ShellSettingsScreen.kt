package com.debridone.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.accounts.Account
import com.debridone.accounts.AccountException
import com.debridone.accounts.AccountsDatabase
import com.debridone.app.DebridOneRuntime
import com.debridone.module.AppearanceOption
import com.debridone.module.CommonAppearanceKeys
import com.debridone.module.ModuleAppearance
import com.debridone.module.ModuleId
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.LocalAccounts
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.labelRes
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.membership.availableAppThemes
import com.nuvio.app.features.profiles.ActiveProfileMiniAvatar
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.ThemeSettingsRepository
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** DebridOne's own settings: identity, appearance and storage. Each section keeps its own settings. */
@Composable
internal fun ShellSettingsScreen(onSwitchProfile: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val page by ShellState.settingsPage.collectAsStateWithLifecycle()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.width(220.dp).fillMaxHeight().padding(start = 28.dp, top = topInset + 40.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
            Spacer(Modifier.height(12.dp))
            SettingsPage.entries.forEach { entry ->
                val selected = entry == page
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) tokens.colors.surface else Color.Transparent)
                        .clickable(role = Role.Tab) { ShellState.selectSettingsPage(entry) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (page) {
                SettingsPage.ACCOUNT -> AccountPage()
                SettingsPage.PROFILES -> ProfilesPage(onSwitchProfile)
                SettingsPage.APPEARANCE -> AppearancePage()
                SettingsPage.STORAGE -> StoragePage()
                SettingsPage.ABOUT -> AboutPage()
            }
        }
    }
}

// ------------------------------------------------------------------ account

@Composable
private fun AccountPage() {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val auth by AuthRepository.state.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<Pair<String, PillTone>?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }

    val local = LocalAccounts.isActive
    val accounts = DebridOneRuntime.accounts
    val account: Account? = remember(auth, refresh) {
        (auth as? AuthState.Authenticated)?.takeIf { local }?.let { accounts.findAccount(it.userId) }
    }

    ShellPage(title = "Account", subtitle = "One account, several profiles.") {
        message?.let { (text, tone) -> Notice(text, tone) }

        if (!local) {
            Notice("This device is connected to a sync server. Account settings are in Streaming › Settings.", PillTone.NEUTRAL)
            return@ShellPage
        }
        if (account == null) {
            Notice("No account is signed in.", PillTone.NEUTRAL)
            return@ShellPage
        }

        ShellCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(account.displayName, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
                StatusPill(if (account.isGuest) "Guest" else "On this device")
            }
            if (account.login != null) {
                Text("Sign-in name: ${account.login}", style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary)
            }
            Text(
                if (account.isGuest) {
                    "A guest account has no password. Anyone using this Mac can open it. Add a sign-in name and password to protect it."
                } else {
                    "Your password is stored only as a salted Argon2id hash on this Mac. It cannot be recovered if you forget it."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }

        ShellCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Sync")
                StatusPill("Local only")
            }
            Text(
                "Nothing leaves this Mac. Accounts, profiles and libraries are stored locally and are not synchronised " +
                    "with other devices. Cross-device sync needs a sync server, which is not configured.",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (account.isGuest) {
                Button(onClick = { dialog = "upgrade" }) { Text("Add sign-in name and password") }
            } else {
                Button(onClick = { dialog = "password" }) { Text("Change password") }
            }
            OutlinedButton(onClick = { scope.launch { AuthRepository.signOut() } }) { Text("Sign out") }
            TextButton(onClick = { dialog = "delete" }) { Text("Delete account…", color = tokens.colors.danger) }
        }
        Text(
            "Signing out keeps everything. Deleting the account removes its profiles, libraries, history and settings in every section.",
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
    }

    when (dialog) {
        "password" -> CredentialsDialog(
            title = "Change password",
            fields = listOf("Current password" to true, "New password (at least ${AccountsDatabase.MIN_PASSWORD_LENGTH} characters)" to true),
            confirmLabel = "Change password",
            onDismiss = { dialog = null },
        ) { values ->
            accountCall { accounts.changePassword(account!!.id, values[0], values[1]) }?.also { return@CredentialsDialog it }
            message = "Password changed." to PillTone.SUCCESS
            dialog = null
            null
        }
        "upgrade" -> CredentialsDialog(
            title = "Protect this account",
            fields = listOf("Sign-in name or email" to false, "Password (at least ${AccountsDatabase.MIN_PASSWORD_LENGTH} characters)" to true),
            confirmLabel = "Save",
            onDismiss = { dialog = null },
        ) { values ->
            accountCall { accounts.upgradeGuest(account!!.id, values[0], values[1]) }?.also { return@CredentialsDialog it }
            AuthRepository.refreshLocalSession()
            refresh++
            message = "This account now needs its password to sign in." to PillTone.SUCCESS
            dialog = null
            null
        }
        "delete" -> CredentialsDialog(
            title = "Delete this account?",
            description = "This permanently removes the account, all of its profiles and everything they own in every section. It cannot be undone.",
            fields = if (account?.hasPassword == true) listOf("Password" to true) else emptyList(),
            confirmLabel = "Delete account",
            destructive = true,
            onDismiss = { dialog = null },
        ) { values ->
            val error = withContext(Dispatchers.Default) { DebridOneRuntime.deleteCurrentAccount(values.firstOrNull()) }
            if (error == null) dialog = null
            error
        }
    }
}

/** Runs an account operation off the UI thread; returns an error message or null. */
private suspend fun accountCall(block: () -> Unit): String? = withContext(Dispatchers.Default) {
    try {
        block()
        null
    } catch (e: AccountException) {
        e.message ?: "The operation failed."
    }
}

@Composable
private fun CredentialsDialog(
    title: String,
    fields: List<Pair<String, Boolean>>,
    confirmLabel: String,
    onDismiss: () -> Unit,
    description: String? = null,
    destructive: Boolean = false,
    onConfirm: suspend (List<String>) -> String?,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val values = remember { fields.map { mutableStateOf("") } }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                fields.forEachIndexed { index, (label, secret) ->
                    OutlinedTextField(
                        value = values[index].value,
                        onValueChange = { values[index].value = it },
                        label = { Text(label) },
                        singleLine = true,
                        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = tokens.colors.danger, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        error = onConfirm(values.map { it.value })
                        busy = false
                    }
                },
            ) { Text(confirmLabel, color = if (destructive) tokens.colors.danger else tokens.colors.accent) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

// ----------------------------------------------------------------- profiles

@Composable
private fun ProfilesPage(onSwitchProfile: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val local = LocalAccounts.isActive
    val accounts = DebridOneRuntime.accounts
    val accountId = remember(profileState, refresh) { if (local) accounts.currentSession()?.accountId else null }
    val stored = remember(profileState, refresh, accountId) { accountId?.let(accounts::listProfiles).orEmpty() }
    val defaultId = stored.firstOrNull { it.isDefault }?.id

    ShellPage(
        title = "Profiles",
        subtitle = "Each profile has its own libraries, history, progress, favourites and appearance in every section.",
        headerTrailing = { Button(onClick = onSwitchProfile) { Text("Switch or edit profiles") } },
    ) {
        ShellCard(Modifier.fillMaxWidth()) {
            profileState.profiles.forEach { profile ->
                val isActive = profile.profileIndex == profileState.activeProfile?.profileIndex
                val isDefault = defaultId == profile.profileIndex.toLong()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ActiveProfileMiniAvatar(profile = profile, avatars = avatars, selected = false, size = 40)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isActive) StatusPill("In use", PillTone.ACCENT)
                            if (isDefault) StatusPill("Opens automatically")
                            if (profile.pinEnabled) StatusPill("PIN")
                        }
                    }
                    if (local && accountId != null) {
                        Text("Open automatically", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                        Switch(
                            checked = isDefault,
                            onCheckedChange = { checked ->
                                runCatching {
                                    accounts.setDefaultProfile(accountId, if (checked) profile.profileIndex.toLong() else null)
                                }
                                refresh++
                            },
                        )
                    }
                }
            }
            if (profileState.profiles.isEmpty()) {
                Text("No profiles yet.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
            }
        }
        Text(
            "Create, rename, change pictures, set a PIN or delete profiles from the profile chooser. " +
                "An account can have up to ${AccountsDatabase.MAX_PROFILES_PER_ACCOUNT} profiles. " +
                "A profile with a PIN always asks for it, even when it opens automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
    }
}

// --------------------------------------------------------------- appearance

@Composable
private fun AppearancePage() {
    val scope = rememberCoroutineScope()
    val selectedModule by ShellState.appearanceModule.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<Pair<String, PillTone>?>(null) }
    val tabs: List<Pair<ModuleId?, String>> = listOf<Pair<ModuleId?, String>>(null to "Global") + ModuleId.active.map { it to it.displayName }

    ShellPage(
        title = "Appearance",
        subtitle = "One look for the app, with separate options for each section. Saved per profile.",
        headerTrailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val file = chooseFile(save = false) ?: return@launch
                        val text = withContext(Dispatchers.IO) { runCatching { Files.readString(file.toPath()) }.getOrNull() }
                        message = if (text == null) {
                            "That file could not be read." to PillTone.DANGER
                        } else {
                            AppearanceTransfer.import(text).fold(
                                onSuccess = { it to PillTone.SUCCESS },
                                onFailure = { (it.message ?: "That file could not be imported.") to PillTone.DANGER },
                            )
                        }
                    }
                }) { Text("Import…") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val file = chooseFile(save = true) ?: return@launch
                        val result = withContext(Dispatchers.IO) { runCatching { Files.writeString(file.toPath(), AppearanceTransfer.export()) } }
                        message = if (result.isSuccess) "Appearance exported to ${file.name}." to PillTone.SUCCESS else "Could not write ${file.name}." to PillTone.DANGER
                    }
                }) { Text("Export…") }
            }
        },
    ) {
        message?.let { (text, tone) -> Notice(text, tone) }
        SegmentedTabs(items = tabs, selected = selectedModule, onSelect = ShellState::selectAppearanceModule)
        when (val module = selectedModule) {
            null -> GlobalAppearance()
            ModuleId.STREAMING -> StreamingAppearance()
            else -> ModuleAppearanceEditor(module)
        }
    }
}

private suspend fun chooseFile(save: Boolean): File? = withContext(Dispatchers.IO) {
    val dialog = FileDialog(null as Frame?, if (save) "Export appearance" else "Import appearance", if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (save) dialog.file = "debridone-appearance.json"
    dialog.isVisible = true
    val name = dialog.file ?: return@withContext null
    File(dialog.directory, if (save && !name.endsWith(".json")) "$name.json" else name)
}

@Composable
private fun GlobalAppearance() {
    val tokens = MaterialTheme.nuvio
    val theme by ThemeSettingsRepository.selectedTheme.collectAsStateWithLifecycle()
    val amoled by ThemeSettingsRepository.amoledEnabled.collectAsStateWithLifecycle()
    val access by MemberAccessRepository.access.collectAsStateWithLifecycle()
    val sidebarStyle by ShellState.sidebarStyle.collectAsStateWithLifecycle()
    val reduceMotion by ShellState.reduceMotion.collectAsStateWithLifecycle()
    val themes = remember(access) { availableAppThemes(access.entitlements).filter { it != AppTheme.CUSTOM } }

    ShellCard(Modifier.fillMaxWidth()) {
        SectionTitle("Theme", "Every theme is included. Custom colours are in Streaming › Settings › Appearance.")
        themes.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option -> ChoiceChip(stringResource(option.labelRes), option == theme) { ThemeSettingsRepository.setTheme(option) } }
            }
        }
        ToggleRow("True black background", "Uses pure black for the darkest surfaces.", amoled) { ThemeSettingsRepository.setAmoled(it) }
    }
    ShellCard(Modifier.fillMaxWidth()) {
        SectionTitle("Sidebar")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SidebarStyle.entries.forEach { style -> ChoiceChip(style.label, style == sidebarStyle) { ShellState.setSidebarStyle(style) } }
        }
    }
    ShellCard(Modifier.fillMaxWidth()) {
        SectionTitle("Accessibility")
        ToggleRow("Reduce motion", "Turns off the sidebar's expand and collapse animation.", reduceMotion) { ShellState.setReduceMotion(it) }
        Text(
            "DebridOne follows macOS for text size and contrast.",
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
    }
}

@Composable
private fun StreamingAppearance() {
    val tokens = MaterialTheme.nuvio
    ShellCard(Modifier.fillMaxWidth()) {
        SectionTitle("Streaming")
        Text(
            "Streaming keeps its own layout options: poster size and style, home rows, hero artwork, navigation bar and " +
                "metadata density. They only affect Streaming.",
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
        )
        Text("Open Streaming, then Settings › Appearance and Layout.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        Button(onClick = { ShellState.select(ShellSection.STREAMING) }) { Text("Open Streaming") }
    }
}

@Composable
private fun ModuleAppearanceEditor(moduleId: ModuleId) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val module = DebridOneRuntime.modules.module(moduleId) ?: return
    val store = module.appearance
    val appearance by store.current.collectAsStateWithLifecycle()
    val name = moduleId.displayName

    ShellCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SectionTitle("Preview", "Sample tiles showing the accent colour and card size. Open $name to see every option applied.") }
            TextButton(onClick = { scope.launch { store.resetToDefaults() } }) { Text("Reset $name") }
        }
        AppearancePreview(appearance)
    }

    ShellCard(Modifier.fillMaxWidth()) {
        SectionTitle("$name options", "These apply to $name only and are saved for the current profile.")
        store.options.forEach { option ->
            when (option) {
                is AppearanceOption.Choice -> {
                    Text(option.label, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val current = appearance.choice(option)
                        option.choices.forEach { choice ->
                            ChoiceChip(choice.label, choice.value == current) { scope.launch { store.set(option.key, choice.value) } }
                        }
                    }
                }
                is AppearanceOption.Toggle -> ToggleRow(option.label, option.description, appearance.toggle(option)) { checked ->
                    scope.launch { store.set(option.key, checked.toString()) }
                }
                is AppearanceOption.Colour -> ColourRow(option, appearance.colour(option)) { hex ->
                    scope.launch { store.set(option.key, hex) }
                }
            }
        }
    }
}

private val accentSwatches = listOf("#7C5CFF", "#1E88E5", "#00ACC1", "#43A047", "#FFB300", "#FB8C00", "#E53935", "#D81B60")

@Composable
private fun ColourRow(option: AppearanceOption.Colour, current: String?, onChange: (String?) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Text(option.label, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
    option.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ChoiceChip("Global", current == null) { onChange(null) }
        accentSwatches.forEach { hex ->
            val selected = current.equals(hex, ignoreCase = true)
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(50))
                    .background(parseHex(hex) ?: Color.Gray)
                    .border(2.dp, if (selected) tokens.colors.textPrimary else Color.Transparent, RoundedCornerShape(50))
                    .clickable(role = Role.RadioButton, onClickLabel = "Accent $hex") { onChange(hex) },
            )
        }
    }
}

@Composable
private fun AppearancePreview(appearance: ModuleAppearance) {
    val tokens = MaterialTheme.nuvio
    val accent = parseHex(appearance.accentHex) ?: tokens.colors.accent
    val tile = when (appearance.values[CommonAppearanceKeys.CARD_SIZE]) {
        "small" -> 84.dp
        "large" -> 148.dp
        else -> 112.dp
    }
    val gap = when (appearance.values[CommonAppearanceKeys.DENSITY]) {
        "compact" -> 8.dp
        "spacious" -> 24.dp
        else -> 14.dp
    }
    val list = appearance.values[CommonAppearanceKeys.LAYOUT] == "list"
    if (list) {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            repeat(3) { index ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PreviewTile(tile * 0.5f, selected = index == 0, accent = accent)
                    Box(Modifier.height(12.dp).width(220.dp).clip(RoundedCornerShape(6.dp)).background(tokens.colors.borderDefault))
                }
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            repeat(5) { index -> PreviewTile(tile, selected = index == 0, accent = accent) }
        }
    }
}

@Composable
private fun PreviewTile(width: androidx.compose.ui.unit.Dp, selected: Boolean, accent: Color) {
    val tokens = MaterialTheme.nuvio
    Box(
        Modifier
            .width(width)
            .height(width * 1.4f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.28f) else tokens.colors.textPrimary.copy(alpha = 0.07f))
            .border(2.dp, if (selected) accent else tokens.colors.borderSubtle, RoundedCornerShape(12.dp)),
    )
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) tokens.colors.onAccent else tokens.colors.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) tokens.colors.accent else tokens.colors.surfaceElevated)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun ToggleRow(label: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
            description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ------------------------------------------------------------------ storage

@Composable
private fun StoragePage() {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val paths = DebridOneRuntime.paths
    val notice by DebridOneRuntime.storageNotice.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<Pair<String, PillTone>?>(null) }
    val backups = remember(refresh) { runCatching { DebridOneRuntime.accounts.listBackups() }.getOrDefault(emptyList()) }
    val secrets = DebridOneRuntime.secrets

    ShellPage(title = "Storage & backups", subtitle = "Everything is stored on this Mac, with separate storage for each section.") {
        notice?.let { Notice(it) }
        message?.let { (text, tone) -> Notice(text, tone) }

        ShellCard(Modifier.fillMaxWidth()) {
            SectionTitle("Where your data lives")
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StorageLine("Accounts and profiles", paths.accountsDir.resolve("accounts.db").toString())
                    ModuleId.active.forEach { id -> StorageLine(id.displayName, paths.moduleDataDir(id.key).toString()) }
                    StorageLine("Backups", paths.backupsDir.toString())
                    StorageLine("Caches (safe to delete)", paths.cacheRoot.toString())
                }
            }
            OutlinedButton(onClick = {
                runCatching { Desktop.getDesktop().open(paths.dataRoot.toFile()) }
                    .onFailure { message = "Could not open the folder." to PillTone.DANGER }
            }) { Text("Open data folder") }
        }

        ShellCard(Modifier.fillMaxWidth()) {
            SectionTitle(
                "Backups",
                "Each database is snapshotted before any upgrade and about once a day. If a database is damaged, the newest healthy snapshot is restored automatically.",
            )
            Text(
                if (backups.isEmpty()) "No accounts backup yet." else "${backups.size} accounts backups, newest: ${backups.last().fileName}",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
            OutlinedButton(onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { DebridOneRuntime.accounts.backupNow() } }
                    message = result.fold(
                        onSuccess = { "Accounts backed up to ${it.fileName}." to PillTone.SUCCESS },
                        onFailure = { "Backup failed: ${it.message}" to PillTone.DANGER },
                    )
                    refresh++
                }
            }) { Text("Back up accounts now") }
        }

        ShellCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Credentials")
                StatusPill(secrets.backendName, if (secrets.isOsProtected) PillTone.SUCCESS else PillTone.WARNING)
            }
            Text(
                if (secrets.isOsProtected) {
                    "Plugin credentials and tokens are kept in your macOS Keychain, separately for each section and profile."
                } else {
                    "This is not a Mac, so credentials use an encrypted file in the data folder. That is a development fallback, not a secure store."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun StorageLine(label: String, value: String) {
    val tokens = MaterialTheme.nuvio
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary, modifier = Modifier.width(210.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
    }
}

// -------------------------------------------------------------------- about

@Composable
private fun AboutPage() {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    ShellPage(title = "About DebridOne", subtitle = "Version ${AppVersionConfig.DESKTOP_VERSION_NAME}. Free software: no subscriptions, tiers or paywalls.") {
        ShellCard(Modifier.fillMaxWidth()) {
            SectionTitle("Built on open source")
            BulletList(
                listOf(
                    "Streaming is Nuvio Desktop by Nuvio Media (GPL-3.0). DebridOne is a modified version and is distributed under the same licence.",
                    "Manga follows Houdoku and the Tiyo extension system (MIT). Its engine is being built; no extensions run yet.",
                    "Music is original DebridOne code. Eclipse Music is a feature reference only.",
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { uriHandler.openUri("https://github.com/NuvioMedia/NuvioDesktop") }) { Text("Nuvio Desktop") }
                OutlinedButton(onClick = { uriHandler.openUri("https://github.com/xgi/houdoku") }) { Text("Houdoku") }
                OutlinedButton(onClick = { uriHandler.openUri("https://github.com/xgi/tiyo") }) { Text("Tiyo") }
            }
        }
        ShellCard(Modifier.fillMaxWidth()) {
            SectionTitle("Your content, your sources")
            Text(
                "DebridOne ships with no addons, sources or extensions. What you install in each section is your choice and your responsibility. " +
                    "DebridOne does not bypass DRM, subscriptions or licensing.",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
        }
    }
}
