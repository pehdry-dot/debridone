package com.debridone.app.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.debridone.manga.library.Chapter
import com.debridone.manga.library.ChapterOrder
import com.debridone.manga.library.Series
import com.debridone.manga.reader.OpenChapter
import com.debridone.manga.reader.PageException
import com.debridone.manga.reader.PageFit
import com.debridone.manga.reader.PageLayout
import com.debridone.manga.reader.ReaderBackground
import com.debridone.manga.reader.ReaderSettings
import com.debridone.manga.reader.ReadingMode
import com.nuvio.app.core.ui.isFullscreenActionActive
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.toggleFullscreenAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface PageState {
    data object Loading : PageState
    class Ready(val bitmap: ImageBitmap) : PageState
    class Failed(val message: String, val temporary: Boolean) : PageState
}

/**
 * One chapter being read: which pages are in memory, where the reader is, and the neighbouring
 * chapters. Only a window of pages around the current one is kept decoded; the rest are released
 * and loaded again when they come back into view, so a 200-page chapter does not sit in memory.
 */
internal class ReaderSession(private val manga: MangaContext, val chapterId: Long, private val scope: CoroutineScope) {
    var open by mutableStateOf<OpenChapter?>(null)
        private set
    var failure by mutableStateOf<PageException?>(null)
        private set
    var series by mutableStateOf<Series?>(null)
        private set
    var chapter by mutableStateOf<Chapter?>(null)
        private set
    var chapters by mutableStateOf<List<Chapter>>(emptyList())
        private set

    val pages = mutableStateMapOf<Int, PageState>()

    /** Height divided by width of every page seen so far; kept after a page is released so its place keeps its size. */
    val ratios = mutableStateMapOf<Int, Float>()
    var current by mutableIntStateOf(0)

    /** Position inside the current page (0..1) in the scrolling modes. */
    var offset by mutableFloatStateOf(0f)
    var finished by mutableStateOf(false)
    private val jobs = HashMap<Int, Job>()
    private var maxWidth = 1800
    private var maxHeight = 3600

    val pageCount: Int get() = open?.pageCount ?: 0
    val next: Chapter? get() = chapter?.let { ChapterOrder.next(it, chapters) }
    val previous: Chapter? get() = chapter?.let { ChapterOrder.previous(it, chapters) }

