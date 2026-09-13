package com.knapsack.fixtool.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent

/**
 * **One row of the menu bar, as data rather than as a Swing menu item.**
 *
 * Three things read the catalogue and none of them may disagree with the others: the menu bar that draws it,
 * the window's key handler that answers its shortcuts, and the tests that hold it to its promises. A row
 * drawn straight into a `JMenuItem` could only be read by the first.
 */
internal sealed interface MenuRow {
    /** Unique among its siblings: what a renderer keeps a row's identity by, and what a test asks for. */
    val tag: String
}

/**
 * An action, a tick or one of a set of choices — the three kinds of row a native menu has.
 *
 * @param checked null for a plain action, or the state of a tick or a choice
 * @param choice one of a set, drawn as a radio rather than as a tick
 */
internal data class MenuItem(
    val label: String,
    override val tag: String,
    val onClick: () -> Unit,
    val chord: Chord? = null,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val choice: Boolean = false,
) : MenuRow

/** A row that opens a list of its own. Greyed rather than withheld when it is empty, so the door is found. */
internal data class Submenu(
    val label: String,
    override val tag: String,
    val rows: List<MenuRow>,
) : MenuRow {
    val enabled: Boolean
        get() = rows.any { (it is MenuItem && it.enabled) || (it is Submenu && it.enabled) }
}

internal data class MenuSeparator(
    override val tag: String,
) : MenuRow

/**
 * One menu on the bar.
 *
 * @param application the FixTool menu. On a Mac that menu already exists, it is the system's own application
 *   menu, so its rows are handed to the system rather than drawn a second time beside it.
 */
internal data class AppMenu(
    val title: String,
    val rows: List<MenuRow>,
    val application: Boolean = false,
)

/** Every item in these rows, submenus opened, in the order a reader meets them. */
internal fun List<MenuRow>.items(): List<MenuItem> =
    flatMap {
        when (it) {
            is MenuItem -> listOf(it)
            is Submenu -> it.rows.items()
            is MenuSeparator -> emptyList()
        }
    }

/** Every item on the bar. */
@JvmName("menuBarItems")
internal fun List<AppMenu>.items(): List<MenuItem> = flatMap { it.rows.items() }

/**
 * **The window's key handler: find the row whose shortcut was pressed, and do what the row does.**
 *
 * There used to be a hand-written branch per shortcut in `App`, beside tooltips that printed the shortcut
 * from a string of their own. Now a shortcut is a property of a menu row, so a shortcut with no row cannot
 * exist, and a row's shortcut cannot do anything but what the row does.
 *
 * A refused row does not answer its shortcut — ⌃R with nothing to run falls through rather than being
 * swallowed — which is exactly what the greyed row says.
 *
 * @param nativeApplicationMenu on a Mac, ⌘, and ⌘Q belong to the system's application menu, which answers
 *   them itself. Answering them here as well would open Settings twice.
 * @return whether a row answered, which is whether the key is consumed
 */
internal fun List<AppMenu>.dispatch(
    event: KeyEvent,
    nativeApplicationMenu: Boolean = IS_MAC,
): Boolean {
    val row =
        filterNot { nativeApplicationMenu && it.application }
            .items()
            .firstOrNull { it.enabled && it.chord?.matches(event) == true }
            ?: return false
    row.onClick()
    return true
}

/**
 * **The catalogue as the window holds it**: published by the composition that builds it, read by the menu bar
 * and by the key handler.
 *
 * Built in a composable of its own (see [PublishAppMenus]) so the state it reads — every session's connection,
 * the active pane's toggles, the run widget's selection — recomposes that small function and not the whole
 * window around it.
 */
@Stable
class AppMenuState {
    internal var menus: List<AppMenu> by mutableStateOf(emptyList())

    /** Answers a key if a row's shortcut matches it. See [dispatch]. */
    fun dispatch(event: KeyEvent): Boolean = menus.dispatch(event)
}
