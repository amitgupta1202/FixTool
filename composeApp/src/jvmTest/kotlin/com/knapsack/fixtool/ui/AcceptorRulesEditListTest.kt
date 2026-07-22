package com.knapsack.fixtool.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The list edits behind the acceptor rules editor.
 *
 * Ordering is *meaning* in both lists the editor shows — rules are first-match-wins, steps are played
 * in the order written — so a move is a real edit to what the acceptor does, and these are worth
 * pinning away from the Compose tree that calls them.
 */
class AcceptorRulesEditListTest {
    @Test
    fun `moving an item takes it to the place asked for`() {
        assertEquals(listOf("b", "a", "c"), listOf("a", "b", "c").moved(0, 1))
        assertEquals(listOf("a", "c", "b"), listOf("a", "b", "c").moved(2, -1))
        assertEquals(listOf("c", "a", "b"), listOf("a", "b", "c").moved(2, -2))
    }

    @Test
    fun `a move off either end is a no-op, not a wrap`() {
        val list = listOf("a", "b", "c")

        assertSame(list, list.moved(0, -1), "the first rule moved up must stay first, not become last")
        assertSame(list, list.moved(2, 1), "the last rule moved down must stay last, not become first")
        assertEquals(listOf("c", "a", "b"), list.moved(2, -5), "an overshoot clamps to the end it was heading for")
    }

    @Test
    fun `replace and remove address the position asked for`() {
        assertEquals(listOf("a", "X", "c"), listOf("a", "b", "c").replaced(1, "X"))
        assertEquals(listOf("a", "c"), listOf("a", "b", "c").without(1))
    }
}