    fun start(startPage: Int?) {
        failure = null
        scope.launch(Dispatchers.IO) {
            try {
                val stored = manga.services.repository.chapter(chapterId) ?: throw PageException("This chapter is no longer in the library.", false)
                val owner = manga.services.repository.series(stored.seriesId) ?: throw PageException("This series is no longer in the library.", false)
                val siblings = manga.services.repository.chapters(owner.id)
                val opened = manga.services.pages.open(chapterId)
                withContext(Dispatchers.Main) {
                    series = owner
                    chapter = stored
                    chapters = siblings
                    // A finished chapter opens at its beginning again; one in progress where it was left.
                    val resumeAt = startPage ?: if (stored.read) 0 else stored.lastPage
                    current = resumeAt.coerceIn(0, (opened.pageCount - 1).coerceAtLeast(0))
                    offset = if (startPage == null && !stored.read && resumeAt == stored.lastPage) stored.scrollOffset else 0f
                    open = opened
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (problem: PageException) {
                withContext(Dispatchers.Main) { failure = problem }
            } catch (other: Throwable) {
                withContext(Dispatchers.Main) { failure = PageException(other.message ?: "The chapter could not be opened.", true, other) }
            }
        }
    }

    /** The series row changed (for example its own reading settings). */
    fun refreshSeries(fresh: Series) {
        series = fresh
    }

    fun limits(width: Int, height: Int) {
        maxWidth = width
        maxHeight = height
    }

    /** Makes sure page [index] is loaded or being loaded. */
    fun want(index: Int) {
        val opened = open ?: return
        if (index !in 0 until opened.pageCount) return
        if (pages[index] is PageState.Ready || jobs[index]?.isActive == true) return
        if (pages[index] !is PageState.Failed) pages[index] = PageState.Loading
        jobs[index] = scope.launch(Dispatchers.IO) {
            val state = try {
                val bytes = manga.services.pages.page(opened, index)
                val bitmap = MangaDecoder.decode(bytes, maxWidth, maxHeight)
                if (bitmap == null) PageState.Failed("This page is not an image DebridOne can show.", false) else PageState.Ready(bitmap)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (problem: PageException) {
                PageState.Failed(problem.message ?: "The page could not be loaded.", problem.temporary)
            } catch (other: Throwable) {
                PageState.Failed(other.message ?: "The page could not be loaded.", true)
            }
            withContext(Dispatchers.Main) {
                if (state is PageState.Ready) ratios[index] = state.bitmap.height.toFloat() / state.bitmap.width.coerceAtLeast(1)
                pages[index] = state
            }
        }
    }

    fun retry(index: Int) {
        pages.remove(index)
        jobs.remove(index)?.cancel()
        want(index)
    }

    /** The reader is on [index]: load ahead, and release what is far behind or ahead. */
    fun focus(index: Int, preload: Int) {
        val count = pageCount
        if (count == 0) return
        current = index.coerceIn(0, count - 1)
        if (current >= count - 1) finished = true
        val keepFrom = current - KEEP_BEHIND
        val keepTo = current + preload + KEEP_AHEAD
        for (page in maxOf(0, current - 1)..minOf(count - 1, current + preload)) want(page)
        for (page in pages.keys.toList()) {
            if (page < keepFrom || page > keepTo) {
                jobs.remove(page)?.cancel()
                pages.remove(page)
            }
        }
    }

    /** Writes the position to the library. Called a moment after every move and when the reader closes. */
    fun save() {
        val count = pageCount
        if (count == 0) return
        runCatching { manga.services.repository.saveProgress(chapterId, current, count, offset, finished = finished) }
    }

    fun close() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        pages.clear()
    }

    private companion object {
        const val KEEP_BEHIND = 3
        const val KEEP_AHEAD = 3
    }
}

/** The reader: immersive, with controls that appear on mouse movement, a key press or a click in the middle. */
@Composable
internal fun MangaReader(chapterId: Long, startPage: Int? = null) {
    val manga = LocalManga.current
    val scope = rememberCoroutineScope()
    val session = remember(chapterId, startPage) { ReaderSession(manga, chapterId, scope) }
    val preferences by manga.services.preferences.values.collectAsState()
    val focus = remember { FocusRequester() }
    var controls by remember { mutableStateOf(true) }
    var lastActivity by remember { mutableStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf(false) }
    var chapterMenu by remember { mutableStateOf(false) }
    var zoom by remember(chapterId) { mutableFloatStateOf(1f) }
    val fullscreen = isFullscreenActionActive()

    LaunchedEffect(session) { session.start(startPage) }
    DisposableEffect(session) {
        onDispose {
            MangaBackground.run { session.save() }
            session.close()
        }
    }
    // Keys go to the reader. Focus is taken back whenever a panel or menu closes and after every
    // click, because the control that was clicked may be gone a moment later, taking focus with it.
    LaunchedEffect(panel, chapterMenu, lastActivity, session.open) {
        if (!panel && !chapterMenu) runCatching { focus.requestFocus() }
    }

    val series = session.series
    // A series can carry its own reading settings; otherwise its shelf's preset applies.
    val ownSettings = series?.readerOverrides != null
    val settings = remember(series?.id, series?.readerOverrides, series?.mediaType, preferences) {
        val shelf = series?.mediaType?.let(manga.services.preferences::readerSettings) ?: ReaderSettings()
        ReaderSettings.decode(series?.readerOverrides, shelf)
    }
    fun update(changed: ReaderSettings) {
        val target = series ?: return
        if (ownSettings) scope.launch(Dispatchers.IO) { manga.services.repository.setReaderOverrides(target.id, changed.normalized().encode()) }
        else manga.services.preferences.setReaderSettings(target.mediaType, changed)
    }
    // The series row is read again when its own settings change.
    val revision by manga.services.repository.revision.collectAsState()
    LaunchedEffect(revision) {
        val id = session.series?.id ?: return@LaunchedEffect
        val fresh = withContext(Dispatchers.IO) { manga.services.repository.series(id) } ?: return@LaunchedEffect
        if (fresh.readerOverrides != session.series?.readerOverrides) session.refreshSeries(fresh)
    }

    // Save a moment after every move, so quitting mid-chapter loses nothing.
    LaunchedEffect(session.current, session.offset, session.finished, session.open) {
        if (session.open == null) return@LaunchedEffect
        delay(400)
        withContext(Dispatchers.IO) { session.save() }
    }
    // Controls fade out when nothing moves.
    LaunchedEffect(lastActivity, panel, chapterMenu) {
        controls = true
        if (panel || chapterMenu) return@LaunchedEffect
        delay(2_800)
        controls = false
    }

    fun go(chapter: Chapter?, atEnd: Boolean = false, edge: String) {
        if (chapter == null) {
            MangaNav.message = edge
            return
        }
        MangaBackground.run { session.save() }
        MangaNav.replace(MangaRoute.Reader(chapter.id, if (atEnd) Int.MAX_VALUE else 0))
    }
    fun nextChapter() = go(session.next, edge = "This is the last chapter.")
    fun previousChapter(atEnd: Boolean = false) = go(session.previous, atEnd, edge = "This is the first chapter.")
    fun exit() {
        if (fullscreen) toggleFullscreenAction()
        MangaNav.back()
    }

    val commands = remember { ReaderCommands() }
    val background = Color(settings.background.argb)
    val onBackground = if (background.luminance() > 0.5f) Color(0xFF1B1B1F) else Color.White

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .focusRequester(focus)
            .focusable()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move || event.type == PointerEventType.Press) lastActivity = System.currentTimeMillis()
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                handleKey(
                    event, settings, commands,
                    onExit = { if (panel) panel = false else if (fullscreen) toggleFullscreenAction() else exit() },
                    onFullscreen = ::toggleFullscreenAction,
                    onNextChapter = ::nextChapter,
                    onPreviousChapter = { previousChapter() },
                    onBookmark = { scope.launch(Dispatchers.IO) { toggleBookmark(manga, session) } },
                    onSettings = { panel = !panel; lastActivity = System.currentTimeMillis() },
                    onMode = {
                        val modes = ReadingMode.entries
                        update(settings.copy(mode = modes[(modes.indexOf(settings.mode) + 1) % modes.size]))
                        MangaNav.message = modes[(modes.indexOf(settings.mode) + 1) % modes.size].label
                    },
                    onLayout = { update(settings.copy(layout = if (settings.layout == PageLayout.SINGLE) PageLayout.DOUBLE else PageLayout.SINGLE)) },
                    onZoom = { step ->
                        if (settings.mode.isPaged) zoom = if (step == 0) 1f else (zoom + step * 0.15f).coerceIn(0.5f, 4f)
                        else update(settings.copy(widthPercent = if (step == 0) 60 else settings.widthPercent + step * 5))
                    },
                )
            },
    ) {
        val failure = session.failure
        val open = session.open
        when {
            failure != null -> ReaderProblem(failure, onBackground, onRetry = { session.start(startPage) }, onBack = ::exit)
            open == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = manga.style.accent) }
            settings.mode.isPaged -> PagedPages(
                session, settings, zoom, commands, onBackground,
                onToggleControls = { lastActivity = if (controls) 0 else System.currentTimeMillis(); controls = !controls },
                onPastEnd = ::nextChapter, onBeforeStart = { previousChapter(atEnd = true) },
                onZoom = { zoom = (zoom * it).coerceIn(0.5f, 4f) },
            )
            else -> StripPages(
                session, settings, commands, onBackground,
                onToggleControls = { lastActivity = if (controls) 0 else System.currentTimeMillis(); controls = !controls },
                onNextChapter = ::nextChapter,
                onWidth = { update(settings.copy(widthPercent = it)) },
            )
        }

        // Page number while the controls are hidden.
        if (open != null && settings.showPageNumber && !controls) {
            Text(
                "${session.current + 1} / ${open.pageCount}",
                style = small(), color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        AnimatedVisibility(controls || panel, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            ReaderTopBar(
                session = session,
                fullscreen = fullscreen,
                ownSettings = ownSettings,
                onBack = ::exit,
                onBookmark = { scope.launch(Dispatchers.IO) { toggleBookmark(manga, session) } },
                onSettings = { panel = !panel },
                onFullscreen = ::toggleFullscreenAction,
            )
        }
        AnimatedVisibility(controls && open != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            ReaderBottomBar(
                session = session,
                reversed = settings.mode == ReadingMode.RIGHT_TO_LEFT,
                chapterMenu = chapterMenu,
                onChapterMenu = { chapterMenu = it },
                onPage = { commands.jump?.invoke(it) },
                onNextChapter = ::nextChapter,
                onPreviousChapter = { previousChapter() },
                onChapter = { chapter ->
                    MangaBackground.run { session.save() }
                    MangaNav.replace(MangaRoute.Reader(chapter.id))
                },
            )
        }
        if (panel && series != null) {
            ReaderSettingsPanel(
                settings = settings,
                shelf = series.mediaType.label,
                ownSettings = ownSettings,
                onChange = ::update,
                onOwnSettings = { own ->
                    scope.launch(Dispatchers.IO) { manga.services.repository.setReaderOverrides(series.id, if (own) settings.encode() else null) }
                },
                onClose = { panel = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 18.dp),
            )
        }
    }
}

