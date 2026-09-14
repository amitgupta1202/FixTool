package com.knapsack.fixtool.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.service.TraceLanes

/**
 * **Where every lane starts and how wide it is** — the one place a lane's x comes from.
 *
 * Every chip, lifeline, arrow, landing and relay reason is placed against this, so a dragged lane moves them
 * all together. Before the lanes could be resized each of them multiplied a shared width by the lane's index,
 * which was right only while every lane was the same width.
 */
internal class LaneGeometry(
    private val widths: List<Dp>,
) {
    private val starts: List<Dp> = widths.runningFold(0.dp) { start, width -> start + width }

    val total: Dp get() = starts.last()

    fun startOf(lane: Int): Dp = starts[lane]

    fun widthOf(lane: Int): Dp = widths[lane]

    fun centreOf(lane: Int): Dp = starts[lane] + widths[lane] / 2

    /** From [left]'s leading edge to [right]'s trailing edge, both lanes included. */
    fun spanOf(left: Int, right: Int): Dp = starts[right + 1] - starts[left]
}

/**
 * How narrow and how wide a lane may be dragged. Wider than a grid column may go, because a lane holds a whole
 * chip — type, name and two ids — and two uuids alone are more than a grid column's 400dp.
 */
internal val LANE_MIN_WIDTH = 80.dp
internal val LANE_MAX_WIDTH = 800.dp

/** The widths Lanes starts from: none set, and a lane's own limits rather than a grid column's. */
fun laneColumnWidths(): GridColumnWidths = GridColumnWidths(min = LANE_MIN_WIDTH, max = LANE_MAX_WIDTH)

/** A lane's width is remembered against its pane's title, which outlives the trace that drew it. */
internal fun laneKey(lane: TraceLanes.Lane): String = lane.title
