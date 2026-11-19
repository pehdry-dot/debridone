package com.debridone.app.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.DebridOneRuntime
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.ShellState
import com.debridone.app.shell.parseHex
import com.debridone.manga.MangaModule
import com.debridone.manga.MangaServices
import com.debridone.manga.library.Series
import com.debridone.manga.sources.SourceEntry
import com.debridone.manga.suwayomi.MangaConnection
import com.debridone.module.CommonAppearanceKeys
import com.debridone.module.ModuleStatus
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface MangaRoute {
    data object Home : MangaRoute
    data object Discover : MangaRoute
    data object Search : MangaRoute
    data object Library : MangaRoute
    data object Downloads : MangaRoute
    data class SeriesPage(val seriesId: Long) : MangaRoute
    /** [page] opens the chapter at that page (a bookmark, or the end when coming back from the next chapter) instead of where it was left. */
    data class Reader(val chapterId: Long, val page: Int? = null) : MangaRoute
}

/** Where the user is inside the Manga section. Kept outside composables so it survives leaving it. */
internal object MangaNav {
    val stack = mutableStateListOf<MangaRoute>(MangaRoute.Home)
    val current: MangaRoute get() = stack.last()
    var dialog by mutableStateOf<MangaDialog?>(null)

    /** A short confirmation or error shown at the bottom of the section. */
    var message by mutableStateOf<String?>(null)

    /** Manga's settings panel: open or not, and the page it shows. The page under it is not touched. */
    var settingsOpen by mutableStateOf(false)
    var settingsSection by mutableStateOf(MangaSettingsSections.APPEARANCE)

    /** Opens the settings, on [section] when given, else where they were last. */
    fun openSettings(section: String? = null) {
        if (section != null) settingsSection = section
        settingsOpen = true
    }

    /** Incremented to ask the search field to take keyboard focus. */
    var searchFocusRequests by mutableIntStateOf(0)
        private set

    fun open(route: MangaRoute) {
        if (current != route) stack += route
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Replaces the page on top: moving from one chapter to the next should not pile readers up. */
    fun replace(route: MangaRoute) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack += route
    }

    /** Jumps to a top-level page, forgetting the pages opened from the previous one. */
    fun root(route: MangaRoute) {
        stack.clear()
        stack += route
    }

    fun focusSearch() {
        if (current != MangaRoute.Search && current != MangaRoute.Discover) root(MangaRoute.Search)
        searchFocusRequests++
    }

    private var profileId: Long? = null

    /** Starts from Home when a different profile becomes active; a profile never sees another's page. */
    fun onProfile(id: Long) {
        if (profileId == id) return
        profileId = id
        root(MangaRoute.Home)
        dialog = null
        message = null
        settingsOpen = false
        SearchState.reset()
        DiscoverState.reset()
        LibraryState.reset()
        MangaCovers.clear()
    }
}

/** How the Manga section looks for the active profile (from its appearance settings). */
@Immutable
internal data class MangaStyle(
    val accent: Color,
    val onAccent: Color,
    /** Width of a cover card; its height follows the 2:3 shape of a book cover. */
    val coverWidth: Dp = 164.dp,
    val gridSpacing: Dp = 16.dp,
    /** `poster`, `overlay` or `cover`. */
    val cardStyle: String = "poster",
    val background: Color = Color.Black,
    val titleScale: Float = 1f,
    val compactChapters: Boolean = false,
    val reduceMotion: Boolean = false,
)

/** Everything a Manga screen needs: the engine's services, the profile and the look. */
@Immutable
internal class MangaContext(val module: MangaModule, val services: MangaServices, val profileId: Long, val style: MangaStyle)

internal val LocalManga = compositionLocalOf<MangaContext> { error("Manga screens must be inside MangaProvider") }

/**
 * Runs a library query off the UI thread and runs it again whenever the library changes or one of
 * [keys] does. Bursts of changes collapse into one query.
 */
@Composable
internal fun <T> rememberMangaQuery(vararg keys: Any?, initial: T, query: (MangaServices, Long) -> T): State<T> {
    val manga = LocalManga.current
    val revision by manga.services.repository.revision.collectAsState()
    return produceState(initial, manga.services, manga.profileId, revision, *keys) {
        delay(40)
        value = withContext(Dispatchers.IO) { runCatching { query(manga.services, manga.profileId) }.getOrDefault(initial) }
    }
}

