package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.service.compare.Verdict
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The ghost line: every field the venue sent appears on the right, once, in wire order — always.**
 *
 * Move a row of the expectation and the field it asserted used to vanish from the received side: the
 * unpaired row "spoke for" its tag, and the whole tag's unclaimed occurrences were swallowed to avoid
 * reporting one move as two contradictory failures. The de-duplication was right about the *count* and
 * wrong about the *display* — the right column read as a message no venue sent, and when the moved value
 * had also changed, the one field that distinguishes "dropped" from "moved and changed" was invisible.
 *
 * So the occurrence an unpaired row speaks for is emitted as a **ghost**: shown at its wire position,
 * judged by nobody, counted by nothing, offered nothing. These tests pin each of those clauses.
 */
class ReconcileGhostLineTest {
    private fun exact(tag: Int, value: String) = FieldExpectation(tag, Matcher.Exact(value))

    /** The user's scenario: captured [35, 11, 150], then row 11 dragged to the end. */
    private val wire = wireView(35 to "8", 11 to "ORD-1", 150 to "2")
    private val movedRow =
        Expectation(listOf(exact(35, "8"), exact(150, "2"), exact(11, "ORD-1")), mode = MatchMode.STRICT)

    @Test
    fun `the field a moved row spoke for is shown as a ghost at its wire position`() {
        val rows = ScenarioReconcile.rows(movedRow, wire, dictionary = null)

        val ghost = rows.singleOrNull { it.ghost }
        assertNotNull(ghost, "the swallowed field must surface as a ghost: $rows")
        assertEquals(11, ghost.tag)
        assertEquals(1, ghost.wireIndex, "the ghost sits where the venue sent the field")
        assertEquals("ORD-1", ghost.actual, "the ghost shows the value the venue sent")
        assertTrue(ghost.unasserted, "a ghost is not a row of the expectation")
        assertFalse(ghost.judged, "a ghost is display, not judgement")

        // The invariant the ghost exists to restore: the right column is the reply, whole, in wire order.
        assertEquals(
            wire.fields().indices.toList(),
            rows.mapNotNull { it.wireIndex },
            "every field the venue sent appears exactly once, in wire order: $rows",
        )

        // And in reading order it sits at the wire position — between the rows claiming fields 0 and 2.
        assertEquals(listOf(35, 11, 150, 11), rows.map { it.tag }, "reading order: 35, ghost 11, 150, moved 11")
    }

    @Test
    fun `a ghost is counted by nothing — one move is one row of attention in both modes`() {
        for (mode in listOf(MatchMode.STRICT, MatchMode.OPEN)) {
            val draft = movedRow.copy(mode = mode)
            val rows = ScenarioReconcile.rows(draft, wire, dictionary = null)
            val moved =
                (ScenarioReconcile.reorder(draft, wire) as? ScenarioReconcile.Reorder.Possible)?.moved.orEmpty()

            val verdict = Verdict.of(rows, moved, movedEntries = 0, mode = mode)
            assertEquals(0, verdict.added, "the ghost is not a tag the venue added ($mode)")
            assertEquals(1, verdict.attention, "one move is one row needing attention, not two ($mode)")
        }
    }

    @Test
    fun `the runner's results never carry the ghost — in either mode`() {
        for (mode in listOf(MatchMode.STRICT, MatchMode.OPEN)) {
            val results = ExpectationEvaluator.evaluate(wire, movedRow.copy(mode = mode))
            assertTrue(
                results.none { it.status == TagStatus.UNEXPECTED },
                "the moved row reports the divergence; the ghost must not double it as an extra ($mode): $results",
            )
            assertEquals(1, results.count { !it.passed }, "one divergence, one failing result ($mode)")
        }
    }

