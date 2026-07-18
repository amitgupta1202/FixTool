package com.knapsack.fixtool.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **Turning a depth into a left edge** — the one place a nesting level becomes pixels.
 *
 * The *depth* is not decided here and never was: a surface reading a message as a flat list of rows
 * asks `GroupOverlay.depthAt`, and one reading `quickfix.Message`'s parsed tree carries an
 * accumulator down it, deepening only when it descends into a group *instance*. Those two
 * derivations are held to the same numbers by `IndentationEquivalenceTest`. What lived in fourteen
 * places until now was the *arithmetic* — `(8 + level * 8).dp` here, `(depth * 10).dp` there, a
 * third file's own constant — so a change to how nesting reads meant finding all fourteen.
 *
 * **The step differs by surface, deliberately.** These are three different densities, not three
 * answers to one question: the diff shows two messages side by side and cannot spend width, the
 * expanded grid is a table whose columns are already wide enough to carry a bigger step, and the
 * detail and editor panes sit between them. Naming them makes the difference a choice on record
 * rather than a discrepancy nobody meant.
 */
object FixIndent {
    /** The detail pane and the message editor — a single-column list of fields. */
    const val DETAIL_STEP = 8

    /** The reconcile and viewer diffs — two messages side by side, so the tightest step. */
    const val DIFF_STEP = 10

    /** The expanded grid — a table whose columns can carry a wider step legibly. */
    const val GRID_STEP = 16

    /** The gutter every row in the detail pane and editor starts from, before any nesting. */
    const val DETAIL_BASE = 8

    /**
     * The left edge for a row at [level], stepping [step] per level from [base].
     *
     * [level] is the row's own depth — the level it is *drawn* at, not its parent's. A group's
     * header sits at the level of the fields around it; its instances and their fields sit one
     * deeper. That rule is the whole convention, and it is why nothing here adds one for you.
     */
    fun start(level: Int, step: Int, base: Int = 0): Dp = (base + level * step).dp

    /** [start] in raw dp units, for the grid's width measurement — which computes in Int, not [Dp]. */
    fun startValue(level: Int, step: Int, base: Int = 0): Int = base + level * step
}