/** The sources of the connected server, kept current as extensions come and go. Empty while there is no server. */
@Composable
internal fun rememberMangaSources(): State<List<SourceEntry>> = rememberMangaQuery(initial = emptyList()) { services, profile -> services.sources.list(profile) }

/** Things screens ask the engine to do. Each runs off the UI thread and reports failures in words. */
internal class MangaActions(private val services: MangaServices, private val profileId: Long, private val scope: CoroutineScope) {
    fun io(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                MangaNav.message = failure.message ?: "Something went wrong."
            }
        }
    }

    fun open(series: Series) = MangaNav.open(MangaRoute.SeriesPage(series.id))

    fun read(chapterId: Long) = MangaNav.open(MangaRoute.Reader(chapterId))

    /** Opens the chapter to carry on with, or the first one. */
    fun continueReading(series: Series) = io {
        var chapter = services.repository.resumeChapter(series)
        if (chapter == null && !series.isLocal && services.repository.chapters(series.id).isEmpty()) {
            services.sources.refresh(series.id)
            chapter = services.repository.resumeChapter(services.repository.series(series.id) ?: series)
        }
        val target = chapter ?: services.repository.chapters(series.id).firstOrNull()
        if (target == null) {
            MangaNav.message = "This series has no chapters to read."
        } else {
            withContext(Dispatchers.Main) { read(target.id) }
        }
    }

    fun toggleLibrary(series: Series) = io {
        services.repository.setInLibrary(series.id, !series.inLibrary)
        MangaNav.message = if (series.inLibrary) "Removed from your library" else "Added to your library"
    }

    fun toggleFavorite(series: Series) = io { services.repository.setFavorite(series.id, !series.favorite) }
}

internal val LocalMangaActions = staticCompositionLocalOf<MangaActions> { error("no Manga actions") }

/**
 * Work that has to finish even when the screen that asked for it is gone: saving where the reader
 * was when a chapter is left. It talks to the manga server, so it never runs on the UI thread.
 */
internal object MangaBackground {
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    fun run(block: () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (failure: Throwable) {
                MangaNav.message = failure.message ?: "Something went wrong."
            }
        }
    }
}

/**
 * Provides the Manga engine to [content]. Renders nothing while the module is starting or when no
 * profile is active, so callers do not have to check.
 */
@Composable
fun MangaProvider(content: @Composable () -> Unit) {
    val module = DebridOneRuntime.modules.manga
    val services by module.services.collectAsStateWithLifecycle()
    val profileId by module.activeProfile.collectAsStateWithLifecycle()
    val appearance by module.appearance.current.collectAsStateWithLifecycle()
    val reduceMotion by ShellState.reduceMotion.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.nuvio
    val ready = services ?: return
    val profile = profileId ?: return

    val accent = parseHex(appearance.accentHex) ?: tokens.colors.accent
    val style = MangaStyle(
        accent = accent,
        onAccent = if (accent.luminance() > 0.55f) Color(0xFF101014) else Color.White,
        coverWidth = when (appearance.values[CommonAppearanceKeys.CARD_SIZE]) {
            "small" -> 132.dp
            "large" -> 200.dp
            else -> 164.dp
        },
        gridSpacing = when (appearance.values[CommonAppearanceKeys.DENSITY]) {
            "compact" -> 10.dp
            "spacious" -> 24.dp
            else -> 16.dp
        },
        cardStyle = appearance.values["card_style"] ?: "poster",
        background = when (appearance.values["library_background"]) {
            "dark" -> Color(0xFF121214)
            "accent" -> Color(
                red = accent.red * 0.10f,
                green = accent.green * 0.10f,
                blue = accent.blue * 0.10f,
            )
            else -> tokens.colors.background
        },
        titleScale = when (appearance.values["typography"]) {
            "compact" -> 0.9f
            "large" -> 1.15f
            else -> 1f
        },
        compactChapters = appearance.values["chapter_display"] == "compact",
        reduceMotion = reduceMotion || appearance.values["animations"] == "reduced",
    )
    val scope = rememberCoroutineScope()
    val actions = remember(ready, profile) { MangaActions(ready, profile, scope) }
    // A source test decodes the first page it fetches exactly as the reader would.
    androidx.compose.runtime.LaunchedEffect(ready) {
        ready.sources.pictureDecoder = { bytes ->
            when (val decoded = MangaDecoder.decodePage(bytes, maxWidth = 1200, maxHeight = 6000)) {
                is Decoding.Done -> "${decoded.page.width} × ${decoded.page.height}" + if (decoded.page.blank) " (one colour throughout: a placeholder rather than a page?)" else ""
                is Decoding.Failed -> throw java.io.IOException(decoded.message)
            }
        }
    }
    val context = remember(module, ready, profile, style) { MangaContext(module, ready, profile, style) }
    MangaNav.onProfile(profile)
    CompositionLocalProvider(LocalManga provides context, LocalMangaActions provides actions, content = content)
}

