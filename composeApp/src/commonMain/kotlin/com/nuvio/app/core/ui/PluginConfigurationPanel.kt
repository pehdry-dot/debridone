package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** True when the person asked for less motion: panels then appear in place instead of sliding. */
val LocalReduceMotion = compositionLocalOf { false }

/**
 * The configuration of one plugin (an addon, a source, an extension, a metadata provider), opened
 * inside the tab or panel it was asked for in, instead of somewhere else. The list it was opened
 * from stays underneath, scrolled where it was and with the same plugin still in view.
 *
 * What is configured differs per kind of plugin and is supplied as [content] (Manga draws a
 * source's settings from what its extension defines, Music a source's sign-in fields from what
 * its adapter asks for, Streaming an addon's own configuration page). The panel around it
 * (the plugin's name, what kind of thing it is, Close, Escape) is the same everywhere.
 */
@Composable
fun PluginConfigurationPanel(
    open: Boolean,
    title: String,
    /** "Manga source", "Music server", "Streaming addon". */
    kind: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SideDrawer(open, onClose, title, modifier, width = 620.dp, background = tokens.colors.background, reduceMotion = LocalReduceMotion.current) {
        Column(Modifier.fillMaxSize().border(1.dp, tokens.colors.borderSubtle)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary, maxLines = 1)
                    Text(kind, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                }
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(tokens.colors.surface)
                        .clickable(role = Role.Button, onClickLabel = "Close (Escape)", onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, contentDescription = "Close (Escape)", tint = tokens.colors.textPrimary, modifier = Modifier.size(20.dp)) }
            }
            HorizontalDivider(color = tokens.colors.borderSubtle)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) { content() }
        }
    }
}
