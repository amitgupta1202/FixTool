package com.knapsack.fixtool.ui.diff

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.service.wireView
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **Search in the reconcile editor: a reading aid, not a filter.**
 *
 * The model half, for the reason `DiffNavigationTest` gives — none of this is drawing, and a behaviour only
 * assertable through a rendered tree is one whose defects hide in the tree. What it pins:
 *
 *  1. A query marks rows and **removes none**. The right column is the reply, whole, in wire order, under
 *     every reading of it — the same promise `GhostLineTest` holds against edits, held here against reading.
 *  2. Both sides are searchable: what the venue sent, and what the expectation asserts.
 *  3. The walk (`↑`/`↓`) is over matches and it wraps.
 */
class DiffSearchTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val arrival: Instant = Instant.parse("2026-07-14T09:35:44Z")

    private val expectation =
        Expectation(
            listOf(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(11, Matcher.Exact("ORD-1")),
                FieldExpectation(31, Matcher.Numeric(1.09244)),
                FieldExpectation(39, Matcher.Exact("2")),
                FieldExpectation(150, Matcher.Exact("2")),
            ),
            messageType = "8",
            mode = MatchMode.OPEN,
        )

    /** The venue's reply carries one field the expectation never mentions (17), so there is an unasserted row. */
    private val reply = wireView(35 to "8", 11 to "ORD-1", 17 to "EXEC-9", 31 to "1.09244", 39 to "2", 150 to "2")

    private fun session() =
        ReconcileSession(
            expectation,
            ReferenceMessage.live(reply, ReferenceMessage.Provenance.THIS_RUN, "this run", arrival),
            dictionary,
        )

    private fun ReconcileSession.lines() = model.items.mapNotNull { (it as? DiffItem.Line)?.line }

    private fun ReconcileSession.matchedTags(query: String): List<Int> {
        search(query)
        return lines().filter { matchesSearch(it) }.map { it.row.tag }
    }

    @Test
    fun `a query marks rows by tag, by field name, and by the value the venue sent`() {
        val session = session()
        assertEquals(listOf(11), session.matchedTags("clordid"), "by the dictionary's name for the tag")
        assertEquals(listOf(11), session.matchedTags("ORD-1"), "by the value on the wire")
        assertEquals(listOf(150), session.matchedTags("150"), "by tag")
    }

    /** A field the expectation never mentions is still a row, and still findable — it is the venue's evidence. */
    @Test
    fun `an unasserted row the reply carried is searchable like any other`() {
        val session = session()
        assertEquals(listOf(17), session.matchedTags("EXEC-9"))
    }

    /**
     * The assertion's own text, not only the wire's. An author hunting the row that asserts `1.09244` must
     * find it whether or not the venue happened to send that number.
     */
    @Test
    fun `the asserted side is searchable too`() {
        val session = session()
        assertTrue(31 in session.matchedTags("1.09244"), "the numeric matcher's expected value")
    }

    /**
     * The invariant, asked of the reader rather than the editor: searching must not be able to remove a row
     * from the diff. If this ever fails, the surface has started filtering and the right column has stopped
     * being the reply.
     */
    @Test
    fun `no query changes how many rows the diff shows`() {
        val session = session()
        val before = session.lines().map { it.row.tag }
        listOf("clordid", "150", "nothing-matches-this", "", "1").forEach { query ->
            session.search(query)
            assertEquals(before, session.lines().map { it.row.tag }, "query '$query' must mark rows, never remove them")
        }
    }

    @Test
    fun `a blank query marks nothing`() {
        val session = session()
        session.search("")
        assertTrue(session.lines().none { session.matchesSearch(it) }, "an empty box must not paint the whole diff")
    }

    /** A new query starts its walk at the first match, not wherever the previous one had got to. */
    @Test
    fun `a fresh query resets the walk`() {
        val session = session()
        session.search("2")
        session.searchCursor = 3
        session.search("clordid")
        assertEquals(0, session.searchCursor)
    }

    /**
     * The variable highlight and the search mark share one channel — the row's border — so where both apply,
     * search wins. The session keeps both facts; the surface resolves them, and this pins the inputs it
     * resolves from rather than letting a later change silently clear one when the other is set.
     */
    @Test
    fun `searching does not clear the variable highlight it temporarily outranks`() {
        val session = session()
        session.highlightedVariable = "id0"
        session.search("clordid")
        assertEquals("id0", session.highlightedVariable, "the chip stays on; the strip says search has the border")
        session.search("")
        assertFalse(session.lines().any { session.matchesSearch(it) }, "and clearing the query hands the border back")
    }
}
