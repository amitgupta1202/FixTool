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

    @Test
    fun `renaming a condition's tag keeps it where it was`() {
        val conditions = mapOf("55" to "ACME", "54" to "1", "40" to "2")

        assertEquals(
            listOf("55" to "ACME", "38" to "1", "40" to "2"),
            conditions.rekeyed(1, "38").entries.map { it.key to it.value },
            "a renamed condition must not jump to the bottom of the list mid-keystroke",
        )
    }

    @Test
    fun `renaming a condition that is not there changes nothing`() {
        val conditions = mapOf("55" to "ACME")

        assertSame(conditions, conditions.rekeyed(3, "38"))
    }

    @Test
    fun `a tag renamed onto one already present collapses, and does not duplicate it`() {
        // The map is the storage, so two rows cannot both be tag 55 — the editor lets the keystroke
        // through and the row disappears into the one already there. Pinned as the known consequence
        // of storing conditions by tag, so a future reader meets it here rather than in the field.
        val conditions = mapOf("55" to "ACME", "54" to "1")

        assertEquals(mapOf("55" to "1"), conditions.rekeyed(1, "55"))
    }
}