private fun toggleBookmark(manga: MangaContext, session: ReaderSession) {
    if (session.open == null) return
    val added = manga.services.repository.toggleBookmark(session.chapterId, session.current)
    MangaNav.message = if (added) "Bookmarked page ${session.current + 1}" else "Bookmark removed"
}

/** What the page area can be asked to do by keys and the page slider. Filled in by whichever layout is showing. */
internal class ReaderCommands {
    var forward: (() -> Unit)? = null
    var backward: (() -> Unit)? = null
    var first: (() -> Unit)? = null
    var last: (() -> Unit)? = null
    var jump: ((Int) -> Unit)? = null

    /** Smaller steps for the scrolling modes; falls back to a page turn. */
    var stepForward: (() -> Unit)? = null
    var stepBackward: (() -> Unit)? = null
}

private fun handleKey(
    event: KeyEvent,
    settings: ReaderSettings,
    commands: ReaderCommands,
    onExit: () -> Unit,
    onFullscreen: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onBookmark: () -> Unit,
    onSettings: () -> Unit,
    onMode: () -> Unit,
    onLayout: () -> Unit,
    onZoom: (Int) -> Unit,
): Boolean {
    val rtl = settings.mode == ReadingMode.RIGHT_TO_LEFT
    val command = event.isMetaPressed || event.isCtrlPressed
    when (event.key) {
        Key.Escape -> onExit()
        Key.F -> onFullscreen()
        // The arrow pointing the way the book is read goes forward.
        Key.DirectionLeft -> if (settings.mode.isPaged) (if (rtl) commands.forward else commands.backward)?.invoke() else commands.backward?.invoke()
        Key.DirectionRight -> if (settings.mode.isPaged) (if (rtl) commands.backward else commands.forward)?.invoke() else commands.forward?.invoke()
        Key.DirectionDown -> (commands.stepForward ?: commands.forward)?.invoke()
        Key.DirectionUp -> (commands.stepBackward ?: commands.backward)?.invoke()
        Key.Spacebar -> if (event.isShiftPressed) commands.backward?.invoke() else commands.forward?.invoke()
        Key.PageDown -> commands.forward?.invoke()
        Key.PageUp -> commands.backward?.invoke()
        Key.MoveHome -> commands.first?.invoke()
        Key.MoveEnd -> commands.last?.invoke()
        Key.RightBracket, Key.N -> onNextChapter()
        Key.LeftBracket, Key.P -> onPreviousChapter()
        Key.B -> onBookmark()
        Key.S -> onSettings()
        Key.M -> onMode()
        Key.D -> onLayout()
        Key.Equals, Key.Plus, Key.NumPadAdd -> onZoom(1)
        Key.Minus, Key.NumPadSubtract -> onZoom(-1)
        Key.Zero, Key.NumPad0 -> if (command) onZoom(0) else return false
        else -> return false
    }
    return true
}

