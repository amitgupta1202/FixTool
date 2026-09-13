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
