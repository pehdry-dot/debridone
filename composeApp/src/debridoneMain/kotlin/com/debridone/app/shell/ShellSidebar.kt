package com.debridone.app.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.ActiveProfileMiniAvatar
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.ProfileRepository

internal val ShellSection.icon: ImageVector
    get() = when (this) {
        ShellSection.HOME -> Icons.Rounded.Home
        ShellSection.STREAMING -> Icons.Rounded.Movie
        ShellSection.MUSIC -> Icons.Rounded.MusicNote
        ShellSection.MANGA -> Icons.AutoMirrored.Rounded.MenuBook
        ShellSection.PLUGINS -> Icons.Rounded.Extension
        ShellSection.DOWNLOADS -> Icons.Rounded.Download
        ShellSection.SETTINGS -> Icons.Rounded.Settings
    }

private val ItemHeight = 52.dp
private val IconSlot = 38.dp

/** The single application sidebar, styled after Nuvio's desktop sidebar. */
@Composable
internal fun ShellSidebar(
    selected: ShellSection,
    expanded: Boolean,
    width: Dp,
    hoverSource: MutableInteractionSource,
    onSelect: (ShellSection) -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val reduceMotion by ShellState.reduceMotion.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .hoverable(hoverSource)
            .zIndex(NuvioTokens.Z.navigation),
        color = tokens.colors.background,
        contentColor = tokens.colors.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topInset + 14.dp, bottom = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SidebarRow(
                label = profileState.activeProfile?.name ?: "Profile",
                description = "Switch profile",
                selected = false,
                expanded = expanded,
                reduceMotion = reduceMotion,
                onClick = onProfileClick,
            ) {
                ActiveProfileMiniAvatar(
                    profile = profileState.activeProfile,
                    avatars = avatars,
                    selected = false,
                    size = 30,
                )
            }
            Spacer(Modifier.height(14.dp))

            val primary = listOf(ShellSection.HOME) + ShellSection.modules
            val secondary = listOf(ShellSection.PLUGINS, ShellSection.DOWNLOADS, ShellSection.SETTINGS)
            primary.forEach { section -> SectionRow(section, selected == section, expanded, reduceMotion, onSelect) }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .padding(horizontal = 18.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(tokens.colors.borderSubtle),
            )
            Spacer(Modifier.height(10.dp))
            secondary.forEach { section -> SectionRow(section, selected == section, expanded, reduceMotion, onSelect) }
        }
    }
}

@Composable
private fun SectionRow(
    section: ShellSection,
    selected: Boolean,
    expanded: Boolean,
    reduceMotion: Boolean,
    onSelect: (ShellSection) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SidebarRow(
        label = section.label,
        description = section.label,
        selected = selected,
        expanded = expanded,
        reduceMotion = reduceMotion,
        onClick = { onSelect(section) },
    ) {
        Icon(
            imageVector = section.icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (selected) tokens.colors.onAccent else tokens.colors.textMuted,
        )
    }
}

@Composable
private fun SidebarRow(
    label: String,
    description: String,
    selected: Boolean,
    expanded: Boolean,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ItemHeight)
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .semantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = description
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(start = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(IconSlot),
            color = if (selected) tokens.colors.accent else Color.Transparent,
            shape = RoundedCornerShape(14.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { leading() }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduceMotion) fadeIn(androidx.compose.animation.core.snap()) else fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
            exit = if (reduceMotion) fadeOut(androidx.compose.animation.core.snap()) else shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