// ---------------------------------------------------------------------------- paged modes

/** One or two pages at a time, turned left or right. */
@Composable
private fun PagedPages(
    session: ReaderSession,
    settings: ReaderSettings,
    zoom: Float,
    commands: ReaderCommands,
    onBackground: Color,
    onToggleControls: () -> Unit,
    onPastEnd: () -> Unit,
    onBeforeStart: () -> Unit,
    onZoom: (Float) -> Unit,
) {
    val count = session.pageCount
    val rtl = settings.mode == ReadingMode.RIGHT_TO_LEFT
    // Which pages share the screen. A wide page (a printed spread) always stands alone.
    val spreads = remember(count, settings.layout, settings.offsetFirstPage, session.ratios.toMap()) {
        buildSpreads(count, settings.layout == PageLayout.DOUBLE, settings.offsetFirstPage) { (session.ratios[it] ?: 1.4f) < 1f }
    }
    val spreadIndex = spreads.indexOfFirst { session.current in it }.coerceAtLeast(0)
    val spread = spreads.getOrElse(spreadIndex) { listOf(session.current) }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()
    var lastWheelTurn by remember { mutableStateOf(0L) }

    fun show(index: Int) {
        val target = spreads.getOrNull(index) ?: return
        session.focus(target.first(), settings.preloadPages)
        session.offset = 0f
        scope.launch {
            vertical.scrollTo(0)
            horizontal.scrollTo(0)
        }
    }
    fun forward() = if (spreadIndex >= spreads.lastIndex) onPastEnd() else show(spreadIndex + 1)
    fun backward() = if (spreadIndex <= 0) onBeforeStart() else show(spreadIndex - 1)

    commands.forward = ::forward
    commands.backward = ::backward
    commands.first = { show(0) }
    commands.last = { show(spreads.lastIndex) }
    commands.jump = { page -> show(spreads.indexOfFirst { page in it }.coerceAtLeast(0)) }
    commands.stepForward = { if (vertical.value < vertical.maxValue) scope.launch { vertical.animateScrollBy(settings.scrollStep * 2f) } else forward() }
    commands.stepBackward = { if (vertical.value > 0) scope.launch { vertical.animateScrollBy(-settings.scrollStep * 2f) } else backward() }

    LaunchedEffect(spread, settings.preloadPages) {
        spread.forEach(session::want)
        session.focus(session.current, settings.preloadPages + spread.size - 1)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(rtl, spreadIndex, spreads.size) {
                detectTapGestures { position ->
                    val third = size.width / 3f
                    when {
                        position.x < third -> if (rtl) forward() else backward()
                        position.x > third * 2 -> if (rtl) backward() else forward()
                        else -> onToggleControls()
                    }
                }
            }
            .pointerInput(spreadIndex, spreads.size) {
                awaitPointerEventScope {
                    while (true) {
                        // Ctrl/Cmd + wheel zooms; this is looked at before the page gets to scroll.
                        val early = awaitPointerEvent(PointerEventPass.Initial)
                        if (early.type == PointerEventType.Scroll && (early.keyboardModifiers.isCtrlPressed || early.keyboardModifiers.isMetaPressed)) {
                            val delta = early.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (delta != 0f) onZoom(if (delta < 0) 1.1f else 1 / 1.1f)
                            early.changes.forEach { it.consume() }
                            continue
                        }
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.type != PointerEventType.Scroll) continue
                        // A wheel turn the page could not use (it is already at its edge) turns the page.
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.isConsumed) continue
                        val now = System.currentTimeMillis()
                        if (now - lastWheelTurn < 260) continue
                        val delta = change.scrollDelta.y
                        if (delta > 0.5f && vertical.value >= vertical.maxValue) { lastWheelTurn = now; forward() }
                        if (delta < -0.5f && vertical.value <= 0) { lastWheelTurn = now; backward() }
                    }
                }
            },
    ) {
        val density = LocalDensity.current
        val viewWidth = maxWidth
        val viewHeight = maxHeight
        session.limits((constraints.maxWidth * 1.5f).toInt().coerceIn(900, 3200), (constraints.maxHeight * 2.5f).toInt().coerceIn(1400, 6000))
        val shown = if (rtl) spread.reversed() else spread
        val slotWidth = viewWidth / shown.size

        Box(Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal), contentAlignment = Alignment.Center) {
            Row(
                Modifier.widthIn(min = viewWidth).padding(vertical = 0.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shown.forEach { index ->
                    val state = session.pages[index]
                    val ratio = session.ratios[index] ?: 1.42f
                    // The size a page is drawn at, from how it should fit and the zoom.
                    val (width, height) = when (settings.fit) {
                        PageFit.SCREEN -> {
                            val byHeight = viewHeight / ratio
                            if (byHeight <= slotWidth) byHeight to viewHeight else slotWidth to slotWidth * ratio
                        }
                        PageFit.WIDTH -> slotWidth to slotWidth * ratio
                        PageFit.HEIGHT -> viewHeight / ratio to viewHeight
                        PageFit.ORIGINAL -> {
                            val pixels = (state as? PageState.Ready)?.bitmap?.width ?: 0
                            val natural = with(density) { pixels.toDp() }.takeIf { pixels > 0 } ?: slotWidth
                            natural to natural * ratio
                        }
                    }
                    PageSlot(session, index, state, width * zoom, height * zoom, minHeight = viewHeight, onBackground = onBackground)
                }
            }
        }
    }
}