/** The Manga section. */
@Composable
fun MangaScreen() {
    val tokens = MaterialTheme.nuvio
    val module = DebridOneRuntime.modules.manga
    val status by module.status.collectAsStateWithLifecycle()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    (status as? ModuleStatus.Failed)?.let { failed ->
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            Notice("Manga ran into a problem and stopped: ${failed.message}\nThe other sections are not affected. Restart DebridOne to try again.", PillTone.DANGER)
        }
        return
    }
    if (status != ModuleStatus.Running) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = tokens.colors.accent) }
        return
    }

    MangaProvider {
        val manga = LocalManga.current
        val route = MangaNav.current
        // The profile's manga server is started the first time Manga is opened, not with the app.
        LaunchedEffect(manga.profileId) { manga.module.ensureServer() }
        // The reader takes the whole window: no sidebar, no navigation.
        val reading = route is MangaRoute.Reader
        LaunchedEffect(reading) { ShellState.setMangaImmersive(reading) }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ShellState.setMangaImmersive(false) } }

        Box(Modifier.fillMaxSize().background(if (reading) Color.Black else manga.style.background)) {
            if (route is MangaRoute.Reader) {
                MangaReader(route.chapterId, route.page)
            } else {
                Column(Modifier.fillMaxSize().padding(top = topInset)) {
                    MangaTopBar()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val connection by manga.services.server.state.collectAsStateWithLifecycle()
                        // The settings panel stays reachable while the server is down: that is where a wrong address is put right.
                        if (connection !is MangaConnection.Ready) {
                            MangaServerStatus(connection)
                        } else when (route) {
                            MangaRoute.Home -> MangaHome()
                            MangaRoute.Discover -> MangaDiscover()
                            MangaRoute.Search -> MangaSearch()
                            MangaRoute.Library -> MangaLibrary()
                            MangaRoute.Downloads -> MangaDownloads()
                            is MangaRoute.SeriesPage -> SeriesScreen(route.seriesId)
                            is MangaRoute.Reader -> Unit
                        }
                        // Over the page, under the navigation: the page keeps its place, the navigation stays in view.
                        MangaSettingsPanel()
                    }
                }
            }
            MangaMessageBar(Modifier.align(Alignment.BottomCenter))
            MangaDialogs()
        }
    }
}

/**
 * Manga's navigation, laid out like Streaming's and Music's: one pill centred at the top with
 * Home, Discover, Search, Library and Downloads, and a cogwheel for the settings.
 */
@Composable
private fun MangaTopBar() {
    val tokens = MaterialTheme.nuvio
    val root = MangaNav.stack.first()
    Box(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 8.dp)) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            if (MangaNav.stack.size > 1) {
                MangaIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", MangaNav::back, tint = tokens.colors.textPrimary, background = tokens.colors.surface)
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF1C1C1E).copy(alpha = 0.72f))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(50))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MangaNavItem("Home", Icons.Rounded.Home, root == MangaRoute.Home) { MangaNav.root(MangaRoute.Home) }
            MangaNavItem("Discover", Icons.Rounded.Explore, root == MangaRoute.Discover) { MangaNav.root(MangaRoute.Discover) }
            MangaNavItem("Search", Icons.Rounded.Search, root == MangaRoute.Search) { MangaNav.root(MangaRoute.Search) }
            MangaNavItem("Library", Icons.Rounded.CollectionsBookmark, root == MangaRoute.Library) { MangaNav.root(MangaRoute.Library) }
            MangaNavItem("Downloads", Icons.Rounded.Download, root == MangaRoute.Downloads) { MangaNav.root(MangaRoute.Downloads) }
            MangaNavItem("Settings", Icons.Rounded.Settings, MangaNav.settingsOpen, iconOnly = true) {
                if (MangaNav.settingsOpen) MangaNav.settingsOpen = false else MangaNav.openSettings()
            }
        }
    }
}

