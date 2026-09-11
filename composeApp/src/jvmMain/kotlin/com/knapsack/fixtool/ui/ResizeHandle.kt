package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/**
 * The draggable seams between panels. Two things they get right that the old inline dividers did not:
 *
 *  1. **A grabbable hit area.** The visible seam is still a 1px line, but the pointer target is [HANDLE_GRAB]
 *     wide. A 1px target is nearly impossible to catch with a mouse — which is why the rail "could not be
 *     resized". The line is centred in the wider transparent grab zone.
 *  2. **No stale-capture bug.** The handle reports the raw drag delta in px and the *caller* applies it to its
 *     own current size. The rail's old divider captured the ratio by value and recomputed `staleRatio + delta`
 *     every event, so it barely moved; letting the caller read its own live state each event fixes that.
 *
 * [onDragEnd] fires on release — the one moment worth persisting the new size to settings (a per-frame save
 * would thrash the disk).
 */
private val HANDLE_GRAB = 6.dp

/** A vertical seam between horizontally-arranged panels (drag left/right). Reports the horizontal delta. */
@Composable
fun WidthResizeHandle(onDeltaPx: (Float) -> Unit, onDragEnd: () -> Unit = {}, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .width(HANDLE_GRAB)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = onDragEnd) { change, drag ->
                        change.consume()
                        onDeltaPx(drag.x)
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(AppTheme.Separators.color))
    }
}

/** A horizontal seam between vertically-stacked areas (drag up/down). Reports the vertical delta. */
@Composable
fun HeightResizeHandle(onDeltaPx: (Float) -> Unit, onDragEnd: () -> Unit = {}, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(HANDLE_GRAB)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = onDragEnd) { change, drag ->
                        change.consume()
                        onDeltaPx(drag.y)
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.height(1.dp).fillMaxWidth().background(AppTheme.Separators.color))
    }
}

/**
 * The width a dragged pane is actually drawn at: [wanted], held at [min] and at whatever leaves the pane
 * beside it [otherMin] of the [available] space.
 *
 * The clamp belongs on the way *out* rather than on the way in, so the caller can keep the width that was
 * asked for. A window narrowed after the fact then borrows from the dragged pane instead of squeezing its
 * neighbour, and widening the window again gives back what was borrowed.
 *
 * An [available] of zero is the frame before the row has been measured: there is nothing to clamp against
 * yet, and clamping to [min] would draw one narrow frame and then jump.
 */
fun boundedPaneWidth(wanted: Dp, available: Dp, min: Dp, otherMin: Dp): Dp =
    if (available <= 0.dp) {
        wanted.coerceAtLeast(min)
    } else {
        wanted.coerceIn(min, maxOf(min, available - otherMin))
    }