/** Groups page numbers into what is shown together. */
internal fun buildSpreads(count: Int, double: Boolean, offsetFirst: Boolean, isWide: (Int) -> Boolean): List<List<Int>> {
    if (!double) return (0 until count).map { listOf(it) }
    val spreads = ArrayList<List<Int>>()
    var index = 0
    if (offsetFirst && count > 0) {
        spreads += listOf(0)
        index = 1
    }
    while (index < count) {
        if (isWide(index) || index + 1 >= count || isWide(index + 1)) {
            spreads += listOf(index)
            index += 1
        } else {
            spreads += listOf(index, index + 1)
            index += 2
        }
    }
    return spreads
}

@Composable
private fun PageSlot(session: ReaderSession, index: Int, state: PageState?, width: Dp, height: Dp, minHeight: Dp, onBackground: Color) {
    val manga = LocalManga.current
    Box(Modifier.width(width).height(maxOf(height, if (state is PageState.Ready) 0.dp else minOf(minHeight, height))), contentAlignment = Alignment.Center) {
        when (state) {
            is PageState.Ready -> Image(
                bitmap = state.bitmap, contentDescription = "Page ${index + 1}", contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High, modifier = Modifier.fillMaxSize(),
            )
            is PageState.Failed -> PageProblem(index, state, onBackground) { session.retry(index) }
            else -> CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp).semantics { contentDescription = "Loading page ${index + 1}" })
        }
    }
}