/** One item of the navigation pill. [iconOnly] items show their name as a tooltip instead. */
@Composable
private fun MangaNavItem(label: String, icon: ImageVector, selected: Boolean, iconOnly: Boolean = false, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val accent = LocalManga.current.style.accent
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var tooltip by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, iconOnly) {
        if (iconOnly && hovered) {
            delay(450)
            tooltip = true
        } else {
            tooltip = false
        }
    }
    val content = if (selected) tokens.colors.textPrimary else Color.White.copy(alpha = 0.72f)
    Row(
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent.copy(alpha = 0.22f) else if (hovered) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(hover)
            .clickable(role = Role.Tab, onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            }
            .padding(horizontal = if (iconOnly) 11.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (iconOnly && tooltip) {
            Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, with(LocalDensity.current) { 34.dp.roundToPx() })) {
                Text(
                    label, style = MaterialTheme.typography.labelMedium, color = tokens.colors.textPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(tokens.colors.surfacePopover).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        if (!iconOnly) Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------- shared pieces

/** The large title at the top of a page, as on Streaming's and Music's Search and Library. */
@Composable
internal fun MangaPageHeading(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Column(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold), color = tokens.colors.textPrimary)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary)
    }
}

@Composable
internal fun MangaSectionHeader(title: String, modifier: Modifier = Modifier, caption: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    val tokens = MaterialTheme.nuvio
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
            if (caption != null) Text(caption, style = small(), color = tokens.colors.textMuted)
        }
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = LocalManga.current.style.accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
internal fun small(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp)

internal fun plural(count: Int, singular: String, plural: String = singular + "s"): String = "$count ${if (count == 1) singular else plural}"

/** A filled or outlined pill button with an optional icon. */
@Composable
internal fun MangaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val style = LocalManga.current.style
    val background = if (primary) style.accent else tokens.colors.surfaceElevated
    val content = when {
        danger -> tokens.colors.danger
        primary -> style.onAccent
        else -> tokens.colors.textPrimary
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) background else background.copy(alpha = 0.4f))
            .then(if (primary) Modifier else Modifier.border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(50)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) content else content.copy(alpha = 0.6f), maxLines = 1)
    }
}

/** A round icon-only button. [description] is what a screen reader announces. */
@Composable
internal fun MangaIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tint: Color = MaterialTheme.nuvio.colors.textSecondary,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(size * 0.56f))
    }
}

@Composable
internal fun MangaChip(text: String, selected: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val style = LocalManga.current.style
    val content = if (selected) style.onAccent else tokens.colors.textSecondary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) style.accent else tokens.colors.surface)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/** Centred message for a page that has nothing to show, with an optional way forward. */
@Composable
internal fun MangaEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(52.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp), content = actions)
    }
}

@Composable
internal fun MangaSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    focusRequests: Int = 0,
    /** The prominent field at the top of the Search and Discover pages. */
    large: Boolean = false,
    onSubmit: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) runCatching { focus.requestFocus() }
    }
    Row(
        modifier = modifier
            .height(if (large) 54.dp else 38.dp)
            .clip(RoundedCornerShape(50))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(50))
            .onPreviewKeyEvent { event ->
                if (onSubmit != null && event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                    onSubmit()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = if (large) 20.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val textStyle = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
        Icon(Icons.Rounded.Search, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(if (large) 22.dp else 18.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = textStyle, color = tokens.colors.textMuted, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = textStyle.copy(color = tokens.colors.textPrimary),
                cursorBrush = SolidColor(LocalManga.current.style.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, contentDescription = "Clear search", tint = tokens.colors.textMuted,
                modifier = Modifier.size(18.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onValueChange("") },
            )
        }
    }
}

@Composable
internal fun MangaMenuItem(text: String, icon: ImageVector? = null, danger: Boolean = false, checked: Boolean = false, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val colour = if (danger) tokens.colors.danger else tokens.colors.textPrimary
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = colour) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, tint = colour, modifier = Modifier.size(18.dp)) } },
        trailingIcon = if (checked) {
            { Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = LocalManga.current.style.accent, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        onClick = onClick,
    )
}

