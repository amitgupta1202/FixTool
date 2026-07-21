package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.ScenarioReconcile.MoveResult
import com.knapsack.fixtool.service.compare.EntrySource
import com.knapsack.fixtool.service.compare.GroupOverlay
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The move rule, entire:**
 *
 * > *No row is ever separated from the entry it belongs to; a row of a repeated tag may cross a same-tag
 * > sibling only if its whole entry crosses with it; and no move may land strictly inside another entry.*
 *
 * The plan this was built from stated it as "a move is legal iff every repeated tag's occurrence mapping is
 * preserved", and that is **wrong** in a way worth writing down: it refuses every entry swap. When FIRMA's
 * party moves past FIRMB's, FIRMA's `448` *becomes the second* `448` — the occurrence mapping is precisely
 * what changes, and that is the move the design calls safe, because FIRMA's `452` becomes the second `452`
 * in the same step. The two rows that jointly say "FIRMA holds role 1" moved in lockstep, so they still say
 * it. A *lone* row crossing its sibling has no such partner, and it re-aims two assertions in silence.
 *
 * That is the whole difference, and every case below is one side of it.
 */
class MoveRuleTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private fun exp(vararg fields: Pair<Int, String>) =
        Expectation(fields.map { (tag, value) -> FieldExpectation(tag, Matcher.Exact(value)) }, messageType = "8")

    private fun overlayFor(draft: Expectation) = GroupOverlay.of(draft, dictionary)

    /** `453=2` and two whole parties — rows 1..3 and 4..6. */
    private fun twoParties() =
        exp(453 to "2", 448 to "FIRMA", 447 to "D", 452 to "1", 448 to "FIRMB", 447 to "D", 452 to "4")

    private fun threeParties() =
        exp(
            453 to "3",
            448 to "FIRMA", 447 to "D", 452 to "1",
            448 to "FIRMB", 447 to "D", 452 to "4",
            448 to "FIRMC", 447 to "D", 452 to "7",
        )

    private fun tags(result: MoveResult) = (result as MoveResult.Applied).expectation.fields.map { it.tag }

    private fun values(result: MoveResult) =
        (result as MoveResult.Applied).expectation.fields.map { (it.matcher as Matcher.Exact).value }

    private fun why(result: MoveResult): String {
        assertTrue(result is MoveResult.Refused, "expected a refusal, got $result")
        return result.why
    }

    // ----- the scalar row, which moves freely ------------------------------------------------------------

    /**
     * The OPEN false red, fixed by a drag. An expectation is a *subsequence* of the reply, so a hand-authored
     * one that lists `11` before `37` when the venue sends `37` first describes a message nobody sent — a real
     * failure, and a tedious one, because the rows are perfectly correct and merely in the wrong order.
     *
     * A tag that appears once has no same-tag sibling to cross and belongs to no entry. Nothing about moving
     * it can re-aim anything, so nothing stops it.
     */
    @Test
    fun `a row whose tag appears once moves anywhere`() {
        val draft = exp(11 to "ORD-1", 37 to "OID-1", 39 to "2")

        val moved = ScenarioReconcile.moveRow(draft, overlayFor(draft), from = 0, to = 1)

        assertEquals(listOf(37, 11, 39), tags(moved))
        assertEquals(listOf("OID-1", "ORD-1", "2"), values(moved), "the assertions travel with their rows")
    }

    // ----- the lone row of a repeated tag, which does not ------------------------------------------------

    /**
     * THE FORBIDDEN MOVE. Move the second `452` above the first and the two rows swap which occurrence each
     * checks — the row reading "the clearing firm's role is 4" quietly comes to mean "the executing firm's",
     * while still saying `452 exact 4` on screen. No value was edited. No warning was possible. It is the
     * assert-the-wrong-field failure the whole sequence model exists to make structurally impossible.
     */
    @Test
    fun `a lone row of a repeated tag cannot cross its sibling`() {
        val draft = twoParties()

        val why = why(ScenarioReconcile.moveRow(draft, overlayFor(draft), from = 6, to = 3))

        assertTrue("swap which occurrence" in why, why)
        assertTrue("still saying exactly the same thing on screen" in why, why)
        assertTrue("Move the whole entry" in why, "a refusal must leave the author somewhere to go: $why")
    }

    /**
     * **And the guard that catches what no entry brackets.** `448, 11, 448` has no repeating run for the row
     * heuristic to find and no group for the dictionary to name, so every structural rule above is silent —
     * and the two `448` rows can still be made to trade occurrences. The occurrence mapping is what refuses
     * it, which is why that check is not redundant with the entry rules.
     */
    @Test
    fun `two rows of a repeated tag that no entry brackets still cannot cross`() {
        val draft = exp(448 to "FIRMA", 11 to "ORD-1", 448 to "FIRMB")
        assertTrue(ScenarioReconcile.entries(draft).isEmpty(), "the fixture must have no entry to hide behind")

        val why = why(ScenarioReconcile.moveRow(draft, overlayFor(draft), from = 2, to = 0))

        assertTrue("swap which occurrence" in why, why)
    }

    // ----- entries are units ----------------------------------------------------------------------------

    /** A field may reorder *within* its entry: a venue that sends 452 before 447 changed shape, not behaviour. */
    @Test
    fun `a row moves freely inside its own entry`() {
        val draft = exp(453 to "1", 448 to "FIRMA", 447 to "D", 452 to "1")

        val moved = ScenarioReconcile.moveRow(draft, overlayFor(draft), from = 3, to = 2)

        assertEquals(listOf(453, 448, 452, 447), tags(moved))
    }

    /**
     * A row that leaves its entry begins asserting a field in a different one. Here the occurrence mapping
     * cannot see it — `447` appears once in each entry and the second party carries none — so the rule that
     * refuses this is the entry rule, and without it the re-judge would show the row going quietly green.
     */
    @Test
    fun `a row cannot leave its entry for another one`() {
        val draft = exp(453 to "2", 448 to "FIRMA", 447 to "D", 452 to "1", 448 to "FIRMB", 452 to "4")

        val why = why(ScenarioReconcile.moveRow(draft, overlayFor(draft), from = 2, to = 5))

        assertTrue("belongs to the entry" in why, why)
        assertTrue("Move the entry instead" in why, why)
    }

    /** An entry begins at its delimiter. A row above it would leave the entry starting somewhere else. */
    @Test
    fun `the row an entry begins with cannot move within it`() {
        val draft = twoParties()

        val why = why(ScenarioReconcile.moveRow(draft, overlayFor(draft), from = 1, to = 3))

        assertTrue("An entry begins at tag 448" in why, why)
        assertTrue("To move the entry, move the entry" in why, why)
    }

    /** No venue sends a top-level field wedged between two party entries, and the tool will not build one. */
    @Test
    fun `a field cannot be dropped inside a group`() {
        val draft =
            exp(453 to "2", 448 to "FIRMA", 447 to "D", 452 to "1", 448 to "FIRMB", 447 to "D", 452 to "4", 58 to "ok")

        val why = why(ScenarioReconcile.moveRow(draft, overlayFor(draft), from = 7, to = 4))

        assertTrue("cannot sit inside a repeating group" in why, why)
        assertTrue("no venue sends a message shaped like that" in why, why)
    }

    // ----- the entry move, which is the same-tag crossing made safe --------------------------------------

    /**
     * THE SAFE MOVE, and the one the plan's original rule would have refused. FIRMA's `448` becomes the
     * *second* `448` — and FIRMA's `452` becomes the second `452` in the same step. The pair that says
     * "FIRMA holds role 1" is intact; it now says it about the party that arrives second.
     */
    @Test
    fun `a whole entry crosses its sibling, and every row of it travels`() {
        val draft = twoParties()

        val moved = ScenarioReconcile.moveEntry(draft, overlayFor(draft), entry = 1..3, toSlot = 1)

        assertEquals(
            listOf("2", "FIRMB", "D", "4", "FIRMA", "D", "1"),
            values(moved),
            "FIRMA still sits beside role 1 — the entry moved, it was not torn apart",
        )
    }

    /** Not merely a swap with the neighbour: an entry may go to any slot of its group. */
    @Test
    fun `an entry moves to any slot among its siblings`() {
        val draft = threeParties()

        val moved = ScenarioReconcile.moveEntry(draft, overlayFor(draft), entry = 7..9, toSlot = 0)

        assertEquals(
            listOf("3", "FIRMC", "D", "7", "FIRMA", "D", "1", "FIRMB", "D", "4"),
            values(moved),
            "the third party is now the first, whole",
        )
    }

    /**
     * THE ROTATION TRAP. In a group of three parties the window `447,452,448` has an identically-shaped
     * neighbour, and neither window is a party — each is the tail of one entry welded to the head of the
     * next. Swapping two of them welds FIRMA's PartyID to FIRMC's role while every row still reads the same
     * on screen, and the expectation's *tag sequence comes out byte-identical*, so a test that checks tag
     * order sees nothing wrong. A window is not an entry.
     */
    @Test
    fun `a rotation window is not an entry, and cannot move`() {
        val draft = threeParties()

        val why = why(ScenarioReconcile.moveEntry(draft, overlayFor(draft), entry = 2..4, toSlot = 0))

        assertTrue("not an entry" in why, why)
        assertTrue("welded to the head of the next" in why, why)
    }

    /**
     * A run of the same tag that only the ROW HEURISTIC brackets is not a group, and its "entries" are not
     * entries — a period of 1 repeats like any other period, so `9002, 9002` looks exactly like a group of
     * two one-field entries and is nothing of the kind. Nothing travels with such a row, so its "entry move"
     * is the per-row arrow in a block's clothes.
     *
     * The fixture is a **venue-custom** group on purpose, which is the case the heuristic exists for (see
     * `GroupOverlay.guessed`). It used to be `NoSecurityAltID(454/455)`, which worked only while the bundled
     * FIX 4.4 dictionary was a 93-field demo subset that had never heard of it; the whole dictionary defines
     * it, the entries came back `DICTIONARY`, and the guard below caught the fixture testing the wrong rule.
     * A tag in the 9000s cannot be learned by any standard dictionary, so this fixture stays heuristic.
     */
    @Test
    fun `a single-row heuristic entry of a repeated tag is the per-row arrow in a block's clothes`() {
        val draft = exp(9001 to "2", 9002 to "ALT-1", 9002 to "ALT-2")
        assertEquals(
            listOf(EntrySource.HEURISTIC, EntrySource.HEURISTIC),
            overlayFor(draft).entries.map { it.source },
            "if the dictionary has learned this group, this fixture no longer tests the heuristic",
        )

        val why = why(ScenarioReconcile.moveEntry(draft, overlayFor(draft), entry = 1..1, toSlot = 1))

        assertTrue("swap which occurrence" in why, why)
    }

    /**
     * **And the same shape, where the DICTIONARY says it is a group, moves.** `NoMDEntryTypes` is delimited by
     * `MDEntryType` and holds nothing else, so each of its entries is one row — a real entry that happens to
     * be one field wide, and the entry drag belongs to it like any other.
     *
     * The occurrence mapping changes, and that is not the objection it looks like: *that is what an entry move
     * is*. FIRMA's `448` becomes the second `448` too. The danger the per-row arrow carries — a row arriving
     * beside another entry's rows and being silently re-aimed onto them — cannot arise where the entry has no
     * other rows to be separated from. This was refused, with a sentence about re-aiming assertions, which
     * took the entry drag away from every one-field group and blamed the move for something it does not do.
     */
    @Test
    fun `a single-row entry the dictionary defines is a real entry, and it moves`() {
        val draft =
            Expectation(
                fields =
                    listOf(
                        FieldExpectation(267, Matcher.Exact("2")),
                        FieldExpectation(269, Matcher.Exact("0")),
                        FieldExpectation(269, Matcher.Exact("1")),
                    ),
                messageType = "V",
            )
        val overlay = overlayFor(draft)
        assertEquals(
            listOf(EntrySource.DICTIONARY, EntrySource.DICTIONARY),
            overlay.entries.map { it.source },
            "the dictionary must be the one bracketing these, or this test is about the heuristic instead",
        )

        val moved = ScenarioReconcile.moveEntry(draft, overlay, entry = 1..1, toSlot = 1)

        assertEquals(listOf("2", "1", "0"), values(moved), "the venue sends the entries the other way round now")
    }

    /** A refusal always says something. An entry at the end of its group has nowhere to go, and says so. */
    @Test
    fun `an entry at the end of its group says there is nowhere to go`() {
        val draft = twoParties()

        val why = why(ScenarioReconcile.moveEntry(draft, overlayFor(draft), entry = 4..6, toSlot = 2))

        assertTrue("nowhere" in why, why)
    }

    /**
     * ...and it says where the entry actually *is*. A middle entry dropped past the end of its group was told
     * it "is already last in its group", which it plainly is not: a refusal that describes the wrong situation
     * is worse than one that says nothing, because the author believes it.
     */
    @Test
    fun `an entry refused a slot outside its group is told where it really sits`() {
        val draft = threeParties()

        val why = why(ScenarioReconcile.moveEntry(draft, overlayFor(draft), entry = 4..6, toSlot = 3))

        assertTrue("3 entries" in why && "number 2" in why, "the middle entry is neither first nor last: $why")
    }

    // ----- the properties -------------------------------------------------------------------------------

    /**
     * Over every (row, destination) a three-party expectation admits: **every move that is applied leaves
     * every row asserting the occurrence it asserted before**, and every move that would not is refused.
     * This is the per-row half of the rule, exhaustively.
     */
    @Test
    fun `no applied row move ever changes which occurrence a row asserts`() {
        val draft = threeParties()
        val overlay = overlayFor(draft)
        val before = occurrenceOf(draft)
        var applied = 0

        for (from in draft.fields.indices) {
            for (to in draft.fields.indices) {
                when (val result = ScenarioReconcile.moveRow(draft, overlay, from, to)) {
                    is MoveResult.Refused -> assertTrue(result.why.isNotBlank(), "a refusal is never silent")
                    is MoveResult.Applied -> {
                        applied++
                        val after = occurrenceOf(result.expectation)
                        assertEquals(
                            before,
                            after,
                            "moving row $from to $to re-aimed an assertion: ${result.expectation.fields}",
                        )
                    }
                }
            }
        }
        assertTrue(applied > 0, "if nothing is ever applied, this test proves nothing")
    }

    /** Every entry move keeps every entry's rows together, and every row with the entry it describes. */
    @Test
    fun `no applied entry move ever tears an entry`() {
        val draft = threeParties()
        val overlay = overlayFor(draft)
        val parties = overlay.entries.map { it.rows }
        var applied = 0

        for (entry in parties) {
            for (slot in 0..2) {
                val result = ScenarioReconcile.moveEntry(draft, overlay, entry, slot)
                if (result !is MoveResult.Applied) continue
                applied++
                // Every party still reads (firm, D, role) as a contiguous, intact triple.
                val fields = result.expectation.fields
                assertEquals(10, fields.size)
                for (start in listOf(1, 4, 7)) {
                    assertEquals(listOf(448, 447, 452), fields.subList(start, start + 3).map { it.tag })
                }
                val firms = listOf(1, 4, 7).map { (fields[it].matcher as Matcher.Exact).value }
                val roles = listOf(3, 6, 9).map { (fields[it].matcher as Matcher.Exact).value }
                assertEquals(
                    firms.map { mapOf("FIRMA" to "1", "FIRMB" to "4", "FIRMC" to "7")[it] },
                    roles,
                    "a firm arrived beside another firm's role — the entry was torn: $fields",
                )
            }
        }
        assertTrue(applied > 0, "if nothing is ever applied, this test proves nothing")
    }

    /** Every row, by the only address it has: its tag and which occurrence of that tag it is. */
    private fun occurrenceOf(draft: Expectation): Map<Matcher, Pair<Int, Int>> {
        val seen = mutableMapOf<Int, Int>()
        return draft.fields.associate { row ->
            val k = seen.merge(row.tag, 1, Int::plus)!! - 1
            row.matcher to (row.tag to k)
        }
    }
}
