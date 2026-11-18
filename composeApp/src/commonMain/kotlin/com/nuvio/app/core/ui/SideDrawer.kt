package com.nuvio.app.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A panel that slides in from the right edge, over the page it belongs to rather than instead of
 * it. The page underneath is not recomposed away: its scroll position, its text fields, what is
 * selected and what is playing are exactly as they were when the panel closes.
 *
 * Put it as the last child of the area it should cover (a section's content area, below the
 * section's navigation, which then stays visible and usable).
 *
 *  - On a wide area the panel takes [width] and the rest of the page shows through a dimmed scrim.
 *  - On an area narrower than [overlayBelow] it fills the area: a strip of page too thin to read
 *    would only be noise.
 *  - Clicking the scrim, pressing Escape, or the platform's Back closes it.
 *
 * [content] is told whether the panel is filling the area (`compact`), to lay itself out for it.
 */
@Composable
fun SideDrawer(
    open: Boolean,
    onClose: () -> Unit,
    /** What a screen reader announces the panel as. */
    title: String,
    modifier: Modifier = Modifier,
    width: Dp = 560.dp,
    overlayBelow: Dp = 900.dp,
    /** Never wider than this share of the area while a strip of the page is left visible. */
    maxFraction: Float = 0.92f,
    background: Color = Color(0xFF151517),
    scrim: Color = Color.Black.copy(alpha = 0.55f),
    /** No sliding: the panel appears and disappears in place. */
    reduceMotion: Boolean = LocalReduceMotion.current,
    content: @Composable (compact: Boolean) -> Unit,
) {
    // Registered while open, so Escape reaches the panel before the page's own Back handling.
    PlatformBackHandler(enabled = open && LocalScreenActive.current, onBack = onClose)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxWidth < overlayBelow
        val panelWidth = if (compact) maxWidth else minOf(width, maxWidth * maxFraction)
        val duration = if (reduceMotion) 0 else 180

        AnimatedVisibility(open, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(scrim)
                    .semantics { contentDescription = "Close $title" }
                    // No ripple: the whole dimmed page is the target.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
        AnimatedVisibility(
            open,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = if (reduceMotion) EnterTransition.None else slideInHorizontally(tween(duration)) { it },
            exit = if (reduceMotion) ExitTransition.None else slideOutHorizontally(tween(duration)) { it },
        ) {
            val focus = remember { FocusRequester() }
            // Keys go to the panel while it is open, so Escape closes it wherever focus was.
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Box(
                Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    .background(background)
                    .semantics { paneTitle = title }
                    // Clicks on the panel's own background stay in the panel.
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onClose()
                            true
                        } else {
                            false
                        }
                    },
            ) { content(compact) }
        }
    }
}