@Composable
internal fun MangaMenuDivider() {
    HorizontalDivider(color = MaterialTheme.nuvio.colors.borderSubtle, modifier = Modifier.padding(vertical = 4.dp))
}

/**
 * A thin horizontal slider, as in Music's player. [onValueChange] follows the pointer, also on a
 * plain click on the track. [value] and the reported values are between 0 and 1.
 */
@Composable
internal fun MangaSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    description: String,
    enabled: Boolean = true,
    /** Fill from the right end, for right-to-left reading. */
    reversed: Boolean = false,
    active: Color = LocalManga.current.style.accent,
    inactive: Color = Color.White.copy(alpha = 0.24f),
) {
    val change by androidx.compose.runtime.rememberUpdatedState(onValueChange)
    val fraction = value.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(24.dp)
            .semantics { contentDescription = description }
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(reversed) {
                    fun report(x: Float) {
                        val raw = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        change(if (reversed) 1f - raw else raw)
                    }
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull() ?: continue
                            if (pointer.pressed) {
                                report(pointer.position.x)
                                pointer.consume()
                            }
                        }
                    }
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(inactive))
        Box(
            Modifier.align(if (reversed) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxWidth(fraction.coerceAtLeast(0.0001f)).height(4.dp).clip(RoundedCornerShape(50)).background(if (enabled) active else inactive),
        )
        // The thumb, placed by the same fraction.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val travel = maxWidth - 14.dp
            val offset = travel * (if (reversed) 1f - fraction else fraction)
            if (enabled) Box(Modifier.padding(start = offset).size(14.dp).clip(CircleShape).background(active))
        }
    }
}

/** A small label over a cover or next to a title: "Manhwa", "Completed", "12 new". */
@Composable
internal fun Badge(text: String, modifier: Modifier = Modifier, background: Color = Color.Black.copy(alpha = 0.72f), content: Color = Color.White) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = content,
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** A book icon standing in for content that has no picture. */
internal val BookIcon: ImageVector get() = Icons.AutoMirrored.Rounded.MenuBook

/** Shows [MangaNav.message] for a few seconds. */
@Composable
private fun MangaMessageBar(modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val message = MangaNav.message ?: return
    LaunchedEffect(message) {
        delay(4_000)
        if (MangaNav.message == message) MangaNav.message = null
    }
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.textPrimary,
        modifier = modifier
            .padding(bottom = 18.dp, start = 40.dp, end = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceElevated)
            .border(1.dp, tokens.colors.borderDefault, RoundedCornerShape(12.dp))
            .clickable { MangaNav.message = null }
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

/**
 * What is shown in place of a page while the profile's manga server is not ready: that it is
 * starting, or why it could not, with a way to try again and the server's own last words.
 */
@Composable
internal fun MangaServerStatus(connection: MangaConnection) {
    val tokens = MaterialTheme.nuvio
    val manga = LocalManga.current
    var details by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        when (connection) {
            is MangaConnection.Failed -> {
                Icon(BookIcon, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(52.dp))
                Text(
                    if (connection.remote) "Your manga server cannot be used right now" else "The manga server could not be started",
                    style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary,
                )
                Text(
                    connection.message + if (connection.retrying) "\nDebridOne keeps trying." else "",
                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth(0.7f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MangaButton("Try again", { manga.services.server.reconnect() })
                    MangaButton("Server settings", { MangaNav.openSettings(MangaSettingsSections.SERVER) }, primary = false)
                    if (connection.log.isNotEmpty()) MangaButton(if (details) "Hide details" else "Show details", { details = !details }, primary = false)
                }
                if (details && connection.log.isNotEmpty()) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            connection.log.takeLast(40).joinToString("\n"),
                            style = small().copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = tokens.colors.textMuted,
                            modifier = Modifier.fillMaxWidth(0.9f).clip(RoundedCornerShape(12.dp)).background(tokens.colors.surface).padding(14.dp),
                        )
                    }
                }
            }
            else -> {
                CircularProgressIndicator(color = manga.style.accent)
                val remote = (connection as? MangaConnection.Connecting)?.remote == true
                Text(
                    if (remote) "Connecting to your manga server…" else "Starting the manga server…",
                    style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary,
                )
                if (!remote) {
                    Text(
                        "Manga runs on Suwayomi-Server, which DebridOne starts for this profile. It takes a few seconds.",
                        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                    )
                }
            }
        }
    }
}
