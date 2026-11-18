package com.nuvio.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.SideDrawer
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.SettingsPage
import com.nuvio.app.features.settings.SettingsScreen

/**
 * DebridOne: Streaming's settings as a panel over the tab that is showing, in place of a Settings
 * tab. These are Nuvio's own settings pages, unchanged; only where they are shown differs. The tab
 * underneath keeps its scroll position, its search and its selection, and the navigation bar above
 * stays in view.
 *
 * Nuvio's settings lay themselves out in two columns from 960 points; the panel is that wide when
 * the window allows, and fills the area when it does not (the pages then use their one-column
 * layout). In both layouts every page opens inside the panel.
 */
@Composable
internal fun StreamingSettingsOverlay(requests: AppTabRequests, state: AppTabState, actions: AppTabActions, topInset: Dp) {
    val tokens = MaterialTheme.nuvio
    val close = { AppShellHost.settingsOverlayOpen = false }
    // A page asked for from inside the panel (the one-column layout's own links).
    var asked by remember { mutableStateOf<String?>(null) }

    SideDrawer(
        open = AppShellHost.settingsOverlayOpen,
        onClose = close,
        title = "Streaming settings",
        modifier = Modifier.padding(top = topInset),
        width = 1120.dp,
        overlayBelow = 1160.dp,
        maxFraction = 0.94f,
        background = tokens.colors.background,
    ) {
        Column(Modifier.fillMaxSize().border(1.dp, tokens.colors.borderSubtle)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Streaming settings", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
                    Text("Changes are saved as you make them", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                }
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(tokens.colors.surface)
                        .clickable(role = Role.Button, onClickLabel = "Close settings (Escape)", onClick = close),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, contentDescription = "Close settings (Escape)", tint = tokens.colors.textPrimary, modifier = Modifier.size(20.dp)) }
            }
            HorizontalDivider(color = tokens.colors.borderSubtle)
            SettingsScreen(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                // The panel already sits below the navigation bar: the pages need no room for it.
                topChromePadding = 14.dp,
                rootActionRequests = requests.settingsRootActionRequests,
                requestedPageName = asked ?: state.requestedSettingsPageName,
                onRequestedPageConsumed = {
                    if (asked != null) asked = null else actions.onRequestedSettingsPageConsumed()
                },
                rootActionsEnabled = AppShellHost.settingsOverlayOpen,
                isSelectedTab = true,
                // No outside navigator: pages are switched inside the panel.
                onNavigatePage = null,
                onSwitchProfile = actions.onSwitchProfile?.let { switch -> { close(); switch() } },
                onHomescreenClick = { asked = SettingsPage.Homescreen.name },
                onMetaScreenClick = { asked = SettingsPage.MetaScreen.name },
                onContinueWatchingClick = { asked = SettingsPage.ContinueWatching.name },
                onAddonsClick = { asked = SettingsPage.Addons.name },
                onPluginsClick = { asked = SettingsPage.Plugins.name },
                onAccountClick = { asked = SettingsPage.Account.name },
                onSupportersContributorsClick = { asked = SettingsPage.SupportersContributors.name },
                onLicensesAttributionsClick = { asked = SettingsPage.LicensesAttributions.name },
                onCheckForUpdatesClick = actions.onCheckForUpdatesClick,
                onTestUpdateBannerClick = actions.onTestUpdateBannerClick,
                // Collections and Watch Party are features with screens of their own, not settings:
                // the panel closes so that screen is seen.
                onCollectionsClick = { close(); actions.onCollectionsSettingsClick() },
                onWatchPartyClick = { close(); actions.onWatchPartySettingsClick() },
            )
        }
    }
    // An addon's "Configure", clicked on the Addons page of these settings, opens over them.
    com.nuvio.app.features.addons.AddonConfigurationPanel(Modifier.padding(top = topInset))
}
