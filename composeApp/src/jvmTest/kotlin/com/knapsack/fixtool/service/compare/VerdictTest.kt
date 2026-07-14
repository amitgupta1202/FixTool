package com.knapsack.fixtool.service.compare

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.ScenarioReconcile
import com.knapsack.fixtool.service.wireView
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The verdict, counted once.**
 *
 * This arithmetic lived inside `ReconcileView.VerdictBar`, where every counter had acquired a comment naming
 * a defect that had shipped in it. Those comments are the specification, and each one is a test here — because
 * the diff surface that replaces that view renders this object rather than counting its own rows, and a bug in
 * it is now a bug in both surfaces at once.
 *
 * The headline is the sentence an author reads first and trusts. Every clause of it has to be true of the rows
 * underneath it.
 */
class VerdictTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private val transactTime: String =
        DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.now())

    /** The four-failures ExecutionReport the whole design was drawn against — the canonical fixture. */
    private val reply =
        wireView(
            35 to "8",
            11 to "ORD-1",
            17 to "EXEC-9",
            150 to "2",
            39 to "2",
            151 to "500000",
            14 to "1000000",
            31 to "1.0851",
            453 to "2",
            448 to "FIRMB",
            447 to "D",
            452 to "4",
            448 to "FIRMA",
            447 to "D",
            452 to "1",
            2376 to "Y",
            60 to transactTime,
        )

    private val captured =
        Expectation(
            listOf(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(11, Matcher.Exact("ORD-1")),
                FieldExpectation(17, Matcher.Presence),
                FieldExpectation(150, Matcher.Exact("2")),
                FieldExpectation(39, Matcher.Exact("2")),
                FieldExpectation(151, Matcher.Numeric(0.0)),
                FieldExpectation(14, Matcher.Numeric(1000000.0)),
                FieldExpectation(31, Matcher.Numeric(1.0851)),
                FieldExpectation(453, Matcher.Exact("2")),
                FieldExpectation(448, Matcher.Exact("FIRMA")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("1")),
                FieldExpectation(448, Matcher.Exact("FIRMB")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("4")),
                FieldExpectation(58, Matcher.Exact("filled")),
                FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)),
            ),
            messageType = "8",
            mode = MatchMode.STRICT,
        )

    private fun verdictOf(draft: Expectation, message: MessageView, movedEntries: Int = 0): Verdict {
        val rows = ScenarioReconcile.rows(draft, message, dictionary)
        val moved = ScenarioReconcile.movedRows(draft, message)
        return Verdict.of(rows, moved, movedEntries, draft.mode)
    }

    /**
     * **THE HEADLINE COUNTS EVERY ROW ONCE.**
     *
     * The canonical failure: the venue filled 500,000 where it left nothing (one value), added
     * `PartyRoleQualifier` (one tag), stopped sending `Text` (one tag), and sent its two party entries the
     * other way round (six rows). Nine rows need attention.
     *
     * The headline said **ten**. `attention` already contained `added`, and the headline added it a second
     * time — so every tag a venue adds inflated the first number an author reads. It is the number they use to
     * decide whether the run is worth looking at.
     */
    @Test
    fun `every row needing attention is counted exactly once`() {
        val verdict = verdictOf(captured, reply, movedEntries = 2)

        assertEquals(1, verdict.values, "151: the venue filled where it used to leave nothing")
        assertEquals(1, verdict.added, "2376: the venue added a tag")
        assertEquals(1, verdict.missing, "58: the venue stopped sending Text")
        assertEquals(6, verdict.movedRows, "two party entries, three rows each")
        assertEquals(0, verdict.unresolved)
        assertEquals(9, verdict.attention, "1 + 1 + 1 + 6 — and the added tag counted once, not twice")
        assertEquals(
            "9 rows need attention",
            verdict.headline,
            "the number an author reads first must be the number of rows below it — and there is no honest " +
                "denominator to put it over, because `attention` counts rows from both sides and `judged` " +
                "counts only the expectation's",
        )
    }

    /** And the split that is the whole point of the bar: one of those nine is a regression; eight are shape. */
    @Test
    fun `the verdict separates shape from behaviour`() {
        val verdict = verdictOf(captured, reply, movedEntries = 2)

        assertEquals("only the value change alters what this scenario checks", verdict.shapeVersusBehaviour)
        assertEquals(
            listOf("1 value changed", "1 tag added", "1 tag missing", "2 entries moved"),
            verdict.parts,
        )
    }

    /**
     * **Two units, and they must not be confused.** `attention` counts ROWS; the phrase counts ENTRIES.
     * Feeding the entry count into the row count made the headline read "2 of 21 rows need attention" over
     * six rows that had all moved.
     */
    @Test
    fun `moved rows and moved entries are different numbers, and each is used where it belongs`() {
        val verdict = verdictOf(captured, reply, movedEntries = 2)

        assertEquals(6, verdict.movedRows)
        assertEquals(2, verdict.movedEntries)
        assertTrue("2 entries moved" in verdict.parts, "the phrase counts entries: ${verdict.parts}")
        assertTrue("9 rows" in verdict.headline, "the headline counts rows: ${verdict.headline}")
    }

    /**
     * **A row this diff cannot judge is not a row that passed.** One click of "Loosen → reference" made a red
     * row unjudgeable, dropped it out of every count, and the bar announced "✓ every assertion would now pass"
     * over an expectation asserting that a ClOrdID equals an OrdStatus.
     */
    @Test
    fun `an unjudgeable row is never reported as a pass`() {
        val draft =
            Expectation(
                listOf(
                    FieldExpectation(35, Matcher.Exact("8")),
                    FieldExpectation(39, Matcher.Reference("\${out.D.11}")),
                ),
                messageType = "8",
                mode = MatchMode.OPEN,
            )

        val verdict = verdictOf(draft, wireView(35 to "8", 39 to "2"))

        assertEquals(1, verdict.unknown)
        assertEquals(1, verdict.judged, "the reference row is not judged, so it is not in the denominator")
        assertTrue("only verifiable on a run" in verdict.headline, verdict.headline)
        assertTrue("1 not checkable here" in verdict.parts, verdict.parts.toString())
    }

    /**
     * **A step that asserts nothing is never a success.** It passes every run for ever while checking nothing,
     * and it is one Drop away from any failing expectation — so this claim outranks every other thing the
     * headline could say, including "every assertion would now pass", which is *technically true* of an
     * expectation with no assertions in it.
     */
    @Test
    fun `an expectation with no rows left is a warning, not a pass`() {
        val verdict = verdictOf(Expectation(emptyList(), messageType = "8"), reply)

        assertTrue(verdict.assertsNothing)
        assertEquals(0, verdict.judged, "there is nothing left to judge — that is exactly the danger")
        assertEquals(
            "⚠ nothing is asserted — this step would pass every run without checking anything",
            verdict.headline,
        )
    }

    /**
     * **A failing row that no bucket caught still counts.** A row reported MOVED for which the engine found no
     * safe re-ordering has no bracket, no Accept-new-order and no per-row fix — it fell out of the count
     * entirely, and the bar declared success over a step that fails.
     */
    @Test
    fun `a failing row no bucket catches is still counted, and the bar does not declare success`() {
        // A lone 452 row of a repeated tag whose reply has it elsewhere: reported MOVED, and no re-order is
        // offered, because moving it alone would swap which occurrence it asserts.
        val draft =
            Expectation(
                listOf(
                    FieldExpectation(453, Matcher.Exact("2")),
                    FieldExpectation(448, Matcher.Exact("FIRMA")),
                    FieldExpectation(452, Matcher.Exact("9")),
                ),
                messageType = "8",
                mode = MatchMode.OPEN,
            )
        val message = wireView(453 to "2", 448 to "FIRMA", 452 to "1", 448 to "FIRMB", 452 to "9")

        val verdict = verdictOf(draft, message)

        assertTrue(verdict.attention > 0, "a step that fails must never read as a pass: ${verdict.headline}")
        assertFalse(
            "would now pass" in verdict.headline,
            "the bar declared success over a failing step: ${verdict.headline}",
        )
    }

    // ----- the bulk button's guard -----------------------------------------------------------------------

    /**
     * **THE SAME RULE, AND THE VERDICT DID NOT KNOW IT.**
     *
     * `canAcceptShape` has known since Phase 1 that an unasserted tag is only a failure in STRICT. The verdict
     * — the sentence an author reads *first* — did not: it counted every tag the venue added into `attention`
     * whatever the mode. So a **fully passing OPEN step** whose venue sends three optional fields announced
     * *"3 rows need attention"*, painted itself FAILED in red, and invited the author to go and fix a step that
     * is not broken. OPEN's entire promise is that an unmentioned tag is ignored, and the one surface that
     * judges was the one telling them otherwise.
     *
     * Worse than the noise: the engine and the diff then disagree about whether the step passes, and the diff
     * is the one that is wrong. Two deciders, and this is the shape they take.
     *
     * The tags are still *shown* — blue, with «assert it» in the gutter, and still counted in [Verdict.parts]
     * as what they are. What they are not is a reason to call the step failed.
     */
    @Test
    fun `a passing OPEN step is not called failed because the venue added a tag`() {
        val draft =
            Expectation(listOf(FieldExpectation(35, Matcher.Exact("8"))), messageType = "8", mode = MatchMode.OPEN)
        val message = wireView(35 to "8", 39 to "2", 151 to "0")

        val verdict = verdictOf(draft, message)

        assertEquals(2, verdict.added, "the venue did add two tags, and the diff still says so")
        assertEquals(0, verdict.attention, "but in OPEN they are not a failure — they are the point of OPEN")
        assertFalse(verdict.needsAttention, "so the FAILED chip must not light, and the bar must not be red")
        assertTrue(verdict.headline.startsWith("✓"), "the headline agrees with the engine: ${verdict.headline}")
    }

    /** And in STRICT the very same message is a real failure, counted and announced — in the plural it earns. */
    @Test
    fun `the same added tags in STRICT are exactly the failure`() {
        val draft =
            Expectation(listOf(FieldExpectation(35, Matcher.Exact("8"))), messageType = "8", mode = MatchMode.STRICT)
        val message = wireView(35 to "8", 39 to "2", 151 to "0")

        val verdict = verdictOf(draft, message)

        assertEquals(2, verdict.attention)
        assertTrue(verdict.needsAttention)
        assertEquals("2 rows need attention", verdict.headline)
    }

    /**
     * **An unasserted tag is only a shape change in STRICT.** In OPEN an unmentioned tag is not a failure — it
     * is the entire point of OPEN — so counting every unasserted field as "a tag the venue added" put the bulk
     * button on a fully-passing OPEN step, and clicking it asserted every field the author had deliberately
     * left alone.
     */
    @Test
    fun `accept-every-shape-change is not offered on a passing OPEN step that merely ignores a tag`() {
        val draft =
            Expectation(listOf(FieldExpectation(35, Matcher.Exact("8"))), messageType = "8", mode = MatchMode.OPEN)
        val message = wireView(35 to "8", 39 to "2", 151 to "0")

        val rows = ScenarioReconcile.rows(draft, message, dictionary)

        assertTrue(rows.any { it.unasserted }, "the fixture must carry tags the expectation ignores")
        assertFalse(
            Verdict.canAcceptShape(rows, MatchMode.OPEN),
            "in OPEN those tags are not a failure — offering the button asserts what the author left alone",
        )
        assertTrue(
            Verdict.canAcceptShape(rows, MatchMode.STRICT),
            "in STRICT the very same tags ARE the failure, and the button is the fix",
        )
    }
}
