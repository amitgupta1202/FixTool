package com.knapsack.fixtool.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor

/**
 * **One column of a grid header.**
 *
 * @param label the word. Empty for a column that carries a control rather than a name — the message
 *   grid's select-all tick sits in one.
 * @param content replaces the label for a column that is not a word.
 * @param onResize makes the column's right edge draggable; called with the drag in dp, for the grid to add
 *   to its own width. The grip sits *inside* the cell, so a resizable column is exactly as wide in the header
 *   as in the rows under it — see [ColumnResizeGrip].
 */
data class GridColumn(
    val label: String,
    val width: Dp,
    val align: Alignment = Alignment.Center,
    val tag: String? = null,
    val content: (@Composable () -> Unit)? = null,
    val onResize: ((Dp) -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
    /** Double-click a header to fit its column to its widest row. */
    val onDoubleClick: (() -> Unit)? = null,
)

/**
 * **One header for the three grids: the message grid, the search results and the trace.**
 *
 * They already agreed on the register — 24dp tall, a `0xFF2D2D2D` ground, a hairline round each cell, 10sp
 * bold monospace — and disagreed about nothing except which file they were written in. Three
 * implementations of one row is three places for the next column to be added to two of.
 *
 * The trace had already grown its own `cell()` and `headerLabel()` helpers to keep its version readable,
 * which is the shape of this file arrived at independently; this is that, shared.
 *
 * @param trailing fills the rest of the row with the header's own ground, so a grid narrower than its pane
 *   does not end in a stripe of the background colour. The message grid, whose total width is computed,
 *   passes false.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridHeader(
    columns: List<GridColumn>,
    modifier: Modifier = Modifier,
    trailing: Boolean = true,
) {
    Row(
        modifier =
            modifier
                .height(GRID_HEADER_HEIGHT)
                .background(GRID_HEADER_GROUND)
                .testTag("grid-header"),
    ) {
        columns.forEach { column ->
            Box(
                modifier =
                    Modifier
                        .width(column.width)
                        .fillMaxHeight()
                        .border(0.5.dp, GRID_HEADER_RULE)
                        .let {
                            if (column.onClick == null && column.onDoubleClick == null) {
                                it
                            } else {
                                it.combinedClickable(
                                    onClick = column.onClick ?: {},
                                    onDoubleClick = column.onDoubleClick,
                                )
                            }
                        }.let { if (column.tag != null) it.testTag(column.tag) else it },
                contentAlignment = column.align,
            ) {
                // Padded so a left- or right-aligned label keeps off the rule the way the rows' text does. A
                // centred label is where it was.
                if (column.content != null) {
                    column.content.invoke()
                } else {
                    GridHeaderLabel(column.label, Modifier.padding(horizontal = 4.dp))
                }
                column.onResize?.let { ColumnResizeGrip(it, tag = column.tag?.let { t -> "$t-resize" }) }
            }
        }
        if (trailing) Spacer(modifier = Modifier.weight(1f).fillMaxHeight().background(GRID_HEADER_GROUND))
    }
}

/**
 * **The draggable right edge of a header cell.**
 *
 * It lives inside the cell rather than between two cells. The seam it replaced was a 1dp box laid out
 * *after* each column, so each header cell was drawn 1dp narrower than the cells under it to make the sums
 * agree — the header's rules and the rows' rules were never on the same x — and a 1dp target is one a mouse
 * can barely catch. Overlaid on the cell's last [COLUMN_GRIP_WIDTH], it takes no width of its own.
 *
 * The drag arrives in px and leaves in dp. The old seam added px straight to a dp width, so on a 2x display
 * the edge ran away from the pointer at twice its speed.
 */
@Composable
fun BoxScope.ColumnResizeGrip(
    onResize: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    // The pointerInput block outlives recomposition, so it reads the newest callback rather than the one it
    // started with — a stale one would add every drag to the width the column had when the drag began.
    val latest by rememberUpdatedState(onResize)
    Box(
        modifier =
            modifier
                .align(Alignment.CenterEnd)
                .width(COLUMN_GRIP_WIDTH)
                .fillMaxHeight()
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        latest(dragAmount.toDp())
                    }
                }.let { if (tag != null) it.testTag(tag) else it },
    )
}

/**
 * **What a grid's columns have been dragged or fitted to**, over the widths the grid declares.
 *
 * Held by the dock rather than inside the grid, because the Ledger and the search results take turns in one
 * dock slot and a width a reader set should outlast switching tabs.
 *
 * The default is passed at each read rather than registered up front, so a column the grid gains later — a
 * tag column added in settings — is resizable without anyone registering it.
 *
 * [min] and [max] are the grids' limits unless a drawing needs its own: a Lanes column holds a whole message
 * chip rather than one value, and a chip carrying two uuids is wider than any grid column is allowed to be.
 */
@Stable
class GridColumnWidths(
    private val min: Dp = GRID_COLUMN_MIN_WIDTH,
    private val max: Dp = GRID_COLUMN_MAX_WIDTH,
) {
    private val set = mutableStateMapOf<String, Dp>()
    private val fitted = mutableStateSetOf<String>()

    fun widthOf(key: String, default: Dp): Dp = set[key] ?: default

    fun resizeBy(key: String, delta: Dp, default: Dp) {
        set[key] = (widthOf(key, default) + delta).coerceIn(min, max)
        fitted.remove(key)
    }

    /** Fit the column to [fit], or — double-clicked again — put back the width the grid declares. */
    fun toggleFit(key: String, fit: () -> Dp) {
        if (fitted.remove(key)) {
            set.remove(key)
        } else {
            set[key] = fit().coerceIn(min, max)
            fitted.add(key)
        }
    }
}

/**
 * The width that shows the longest of [samples] whole, in the grids' 10sp monospace.
 *
 * The estimate the message grid has always fitted with — a character's advance plus the cell's padding.
 * Measuring the text would be exact, and would lay out every row in the window to widen one column.
 */
fun fittedColumnWidth(
    samples: Sequence<String>,
    min: Dp = GRID_COLUMN_MIN_WIDTH,
    max: Dp = GRID_COLUMN_MAX_WIDTH,
): Dp {
    val longest = samples.maxOfOrNull { it.length } ?: 0
    return (longest * FIT_CHAR_WIDTH + FIT_PADDING).dp.coerceIn(min, max)
}

/** The register a grid header speaks: small, bold, monospace, and quieter than the rows under it. */
@Composable
fun GridHeaderLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = AppTheme.Colors.textSecondary,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier,
    )
}

/** A header cell that is not a column of its own — the padding a grid keeps at its left edge. */
@Composable
fun GridHeaderFiller(width: Dp) {
    Box(modifier = Modifier.width(width).fillMaxHeight().background(GRID_HEADER_GROUND))
}

internal val GRID_HEADER_HEIGHT = 24.dp

/**
 * The one grid-header grey.
 *
 * Not `surfaceHeader`: a grid header sits over rows that are already `surface`, and the two need a step
 * between them that a dock header — which sits over content of its own — does not.
 */
internal val GRID_HEADER_GROUND = Color(0xFF2D2D2D)

internal val GRID_HEADER_RULE = Color(0xFF454545)

/** How narrow and how wide a dragged or fitted column may go: the message grid's limits, now every grid's. */
internal val GRID_COLUMN_MIN_WIDTH = 50.dp
internal val GRID_COLUMN_MAX_WIDTH = 400.dp

/** How much of a header cell's right edge catches a drag. */
internal val COLUMN_GRIP_WIDTH = 6.dp

private const val FIT_CHAR_WIDTH = 7
private const val FIT_PADDING = 16