    @Test
    fun `the credit is per occurrence — a genuine extra does not ride in under a move's excuse`() {
        // Row 11 is out of order AND the venue sent a second 11 nobody asserts. One ghost, one real extra.
        val wire = wireView(11 to "ORD-1", 11 to "ORD-2", 35 to "8")
        val draft = Expectation(listOf(exact(35, "8"), exact(11, "ORD-1")), mode = MatchMode.STRICT)

        val rows = ScenarioReconcile.rows(draft, wire, dictionary = null)
        assertEquals(1, rows.count { it.ghost }, "one unpaired row amnesties ONE occurrence: $rows")
        assertEquals(0, rows.single { it.ghost }.wireIndex, "the first unclaimed occurrence is the spoken-for one")

        val extra = rows.single { it.unasserted && !it.ghost }
        assertEquals(1, extra.wireIndex, "the second occurrence is a genuine extra, shown and counted")

        val verdict = Verdict.of(rows, movedRows = emptySet(), movedEntries = 0, mode = MatchMode.STRICT)
        assertEquals(1, verdict.added, "the over-credit occurrence still counts as a tag the venue added")
    }

    @Test
    fun `a row that moved AND changed value no longer hides the evidence`() {
        // The expectation lists 11 last, asserting ORD-1; the venue sent 11 second, carrying ORD-9. The row
        // reads MISSING (nothing satisfying it anywhere) — and the field it is actually about used to vanish.
        val wire = wireView(35 to "8", 11 to "ORD-9", 150 to "2")
        val rows = ScenarioReconcile.rows(movedRow, wire, dictionary = null)

        assertEquals(TagStatus.MISSING, rows.single { it.index == 2 }.status)
        val ghost = rows.single { it.ghost }
        assertEquals("ORD-9", ghost.actual, "the author must SEE the value the venue sent: $rows")
        assertEquals(1, ghost.wireIndex)
    }

    @Test
    fun `the ghost's note is short, and names the STRICT consequence only under STRICT`() {
        val strict = ScenarioReconcile.rows(movedRow, wire, dictionary = null).single { it.ghost }
        assertEquals("asserted, but not in this position (a STRICT extra)", strict.reason)

        val open =
            ScenarioReconcile.rows(movedRow.copy(mode = MatchMode.OPEN), wire, dictionary = null).single { it.ghost }
        // OPEN ignores unclaimed tags, so threatening the author with an extra would be false.
        assertEquals("asserted, but not in this position", open.reason)
        assertFalse("STRICT" in open.reason || "extra" in open.reason, "no STRICT threat in OPEN: ${open.reason}")
    }

    @Test
    fun `accept every shape change does not assert the ghost — it re-orders the row that owns it`() {
        val repaired = ScenarioReconcile.acceptEveryShapeChange(movedRow, wire, dictionary = null)
        assertEquals(3, repaired.fields.size, "no second row for tag 11 was seeded: ${repaired.fields}")
        assertEquals(listOf(35, 11, 150), repaired.fields.map { it.tag }, "the move was repaired as a move")
        assertTrue(
            ExpectationEvaluator.evaluate(wire, repaired).all { it.passed },
            "the repaired expectation passes the reply",
        )
    }

    @Test
    fun `absent rows keep their tag-wide voice — no ghost, no extra, one failure`() {
        val wire = wireView(35 to "8", 58 to "x")
        val draft =
            Expectation(listOf(exact(35, "8"), FieldExpectation(58, Matcher.Absent)), mode = MatchMode.STRICT)
        val rows = ScenarioReconcile.rows(draft, wire, dictionary = null)
        assertTrue(rows.none { it.ghost }, "an absent row's field already faces it — a ghost would show it twice")
        assertTrue(rows.none { it.unasserted }, "and it is not an extra either: the absent row speaks for it")
        assertEquals(1, rows.count { !it.passed })
    }

    @Test
    fun `a clean match has no ghosts`() {
        val seeded =
            Expectation(listOf(exact(35, "8"), exact(11, "ORD-1"), exact(150, "2")), mode = MatchMode.STRICT)
        assertTrue(ScenarioReconcile.rows(seeded, wire, dictionary = null).none { it.ghost })
    }
}
