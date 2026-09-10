package com.knapsack.fixtool.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key

/** Which edge of the window a tool window opens from, and so which stripe carries its tab. */
enum class ToolWindowEdge {
    LEFT,
    RIGHT,
    BOTTOM,
}

/**
 * **The eight tool windows, in stripe order.**
 *
 * One row of data each for what used to be eight hand-written toolbar buttons: the title its tab prints,
 * the edge its window opens from, the icon it wears, and the digit that opens it. The declaration order is
 * the order the tabs are drawn in, so a stripe is a filter over this list rather than a third copy of a
 * layout (see [ToolWindowStripe]).
 *
 * Every title is one noun, because a tab has room for a noun and no room for a sentence. "Message Detail",
 * "Latency Stats", "Repeatable Scenarios" and "Trace across sessions" were tooltips carrying a whole
 * explanation, which is what a tooltip had to do while the only thing on screen was a grey icon. The
 * shortcut goes in the tooltip instead, so it is learned by hovering rather than by reading the guide.
 */
enum class ToolWindow(
    val title: String,
    val edge: ToolWindowEdge,
    val icon: ImageVector,
    /** The digit that toggles it, with Cmd on macOS and Ctrl elsewhere. IntelliJ's numbering, in stripe order. */
    val shortcut: Int,
) {
    EDITOR("Editor", ToolWindowEdge.LEFT, Icons.Default.EditNote, 1),
    SCENARIOS("Scenarios", ToolWindowEdge.LEFT, Icons.Default.PlaylistPlay, 2),
    DETAIL("Detail", ToolWindowEdge.RIGHT, Icons.Default.Article, 3),
    CONNECTION("Connection", ToolWindowEdge.RIGHT, Icons.Default.ElectricalServices, 4),
    ORDER_BOOK("Order book", ToolWindowEdge.RIGHT, Icons.Default.ListAlt, 5),
    LATENCY("Latency", ToolWindowEdge.RIGHT, Icons.Default.Timer, 6),
    TERMINAL("Terminal", ToolWindowEdge.BOTTOM, Icons.Default.Terminal, 7),
    TRACE("Trace", ToolWindowEdge.BOTTOM, Icons.Default.AltRoute, 8),
    ;

    /** The tag its tab carries, so a test names a window instead of a position in a row. */
    val testTag: String get() = "tool-window-${name.lowercase()}"

    /** What the tab says on hover: "Editor · ⌘1" on macOS, "Editor · Ctrl+1" elsewhere. */
    val tooltip: String get() = "$title · $shortcutLabel"

    /** The shortcut as a reader of this platform writes it. */
    val shortcutLabel: String get() = if (IS_MAC) "⌘$shortcut" else "Ctrl+$shortcut"

    companion object {
        private val IS_MAC = System.getProperty("os.name").lowercase().contains("mac")

        /** ⌘1 to ⌘8, in stripe order. Only the top-row digits: a numeric keypad is nobody's window switcher. */
        private val DIGITS =
            listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight)

        private val byKey: Map<Key, ToolWindow> = entries.associateBy { DIGITS[it.shortcut - 1] }

        /** The windows on one edge, in stripe order. */
        fun on(edge: ToolWindowEdge): List<ToolWindow> = entries.filter { it.edge == edge }

        /** The window a Cmd or Ctrl digit toggles, or null for any other key. */
        fun forKey(key: Key): ToolWindow? = byKey[key]
    }
}
