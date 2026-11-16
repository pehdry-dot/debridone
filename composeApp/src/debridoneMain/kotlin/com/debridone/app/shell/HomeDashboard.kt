package com.debridone.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.DebridOneRuntime
import com.debridone.module.ModuleId
import com.debridone.module.ModuleReadiness
import com.debridone.module.ModuleStatus
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.ProfileRepository

private val moduleBlurb = mapOf(
    ShellSection.STREAMING to "Movies and series through your own addons.",
    ShellSection.MUSIC to "Your music library, playlists and player.",
    ShellSection.GAMES to "Your game library, launcher and playtime.",
    ShellSection.MANGA to "Manga and manhwa with compatible extensions.",
)

/**
 * Launch dashboard. It only links to the four sections and shows the current profile; it never
 * lists, searches or mixes content from the modules.
 */
@Composable
internal fun HomeDashboard() {
    val tokens = MaterialTheme.nuvio
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val storageNotice by DebridOneRuntime.storageNotice.collectAsStateWithLifecycle()
    val name = profileState.activeProfile?.name

    ShellPage(
        title = if (name.isNullOrBlank()) "Welcome to DebridOne" else "Welcome back, $name",
        subtitle = "Four separate sections, one app. Pick where to go.",
    ) {
        storageNotice?.let { Notice(it) }

        ShellSection.modules.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEach { section ->
                    ModuleCard(section, Modifier.weight(1f))
                }
            }
        }

        SectionTitle("Manage")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                ShellSection.PLUGINS to "Each section has its own plugins.",
                ShellSection.DOWNLOADS to "Downloads, per section.",
                ShellSection.SETTINGS to "Account, profiles and appearance.",
            ).forEach { (section, caption) ->
                ShellCard(Modifier.weight(1f), onClick = { ShellState.select(section) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(section.icon, contentDescription = null, tint = tokens.colors.textSecondary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(section.label, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
                    }
                    Text(caption, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                }
            }
        }
    }
}

@Composable
private fun ModuleCard(section: ShellSection, modifier: Modifier) {
    val tokens = MaterialTheme.nuvio
    val moduleId = section.module
    val engine = moduleId?.takeIf { it != ModuleId.STREAMING }?.let { DebridOneRuntime.modules.module(it) }
    val status = engine?.status?.collectAsStateWithLifecycle()?.value

    ShellCard(modifier, onClick = { ShellState.select(section) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(section.icon, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Text(section.label, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
            when {
                engine == null -> StatusPill("Ready", PillTone.SUCCESS)
                status is ModuleStatus.Failed -> StatusPill("Needs attention", PillTone.DANGER)
                engine.readiness is ModuleReadiness.FoundationOnly -> StatusPill("In development", PillTone.WARNING)
                else -> StatusPill("Ready", PillTone.SUCCESS)
            }
        }
        Text(moduleBlurb.getValue(section), style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary)
    }
}
