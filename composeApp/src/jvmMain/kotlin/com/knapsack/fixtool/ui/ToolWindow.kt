package com.knapsack.fixtool.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key

/** Which side of the window a stripe runs down. Two stripes, because the bottom edge belongs to the dock. */
enum class StripeSide {
    LEFT,
    RIGHT,
}

/**
 * **A group of tool windows that share one area, and so show one window at a time.**
 *
 * IntelliJ's rule: a click on another tab in the same group replaces what is showing rather than adding to
 * it. Three groups, so at most three windows are on screen (one width on the left, one on the right, one
 * height at the bottom) instead of eight flags that could squeeze the session grid to a strip.
 *
 * [LEFT_TOP] and [LEFT_BOTTOM] share the left stripe, separated by a weighted gap: the bottom group's tabs
 * sit at the foot of the stripe, which is where IntelliJ puts the tabs of the bottom tool windows now that
 * there is no bottom stripe to carry them.
 */
enum class StripeGroup(
    val side: StripeSide,
) {
    LEFT_TOP(StripeSide.LEFT),
    LEFT_BOTTOM(StripeSide.LEFT),
    RIGHT(StripeSide.RIGHT),
}

/**
 * **The tool windows, in stripe order.**
 *
 * One row of data each for what used to be eight hand-written toolbar buttons: the title its tab prints,
 * the group it belongs to, the icon it wears, and the digit that opens it. The declaration order is the
 * order the tabs are drawn in, so a stripe is a filter over this list rather than a second copy of a
 * layout (see [ToolWindowStripe]).
 *
 * Every title is one noun, because a tab has room for a noun and no room for a sentence. "Message Detail",
 * "Latency Stats", "Repeatable Scenarios" and "Trace across sessions" were tooltips carrying a whole
 * explanation, which is what a tooltip had to do while the only thing on screen was a grey icon. The
 * shortcut goes in the tooltip instead, so it is learned by hovering rather than by reading the guide.
 *
 * [DOCUMENTS] is the odd one: it names the scenario editors and run reports open in the bottom dock rather
 * than a window of its own, it has no digit yet, and its tab is present only while a document is open.
 */
enum class ToolWindow(
    val title: String,
    val group: StripeGroup,
    val icon: ImageVector,
    /** The digit that toggles it, with Cmd on macOS and Ctrl elsewhere. Null for a window with no digit. */
    val shortcut: Int? = null,
) {
    EDITOR("Editor", StripeGroup.LEFT_TOP, Icons.Default.EditNote, 1),
    SCENARIOS("Scenarios", StripeGroup.LEFT_TOP, Icons.Default.PlaylistPlay, 2),
    DETAIL("Detail", StripeGroup.RIGHT, Icons.Default.Article, 3),
    CONNECTION("Connection", StripeGroup.RIGHT, Icons.Default.ElectricalServices, 4),
    ORDER_BOOK("Order book", StripeGroup.RIGHT, Icons.Default.ListAlt, 5),
    LATENCY("Latency", StripeGroup.RIGHT, Icons.Default.Timer, 6),
    TERMINAL("Terminal", StripeGroup.LEFT_BOTTOM, Icons.Default.Terminal, 7),
    TRACE("Trace", StripeGroup.LEFT_BOTTOM, Icons.Default.AltRoute, 8),
    DOCUMENTS("Documents", StripeGroup.LEFT_BOTTOM, Icons.Default.Description),
    ;

    /** The tag its tab carries, so a test names a window instead of a position in a row. */
    val testTag: String get() = "tool-window-${name.lowercase()}"

    /** What the tab says on hover: "Editor · ⌘1" on macOS, "Editor · Ctrl+1" elsewhere, "Documents" bare. */
    val tooltip: String get() = shortcutLabel?.let { "$title · $it" } ?: title

    /**
     * The digit as a shortcut: what the stripe prints, what the Window menu draws, and what the window's key
     * handler answers, all read off one value. Null for a window with no digit.
     */
    internal val chord: Chord? get() = shortcut?.let { Chord(DIGITS[it - 1], "$it") }

    /** The shortcut as a reader of this platform writes it, or null for a window with no digit. */
    val shortcutLabel: String? get() = chord?.label

    /**
     * What a dock's Hide says on hover: "Hide Detail · ⌘3".
     *
     * Hide and not Close, because nothing is lost — and the shortcut is named on the button that does the
     * hiding, which is where somebody who has just hidden a dock wants to read how to get it back.
     */
    val hideTooltip: String get() = shortcutLabel?.let { "Hide $title · $it" } ?: "Hide $title"

    companion object {
        /** ⌘1 to ⌘8, in stripe order. Only the top-row digits: a numeric keypad is nobody's window switcher. */
        private val DIGITS =
            listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight)

        /** The windows in one group, in stripe order. */
        fun inGroup(group: StripeGroup): List<ToolWindow> = entries.filter { it.group == group }
    }
}
