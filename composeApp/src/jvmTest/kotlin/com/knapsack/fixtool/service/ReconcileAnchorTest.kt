package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.service.compare.StrictSemantics
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A moment is judged against the moment the message arrived, everywhere — including inside the code that
 * decides whether an entry moved.**
 *
 * The diff already judged temporal rows at `actualAt`. What it did *not* do was tell
 * `ScenarioReconcile.reorder` about it: `verbatimWindow` asked `ExpectationEvaluator.satisfies`, which
 * hard-coded `Instant.now()` and a null resolver. So an entry carrying a `~now ±60s` row — an `MDEntryTime`
 * on any market-data snapshot, and every venue sends those — could never be recognised as having moved
 * *verbatim*, because one row of it "failed" against the reader's wall clock. Accept-new-order was then
 * silently withheld from exactly the messages that need it, and the author was left with a screenful of red
 * rows, no button, and no reason. A reference row inside an entry did the same thing, for the same reason.
 */
class ReconcileAnchorTest {
    /** 1 Jan 2025, which is not now and will never be now again. */
    private val arrival = Instant.parse("2025-01-01T00:00:00Z")
    private val arrivalOnTheWire = "20250101-00:00:00"

    /** Two market-data entries, each carrying a timestamp — the shape the defect hid in. */
    private fun snapshot() =
        Expectation(
            fields =
                listOf(
                    FieldExpectation(268, Matcher.Exact("2")),
                    FieldExpectation(269, Matcher.Exact("0")),
                    FieldExpectation(270, Matcher.Exact("1.10")),
                    FieldExpectation(273, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)),
                    FieldExpectation(269, Matcher.Exact("1")),
                    FieldExpectation(270, Matcher.Exact("1.20")),
                    FieldExpectation(273, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)),
                ),
            messageType = "W",
        )

    /** The same two entries, in the other order. Nothing regressed; the venue reshuffled. */
    private fun swapped() =
        wireView(
            268 to "2",
            269 to "1",
            270 to "1.20",
            273 to arrivalOnTheWire,
            269 to "0",
            270 to "1.10",
            273 to arrivalOnTheWire,
        )

    /**
     * THE DEFECT, REPRODUCED. The entries moved, whole and verbatim, and every row of them would pass at the
     * instant the message arrived — which is the instant the diff itself judges them at. The re-order is
     * withheld anyway, because the one row that cannot be judged against a wall clock is judged against one.
     */
    @Test
    fun `an entry carrying a timestamp is still recognised as having moved`() {
        val draft = snapshot()
        val reply = swapped()

        // The caller says which moment to judge at — and `reorder` has always accepted it. It was the
        // placement underneath that went to the wall clock behind its back.
        val reorder = ScenarioReconcile.reorder(draft, reply, now = { arrival })

        assertTrue(
            reorder is ScenarioReconcile.Reorder.Possible,
            "the entries moved verbatim — withholding the re-order here leaves the author with red rows, no " +
                "button and no reason: $reorder",
        )
        assertTrue(
            ExpectationEvaluator.evaluate(reply, reorder.reordered, now = { arrival }).all { it.passed },
            "and accepting it must actually repair the step",
        )
    }

    /** The same, for an echo: a reference row inside a moved entry blocked the move just as thoroughly. */
    @Test
    fun `an entry carrying a reference is still recognised as having moved`() {
        val draft =
            Expectation(
                fields =
                    listOf(
                        FieldExpectation(453, Matcher.Exact("2")),
                        FieldExpectation(448, Matcher.Exact("FIRMA")),
                        FieldExpectation(447, Matcher.Reference("\${src}")),
                        FieldExpectation(452, Matcher.Exact("1")),
                        FieldExpectation(448, Matcher.Exact("FIRMB")),
                        FieldExpectation(447, Matcher.Reference("\${src}")),
                        FieldExpectation(452, Matcher.Exact("4")),
                    ),
                messageType = "8",
            )
        val reply =
            wireView(
                453 to "2",
                448 to "FIRMB",
                447 to "D",
                452 to "4",
                448 to "FIRMA",
                447 to "D",
                452 to "1",
            )

        val reordered =
            ScenarioReconcile.acceptNewOrder(draft, reply, referenceResolver = { if (it == "\${src}") "D" else null })

        assertNotNull(reordered, "an echoed id inside a party entry must not hide the fact that the entry moved")
        assertEquals(
            listOf("2", "FIRMB", "\${src}", "4", "FIRMA", "\${src}", "1"),
            reordered.fields.map { row ->
                when (val m = row.matcher) {
                    is Matcher.Exact -> m.value
                    is Matcher.Reference -> m.expression
                    else -> "?"
                }
            },
            "and FIRMA must still be the party that holds role 1",
        )
    }

    /**
     * And the rule the anchor exists for, stated on its own: a row that passed during the run does not become
     * a venue regression because the engineer took two minutes to click Reconcile.
     */
    @Test
    fun `a temporal row judged at the arrival instant passes, and judged at the clock does not`() {
        val draft =
            Expectation(
                fields = listOf(FieldExpectation(273, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60))),
                messageType = "W",
            )
        val reply = wireView(273 to arrivalOnTheWire)

        assertTrue(
            ScenarioReconcile.rows(draft, reply, dictionary = null, now = { arrival }).single().passed,
        )
        assertTrue(
            !ScenarioReconcile.rows(draft, reply, dictionary = null, now = { Instant.now() }).single().passed,
            "if this passes, the fixture's timestamp is close enough to now that the test proves nothing",
        )
    }

    // ----- the reference slot ---------------------------------------------------------------------------

    private fun temporalRow() =
        Expectation(
            fields = listOf(FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60))),
            messageType = "8",
        )

    /**
     * **A pasted message is judged at its own SendingTime, not at the clock.** Paste a message an hour old
     * into the slot and a `~now ±60s` row must not go red for that reason: it passed when the message was
     * sent, which is the only claim it ever made. The author's repairs on a temporal row are to loosen it or
     * drop it, so a phantom red here does not stay a red — it becomes a deleted assertion.
     */
    @Test
    fun `a pasted message anchors its temporals to its own SendingTime`() {
        val anHourAgo = "20250101-00:00:00"
        val paste = wireView(35 to "8", 52 to anHourAgo, 60 to anHourAgo)

        val reference = ReferenceMessage.pasted(paste)

        assertEquals(arrival, reference.anchorInstant, "the anchor is tag 52, read by the evaluator's own parser")
        assertEquals(ReferenceMessage.Provenance.PASTED, reference.provenance)
        val row = ScenarioReconcile.rows(temporalRow(), reference, dictionary = null).first { it.tag == 60 }
        assertTrue(row.passed, "judged at the moment it was sent, this row holds — and it is not a venue regression")
    }

    /**
     * And where a paste carries no moment at all, the row is **unjudged** — not passed, not failed. The tool
     * does not know when that message was sent, and inventing an answer costs coverage either way: a red
     * gets the assertion deleted, a green asserts nothing.
     */
    @Test
    fun `a paste with no SendingTime leaves its temporal rows unjudged, and says why`() {
        val reference = ReferenceMessage.pasted(wireView(35 to "8", 60 to "20250101-00:00:00"))

        assertTrue(reference.unanchored)
        val row = ScenarioReconcile.rows(temporalRow(), reference, dictionary = null).first { it.tag == 60 }

        assertTrue(row.unknown, "an unanchored temporal is a third state, and it is excluded from the counts")
        assertTrue(!row.judged)
        assertTrue("no SendingTime(52)" in row.reason, row.reason)
        assertTrue("left unjudged" in row.reason, row.reason)
    }

    /** The slot swaps, and the whole diff re-judges. That is all "compare against something else" is. */
    @Test
    fun `swapping the reference re-judges every row`() {
        val draft = Expectation(listOf(FieldExpectation(39, Matcher.Exact("2"))), messageType = "8")
        val golden = ReferenceMessage.golden(wireView(35 to "8", 52 to "20250101-00:00:00", 39 to "2"))
        val thisRun =
            ReferenceMessage.live(
                wireView(35 to "8", 39 to "8"),
                ReferenceMessage.Provenance.THIS_RUN,
                "this run · 09:35:44",
                arrival,
            )

        val againstGolden = StrictSemantics.align(draft, golden, null).rows
        val againstThisRun = StrictSemantics.align(draft, thisRun, null).rows

        assertTrue(againstGolden.first { it.tag == 39 }.passed)
        assertTrue(
            !againstThisRun.first { it.tag == 39 }.passed,
            "the same expectation, a different reference, and no edit in between",
        )
    }
}
