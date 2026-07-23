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
