package com.knapsack.fixtool.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A grid column's draggable edge, and the widths it writes.**
 *
 * One grip serves the message grid, the search results and the Ledger, so what it gets wrong it gets wrong in
 * all three. Two things it used to get wrong are pinned here: the drag was added to the width in px, so on a
 * 2x display the edge ran from the pointer at twice its speed; and the seam was a 1dp box *between* cells,
 * which the header paid for by drawing every column 1dp narrower than its rows.
 */
class GridColumnResizeTest {
    @Test
    fun `a drag is clamped to the grid limits, and a fit restores the declared width when asked again`() {
        val widths = GridColumnWidths()

        widths.resizeBy("Summary", (-500).dp, default = 200.dp)
        assertEquals(GRID_COLUMN_MIN_WIDTH, widths.widthOf("Summary", 200.dp))
        widths.resizeBy("Summary", 1_000.dp, default = 200.dp)
        assertEquals(GRID_COLUMN_MAX_WIDTH, widths.widthOf("Summary", 200.dp))

        widths.toggleFit("Time") { 88.dp }
        assertEquals(88.dp, widths.widthOf("Time", 120.dp))
        widths.toggleFit("Time") { 88.dp }
        assertEquals(120.dp, widths.widthOf("Time", 120.dp), "the second double-click puts back the grid's own width")
    }

    @Test
    fun `dragging after a fit makes the next double-click fit again, not restore`() {
        val widths = GridColumnWidths()

        widths.toggleFit("Dir") { 70.dp }
        widths.resizeBy("Dir", 10.dp, default = 50.dp)
        widths.toggleFit("Dir") { 70.dp }

        assertEquals(70.dp, widths.widthOf("Dir", 50.dp), "a dragged column is not a fitted one any more")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `on a 2x display the edge moves as far as the pointer, and the next column moves with it`() =
        runSkikoComposeUiTest(size = Size(1600f, 200f), density = Density(2f)) {
            val widths = GridColumnWidths()
            setContent {
                GridHeader(
                    listOf(
                        GridColumn(
                            label = "A",
                            width = widths.widthOf("A", 120.dp),
                            tag = "col-A",
                            onResize = { widths.resizeBy("A", it, 120.dp) },
                        ),
                        GridColumn("B", 100.dp, tag = "col-B"),
                    ),
                )
            }

            onNodeWithTag("col-A-resize").performMouseInput {
                moveTo(center)
                press()
                // 100px in tens: a pointer that crossed 50dp of a 2x screen.
                repeat(10) { moveBy(Offset(10f, 0f)) }
                release()
            }
            waitForIdle()

            val grown = widths.widthOf("A", 120.dp) - 120.dp
            // Mouse slop is a fraction of a px, so the whole 50dp arrives, less that. The px-as-dp seam made this 100dp.
            assertTrue(abs(grown.value - 50f) < 1.5f, "a 100px drag at 2x is 50dp, got $grown")

            val a = onNodeWithTag("col-A").getUnclippedBoundsInRoot()
            val b = onNodeWithTag("col-B").getUnclippedBoundsInRoot()
            assertTrue(abs((a.width - widths.widthOf("A", 120.dp)).value) < 0.5f, "the header cell is the whole width: ${a.width}")
            assertTrue(abs((b.left - a.right).value) < 0.5f, "B starts where A ends — no seam between them: ${a.right} vs ${b.left}")
        }
}
