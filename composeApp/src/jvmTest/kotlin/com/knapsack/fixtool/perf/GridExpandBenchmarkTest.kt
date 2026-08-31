package com.knapsack.fixtool.perf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.AppMessage
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.ui.HierarchicalGridView
import org.junit.Test
import kotlin.test.assertTrue

/**
 * **What a new message costs the grid, and whether that depends on how many messages it already holds.**
 *
 * This is the app's hottest redraw. `FixMessageSession` publishes a fresh snapshot of the retained
 * window on every 100ms drain cycle, so while traffic flows the grid is handed a new list ten times a
 * second and rebuilds from it. If that rebuild is proportional to the window rather than to what
 * changed, the tool gets slower the longer a session runs — which is exactly backwards, and exactly
 * what a `LazyColumn` exists to prevent.
 *
 * `HierarchicalGridView` built its list differently from every other list in the codebase:
 *
 * ```
 * LazyColumn(...) {
 *     renderRows.forEach { row ->                              // N intervals, not one
 *         ...
 *         val isExpanded = expandedMessages[messageId] ?: false // a snapshot-state read,
 *         item(key = messageId) { ... }                        // OUTSIDE the item lambda
 * ```
 *
 * Two costs in one shape. `forEach { item(...) }` registers one interval object per row where
 * `items(list, key = ...)` registers a single interval with a count — so building the provider is O(N)
 * allocations instead of O(1). And `expandedMessages` is a `mutableStateMapOf` read in the
 * *item-provider* scope rather than inside an item's content; that scope is snapshot-observed, so
 * touching one entry invalidates the provider for the whole list and expanding a single row rebuilds
 * every interval in the grid.
 *
 * **The measurement is the scaling, not the figure.** Absolute frame times on any given box are
 * meaningless; the ratio between two window sizes measured back to back on the same box is not.
 */
@OptIn(ExperimentalTestApi::class)
class GridExpandBenchmarkTest {
    private val dictionary = FixDictionary.createDefault()

    /**
     * Renders the grid over a window of [count] messages, then repeatedly delivers a new list with one
     * more message on the end — the drain cycle, as the grid experiences it.
     *
     * The list identity changes every time, exactly as it does in production (`retained.toList()`), so
     * nothing upstream can memoise the work away and the measurement is of the rebuild itself.
     */
    private fun tickCost(count: Int): Bench.Result {
        var result: Bench.Result? = null
        runComposeUiTest {
            val corpus = Corpus.rfqFlow(count + 64)
            var window by mutableStateOf<List<AppMessage>>(corpus.take(count))
            setContent {
                Box(Modifier.size(1400.dp, 900.dp)) {
                    HierarchicalGridView(
                        messages = window,
                        dictionary = dictionary,
                        hideProtocolTags = true,
                    )
                }
            }
            waitForIdle()

            var next = 0
            result =
                Bench.measure(
                    "window of %,d messages".format(count),
                    ops = 16, warmupRounds = 2, rounds = 5, allThreads = true,
                ) {
                    next++
                    // A fresh list of the same length, as the ring buffer produces: one message rolls off
                    // the front and one arrives at the back.
                    window = corpus.subList(next % 32, next % 32 + count)
                    waitForIdle()
                    true
                }
        }
        return requireNotNull(result)
    }

    /**
     * The pin. Twenty times the retained window must not mean twenty times the cost of one drain cycle.
     *
     * The bound is deliberately loose — some growth is honest, because a bigger window means a longer
     * row list to diff and more keys to reconcile. What it must not do is track the window length the
     * way rebuilding every interval does.
     */
    @Test
    fun `a drain cycle does not get more expensive as the retained window fills`() {
        val small = tickCost(50)
        val large = tickCost(1_000)

        val timeRatio = large.nanosPerOp.toDouble() / small.nanosPerOp
        val allocRatio = if (small.bytesPerOp > 0) large.bytesPerOp.toDouble() / small.bytesPerOp else 0.0

        println("\n┌─ One drain cycle through the grid, by retained window size")
        println("│  " + small.render())
        println("│  " + large.render())
        println("└─ 20x the window cost %.1fx the time, %.1fx the allocation\n".format(timeRatio, allocRatio))

        assertTrue(
            timeRatio < 10.0,
            "a drain cycle over a 1,000-message window took ${large.nanosPerOp}ns against " +
                "${small.nanosPerOp}ns over 50 (${timeRatio}x for 20x the messages) — the cost is " +
                "tracking the window length, which is what a lazy list exists not to do",
        )
    }
}
