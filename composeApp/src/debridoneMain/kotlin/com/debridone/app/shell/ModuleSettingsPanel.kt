package com.debridone.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.SideDrawer
import com.nuvio.app.core.ui.nuvio

/** One page of a section's settings. */
internal class SettingsCategory(
    val id: String,
    val label: String,
    val icon: ImageVector,
    /** How what is on the page is kept, said under the panel's title. */
    val saving: String = "changes are saved as you make them",
)

/**
 * The settings of one section (Music, Manga), shown inside that section: a panel on the right with
 * the section's categories down its side and the chosen category's settings beside them. The
 * section's page stays where it was underneath, and its navigation stays in view above.
 *
 * Every section uses this same panel, so settings look and behave alike everywhere: the same
 * Close button and Escape, the chosen category marked, each category remembering where it was
 * scrolled to. Settings in it take effect as they are changed; anything that goes wrong is said
 * where it happened.
 */
@Composable
internal fun ModuleSettingsPanel(
    open: Boolean,
    title: String,
    categories: List<SettingsCategory>,
    selected: String,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    width: Dp = 860.dp,
    /** Categories that lay themselves out (a list that scrolls by itself); the others are scrolled by the panel. */
    selfScrolling: Set<String> = emptySet(),
    content: @Composable (categoryId: String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    // Kept while the section is on screen: each category comes back scrolled to where it was left.
    val pages = rememberSaveableStateHolder()
    val current = categories.firstOrNull { it.id == selected } ?: categories.first()

    SideDrawer(open, onClose, title, modifier, width = width, background = tokens.colors.background) { compact ->
        Column(Modifier.fillMaxSize().border(1.dp, tokens.colors.borderSubtle)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
                    Text(
                        "${current.label} · ${current.saving}",
                        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted,
                    )
                }
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(tokens.colors.surface)
                        .clickable(role = Role.Button, onClickLabel = "Close settings (Escape)", onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, contentDescription = "Close settings (Escape)", tint = tokens.colors.textPrimary, modifier = Modifier.size(20.dp)) }
            }
            HorizontalDivider(color = tokens.colors.borderSubtle)
            if (compact) {
                // A narrow window: the categories become a strip across the top.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { categories.forEach { category -> CategoryItem(category, category.id == current.id, accent, compact = true) { onSelect(category.id) } } }
                HorizontalDivider(color = tokens.colors.borderSubtle)
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (!compact) {
                    Column(
                        Modifier.width(200.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) { categories.forEach { category -> CategoryItem(category, category.id == current.id, accent, compact = false) { onSelect(category.id) } } }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(tokens.colors.borderSubtle))
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    pages.SaveableStateProvider(current.id) {
                        if (current.id in selfScrolling) {
                            content(current.id)
                        } else {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 40.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) { content(current.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryItem(category: SettingsCategory, selected: Boolean, accent: Color, compact: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val colour = if (selected) tokens.colors.textPrimary else tokens.colors.textSecondary
    Row(
        modifier = (if (compact) Modifier else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(if (compact) 50 else 12))
            .background(if (selected) accent.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(role = Role.Tab, onClickLabel = category.label, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(category.icon, contentDescription = null, tint = if (selected) accent else colour, modifier = Modifier.size(18.dp))
        Text(category.label, style = MaterialTheme.typography.labelLarge, color = colour, maxLines = 1)
    }
}
