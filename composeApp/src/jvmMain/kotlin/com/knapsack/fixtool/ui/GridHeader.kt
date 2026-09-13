package com.knapsack.fixtool.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **One column of a grid header.**
 *
 * @param label the word. Empty for a column that carries a control rather than a name — the message
 *   grid's select-all tick sits in one.
 * @param content replaces the label for a column that is not a word.
 * @param after drawn between this column and the next, outside the cell's border: the message grid's
 *   draggable seams live here, which is why they cannot simply be part of the cell.
 */
data class GridColumn(
    val label: String,
    val width: Dp,
    val align: Alignment = Alignment.Center,
    val tag: String? = null,
    val content: (@Composable () -> Unit)? = null,
    val after: (@Composable () -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
    /** Double-click a message-grid header to fit its column to its widest row. */
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
 *   does not end in a stripe of the background colour. The message grid, whose columns are resizable and
 *   whose total width is computed, passes false.
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
                if (column.content != null) column.content.invoke() else GridHeaderLabel(column.label)
            }
            column.after?.invoke()
        }
        if (trailing) Spacer(modifier = Modifier.weight(1f).fillMaxHeight().background(GRID_HEADER_GROUND))
    }
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
