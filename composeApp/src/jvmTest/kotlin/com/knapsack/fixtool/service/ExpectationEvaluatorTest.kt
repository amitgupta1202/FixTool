package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.model.scenario.TemporalKind
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sequence model, pinned.
 *
 * An expectation is an ordered list of rows; the k-th row for tag T asserts the k-th occurrence of T
 * in the message. The tests that matter most here are not the matcher vocabulary — they are the ones
 * that hold the line against a **false green**: a step that passes while asserting a field nobody
 * wrote. Every previous model could produce one. This one structurally cannot, and these tests are
 * what keep it that way when it becomes inconvenient.
 */
class ExpectationEvaluatorTest {
    // A fixed "now" so temporal matchers are deterministic: 2026-06-30 09:15:02 UTC.
    private val fixedNow = { Instant.parse("2026-06-30T09:15:02Z") }

    private fun eval(
        view: MessageView,
        vararg fields: FieldExpectation,
        mode: MatchMode = MatchMode.OPEN,
        resolver: (String) -> String? = { null },
    ) = ExpectationEvaluator.evaluate(view, Expectation(fields.toList(), mode = mode), resolver, fixedNow)

    private fun fe(tag: Int, matcher: Matcher) = FieldExpectation(tag, matcher)

    // ------------------------------------------------------------------ the matcher vocabulary

    @Test
    fun `exact matches and mismatches`() {
        val v = wireView(35 to "8", 150 to "F")
        assertTrue(eval(v, fe(35, Matcher.Exact("8"))).single().passed)
        val miss = eval(v, fe(150, Matcher.Exact("0"))).single()
        assertFalse(miss.passed)
        assertEquals("F", miss.actual)
        assertEquals("0", miss.expected)
        assertEquals(TagStatus.VALUE, miss.status)
    }

    @Test
    fun `presence and absent`() {
        val v = wireView(37 to "OID-9")
        assertTrue(eval(v, fe(37, Matcher.Presence)).single().passed)
        assertFalse(eval(v, fe(99, Matcher.Presence)).single().passed)
        assertTrue(eval(v, fe(58, Matcher.Absent)).single().passed)
        val present = eval(v, fe(37, Matcher.Absent)).single()
        assertFalse(present.passed)
        assertEquals("OID-9", present.actual)
    }

