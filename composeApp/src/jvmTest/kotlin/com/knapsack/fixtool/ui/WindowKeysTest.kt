package com.knapsack.fixtool.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import org.junit.Test
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The window answers a key after whatever has focus has had it, and only if nothing used it.**
 *
 * The window's shortcuts were an `onKeyEvent` on the root of its content, which hears only what bubbles up from
 * a focused Compose node. Click an empty part of the window, or into the terminal, and ⌃R, ⌘F and the digits
 * went nowhere. They are answered from AWT now, after the focused component — so the order is the claim: a key
 * the terminal consumed stays the terminal's, and one nothing used is the window's.
 */
class WindowKeysTest {
    private var pressed = 0

    private val state =
        AppMenuState().apply {
            menus =
                listOf(
                    AppMenu(
                        "Window",
                        listOf(MenuItem("Editor", "menu-window-editor", { pressed++ }, chord = ToolWindow.EDITOR.chord)),
                    ),
                )
        }

    private fun press(
        keyCode: Int,
        modifiers: Int = InputEvent.META_DOWN_MASK,
        id: Int = KeyEvent.KEY_PRESSED,
    ): KeyEvent = KeyEvent(Canvas(), id, 0L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED)

    @Test
    fun `a key nothing used is answered by the row that owns it, and consumed`() {
        val event = press(KeyEvent.VK_1)

        assertTrue(state.answer(event))
        assertEquals(1, pressed)
        assertTrue(event.isConsumed, "answered, so nothing after the window sees it again")
    }

    @Test
    fun `a key the focused component already used is left alone`() {
        // The terminal consumes the keys a shell needs — ⌃R is readline's reverse search — before the window hears.
        val event = press(KeyEvent.VK_1).apply { consume() }

        assertFalse(state.answer(event))
        assertEquals(0, pressed)
    }

    @Test
    fun `only a press is answered, not the typed character or the release after it`() {
        assertFalse(state.answer(press(KeyEvent.VK_1, id = KeyEvent.KEY_RELEASED)))
        assertEquals(0, pressed)
    }

    // ---------------------------------------------------------------- a held key is not a second press

    private var asked = 0

    private val closeAll =
        MenuItem("Close all", "menu-close-all", { asked++ }, chord = Shortcuts.CLOSE_ALL, asks = true)

    private fun holding() =
        state.apply { menus = menus + AppMenu("Session", listOf(closeAll)) }

    private val shiftW = InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK

    @Test
    fun `a held chord is answered once on a row that asks, and on every repeat on a row that does not`() {
        val window = holding()

        repeat(3) { assertTrue(window.answer(press(KeyEvent.VK_1))) }
        assertEquals(3, pressed, "a row that does not ask repeats as a held key always has")

        repeat(3) { assertTrue(window.answer(press(KeyEvent.VK_W, shiftW)), "still the row's key, so consumed") }
        assertEquals(1, asked, "the press asks, and its repeats are not the answer")

        window.answer(press(KeyEvent.VK_W, shiftW, KeyEvent.KEY_RELEASED))
        window.answer(press(KeyEvent.VK_W, shiftW))
        assertEquals(2, asked, "a fresh press after the release is")
    }

    /**
     * The menu bar's own accelerator answers the key before the window's handler hears it, consuming it on the way, so
     * the handler is only counting. Both doors have to agree about which press is a repeat.
     */
    @Test
    fun `the menu bar's accelerator is held to a press too`() {
        val window = holding()

        val first = press(KeyEvent.VK_W, shiftW)
        assertTrue(window.acts(closeAll, first))
        first.consume()
        assertFalse(window.answer(first))

        val repeat = press(KeyEvent.VK_W, shiftW)
        assertFalse(window.acts(closeAll, repeat), "the repeat of a held chord does not act on a row that asks")
        assertTrue(window.acts(closeAll.copy(asks = false), repeat), "on a row that does not ask, it does")

        window.answer(press(KeyEvent.VK_W, shiftW, KeyEvent.KEY_RELEASED))
        assertTrue(window.acts(closeAll, press(KeyEvent.VK_W, shiftW)))
    }

    @Test
    fun `a key let go in another window is not left held`() {
        val window = holding()

        window.answer(press(KeyEvent.VK_W, shiftW))
        window.releaseAll()
        window.answer(press(KeyEvent.VK_W, shiftW))

        assertEquals(2, asked, "the release went elsewhere, so the next press is a press")
    }

    @Test
    fun `Esc is the one key answered that is no row's`() {
        var unfollowed = false
        state.unclaimed = { event -> (event.key == Key.Escape).also { unfollowed = it } }

        assertTrue(state.answer(press(KeyEvent.VK_ESCAPE, modifiers = 0)))
        assertTrue(unfollowed)
        assertFalse(state.answer(press(KeyEvent.VK_2)), "⌘2 is no row's here and no unclaimed key's either")
    }

    @Test
    fun `an AWT press reads as the Compose press a chord matches`() {
        val event = press(KeyEvent.VK_F, modifiers = InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
        val compose = event.toComposeKeyEvent()

        assertEquals(Key.F, compose.key)
        assertEquals(KeyEventType.KeyDown, compose.type)
        assertTrue(Shortcuts.SEARCH_ALL_SESSIONS.matches(compose))
        assertFalse(Shortcuts.SEARCH_IN_PANE.matches(compose))
    }
}
