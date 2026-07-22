package com.knapsack.fixtool.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The one rule three field grids obey.** The message editor, the scenario editor's Send step and the
 * reconcile diff all ask [FieldSearch] the same question, so the answer is pinned here once rather than
 * three times in three UI tests that could drift apart without ever going red.
 */
class FieldSearchTest {
    @Test
    fun `a field answers by tag, by dictionary name, or by value`() {
        assertTrue(FieldSearch.matches("11", tag = 11, name = "ClOrdID", value = "ORD-1"), "by tag")
        assertTrue(FieldSearch.matches("clord", tag = 11, name = "ClOrdID", value = "ORD-1"), "by name")
        assertTrue(FieldSearch.matches("ord-1", tag = 11, name = "ClOrdID", value = "ORD-1"), "by value")
        assertFalse(FieldSearch.matches("55", tag = 11, name = "ClOrdID", value = "ORD-1"), "and by none of them")
    }

    @Test
    fun `matching is case-insensitive and ignores surrounding space`() {
        assertTrue(FieldSearch.matches("CLORDID", 11, "ClOrdID", "ORD-1"))
        assertTrue(FieldSearch.matches("  clordid  ", 11, "ClOrdID", "ORD-1"))
    }

    @Test
    fun `a partial tag matches, because an author types as they remember`() {
        // "1" finding 11, 15 and 150 is the point, not a defect: the grid highlights rather than filters, so
        // a broad query costs the reader nothing and a narrowing keystroke is always available.
        assertTrue(FieldSearch.matches("1", tag = 150, name = "ExecType", value = "2"))
        assertTrue(FieldSearch.matches("15", tag = 150, name = "ExecType", value = "2"))
    }

    /**
     * The one that decides how the feature *feels*. Search here marks rows in place rather than removing
     * them, so "a blank query matches everything" would paint the whole grid gold the instant the box was
     * focused and emptied — the reader would have to clear the search to be able to read anything.
     */
    @Test
    fun `a blank query matches nothing`() {
        assertFalse(FieldSearch.matches("", 11, "ClOrdID", "ORD-1"))
        assertFalse(FieldSearch.matches("   ", 11, "ClOrdID", "ORD-1"))
    }

    @Test
    fun `absent facts are simply not matched, never crashed on`() {
        assertFalse(FieldSearch.matches("clord", tag = null, name = null, value = null))
        assertTrue(FieldSearch.matches("ord-1", tag = null, name = null, value = "ORD-1"))
    }

    @Test
    fun `a value expression is searchable as authored, so a mint is found by its name`() {
        // ${id0 = uuid:20} in a Send field: searching the variable's name must find the row that mints it.
        assertTrue(FieldSearch.matches("id0", tag = 11, name = "ClOrdID", value = "\${id0 = uuid:20}"))
    }
}