@Composable
private fun PageProblem(index: Int, state: PageState.Failed, onBackground: Color, onRetry: () -> Unit) {
    Column(Modifier.padding(24.dp).widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Page ${index + 1} could not be shown", style = MaterialTheme.typography.titleMedium, color = onBackground)
        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = onBackground.copy(alpha = 0.72f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        MangaButton("Try again", onRetry, primary = false, icon = Icons.Rounded.Refresh)
    }
}

// ---------------------------------------------------------------------------- scrolling modes

/** All pages in one column: with gaps (vertical scrolling) or as one unbroken strip (webtoon). */
@Composable
private fun StripPages(
    session: ReaderSession,
    settings: ReaderSettings,
    commands: ReaderCommands,
    onBackground: Color,
    onToggleControls: () -> Unit,
    onNextChapter: () -> Unit,
    onWidth: (Int) -> Unit,
) {
    val manga = LocalManga.current
    val count = session.pageCount
    val scope = rememberCoroutineScope()
    val startIndex = remember(session) { session.current }
    val startOffset = remember(session) { session.offset }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val gap = if (settings.mode == ReadingMode.WEBTOON) 0.dp else settings.pageGap.dp
    var restored by remember(session) { mutableStateOf(startOffset <= 0f) }

    fun scrollBy(pixels: Float) {
        scope.launch { list.animateScrollBy(pixels) }
    }
    fun jump(page: Int) {
        scope.launch { list.scrollToItem(page.coerceIn(0, (count - 1).coerceAtLeast(0))) }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onToggleControls() } }
            .pointerInput(settings.widthPercent) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Scroll && (event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed)) {
                            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (delta != 0f) onWidth((settings.widthPercent + if (delta < 0) 5 else -5).coerceIn(30, 100))
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        val density = LocalDensity.current
        val viewHeightPx = constraints.maxHeight.toFloat()
        val columnWidth = maxWidth * (settings.widthPercent / 100f)
        val columnWidthPx = with(density) { columnWidth.toPx() }
        // Decode no wider than the column needs (with room for sharp display), however large the source image is.
        session.limits((columnWidthPx * 1.5f).toInt().coerceIn(700, 2400), 24_000)
        val typical = session.ratios.values.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 1.42f

        commands.forward = { scrollBy(viewHeightPx * 0.85f) }
        commands.backward = { scrollBy(-viewHeightPx * 0.85f) }
        commands.stepForward = { scrollBy(settings.scrollStep.toFloat() * density.density) }
        commands.stepBackward = { scrollBy(-settings.scrollStep.toFloat() * density.density) }
        commands.first = { jump(0) }
        commands.last = { jump(count - 1) }
        commands.jump = ::jump

        // Follow the scroll position: which page is at the top, and how far into it.
        LaunchedEffect(list, count) {
            snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) }
                .collectLatest { (index, scroll, lastVisible) ->
                    if (!restored) return@collectLatest
                    val page = index.coerceAtMost((count - 1).coerceAtLeast(0))
                    val size = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
                    session.offset = if (size > 0 && index < count) (scroll.toFloat() / size).coerceIn(0f, 1f) else 0f
                    session.focus(page, settings.preloadPages)
                    // The footer after the last page is on screen: the chapter has been read to the end.
                    if (lastVisible >= count) session.finished = true
                }
        }
        // Return to where the reader left off inside the page, once that page has its real height.
        LaunchedEffect(session.ratios[startIndex], restored) {
            if (restored) return@LaunchedEffect
            val ratio = session.ratios[startIndex] ?: return@LaunchedEffect
            list.scrollToItem(startIndex, (columnWidthPx * ratio * startOffset).toInt())
            restored = true
        }
        LaunchedEffect(session.open) { session.focus(startIndex, settings.preloadPages) }

        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
            items(count, key = { it }) { index ->
                val state = session.pages[index]
                LaunchedEffect(index, state == null) { if (state == null) session.want(index) }
                val ratio = session.ratios[index] ?: typical
                Box(Modifier.width(columnWidth).height(columnWidth * ratio), contentAlignment = Alignment.Center) {
                    when (state) {
                        is PageState.Ready -> Image(
                            bitmap = state.bitmap, contentDescription = "Page ${index + 1}", contentScale = ContentScale.FillBounds,
                            filterQuality = FilterQuality.High, modifier = Modifier.fillMaxSize(),
                        )
                        is PageState.Failed -> PageProblem(index, state, onBackground) { session.retry(index) }
                        else -> CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    }
                }
            }
            item(key = "end") {
                Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("End of ${session.chapter?.displayName ?: "the chapter"}", style = MaterialTheme.typography.titleMedium, color = onBackground)
                    val next = session.next
                    if (next != null) {
                        MangaButton("Next: ${next.displayName.take(40)}", onNextChapter, icon = Icons.Rounded.SkipNext)
                    } else {
                        Text("You are up to date.", style = MaterialTheme.typography.bodyMedium, color = onBackground.copy(alpha = 0.7f))
                        MangaButton("Back to the series", MangaNav::back, primary = false)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- controls

@Composable
private fun ReaderProblem(failure: PageException, onBackground: Color, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("This chapter could not be opened", style = MaterialTheme.typography.titleLarge, color = onBackground)
            Text(failure.message.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = onBackground.copy(alpha = 0.75f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (failure.temporary) MangaButton("Try again", onRetry, icon = Icons.Rounded.Refresh)
                MangaButton("Back", onBack, primary = false)
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    session: ReaderSession,
    fullscreen: Boolean,
    ownSettings: Boolean,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    onSettings: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val manga = LocalManga.current
    val bookmarked by rememberMangaQuery(session.chapterId, session.current, initial = false) { services, _ -> services.repository.bookmark(session.chapterId, session.current) != null }
    Row(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent))).padding(start = 14.dp, end = 14.dp, top = 30.dp, bottom = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MangaIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Close the reader", onBack, tint = Color.White, background = Color.White.copy(alpha = 0.12f))
        Column(Modifier.weight(1f)) {
            Text(session.series?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val origin = when (session.open?.origin) {
                "local" -> "Local file"
                "downloaded" -> "Downloaded · ${session.series?.sourceName.orEmpty()}"
                "online" -> session.series?.sourceName.orEmpty()
                else -> ""
            }
            Text(
                listOfNotNull(session.chapter?.displayName, origin.takeIf { it.isNotBlank() }, "own settings".takeIf { ownSettings }).joinToString("  ·  "),
                style = small(), color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (session.open?.origin == "downloaded") {
            androidx.compose.material3.Icon(Icons.Rounded.DownloadDone, contentDescription = "Reading the downloaded copy", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
        }
        MangaIconButton(
            if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (bookmarked) "Remove the bookmark" else "Bookmark this page", onBookmark,
            tint = if (bookmarked) manga.style.accent else Color.White, background = Color.White.copy(alpha = 0.12f), enabled = session.open != null,
        )
        MangaIconButton(Icons.Rounded.Tune, "Reading settings", onSettings, tint = Color.White, background = Color.White.copy(alpha = 0.12f))
        MangaIconButton(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (fullscreen) "Leave full screen" else "Full screen", onFullscreen, tint = Color.White, background = Color.White.copy(alpha = 0.12f))
    }
}

@Composable
private fun ReaderBottomBar(
    session: ReaderSession,
    reversed: Boolean,
    chapterMenu: Boolean,
    onChapterMenu: (Boolean) -> Unit,
    onPage: (Int) -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onChapter: (Chapter) -> Unit,
) {
    val manga = LocalManga.current
    val count = session.pageCount
    Row(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.86f)))).padding(start = 18.dp, end = 18.dp, top = 30.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MangaIconButton(Icons.Rounded.SkipPrevious, "Previous chapter", onPreviousChapter, tint = Color.White, enabled = session.previous != null)
        Text("${session.current + 1}", style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        // In right-to-left reading the slider runs the way the pages do.
        MangaSlider(
            value = if (count <= 1) 0f else session.current.toFloat() / (count - 1),
            onValueChange = { fraction -> onPage((fraction * (count - 1) + 0.5f).toInt().coerceIn(0, (count - 1).coerceAtLeast(0))) },
            description = "Page ${session.current + 1} of $count",
            enabled = count > 1,
            reversed = reversed,
            modifier = Modifier.weight(1f),
        )
        Text("$count", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.72f), modifier = Modifier.width(34.dp))
        MangaIconButton(Icons.Rounded.SkipNext, "Next chapter", onNextChapter, tint = Color.White, enabled = session.next != null)
        Box {
            MangaIconButton(Icons.AutoMirrored.Rounded.FormatListBulleted, "Chapters", { onChapterMenu(true) }, tint = Color.White)
            DropdownMenu(chapterMenu, { onChapterMenu(false) }, modifier = Modifier.width(320.dp).height(420.dp)) {
                // A long series is shown as the chapters around the one being read.
                val position = session.chapters.indexOfFirst { it.id == session.chapterId }.coerceAtLeast(0)
                val nearby = session.chapters.subList((position - 60).coerceAtLeast(0), (position + 61).coerceAtMost(session.chapters.size))
                nearby.reversed().forEach { chapter ->
                    MangaMenuItem(chapter.displayName.take(44) + if (chapter.read) "  ✓" else "", checked = chapter.id == session.chapterId) {
                        onChapterMenu(false)
                        if (chapter.id != session.chapterId) onChapter(chapter)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderSettingsPanel(
    settings: ReaderSettings,
    shelf: String,
    ownSettings: Boolean?,
    onChange: (ReaderSettings) -> Unit,
    onOwnSettings: (Boolean) -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier.width(380.dp).clip(RoundedCornerShape(18.dp)).background(tokens.colors.surfaceDialog).padding(18.dp)
            // Clicks on the panel must not reach the page underneath.
            .pointerInput(Unit) { detectTapGestures { } },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reading settings", style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
            if (onClose != null) Text("Done", style = MaterialTheme.typography.labelLarge, color = LocalManga.current.style.accent, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(6.dp))
        }
        SettingGroup("Reading mode") {
            ReadingMode.entries.forEach { mode -> MangaChip(mode.label, settings.mode == mode) { onChange(settings.copy(mode = mode)) } }
        }
        if (settings.mode.isPaged) {
            SettingGroup("Pages") {
                PageLayout.entries.forEach { layout -> MangaChip(layout.label, settings.layout == layout) { onChange(settings.copy(layout = layout)) } }
                if (settings.layout == PageLayout.DOUBLE) MangaChip("First page alone", settings.offsetFirstPage) { onChange(settings.copy(offsetFirstPage = !settings.offsetFirstPage)) }
            }
            SettingGroup("Fit") {
                PageFit.entries.forEach { fit -> MangaChip(fit.label, settings.fit == fit) { onChange(settings.copy(fit = fit)) } }
            }
        } else {
            SettingSlider("Width", "${settings.widthPercent}% of the window", settings.widthPercent, 30..100) { onChange(settings.copy(widthPercent = it)) }
            if (settings.mode == ReadingMode.VERTICAL) {
                SettingSlider("Space between pages", "${settings.pageGap} dp", settings.pageGap, 0..80) { onChange(settings.copy(pageGap = it)) }
            }
            SettingSlider("Arrow-key step", "${settings.scrollStep} px", settings.scrollStep, 40..800) { onChange(settings.copy(scrollStep = it)) }
        }
        SettingGroup("Background") {
            ReaderBackground.entries.forEach { background -> MangaChip(background.label, settings.background == background) { onChange(settings.copy(background = background)) } }
        }
        SettingSlider("Load ahead", plural(settings.preloadPages, "page"), settings.preloadPages, 0..8) { onChange(settings.copy(preloadPages = it)) }
        SettingGroup("Display") {
            MangaChip("Page number", settings.showPageNumber) { onChange(settings.copy(showPageNumber = !settings.showPageNumber)) }
        }
        if (ownSettings != null) {
            SettingGroup("These settings apply to") {
                MangaChip("All $shelf", !ownSettings) { onOwnSettings(false) }
                MangaChip("This series only", ownSettings) { onOwnSettings(true) }
            }
        }
        Text("Keys: ← → pages · ↑ ↓ scroll · Space next · [ ] chapters · F full screen · B bookmark · M mode · D double page · + − zoom · Esc close", style = small(), color = tokens.colors.textMuted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.nuvio.colors.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun SettingSlider(title: String, value: String, current: Int, range: IntRange, onChange: (Int) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val accent = LocalManga.current.style.accent
    Column {
        Row {
            Text(title, style = MaterialTheme.typography.labelLarge, color = tokens.colors.textSecondary, modifier = Modifier.weight(1f))
            Text(value, style = small(), color = tokens.colors.textMuted)
        }
        val span = (range.last - range.first).coerceAtLeast(1)
        MangaSlider(
            value = (current - range.first).toFloat() / span,
            onValueChange = { onChange(range.first + (it * span + 0.5f).toInt()) },
            description = "$title: $value",
            active = accent, inactive = tokens.colors.borderDefault,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
