package com.knapsack.fixtool.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowExceptionHandler
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JSeparator
import javax.swing.SwingUtilities
import kotlin.test.assertEquals

/**
 * **The menu bar drawn for real**, in a window, through Compose's own Swing menu applier.
 *
 * [AppMenusTest] reads the catalogue as data, and that could not see this: opening a workspace moves it to the top
 * of Recent workspaces, and the next time the menu bar was drawn the window crashed with "No such child: 1".
 * Compose moves a keyed node inside a menu with `getComponent`, which a `JMenu` does not answer — its items live in
 * its popup — so any row that changed places inside a menu took the window down.
 */
class AppMenuBarTest {
    private val failures = CopyOnWriteArrayList<Throwable>()

    @Test
    fun `a row that moves up its menu is drawn in its new place, and the window survives it`() {
        drawing(recentWorkspaces("workspaces", "fixed-income-rfq", "rfq-venue")) { state, window ->
            // What opening a workspace does to the list: the one opened goes to the top.
            onEdt { state.menus = recentWorkspaces("fixed-income-rfq", "workspaces", "rfq-venue") }
            assertEquals(listOf("fixed-income-rfq", "workspaces", "rfq-venue"), awaitRecent(window, 3) { it.first() == "fixed-income-rfq" })

            // One opened that was not there, and one whose folder has gone.
            onEdt { state.menus = recentWorkspaces("fx-venue", "fixed-income-rfq", "workspaces") }
            assertEquals(listOf("fx-venue", "fixed-income-rfq", "workspaces"), awaitRecent(window, 3) { it.first() == "fx-venue" })
        }
    }

    @Test
    fun `a row that becomes a row of another kind in the same place is drawn as that kind`() {
        val before = listOf(MenuItem("a", "a", {}), MenuSeparator("rule"), MenuItem("b", "b", {}))
        val after = listOf(MenuItem("b", "b", {}), MenuItem("a", "a", {}), MenuSeparator("rule"))
        drawing(recentWorkspaces(rows = before)) { state, window ->
            onEdt { state.menus = recentWorkspaces(rows = after) }
            assertEquals(listOf("b", "a", "---"), awaitRecent(window, 3) { it.first() == "b" })
        }
    }

    /**
     * Draws [initial] in a real window, runs [block] against it, and closes it.
     *
     * Skipped, not failed, where no window can be drawn at all: that is a machine without a display, not a menu bar
     * that is wrong. Once the first menus are on screen, anything after is held to account.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun drawing(
        initial: List<AppMenu>,
        block: (AppMenuState, ComposeWindow) -> Unit,
    ) {
        assumeFalse("draws a real window", GraphicsEnvironment.isHeadless())
        val state = AppMenuState()
        state.menus = initial
        val window =
            onEdt {
                ComposeWindow().apply {
                    exceptionHandler = WindowExceptionHandler { failures += it }
                    setContent { AppMenuBar(state) }
                    setSize(320, 120)
                    isVisible = true
                }
            }
        val drawn =
            runCatching {
                val rows = initial.single().rows.recent()
                val first = awaitRecent(window, rows.size)
                assumeTrue("the window never drew its menu bar", first.isNotEmpty() || failures.isNotEmpty())
                block(state, window)
            }
        // Closed either way, and after: a composition the move broke throws again on the way out, and that second
        // throw is the one the app's log ends with, not the one that explains it.
        val closed = runCatching { onEdt { window.dispose() } }
        failures.firstOrNull()?.let { throw AssertionError("drawing the menu bar threw", it) }
        drawn.getOrThrow()
        closed.getOrThrow()
    }

    private fun List<MenuRow>.recent(): List<MenuRow> = (single() as Submenu).rows

    private fun recentWorkspaces(vararg names: String) = recentWorkspaces(rows = names.map { MenuItem(it, "menu-workspace-recent-$it", {}) })

    private fun recentWorkspaces(rows: List<MenuRow>) =
        listOf(AppMenu(title = "Workspace", rows = listOf(Submenu(RECENT_WORKSPACES_LABEL, "menu-workspace-recent", rows))))

    /** What Recent workspaces shows, a separator as `---`. */
    private fun recentRows(window: ComposeWindow): List<String> {
        val workspace = window.jMenuBar?.takeIf { it.menuCount > 0 }?.getMenu(0) ?: return emptyList()
        val recent = workspace.takeIf { it.menuComponentCount > 0 }?.getMenuComponent(0) as? JMenu ?: return emptyList()
        return recent.menuComponents.map { if (it is JSeparator) "---" else (it as JMenuItem).text }
    }

    /** The rows once there are [count] of them and [settled] holds, or whatever is there when it gives up. */
    private fun awaitRecent(
        window: ComposeWindow,
        count: Int,
        settled: (List<String>) -> Boolean = { true },
    ): List<String> {
        val deadline = System.currentTimeMillis() + 10_000
        var drawn = emptyList<String>()
        while (System.currentTimeMillis() < deadline && failures.isEmpty()) {
            drawn = onEdt { recentRows(window) }
            if (drawn.size == count && settled(drawn)) return drawn
            Thread.sleep(20)
        }
        failures.firstOrNull()?.let { throw AssertionError("drawing the menu bar threw", it) }
        return drawn
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
