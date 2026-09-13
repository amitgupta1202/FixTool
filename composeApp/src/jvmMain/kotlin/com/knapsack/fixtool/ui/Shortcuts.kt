package com.knapsack.fixtool.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** Whether the keyboard in front of the window is a Mac's. Decided once: it cannot change under a running app. */
internal val IS_MAC: Boolean =
    System
        .getProperty("os.name")
        .orEmpty()
        .lowercase()
        .contains("mac")

/**
 * **A keyboard shortcut, written down once.**
 *
 * Three things used to be written separately for every shortcut the app has: the branch in the window's key
 * handler that answers it, the word a tooltip prints for it, and now the accelerator a menu bar row draws
 * beside its name. Two of them had already drifted before the third existed: the pane's search button printed
 * "Ctrl+F" beside a handler that opened something else. All three are read off this value, so a shortcut
 * cannot be printed one way and bound another.
 *
 * @param keyName what [label] prints for the key, because `KeyEvent.getKeyText` says "Comma" for `,`
 * @param control ⌃ rather than ⌘ on a Mac, which is IntelliJ's ⌃R. Off a Mac both are Ctrl.
 */
internal data class Chord(
    val key: Key,
    val keyName: String,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val control: Boolean = false,
) {
    /** "⌘⇧F" on a Mac and "Ctrl+Shift+F" elsewhere: the primary modifier first, as the app has always printed. */
    val label: String get() = label(IS_MAC)

    /** [label] for a given platform, so a test can hold both spellings without pretending to be a Mac. */
    fun label(mac: Boolean): String =
        if (mac) {
            buildString {
                append(if (control) "⌃" else "⌘")
                if (alt) append("⌥")
                if (shift) append("⇧")
                append(keyName)
            }
        } else {
            listOfNotNull("Ctrl", "Alt".takeIf { alt }, "Shift".takeIf { shift }, keyName).joinToString("+")
        }

    /** The stroke a menu row draws beside its name, and the one Swing binds to that row. */
    val keyShortcut: KeyShortcut
        get() = KeyShortcut(key, ctrl = control || !IS_MAC, meta = IS_MAC && !control, alt = alt, shift = shift)

    /**
     * Whether [event] is this shortcut being pressed.
     *
     * ⌘ or Ctrl both count as the primary modifier, on every platform, because that is what the window's key
     * handler has always accepted: ⌘1 and ⌃1 both open the editor on a Mac, and ⌘R runs as well as ⌃R. Shift
     * and Option must match exactly, so ⌘F is not ⌘⇧F — those are two different searches — and neither is ⌥⌘F.
     */
    fun matches(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || event.key != key) return false
        if (!event.isMetaPressed && !event.isCtrlPressed) return false
        return event.isShiftPressed == shift && event.isAltPressed == alt
    }
}

/**
 * **Every shortcut the window answers, in one table.**
 *
 * The tool windows' digits are not listed here because they are a property of the window they open — see
 * [ToolWindow.chord] — but they are in the same menu bar, and the menu bar is what a collision is tested on.
 *
 * Two pairs share a letter and differ by ⇧, the way IntelliJ's find in file and find in path do: an action on
 * the pane, and its twin on every pane. ⌘F searches the pane and ⌘⇧F every session; ⌘B marks the pane with a
 * blank line and ⌘⇧B marks every pane at once.
 */
internal object Shortcuts {
    val SEARCH_IN_PANE = Chord(Key.F, "F")
    val SEARCH_ALL_SESSIONS = Chord(Key.F, "F", shift = true)
    val BLANK_LINE = Chord(Key.B, "B")
    val BLANK_LINE_ALL_PANES = Chord(Key.B, "B", shift = true)

    /** ⌘⇧H. Not ⌥⌘H, which every Mac application menu already spends on Hide Others. */
    val HIDE_PROTOCOL_TAGS = Chord(Key.H, "H", shift = true)

    /** IntelliJ's run shortcut, which is ⌃R on a Mac rather than ⌘R. */
    val RUN = Chord(Key.R, "R", control = true)

    val SETTINGS = Chord(Key.Comma, ",")
    val QUIT = Chord(Key.Q, "Q")

    /** IntelliJ's, including the F12 a laptop reaches with fn. */
    val HIDE_ALL_TOOL_WINDOWS = Chord(Key.F12, "F12", shift = true)
}
