package com.debridone.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

/**
 * Landing screen of a module whose engine is not built yet. It states exactly what exists and what
 * does not; it shows no sample content.
 */
@Composable
internal fun ModuleScreen(moduleId: ModuleId) {
    val tokens = MaterialTheme.nuvio
    val module = DebridOneRuntime.modules.module(moduleId) ?: return
    val status by module.status.collectAsStateWithLifecycle()
    val appearance by module.appearance.current.collectAsStateWithLifecycle()
    val accent = parseHex(appearance.accentHex) ?: tokens.colors.accent
    val section = ShellSection.entries.first { it.module == moduleId }
    val readiness = module.readiness

    ShellPage(
        title = moduleId.displayName,
        subtitle = (readiness as? ModuleReadiness.FoundationOnly)?.summary,
        headerTrailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(section.icon, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                when (status) {
                    is ModuleStatus.Failed -> StatusPill("Needs attention", PillTone.DANGER)
                    ModuleStatus.Running -> StatusPill(
                        if (readiness is ModuleReadiness.Ready) "Ready" else "In development",
                        PillTone.ACCENT,
                        accent,
                    )
                    else -> StatusPill("Starting…")
                }
            }
        },
    ) {
        (status as? ModuleStatus.Failed)?.let { failed ->
            Notice(
                "${moduleId.displayName} could not open its storage: ${failed.message}\n" +
                    "The other sections are not affected.",
                PillTone.DANGER,
            )
        }

        if (readiness is ModuleReadiness.FoundationOnly) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
                ShellCard(Modifier.weight(1f)) {
                    SectionTitle("Available now")
                    BulletList(
                        listOf(
                            "Its own database, separate from every other section",
                            "Its own plugin registry (see Plugins › ${moduleId.displayName})",
                            "Its own metadata provider settings",
                            "Appearance settings saved per profile",
                        ),
                    )
                }
                ShellCard(Modifier.weight(1f)) {
                    SectionTitle("Not built yet")
                    BulletList(readiness.upcoming)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { ShellState.openPlugins(moduleId) }) { Text("${moduleId.displayName} plugins") }
            OutlinedButton(onClick = { ShellState.openSettings(SettingsPage.APPEARANCE, moduleId) }) {
                Text("${moduleId.displayName} appearance")
            }
        }
    }
}
