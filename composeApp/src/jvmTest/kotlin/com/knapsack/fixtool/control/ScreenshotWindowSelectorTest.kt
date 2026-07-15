package com.knapsack.fixtool.control

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * **`?window=` picks by title, not by list order.**
 *
 * `windowProvider` used to be `getWindows().firstOrNull()`, right only because there was one window. Once the
 * diff opens in its own window, `getWindows()` has no defined order — so a selector that trusts the first entry
 * photographs the diff when asked for the main, or the reverse, from run to run. This pins the policy that
 * makes both deterministic.
 */
class ScreenshotWindowSelectorTest {
    private val main = ControlServer.MAIN_WINDOW_TITLE to "MAIN"
    private val diff = "rfq flow v2 · Step 2 · Expect ExecutionReport(8) — FixTool" to "DIFF"

    /** The diff is listed FIRST, deliberately: the old `firstOrNull()` would have returned it for `main`. */
    private val windows = listOf(diff, main)

    @Test
    fun `main names the main window by title, even when it is not first in the list`() {
        assertEquals("MAIN", ControlServer.pickWindow(windows, "main")?.second)
        assertEquals("MAIN", ControlServer.pickWindow(windows, null)?.second, "absent selector is main")
        assertEquals("MAIN", ControlServer.pickWindow(windows, "")?.second)
    }

    @Test
    fun `diff names the window that is not the main one`() {
        assertEquals("DIFF", ControlServer.pickWindow(windows, "diff")?.second)
    }

    @Test
    fun `a title substring targets a specific window`() {
        val second = "rfq flow v2 · Step 4 · Expect Quote(S) — FixTool" to "DIFF2"
        val three = listOf(main, diff, second)
        assertEquals("DIFF", ControlServer.pickWindow(three, "Step 2")?.second)
        assertEquals("DIFF2", ControlServer.pickWindow(three, "Step 4")?.second)
    }

    @Test
    fun `with only the main window, diff names nothing rather than returning the main by accident`() {
        assertNull(ControlServer.pickWindow(listOf(main), "diff")?.second)
        assertEquals("MAIN", ControlServer.pickWindow(listOf(main), "main")?.second)
    }

    @Test
    fun `no windows at all is null, not an exception`() {
        assertNull(ControlServer.pickWindow(emptyList<Pair<String, String>>(), "main"))
        assertNull(ControlServer.pickWindow(emptyList<Pair<String, String>>(), "diff"))
    }
}
