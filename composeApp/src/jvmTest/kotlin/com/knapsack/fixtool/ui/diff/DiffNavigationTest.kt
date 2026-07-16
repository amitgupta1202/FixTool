package com.knapsack.fixtool.ui.diff

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.compare.ChunkKind
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.service.wireView
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The cursor, the chunk keys, and the one defect that only shows up on the second keypress.**
 *
 * Everything here is the *model* half of Phase 4's keyboard — no Compose, because none of it is drawing. That
 * is deliberate: `↑/↓`, `n`/`p` and `alt+↑/↓` were the reason the display list had to come out of the
 * composable, and a keyboard whose behaviour can only be asserted through a rendered tree is a keyboard whose
 * defects hide in the tree.
 */
class DiffNavigationTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val arrival: Instant = Instant.parse("2026-07-14T09:35:44Z")

    private fun session(expectation: Expectation, message: MessageView) =
        ReconcileSession(
            expectation,
            ReferenceMessage.live(message, ReferenceMessage.Provenance.THIS_RUN, "this run", arrival),
            dictionary,
        )

    /** Three parties: the shape where tearing an entry is possible, so the shape a move test must use. */
    private val threeParties =
        Expectation(
            listOf(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(453, Matcher.Exact("3")),
                FieldExpectation(448, Matcher.Exact("FIRMA")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("1")),
                FieldExpectation(448, Matcher.Exact("FIRMB")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("4")),
                FieldExpectation(448, Matcher.Exact("FIRMC")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("7")),
            ),
            messageType = "8",
            mode = MatchMode.STRICT,
        )
    private val asCaptured =
        wireView(
            35 to "8",
            453 to "3",
            448 to "FIRMA",
            447 to "D",
            452 to "1",
            448 to "FIRMB",
            447 to "D",
            452 to "4",
            448 to "FIRMC",
            447 to "D",
            452 to "7",
        )

    /** A hand-authored step whose rows are not in the venue's order — the OPEN `moved` false red, and its fix. */
    private val outOfOrder =
        Expectation(
            listOf(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(11, Matcher.Exact("ORD-1")),
                FieldExpectation(37, Matcher.Presence),
            ),
            messageType = "8",
            mode = MatchMode.OPEN,
        )
    private val wireOrder = wireView(35 to "8", 37 to "OID-4417", 11 to "ORD-1", 2376 to "Y")

    // ----- the defect that needs two keypresses to show itself ---------------------------------------------

    /**
     * **`alt+↓` twice must move the same row twice.**
     *
     * The selection is an *index*, and a move is precisely the thing that changes indices. Leave the selection
     * where it was and the second press moves whatever slid up into the vacated slot — so the author presses
     * one key twice, watching one highlight, and moves two different rows. (Delete the re-anchoring in
     * `moveSelection` and this test fails on the final assertion, with row 11 stranded at index 2.)
     */
    @Test
    fun `alt-down twice moves the same row two places, not two different rows`() {
        val s = session(outOfOrder, wireOrder)
        s.selection = DiffSelection.Row(0) // tag 35

        assertIs<EditResult.Applied>(s.moveSelection(down = true))
        assertEquals(DiffSelection.Row(1), s.selection, "the selection travelled with the row it moved")
        assertIs<EditResult.Applied>(s.moveSelection(down = true))

        assertEquals(DiffSelection.Row(2), s.selection)
        assertEquals(
            listOf(11, 37, 35),
            s.draft.fields.map { it.tag },
            "tag 35 went down twice — the same row, moved by the same key, pressed twice",
        )
    }

    /** Off the end of the list is not a refusal. It is no move, and it has nothing to say about itself. */
    @Test
    fun `alt-up on the first row does nothing, and says nothing`() {
        val s = session(outOfOrder, wireOrder)
        s.selection = DiffSelection.Row(0)

        assertEquals(EditResult.Unchanged, s.moveSelection(down = false))

        assertNull(s.refusal, "a row that is already first is not a forbidden move; it is no move")
        assertEquals(0, s.staged)
    }

    /** A field the reply carries that no row asserts is not a row of the expectation. There is nothing to move. */
    @Test
    fun `alt-down on an unasserted field is not a move at all`() {
        val s = session(outOfOrder, wireOrder)
        // The ghost's left is a gap too — the genuine extra is the one that is not spoken for.
        val added = s.model.lines.single { it.leftIsGap && !it.row.ghost }
        s.selection = assertNotNull(added.selection)
        assertIs<DiffSelection.Added>(s.selection)

        assertEquals(EditResult.Unchanged, s.moveSelection(down = true))
        assertNull(s.refusal)
    }

    // ----- the entry is the unit, and the refusal is a sentence ---------------------------------------------

    /** `alt+↓` on a band moves the whole party, and the selection lands on it where it now is. */
    @Test
    fun `alt-down on an entry band moves the entry, whole, and the cursor goes with it`() {
        val s = session(threeParties, asCaptured)
        val firmA = s.model.overlay.entries.first { it.rows.first == 2 }
        s.selection = DiffSelection.Entry(firmA.rows)

        assertIs<EditResult.Applied>(s.moveSelection(down = true))

        assertEquals(
            listOf("FIRMB", "FIRMA", "FIRMC"),
            s.draft.fields.filter { it.tag == 448 }.map { (it.matcher as Matcher.Exact).value },
            "FIRMA changed places with FIRMB, and took all of itself with it",
        )
        assertEquals(DiffSelection.Entry(5..7), s.selection, "the highlight is on the party that travelled")
        // The pair that jointly says "FIRMA holds role 1" is intact: FIRMA is now the 2nd 448, and role 1 the
        // 2nd 452. That is the whole of D1, asserted rather than assumed.
        val roles = s.draft.fields.filter { it.tag == 452 }.map { (it.matcher as Matcher.Exact).value }
        assertEquals(listOf("4", "1", "7"), roles)
    }

    /**
     * **A refused move from the keyboard says why, inline — because there is no cursor to say it at.**
     *
     * `452` is the last row of FIRMA's party. One step down and it has left its entry: it would be listed
     * among FIRMB's rows while still reading `452 exact 1`, and the pair that jointly says *"FIRMA holds role
     * 1"* would be broken in half. The engine has always had this sentence; until now `EditOp` computed it and
     * threw it away.
     *
     * (The *occurrence-swap* refusal — the mockup's `452#2` crossing `452#1` — cannot be reached by a single
     * `alt` press inside a dictionary-bracketed group, because the entry boundary is hit first, and that is
     * the engine being right. It is reached by a drag, and `DiffDragTest` is where it is pinned.)
     */
    @Test
    fun `alt-down out of an entry is refused, and the sentence names what would have broken`() {
        val s = session(threeParties, asCaptured)
        s.selection = DiffSelection.Row(4) // FIRMA's 452 — the last row of the first party
        val before = s.draft

        val result = s.moveSelection(down = true)

        val refused = assertIs<EditResult.Refused>(result)
        assertEquals(before, s.draft, "nothing moved")
        assertEquals(refused.why, s.refusal, "and the surface can read it — which is the whole point of M1")
        assertTrue("Move the entry instead" in refused.why, refused.why)
        assertEquals(DiffSelection.Row(4), s.selection, "and the cursor stayed on the row that did not move")
    }

    /** A row moving **inside** its own entry is nobody's business but the author's, and it is allowed. */
    @Test
    fun `a row may be re-ordered within its own entry`() {
        val s = session(threeParties, asCaptured)
        s.selection = DiffSelection.Row(4) // FIRMA's 452

        assertIs<EditResult.Applied>(s.moveSelection(down = false)) // above FIRMA's 447

        assertEquals(
            listOf(448, 452, 447),
            s.draft.fields.slice(2..4).map { it.tag },
            "the party keeps all of its rows; they are simply in a different order inside it",
        )
        assertNull(s.refusal)
    }

    // ----- walking the diff -------------------------------------------------------------------------------

    /** `n` stops at differences. A chunk with nothing wrong in it is what the author is navigating *past*. */
    @Test
    fun `n walks the chunks that are differences, and p walks back, and neither wraps`() {
        val s = session(outOfOrder, wireOrder)
        val model = s.model
        val diffs = model.diffChunks
        assertTrue(diffs.isNotEmpty(), "this step fails; there is something to navigate to")
        assertTrue(
            model.chunks.filter { it.id in diffs }.none { it.kind == ChunkKind.SAME },
            "a SAME chunk is not a diff, and stopping on one wastes the keystroke",
        )

        s.nextChunk()
        val first = s.selection
        assertNotNull(first, "n from nowhere lands on the first difference")

        // Walk to the end, then keep pressing: it must stay put rather than teleport back to the top.
        repeat(diffs.size + 3) { s.nextChunk() }
        val last = s.selection
        s.nextChunk()
        assertEquals(last, s.selection, "n at the last difference stays there — a wrap silently teleports the reader")

        repeat(diffs.size + 3) { s.prevChunk() }
        s.prevChunk()
        assertEquals(first, s.selection, "and p at the first stays there")
    }

    /** `↑/↓` walk every item, bands included — which is what lets one key select an entry *as* an entry. */
    @Test
    fun `up and down walk the body, and a band is one of the things they land on`() {
        val s = session(threeParties, asCaptured)

        s.selectNext()
        assertEquals(s.model.items.first().selection, s.selection)

        val bandAt = s.model.items.indexOfFirst { it is DiffItem.Band }
        assertTrue(bandAt > 0, "the party group has a band")
        repeat(bandAt) { s.selectNext() }
        assertIs<DiffSelection.Entry>(s.selection, "↑/↓ can put the cursor on an entry, so alt+↓ can move one")

        s.selectPrev()
        assertIs<DiffSelection.Row>(s.selection)
    }
}