    @Test
    fun `oneOf regex numeric and temporal`() {
        val v = wireView(39 to "2", 11 to "ORD-abc123", 44 to "1.23450", 60 to "20260630-09:15:30.000")
        assertTrue(eval(v, fe(39, Matcher.OneOf(listOf("1", "2")))).single().passed)
        assertFalse(eval(v, fe(39, Matcher.OneOf(listOf("0", "8")))).single().passed)
        assertTrue(eval(v, fe(11, Matcher.Regex("ORD-[a-z0-9]+"))).single().passed)
        assertFalse(eval(v, fe(11, Matcher.Regex("EXEC-\\d+"))).single().passed)
        // Tolerance 0 still ignores formatting: both sides parse as numbers.
        assertTrue(eval(v, fe(44, Matcher.Numeric(1.2345, 0.0))).single().passed)
        assertTrue(eval(v, fe(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60))).single().passed)
        assertFalse(eval(v, fe(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 5))).single().passed)
        assertTrue(eval(v, fe(60, Matcher.Temporal(TemporalKind.TODAY))).single().passed)
    }

    @Test
    fun `reference resolves against the run scope`() {
        val v = wireView(11 to "ORD-1")
        assertTrue(eval(v, fe(11, Matcher.Reference("\${out.D.11}")), resolver = { "ORD-1" }).single().passed)
        assertFalse(eval(v, fe(11, Matcher.Reference("\${out.D.11}")), resolver = { "ORD-2" }).single().passed)
        // Unresolvable is a failure, not a pass — a reference nobody can resolve has checked nothing.
        assertFalse(eval(v, fe(11, Matcher.Reference("\${nope}")), resolver = { null }).single().passed)
    }

    @Test
    fun `an uncompilable pattern fails its own row and says why`() {
        val r = eval(wireView(37 to "EXEC-9"), fe(37, Matcher.Regex("EXEC-["))).single()
        assertFalse(r.passed)
        assertEquals(TagStatus.INVALID, r.status)
        assertTrue(r.expected.contains("Unclosed character class"), r.expected)
    }

    // ------------------------------------------------------- the case the old model could not do

    /**
     * The message that broke the group-path model: the same firm, twice, under two different roles.
     * Both entries carry `448=FIRMA`, so an identity cannot name one of them. Position can.
     */
    private val twoPartyEntries =
        listOf(
            35 to "8", 11 to "ORD-1", 39 to "2",
            453 to "2",
            448 to "FIRMA", 447 to "D", 452 to "1", // executing firm
            448 to "FIRMA", 447 to "D", 452 to "4", // clearing firm
        )

    private fun twoPartyExpectation(firstRole: String, secondRole: String) =
        listOf(
            fe(35, Matcher.Exact("8")), fe(11, Matcher.Exact("ORD-1")), fe(39, Matcher.Exact("2")),
            fe(453, Matcher.Exact("2")),
            fe(448, Matcher.Exact("FIRMA")), fe(447, Matcher.Exact("D")), fe(452, Matcher.Exact(firstRole)),
            fe(448, Matcher.Exact("FIRMA")), fe(447, Matcher.Exact("D")), fe(452, Matcher.Exact(secondRole)),
        )

    @Test
    fun `two entries of the same firm are asserted separately and correctly`() {
        val results = eval(wireView(twoPartyEntries), *twoPartyExpectation("1", "4").toTypedArray())

        assertTrue(results.all { it.passed }, results.filterNot { it.passed }.toString())
        // The two 452 rows refer to different occurrences, and say so.
        val roles = results.filter { it.tag == 452 }
        assertEquals(listOf(0, 1), roles.map { it.occurrence })
        assertEquals(listOf("1", "4"), roles.map { it.actual })
    }

    @Test
    fun `asserting the roles the wrong way round fails — it does not find the entry that fits`() {
        // This is the false green, and the only test in this file that really matters. The expectation
        // says the first party's role is 4 and the second's is 1; the message says the opposite. Both
        // values exist in the message, and a pairing rule clever enough to "find the entry that
        // matches" would report a serene green while asserting the wrong entries.
        val results = eval(wireView(twoPartyEntries), *twoPartyExpectation("4", "1").toTypedArray())

        val roles = results.filter { it.tag == 452 }
        assertTrue(roles.none { it.passed }, "pairing must never re-aim a row onto the occurrence that satisfies it")
        assertEquals(listOf("1", "4"), roles.map { it.actual }, "each row keeps the occurrence its position names")
    }

    @Test
    fun `a venue that swaps its two party entries fails, in both modes`() {
        val swapped =
            listOf(
                35 to "8", 11 to "ORD-1", 39 to "2",
                453 to "2",
                448 to "FIRMA", 447 to "D", 452 to "4", // clearing firm now first
                448 to "FIRMA", 447 to "D", 452 to "1",
            )
        for (mode in listOf(MatchMode.OPEN, MatchMode.STRICT)) {
            val results = eval(wireView(swapped), *twoPartyExpectation("1", "4").toTypedArray(), mode = mode)
            assertTrue(
                results.any { it.tag == 452 && !it.passed },
                "$mode let a reordering of the party entries pass — the assertions now check other entries",
            )
        }
    }

    // ------------------------------------------------------------------------------- OPEN

    /**
     * OPEN, stated once, in the form it was agreed: **the expectation must be a subsequence of the
     * message.** Given a reply carrying tags 1,2,3,4,5 in that order, an expectation listing
     * [1,3,5] or [1,2,3] or [1,2,4] holds — anything the reply carries in between is ignored. An
     * expectation listing [1,6,3] does not (there is no 6). Nor does [1,3,2]: the reply puts 2 before
     * 3, so an expectation naming 3 before 2 has described a message the venue did not send.
     *
     * That last one is the whole cost of this rule, and it is deliberate. A hand-written expectation
     * whose rows are not in the order the venue sends them goes red — and the author, not the tool,
     * decides what the order means. Capture always seeds rows in wire order, so a captured scenario is
     * a subsequence of its own golden by construction.
     */
    @Test
    fun `OPEN accepts exactly the subsequences of the reply, and nothing else`() {
        val reply = wireView(1 to "a", 2 to "b", 3 to "c", 4 to "d", 5 to "e")
        val values = mapOf(1 to "a", 2 to "b", 3 to "c", 4 to "d", 5 to "e", 6 to "f")

        fun rows(vararg tags: Int) = tags.map { fe(it, Matcher.Exact(values.getValue(it))) }.toTypedArray()
        fun holds(vararg tags: Int) = eval(reply, *rows(*tags)).all { it.passed }

        assertTrue(holds(1, 3, 5), "a subsequence: the reply may carry 2 and 4 in between")
        assertTrue(holds(1, 2, 3))
        assertTrue(holds(1, 2, 4))
        assertTrue(holds(1, 2, 3, 4, 5), "the whole thing, in order")

        assertFalse(holds(1, 6, 3), "6 is not in the reply at all")
        assertFalse(holds(1, 3, 2), "the reply puts 2 before 3 — an expectation may not reorder it")
    }

    @Test
    fun `OPEN ignores a tag the expectation never mentions`() {
        val v = wireView(35 to "8", 39 to "2", 2376 to "Y")
        val results = eval(v, fe(35, Matcher.Exact("8")), fe(39, Matcher.Exact("2")))
        assertTrue(results.all { it.passed })
        assertEquals(2, results.size, "an unasserted tag is not a result row in OPEN")
    }

    @Test
    fun `OPEN is a subsequence, so two asserted rows that swap order fail`() {
        // Set semantics would pass this. The two entries swapped, and the assertion would be none the
        // wiser — which is exactly the hole OPEN's relative order closes.
        val v = wireView(35 to "8", 58 to "second", 39 to "first")
        val results = eval(v, fe(39, Matcher.Exact("first")), fe(58, Matcher.Exact("second")))

        assertTrue(results.any { !it.passed }, "OPEN must enforce the order of the rows it lists")
    }

    @Test
    fun `OPEN tolerates an extra occurrence, and the count tag is what catches it`() {
        // A fifth party arrives when four were captured. OPEN pairs the rows it has and ignores the
        // rest — so the assertion that notices is the count tag, which the seeder always seeds.
        val threeParties =
            listOf(
                453 to "3",
                448 to "A", 452 to "1",
                448 to "B", 452 to "4",
                448 to "C", 452 to "7",
            )
        val expectationForTwo =
            listOf(
                fe(453, Matcher.Exact("2")),
                fe(448, Matcher.Exact("A")), fe(452, Matcher.Exact("1")),
                fe(448, Matcher.Exact("B")), fe(452, Matcher.Exact("4")),
            )

        val results = eval(wireView(threeParties), *expectationForTwo.toTypedArray())
        val failed = results.filterNot { it.passed }

        assertEquals(listOf(453), failed.map { it.tag }, "NoPartyIDs is the row that sees the extra entry")
        assertEquals("3", failed.single().actual)
    }

    // ----------------------------------------------------------------------------- STRICT

    @Test
    fun `STRICT reports a tag the reply carries and the expectation does not`() {
        val v = wireView(35 to "8", 39 to "2", 2376 to "Y")
        val results = eval(v, fe(35, Matcher.Exact("8")), fe(39, Matcher.Exact("2")), mode = MatchMode.STRICT)

        val extra = results.single { !it.passed }
        assertEquals(2376, extra.tag)
        assertEquals(TagStatus.UNEXPECTED, extra.status)
        assertEquals("Y", extra.actual)
    }

    @Test
    fun `STRICT passes a message shaped exactly as captured, groups and all`() {
        val results = eval(wireView(twoPartyEntries), *twoPartyExpectation("1", "4").toTypedArray(), mode = MatchMode.STRICT)
        assertTrue(results.all { it.passed }, results.filterNot { it.passed }.toString())
    }

    @Test
    fun `a tag the venue stopped sending is missing, not merely mismatched`() {
        val results = eval(wireView(35 to "8"), fe(35, Matcher.Exact("8")), fe(58, Matcher.Exact("ok")))

        val gone = results.single { it.tag == 58 }
        assertFalse(gone.passed)
        assertEquals(TagStatus.MISSING, gone.status)
        assertEquals(null, gone.actual)
    }

    @Test
    fun `a value that is in the reply but in the wrong place reads as moved, not missing`() {
        // The difference matters: "the venue dropped a field" is a regression; "the venue put it
        // somewhere else" is a reshape a click can accept. Reporting both as "missing" hides which is
        // which, and sends the reader hunting for a field that is right there in the message.
        val v = wireView(58 to "note", 39 to "2")
        val results = eval(v, fe(39, Matcher.Exact("2")), fe(58, Matcher.Exact("note")))

        val moved = results.single { it.tag == 58 }
        assertFalse(moved.passed)
        assertEquals(TagStatus.MOVED, moved.status)
    }

    @Test
    fun `two rows on the same tag whose values are swapped both fail on value, not on position`() {
        // Both rows still pair — the first 452 row with the first 452, the second with the second — so
        // this is two value mismatches, not a move. The distinction is what tells an author "the venue
        // swapped its entries" apart from "the venue moved a field elsewhere in the message".
        val results = eval(wireView(452 to "4", 452 to "1"), fe(452, Matcher.Exact("1")), fe(452, Matcher.Exact("4")))

        assertTrue(results.none { it.passed })
        assertEquals(listOf(TagStatus.VALUE, TagStatus.VALUE), results.map { it.status })
        assertEquals(listOf(0, 1), results.map { it.occurrence })
    }

    // ------------------------------------------------------------------------- the envelope

    private val withEnvelope =
        wireView(8 to "FIX.4.4", 9 to "212", 35 to "8", 34 to "5", 49 to "VENUE", 56 to "ME", 52 to "x", 39 to "2", 10 to "071")

    @Test
    fun `the envelope is never an unexpected extra`() {
        val results = eval(withEnvelope, fe(35, Matcher.Exact("8")), fe(39, Matcher.Exact("2")), mode = MatchMode.STRICT)

        assertTrue(results.all { it.passed }, "STRICT must not call the session's own envelope an extra: $results")
        assertEquals(setOf(35, 39), results.map { it.tag }.toSet())
    }

    @Test
    fun `but a row that names an envelope tag is still evaluated`() {
        // Never *seeded* is not never *checkable*. A gap-fill or resend scenario asserts MsgSeqNum by
        // hand; filtering the envelope out of the wire before pairing made that row permanently red
        // with "the reply has no such tag" — while the raw pane sat there showing 34=5.
        assertTrue(eval(withEnvelope, fe(34, Matcher.Exact("5"))).single().passed)

        val wrong = eval(withEnvelope, fe(34, Matcher.Exact("9"))).single()
        assertFalse(wrong.passed)
        assertEquals("5", wrong.actual, "it must compare against the value that is actually there")
        assertEquals(TagStatus.VALUE, wrong.status)
    }

    @Test
    fun `a reordered tag in STRICT is one problem, reported once`() {
        // The row and the wire slot are the same field. Reporting the row as MOVED *and* its slot as
        // UNEXPECTED gave CI two contradictory lines for one tag: "expected <absent>, actual Y" beside
        // "expected presence — present, but not in this position".
        val v = wireView(37 to "Y", 11 to "X")
        val results = eval(v, fe(11, Matcher.Exact("X")), fe(37, Matcher.Presence), mode = MatchMode.STRICT)

        val on37 = results.filter { it.tag == 37 }
        assertEquals(1, on37.size, "one field, one problem: $results")
        assertEquals(TagStatus.MOVED, on37.single().status)
    }

    // ------------------------------------------------------------------ addressing the failure

    @Test
    fun `every row reports which row it is and which occurrence it checked`() {
        val results = eval(wireView(twoPartyEntries), *twoPartyExpectation("1", "9").toTypedArray())

        val failed = results.single { !it.passed }
        assertEquals(452, failed.tag)
        assertEquals(9, failed.index, "the row's position in the expectation")
        assertEquals(1, failed.occurrence, "the second 452 in the message")
    }
}
