package com.debridone.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio

/** Scrollable page frame shared by the shell's own screens. */
@Composable
internal fun ShellPage(
    title: String,
    subtitle: String? = null,
    maxWidth: Dp = 1080.dp,
    headerTrailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 40.dp, end = 40.dp, top = topInset + 36.dp, bottom = 48.dp)
                .widthIn(max = maxWidth),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineLarge, color = tokens.colors.textPrimary)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary)
                    }
                }
                headerTrailing?.invoke()
            }
            content()
        }
    }
}

@Composable
internal fun ShellCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
internal fun SectionTitle(text: String, caption: String? = null) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        }
    }
}

internal enum class PillTone { NEUTRAL, ACCENT, SUCCESS, WARNING, DANGER }

@Composable
internal fun StatusPill(text: String, tone: PillTone = PillTone.NEUTRAL, accent: Color? = null) {
    val tokens = MaterialTheme.nuvio
    val colour = when (tone) {
        PillTone.NEUTRAL -> tokens.colors.textMuted
        PillTone.ACCENT -> accent ?: tokens.colors.accent
        PillTone.SUCCESS -> tokens.colors.success
        PillTone.WARNING -> tokens.colors.warning
        PillTone.DANGER -> tokens.colors.danger
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = colour,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colour.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A row of mutually exclusive tabs. Each tab is an independent view, never a filter. */
@Composable
internal fun <T> SegmentedTabs(
    items: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.colors.surface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { (value, label) ->
            val isSelected = value == selected
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (isSelected) tokens.colors.onAccent else tokens.colors.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) tokens.colors.accent else Color.Transparent)
                    .semantics {
                        role = Role.Tab
                        this.selected = isSelected
                    }
                    .clickable { onSelect(value) }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
internal fun BulletList(items: List<String>) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row {
                Text("•", color = tokens.colors.textMuted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(start = 10.dp))
                Text(item, color = tokens.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun Notice(text: String, tone: PillTone = PillTone.WARNING) {
    val tokens = MaterialTheme.nuvio
    val colour = when (tone) {
        PillTone.DANGER -> tokens.colors.danger
        PillTone.SUCCESS -> tokens.colors.success
        PillTone.WARNING -> tokens.colors.warning
        else -> tokens.colors.textSecondary
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.textPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colour.copy(alpha = 0.12f))
            .border(1.dp, colour.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(16.dp),
    )
}

@Composable
internal fun VerticalGap(height: Dp) {
    Spacer(Modifier.height(height))
}

internal fun parseHex(hex: String?): Color? {
    val value = hex?.removePrefix("#") ?: return null
    if (value.length != 6) return null
    return runCatching { Color(("FF$value").toLong(16)) }.getOrNull()
}
