package com.knapsack.fixtool.ui.diff

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioVariable
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.service.RawMessageView
import com.knapsack.fixtool.service.compare.ChunkKind
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.service.wireView
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The draft, the stack, and the offers.**
 *
 * The two properties that carry the surface's whole safety promise, and both are pinned here:
 *
 * - **Nothing is written until Save, and undo restores *exactly* what was there.** The stack is snapshots,
 *   not command inverses, precisely so that this can be asserted byte-for-byte rather than argued about.
 * - **The surface cannot offer what the engine would refuse.** Every gutter control comes from `offersFor`,
 *   which is a pure function of the engine's own `can*` predicates — so a temporal row has no Accept-actual,
 *   an `absent` the reply contradicts is never proposed, and a whole-tag drop announces itself.
 */
class ReconcileSessionTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val arrival = Instant.parse("2025-01-01T00:00:00Z")

    private fun ref(view: com.knapsack.fixtool.service.MessageView) =
        ReferenceMessage.live(view, ReferenceMessage.Provenance.THIS_RUN, "this run", arrival)

    private fun session(
        expectation: Expectation,
        message: com.knapsack.fixtool.service.MessageView,
        onChange: (Expectation) -> Unit = {},
    ) = ReconcileSession(expectation, ref(message), dictionary, onChange = onChange)

    /** The venue filled where it left nothing, added a tag, dropped a tag, and swapped its two parties. */
    private val reply =
        wireView(
            35 to "8",
            11 to "ORD-1",
            150 to "2",
            151 to "500000",
            453 to "2",
            448 to "FIRMB",
            447 to "D",
            452 to "4",
            448 to "FIRMA",
            447 to "D",
            452 to "1",
            2376 to "Y",
        )

    private val captured =
        Expectation(
            listOf(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(11, Matcher.Exact("ORD-1")),
                FieldExpectation(150, Matcher.Exact("2")),
                FieldExpectation(151, Matcher.Numeric(0.0)),
                FieldExpectation(453, Matcher.Exact("2")),
                FieldExpectation(448, Matcher.Exact("FIRMA")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("1")),
                FieldExpectation(448, Matcher.Exact("FIRMB")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("4")),
                FieldExpectation(58, Matcher.Exact("filled")),
            ),
            messageType = "8",
            mode = MatchMode.STRICT,
        )

    // ----- the stack --------------------------------------------------------------------------------------

    /**
     * **THE PROPERTY THE SNAPSHOT STACK EXISTS FOR.** Apply anything the surface can apply, undo it, and the
     * expectation is byte-equal to what was there before. Not *equivalent* — equal. A command-inverse stack
     * would have had to earn this operation by operation, and `drop` (which takes the whole tag) and `reseed`
     * (which rebuilds and re-attaches the echoes) are exactly the ones an inverse gets subtly wrong.
     */
    @Test
    fun `every op undoes to a byte-equal expectation`() {
        val s = session(captured, reply)
        val ops =
            listOf(
                EditOp.acceptActual(3, 151, "500000"),
                EditOp.drop(5, 448),
                EditOp.assertAbsent(11, 58),
                EditOp.insertAbsent(9999),
                EditOp.loosen(3, 151, Matcher.Presence),
                EditOp.setMode(MatchMode.OPEN),
                EditOp.reseed(reply, dictionary),
                EditOp.acceptAllShape(reply, dictionary),
                EditOp.assertIt(11, 2376, "Y", reply, dictionary),
            )

        for (op in ops) {
            val before = s.draft
            assertIs<EditResult.Applied>(s.apply(op), "${op.label} must actually change the draft, or this proves nothing")
            assertTrue(s.draft != before)
            s.undo()
            assertEquals(before, s.draft, "undo after '${op.label}' did not restore the expectation exactly")
        }
    }

    @Test
    fun `redo walks forward again, and a fresh edit abandons the redo branch`() {
        val s = session(captured, reply)
        s.apply(EditOp.acceptActual(3, 151, "500000"))
        val accepted = s.draft

        s.undo()
        assertEquals(captured, s.draft)
        assertTrue(s.canRedo)

        s.redo()
        assertEquals(accepted, s.draft, "redo must reach the very state undo left")

        s.undo()
        s.apply(EditOp.drop(11, 58))
        assertFalse(s.canRedo, "an edit after an undo abandons the branch — the ordinary editor contract")
    }

    @Test
    fun `discard restores the original after an arbitrary sequence, and leaves no redo behind`() {
        val s = session(captured, reply)
        s.apply(EditOp.acceptActual(3, 151, "500000"))
        s.apply(EditOp.drop(11, 58))
        s.apply(EditOp.setMode(MatchMode.OPEN))
        s.undo()
        assertTrue(s.isDirty)

        s.discard()

        assertEquals(captured, s.draft, "cancel must leave no trace at all")
        assertFalse(s.isDirty)
        assertFalse(s.canUndo)
        assertFalse(s.canRedo)
        assertEquals(0, s.staged)
    }

    /** The footer counts edits, and names them. The original is not an edit. */
    @Test
    fun `the staged count and its labels are the edits, and nothing else`() {
        val s = session(captured, reply)
        assertEquals(0, s.staged)
        assertEquals(emptyList(), s.stagedLabels)

        s.apply(EditOp.acceptActual(3, 151, "500000"))
        s.apply(EditOp.drop(11, 58))

        assertEquals(2, s.staged)
        assertEquals(listOf("Accepted 151 = 500000", "Dropped 58"), s.stagedLabels)
    }

    /** A refused or no-op edit is not an edit: it must not stack, dirty, or make Undo light up. */
    @Test
    fun `an operation that changes nothing is never staged`() {
        val s = session(captured, reply)

        // Accept-actual on a row whose matcher already holds: the engine returns the same expectation.
        assertEquals(EditResult.Unchanged, s.apply(EditOp.acceptActual(0, 35, "8")))
        // A move the engine refuses (a lone row of a repeated tag crossing its sibling).
        assertIs<EditResult.Refused>(s.apply(EditOp.moveRow(null, from = 10, to = 7)))

        assertEquals(0, s.staged)
        assertFalse(s.canUndo)
        assertFalse(s.isDirty)
    }

    /**
     * **The sentence the engine writes is the sentence the author reads.**
     *
     * `EditOp` used to be `(Expectation) -> Expectation?`, where `null` meant *both* "the engine refused" and
     * "nothing changed" — so `MoveResult.Refused(why)` was computed by the validator on every call and thrown
     * away by the op that called it. Invisible while the only movers were the ↑/↓ arrows, which are *disabled*
     * where a move is illegal. A drag has no disabled state.
     */
    @Test
    fun `a refused move leaves the draft byte-identical and says why, in the engine's own words`() {
        val s = session(captured, reply)
        val before = s.draft

        // The forbidden drag: the second 452 crossing the first. Both rows still read `452 exact …`; only
        // which occurrence each of them checks would change.
        val result = s.apply(EditOp.moveRow(null, from = 10, to = 7))

        val refused = assertIs<EditResult.Refused>(result)
        assertEquals(before, s.draft, "a refused move changes nothing at all")
        assertEquals(0, s.staged)
        assertEquals(refused.why, s.refusal, "and the surface can read it — which is the whole point")
        assertTrue(
            "swap which occurrence" in refused.why,
            "the refusal names the thing that would have gone wrong, verbatim from the engine: ${refused.why}",
        )

        // It is not a modal, and it does not outlive its cause: the next real edit clears it.
        s.apply(EditOp.drop(11, 58))
        assertNull(s.refusal)
    }

    /** A no-op is not a refusal, and must not be dressed as one: a drop that lands where the row was says nothing. */
    @Test
    fun `a move that goes nowhere is unchanged, not refused`() {
        val s = session(captured, reply)

        assertEquals(EditResult.Unchanged, s.apply(EditOp.moveRow(null, from = 3, to = 3)))

        assertNull(s.refusal, "a drag that did not go anywhere has nothing to apologise for")
        assertEquals(0, s.staged)
    }

    /** The tooltip asks the same question the drop will, and asking must not answer it. */
    @Test
    fun `preview stages nothing, dirties nothing, and pushes no undo`() {
        val s = session(captured, reply)
        val before = s.draft

        val legal = s.preview(EditOp.moveRow(null, from = 3, to = 2))
        val refused = s.preview(EditOp.moveRow(null, from = 10, to = 7))

        assertIs<EditResult.Applied>(legal)
        assertIs<EditResult.Refused>(refused)
        assertEquals(before, s.draft)
        assertEquals(0, s.staged)
        assertFalse(s.canUndo)
        assertFalse(s.isDirty)
        assertNull(s.refusal, "a question is not an action, and must not leave a refusal on screen")
    }

    /** The host is told every time the draft moves — that is the loop the editor lives on. */
    @Test
    fun `the host hears every change, including undo and discard`() {
        val heard = mutableListOf<Expectation>()
        val s = session(captured, reply) { heard += it }

        s.apply(EditOp.drop(11, 58))
        s.undo()
        s.redo()
        s.discard()

        assertEquals(4, heard.size)
        assertEquals(captured, heard.last(), "the last thing the host hears about a cancel is the original")
    }

    // ----- the reference slot is not an edit --------------------------------------------------------------

    /**
     * Swapping the reference changes what you are comparing *against*, not what you are *asserting*. It
     * re-judges, and that is all — offering to "undo" it would be offering to undo looking at something.
     */
    @Test
    fun `swapping the reference re-judges without staging, dirtying, or pushing undo`() {
        val s = session(captured, reply)
        val failing = s.model.verdict.attention
        assertTrue(failing > 0)

        s.swapReference(ref(wireView(35 to "8", 11 to "ORD-1", 150 to "2", 151 to "0")))

        assertEquals(0, s.staged)
        assertFalse(s.canUndo)
        assertFalse(s.isDirty)
        assertTrue(s.model.verdict.attention != failing, "the rows must have been re-judged against the new bytes")
    }

    // ----- the memo ---------------------------------------------------------------------------------------

    /**
     * **THE MEMO KEY IS BY VALUE, AND THAT IS THE WHOLE TRICK.**
     *
     * `RawMessageView` has no `equals`, so a key holding the `ReferenceMessage` would compare its view by
     * *identity*. Rebuild the reference inside the composable — the natural way to write it — and the key
     * would change on every recomposition: the memo would never hit, and every frame would re-run `reorder`,
     * which enumerates every contiguous block of the expectation. Nothing fails. It is merely slow, for a
     * reason nobody would ever find. So this asserts the failure mode directly.
     */
    @Test
    fun `an equal-but-distinct reference does not invalidate the memo`() {
        val soh = ''
        val bytes = listOf("35=8", "11=ORD-1", "150=2", "151=0").joinToString("$soh", postfix = "$soh")
        val s = ReconcileSession(captured, ref(RawMessageView(bytes)), dictionary)

        s.model
        val after = s.rebuilds
        assertEquals(1, after)

        // A different object, the same message — exactly what a recomposition would hand it.
        s.swapReference(ref(RawMessageView(bytes)))
        s.model
        s.model

        assertEquals(after, s.rebuilds, "the model was rebuilt for a reference that had not changed at all")
    }

    @Test
    fun `the model rebuilds when the draft changes, and when the reference really changes`() {
        val s = session(captured, reply)
        s.model
        val start = s.rebuilds

        s.apply(EditOp.drop(11, 58))
        s.model
        assertEquals(start + 1, s.rebuilds, "an edit must re-judge")

        s.swapReference(ref(wireView(35 to "8", 151 to "999")))
        s.model
        assertEquals(start + 2, s.rebuilds, "different bytes must re-judge")

        s.model
        s.model
        assertEquals(start + 2, s.rebuilds, "and nothing else may")
    }

    // ----- the display model ------------------------------------------------------------------------------

    /**
     * **Every row of the diff is a line, and every line's field is the field that row faces.** The two sides
     * are paired once, in the engine's own terms; nothing downstream re-zips two lists of different lengths.
     */
    @Test
    fun `the lines are the rows, and a row that faces nothing says so`() {
        val model = session(captured, reply).model

        assertEquals(
            model.chunks.sumOf { it.rows.size },
            model.lines.size,
            "every row of every chunk is exactly one line",
        )
        val missing = model.lines.single { it.row.tag == 58 }
        assertTrue(missing.rightIsGap, "the venue stopped sending 58 — there is nothing to face")
        val added = model.lines.single { it.row.tag == 2376 }
        assertTrue(added.leftIsGap, "nothing asserts 2376 — the left column is a gap")
        assertNotNull(added.right)
    }

    /**
     * **The right-hand column is the reply, in the reply's order — including across a move.**
     *
     * It used to pair a moved row with the field it *landed on*, so FIRMA's row faced FIRMA and the two sides
     * agreed. Two things were wrong with that, and the second is the one that matters:
     *
     * - Read the right column top to bottom across the swapped parties and it came out as wire `3,4,5,0,1,2`
     *   — **a message no venue sent**, which is the one thing `wireRaw` exists to prevent, committed in the
     *   renderer instead of the codec.
     * - It left the crossing connector with nothing to cross. FIRMA opposite FIRMA is a straight line.
     *
     * Where the rows went is `Chunk.landing`, published beside the pairing rather than instead of it, and the
     * violet curve is what says it.
     */
    @Test
    fun `a moved row faces the field the venue sent there, and the chunk says where it went`() {
        val model = session(captured, reply).model
        val moved = model.lines.filter { it.kind == ChunkKind.MOVED }

        assertTrue(moved.isNotEmpty(), "the two party entries swapped — the engine must see it")
        assertTrue(moved.all { it.right != null }, "a moved row facing nothing has no crossing to draw")

        val firma = moved.single { it.row.tag == 448 && (it.row.matcher as Matcher.Exact).value == "FIRMA" }
        assertEquals(
            "FIRMB",
            firma.right!!.value,
            "FIRMA's row faces what the venue actually sent in that position, which is FIRMB — that IS the move",
        )

        // And the right column, read as the author reads it, is the message.
        val wire = model.lines.mapNotNull { it.right?.wireIndex }
        assertEquals(wire.sorted(), wire, "the right column must never show the reply's fields out of the reply's order")

        val chunk = model.chunks.single { it.id == firma.chunkId }
        assertEquals(listOf(8, 9, 10), chunk.landing, "FIRMA's entry went to the second party's place on the wire")
    }

    /** The entry bands come off the overlay, and both sides of a line agree about which entry it is in. */
    @Test
    fun `lines inside a party entry carry that entry`() {
        val model = session(captured, reply).model

        val party = model.lines.filter { it.row.tag in listOf(448, 447, 452) }
        assertEquals(6, party.size)
        assertTrue(party.all { it.entry != null }, "every party row belongs to an entry the overlay named")
        assertEquals(2, party.mapNotNull { it.entry }.distinct().size, "two parties, two entries")
        assertTrue(model.lines.single { it.row.tag == 35 }.entry == null, "MsgType is in no group")
    }

    // ----- the offers -------------------------------------------------------------------------------------

    /**
     * **The gutter cannot offer what the engine refuses.** A temporal row's Accept-actual would pin it to a
     * timestamp that will not recur, so the step is red on every run from then on and the author's only way
     * out is to delete the assertion — a red that leads to a deleted assertion is a green by a longer route.
     * A reference row's would destroy the cross-step binding it exists to express.
     */
    @Test
    fun `a temporal and a reference row are never offered Accept actual`() {
        val draft =
            Expectation(
                listOf(
                    FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)),
                    FieldExpectation(11, Matcher.Reference("\${id0}")),
                    FieldExpectation(151, Matcher.Exact("0")),
                ),
                messageType = "8",
                mode = MatchMode.OPEN,
            )
        // An hour off its window, so the temporal genuinely fails and an offer would be drawn if one existed.
        val message = wireView(60 to "20200101-00:00:00", 11 to "ORD-1", 151 to "500000")

        val model = ReconcileSession(draft, ref(message), dictionary).model

        fun kinds(tag: Int) =
            model.lines
                .single { it.row.tag == tag }
                .offers
                .map { it.kind }

        assertFalse(OfferKind.ACCEPT_ACTUAL in kinds(60), "a temporal has nothing honest to accept")
        assertFalse(OfferKind.ACCEPT_ACTUAL in kinds(11), "a reference has nothing honest to accept")
        assertTrue(OfferKind.ACCEPT_ACTUAL in kinds(151), "and an ordinary value mismatch does")
    }

    /**
     * `absent` asserts the tag appears **nowhere** — it is not scoped to one occurrence. Offer it on the row
     * for a third party the venue stopped sending, while it still sends two, and the author gets an assertion
     * that can never pass. Dropping is the honest repair there, and it is the only one offered.
     */
    @Test
    fun `assert-absent is withheld where the reply still carries the tag`() {
        val draft =
            Expectation(
                listOf(
                    FieldExpectation(452, Matcher.Exact("1")),
                    FieldExpectation(452, Matcher.Exact("4")),
                    FieldExpectation(452, Matcher.Exact("7")),
                    FieldExpectation(58, Matcher.Exact("filled")),
                ),
                messageType = "8",
                mode = MatchMode.OPEN,
            )
        val message = wireView(452 to "1", 452 to "4") // the third party is gone; 58 is gone entirely

        val model = ReconcileSession(draft, ref(message), dictionary).model
        val third = model.lines.last { it.row.tag == 452 }
        val text = model.lines.single { it.row.tag == 58 }

        assertFalse(
            OfferKind.ASSERT_ABSENT in third.offers.map { it.kind },
            "the reply still carries 452 twice — 'absent' there could never pass",
        )
        assertTrue(OfferKind.ASSERT_ABSENT in text.offers.map { it.kind }, "but 58 really is gone from the reply")
    }

    /**
     * Dropping a row of a repeated tag takes the tag's rows with it — and the gutter says so first.
     *
     * The fixture is the **role swap**, deliberately: there the `448` rows genuinely are value mismatches (the
     * firms changed roles in place, nothing moved), so they carry a Drop. In the entries-swapped fixture those
     * same rows have *moved*, and a moved row is offered nothing at all — which is the point of the test above
     * this one, and the reason this one cannot use that fixture.
     */
    @Test
    fun `the whole-tag drop announces itself before it happens`() {
        val roleSwap =
            Expectation(
                listOf(
                    FieldExpectation(448, Matcher.Exact("FIRMA")),
                    FieldExpectation(452, Matcher.Exact("1")),
                    FieldExpectation(448, Matcher.Exact("FIRMB")),
                    FieldExpectation(452, Matcher.Exact("4")),
                    FieldExpectation(58, Matcher.Exact("filled")),
                ),
                messageType = "8",
                mode = MatchMode.OPEN,
            )
        val model =
            session(roleSwap, wireView(448 to "FIRMA", 452 to "4", 448 to "FIRMB", 452 to "1")).model

        // The ROLES changed, not the firms — so 448 still passes and 452 is the mismatch that carries a Drop.
        val party =
            model.lines
                .first { it.row.tag == 452 }
                .offers
                .single { it.kind == OfferKind.DROP }
        val text =
            model.lines
                .single { it.row.tag == 58 }
                .offers
                .single { it.kind == OfferKind.DROP }

        assertTrue("every row for 452 goes" in party.tooltip, party.tooltip)
        assertTrue("stops checking 58" in text.tooltip, text.tooltip)
    }

    /**
     * **A ROW THAT MOVED IS OFFERED NOTHING — and this is the one that nearly shipped.**
     *
     * A moved row's *status* is `VALUE`: FIRMA's `448` now faces FIRMB, which is indistinguishable, row by
     * row, from the venue having changed the value. A gutter keyed on status alone therefore drew an
     * Accept-actual under it — and one click would rebase FIRMA's assertion onto FIRMB while the two `452`
     * rows stayed exactly where they were. The expectation would read FIRMB/role-1 and FIRMB/role-4, "FIRMA
     * holds role 1" would be gone, and the step would be green.
     *
     * That is the false green the entire sequence model exists to make impossible, walked back in through the
     * gutter. The entry moved as a unit and it is repaired as one — by Accept-new-order, and by nothing else.
     */
    @Test
    fun `a row the engine proved moved is offered no per-row fix`() {
        val model = session(captured, reply).model

        val moved = model.lines.filter { it.kind == ChunkKind.MOVED }
        assertTrue(moved.size == 6, "two party entries, three rows each: ${moved.size}")
        assertTrue(
            moved.all { it.offers.isEmpty() },
            "Accept-actual on a row that merely moved rebases it onto another party's value and deletes the " +
                "assertion the author wrote: ${moved.filter { it.offers.isNotEmpty() }.map { it.row.tag to it.offers.map { o -> o.kind } }}",
        )
        assertNotNull(model.acceptOrder, "the honest repair is the one the engine proved, and it is on offer")
    }

    /**
     * A passing row has nothing to *repair*, and a row nobody can read has nothing to repair *here* — but
     * deleting an assertion is authoring, not repair, and this surface is the app's only assertion editor.
     * On a fresh capture every row is green against its own golden, and a gutter that answered green with
     * silence left the author no way to stop asserting anything. So the authoring edits are offered — the
     * drop, and the capture (which asserts nothing) — and **only** those: an Accept-actual or Loosen on a
     * row that is not failing would be a one-click way to be wrong.
     */
    @Test
    fun `passing and unjudgeable rows are offered the authoring edits, and no repair`() {
        val draft =
            Expectation(
                listOf(
                    FieldExpectation(35, Matcher.Exact("8")),
                    FieldExpectation(11, Matcher.Reference("\${id0}")),
                ),
                messageType = "8",
                mode = MatchMode.OPEN,
            )
        val model = ReconcileSession(draft, ref(wireView(35 to "8", 11 to "ORD-1")), dictionary).model

        assertEquals(
            listOf(OfferKind.DROP, OfferKind.CAPTURE),
            model.lines.single { it.row.tag == 35 }.offers.map { it.kind },
            "a green row's honest edits are authoring, not repair: stop asserting it, or capture its value",
        )
        val echo = model.lines.single { it.row.tag == 11 }
        assertTrue(echo.unjudged, "a reference has no scope outside a run — amber, not red")
        assertEquals(
            listOf(OfferKind.DROP, OfferKind.CAPTURE),
            echo.offers.map { it.kind },
            "authoring is legitimate even on a row this view cannot judge; repairing it is not",
        )
    }

    // ----- the move, and the reason it is withheld --------------------------------------------------------

    /** The engine proved these entries swapped, so the one-click order is on offer. */
    @Test
    fun `a proven move is offered once, and applying it repairs the step`() {
        val s = session(captured, reply)
        val op = s.model.acceptOrder

        assertNotNull(op, "the two parties plainly swapped: ${s.model.withheldMove}")
        assertNull(s.model.withheldMove)
        assertIs<EditResult.Applied>(s.apply(op))
        assertTrue(
            s.model.lines.none { it.kind == ChunkKind.MOVED },
            "after accepting the order nothing is out of place any more",
        )
    }

    /**
     * And the role swap: the firms exchanged roles in place, which looks identical and is a regression. No
     * move is offered — and the reason is *carried*, because an author who is shown a group of red rows and
     * no button concludes the feature does not exist. That is exactly what happened.
     */
    @Test
    fun `a role swap offers no move, and says why not`() {
        val draft =
            Expectation(
                listOf(
                    FieldExpectation(453, Matcher.Exact("2")),
                    FieldExpectation(448, Matcher.Exact("FIRMA")),
                    FieldExpectation(452, Matcher.Exact("1")),
                    FieldExpectation(448, Matcher.Exact("FIRMB")),
                    FieldExpectation(452, Matcher.Exact("4")),
                ),
                messageType = "8",
                mode = MatchMode.OPEN,
            )
        val rolesSwapped = wireView(453 to "2", 448 to "FIRMA", 452 to "4", 448 to "FIRMB", 452 to "1")

        val model = ReconcileSession(draft, ref(rolesSwapped), dictionary).model

        assertNull(model.acceptOrder, "offering a move here would fake a pass over a real regression")
        assertNotNull(model.withheldMove)
        assertTrue("did not move" in model.withheldMove, model.withheldMove)
        assertTrue(model.lines.none { it.kind == ChunkKind.MOVED }, "and nothing is banded as moved")
    }

    /**
     * **A Loosen that leaves the row red is a broken button.** The evaluator's numeric compare is plain
     * doubles, and the decimal difference is not the double difference: `2.0` vs `1.9` differ by `0.1` in
     * decimal, but `2.0d - 1.9d` exceeds `0.1d` by ~6 ulps — the exact case where a tolerance computed in
     * exact decimal arithmetic fails the arithmetic the runner will actually do. The offered band must
     * cover the evaluator's own difference, whatever the pretty tooltip says.
     */
    @Test
    fun `the loosen offer's band passes the evaluator's own double arithmetic`() {
        val expectation =
            Expectation(
                listOf(
                    FieldExpectation(35, Matcher.Exact("8")),
                    FieldExpectation(44, Matcher.Exact("2.0")),
                ),
                messageType = "8",
                mode = MatchMode.OPEN,
            )
        val message = wireView(35 to "8", 44 to "1.9")
        val session = session(expectation, message)

        val priceLine = session.model.lines.single { it.row.tag == 44 }
        val loosen = priceLine.offers.single { it.glyph == "±" }
        assertIs<EditResult.Applied>(session.apply(loosen.op))

        val reJudged = session.model.lines.single { it.row.tag == 44 }
        assertTrue(reJudged.row.passed, "loosened to cover both sides, so the row is green — not red by one ulp")
    }

    /**
     * **The ± is bound by the same single decider the fix plan consults** — `ExpectationSeeder.numericFamily`.
     * PartyRole(452) asserted `4` and answered `1` is a role change, not drift: the plan proposes nothing for
     * it, and the gutter must not hand the same band out by another door. `4 ± 3` accepts seven different
     * meanings while reading like a tolerance — a permanent false green, one click wide. The price row in the
     * same diff keeps its ±, so the withholding is the classification, not a lost feature.
     */
    @Test
    fun `the loosen offer keeps the seed's counsel — no band on an enum-coded int`() {
        val expectation =
            Expectation(
                listOf(
                    FieldExpectation(35, Matcher.Exact("8")),
                    FieldExpectation(452, Matcher.Exact("4")),
                    FieldExpectation(44, Matcher.Exact("1.0850")),
                ),
                messageType = "8",
                mode = MatchMode.OPEN,
            )
        val message = wireView(35 to "8", 452 to "1", 44 to "1.0900")
        val session = session(expectation, message)

        val roleLine = session.model.lines.single { it.row.tag == 452 }
        assertTrue(
            roleLine.offers.none { it.glyph == "±" },
            "a failing PartyRole is a role change, not drift — what the fix plan refuses, the gutter must not offer",
        )
        val priceLine = session.model.lines.single { it.row.tag == 44 }
        assertTrue(
            priceLine.offers.any { it.glyph == "±" },
            "the price row keeps its ± — the withholding is per-family, not global",
        )
    }

    // ----- authoring on a step that passes ----------------------------------------------------------------

    /**
     * **A fresh capture is all green, and green must still be editable.** The diff is the app's only
     * assertion-authoring surface; a gutter that answers a passing row with silence leaves the author no way
     * to stop asserting anything. The drop is offered, it works, and undo restores the draft byte-for-byte.
     */
    @Test
    fun `a green row offers the drop, applying it removes the row, and undo restores it exactly`() {
        val greens =
            Expectation(
                listOf(
                    FieldExpectation(35, Matcher.Exact("8")),
                    FieldExpectation(11, Matcher.Exact("ORD-1")),
                    FieldExpectation(58, Matcher.Exact("filled")),
                ),
                messageType = "8",
                mode = MatchMode.OPEN,
            )
        val s = session(greens, wireView(35 to "8", 11 to "ORD-1", 58 to "filled"))
        val line = s.model.lines.single { it.row.tag == 58 }
        assertTrue(line.row.passed, "the fixture is a capture judged against its own bytes: green")
        assertEquals(
            listOf(OfferKind.DROP, OfferKind.CAPTURE),
            line.offers.map { it.kind },
            "a passing row's edits are authoring: delete it, or capture its value — repair has nothing to do",
        )

        val before = s.draft
        assertIs<EditResult.Applied>(s.apply(line.offers.single { it.kind == OfferKind.DROP }.op))
        assertTrue(s.draft.fields.none { it.tag == 58 }, "the row is gone from the draft")
        assertTrue(s.model.lines.none { it.row.tag == 58 && !it.row.unasserted }, "and from the diff")

        s.undo()
        assertEquals(before, s.draft, "undo after an authoring delete restores the expectation byte-for-byte")
    }

    /**
     * **The STRICT caveat is real, and the tooltip says it before it happens.** In STRICT a tag no row
     * mentions is an unexpected extra — so dropping a row for a tag the venue still sends does not stop the
     * step caring about it, it flips *which way* the step cares, and the next run is red where the edit
     * looked like a clean-up. OPEN has no such cliff, and its tooltip carries no such warning.
     */
    @Test
    fun `in STRICT the drop on a venue-sent row warns about the extra, and dropping really does flip the step red`() {
        val strict =
            Expectation(
                listOf(
                    FieldExpectation(35, Matcher.Exact("8")),
                    FieldExpectation(58, Matcher.Exact("filled")),
                ),
                messageType = "8",
                mode = MatchMode.STRICT,
            )
        val s = session(strict, wireView(35 to "8", 58 to "filled"))
        assertFalse(s.model.verdict.needsAttention, "everything passes before the edit")

        val drop = s.model.lines.single { it.row.tag == 58 }.offers.single { it.kind == OfferKind.DROP }
        assertTrue("unexpected extra" in drop.tooltip, "the consequence is said where the click is: ${drop.tooltip}")

        assertIs<EditResult.Applied>(s.apply(drop.op))
        assertTrue(
            s.model.verdict.needsAttention,
            "the venue still sends 58, so STRICT now counts it against the step — the tooltip promised exactly this",
        )

        // The same drop under OPEN is what it looks like: the step simply stops checking the tag.
        val open = session(strict.copy(mode = MatchMode.OPEN), wireView(35 to "8", 58 to "filled"))
        val openDrop =
            open.model
                .lines
                .single { it.row.tag == 58 }
                .offers
                .single { it.kind == OfferKind.DROP }
        assertFalse("unexpected extra" in openDrop.tooltip, "OPEN has no extra to warn about: ${openDrop.tooltip}")
        assertTrue("stops checking 58" in openDrop.tooltip, openDrop.tooltip)
    }

    /** And on a repeated tag in STRICT, the whole-tag rule and the extra warning are both said — merged, not either/or. */
    @Test
    fun `the whole-tag drop and the STRICT caveat merge into one tooltip`() {
        val parties =
            Expectation(
                listOf(
                    FieldExpectation(448, Matcher.Exact("FIRMA")),
                    FieldExpectation(448, Matcher.Exact("FIRMB")),
                ),
                messageType = "8",
                mode = MatchMode.STRICT,
            )
        val s = session(parties, wireView(448 to "FIRMA", 448 to "FIRMB"))

        val drop =
            s.model.lines
                .first { it.row.tag == 448 }
                .offers
                .single { it.kind == OfferKind.DROP }
        assertTrue("every row for 448 goes" in drop.tooltip, drop.tooltip)
        assertTrue("unexpected extra" in drop.tooltip, drop.tooltip)
    }

    /**
     * **The assertion no row can host: "this tag appears nowhere."** The new row is appended at the end —
     * a position the model cannot misread, because an `absent` row takes no part in the alignment's scan —
     * and it is judged like any other row: green where the reference lacks the tag, red the moment it does
     * not, which is the honest answer to an author who asserted absence of something the venue sends.
     */
    @Test
    fun `insert-absent appends, is green where the tag is missing, red where the reply carries it, and undoes exactly`() {
        val draft =
            Expectation(
                listOf(FieldExpectation(35, Matcher.Exact("8"))),
                messageType = "8",
                mode = MatchMode.OPEN,
            )
        val s = session(draft, wireView(35 to "8", 58 to "note"))
        val before = s.draft

        assertIs<EditResult.Applied>(s.apply(EditOp.insertAbsent(9999)))
        assertEquals(FieldExpectation(9999, Matcher.Absent), s.draft.fields.last(), "appended at the end")
        val absent = s.model.lines.single { it.row.tag == 9999 }
        assertTrue(absent.row.passed, "the reference has no 9999, so the new row is green")
        assertTrue(absent.rightIsGap, "an absent row that holds faces nothing")

        assertIs<EditResult.Applied>(s.apply(EditOp.insertAbsent(58)))
        val contradicted = s.model.lines.single { it.row.tag == 58 }
        assertFalse(contradicted.row.passed, "the reply carries 58 — the new row says so on the very next re-judge")
        assertEquals("note", contradicted.row.actual, "and it shows the value that contradicts it")

        s.undo()
        s.undo()
        assertEquals(before, s.draft, "two undos, and the draft is byte-for-byte what was loaded")
    }

    /**
     * The same append, judged under **STRICT** — where the trap would be double-counting: the absent row
     * fails because the reply carries the tag, and the reply's field must not *also* be reported as an
     * unexpected extra. One fact, one failure. (The alignment's `spokenFor` rule is what carries this.)
     */
    @Test
    fun `under STRICT an inserted absent row is one failure, not a failure plus an extra`() {
        val draft =
            Expectation(
                listOf(
                    FieldExpectation(35, Matcher.Exact("8")),
                    FieldExpectation(58, Matcher.Exact("note")),
                ),
                messageType = "8",
                mode = MatchMode.STRICT,
            )
        val s = session(draft, wireView(35 to "8", 58 to "note"))
        assertEquals(0, s.model.verdict.attention)

        s.apply(EditOp.insertAbsent(9999))
        assertEquals(0, s.model.verdict.attention, "a tag in neither column holds under STRICT too")

        s.apply(EditOp.insertAbsent(58))
        assertEquals(
            1,
            s.model.verdict.attention,
            "the contradicted absent row is exactly one failure — the wire's 58 is spoken for, not an extra on top",
        )
    }

    // ----- the fix plan -----------------------------------------------------------------------------------

    /**
     * The plan was previewed row by row before it was applied, so it is **one edit**: one line in the
     * footer, one ⌘Z — undoing it row by row would walk back through decisions the author made as one.
     */
    @Test
    fun `the fix plan applies as one snapshot, repairs every planned row, and one undo removes it whole`() {
        val drifted =
            Expectation(
                listOf(
                    FieldExpectation(35, Matcher.Exact("8")),
                    FieldExpectation(6, Matcher.Numeric(1.09087)),
                    FieldExpectation(31, Matcher.Numeric(1.09087)),
                ),
                messageType = "8",
                mode = MatchMode.OPEN,
            )
        val s = session(drifted, wireView(35 to "8", 6 to "1.09152", 31 to "1.09152"))

        val plan = s.fixPlan()
        assertEquals(listOf(6, 31), plan.map { it.tag })
        assertTrue(plan.all { it.repairs })

        val before = s.draft
        assertIs<EditResult.Applied>(s.apply(EditOp.fixPlan(plan)))
        assertEquals(1, s.staged, "two rows widened, one edit staged")
        assertEquals(listOf("Loosened 2 rows to fit the reply"), s.stagedLabels)
        assertEquals(0, s.model.verdict.attention, "every planned row now passes")

        s.undo()
        assertEquals(before, s.draft, "one undo, and the plan is gone whole — byte-for-byte")
    }

    // ------------------------------------------------------------------ the reference's scope

    private fun scoped(vararg vars: Pair<String, String>) =
        vars.map { (n, v) -> ScenarioVariable(n, v) }

    /**
     * **The scope travels with the reference.** A `${id0}` row against the run's own message — whose
     * reference carries the run's scope — gets a real verdict; the same row against a reference with no
     * scope is neither passing nor failing. This is the whole slice-1 promise: the reconcile view judges
     * reference rows with exactly what the runner judged them with, and with nothing else.
     */
    @Test
    fun `a reference row is judged by the scope the reference carries, and unjudged without one`() {
        val expectation =
            Expectation(fields = listOf(FieldExpectation(11, Matcher.Reference("\${id0}"))), messageType = "8")
        val message = wireView(35 to "8", 11 to "A1")

        val bare = ReconcileSession(expectation, ref(message), dictionary)
        assertTrue(bare.model.lines.single { it.row.tag == 11 }.unjudged, "no scope, no verdict")

        val s = ReconcileSession(expectation, ref(message).copy(variables = scoped("id0" to "A1")), dictionary)
        val line = s.model.lines.single { it.row.tag == 11 }
        assertFalse(line.unjudged, "the run's scope is in hand — the row has a verdict")
        assertTrue(line.row.passed, "and the echo matches, so it is green")
        assertEquals(0, s.model.verdict.attention)

        val red = ReconcileSession(expectation, ref(message).copy(variables = scoped("id0" to "B9")), dictionary)
        val redLine = red.model.lines.single { it.row.tag == 11 }
        assertFalse(redLine.unjudged)
        assertFalse(redLine.row.passed, "the echo differs from what this run minted — a real red")
    }

    /**
     * Per-row honesty — the mutation check against the old any-reference-resolves flag. Two reference
     * rows, a scope that covers exactly one: the covered row is judged, the uncovered row stays
     * *unjudged*. The old global flag would have judged BOTH the moment one resolved, putting a
     * confident red on an expression nobody evaluated ("unknown, never false").
     */
    @Test
    fun `a scope that covers one reference row judges that row and leaves the other unjudged`() {
        val expectation =
            Expectation(
                fields =
                    listOf(
                        FieldExpectation(11, Matcher.Reference("\${id0}")),
                        FieldExpectation(41, Matcher.Reference("\${out.D.11}")), // history ref: no session here
                    ),
                messageType = "8",
            )
        val message = wireView(35 to "8", 11 to "A1", 41 to "A1")
        val s = ReconcileSession(expectation, ref(message).copy(variables = scoped("id0" to "A1")), dictionary)
        assertFalse(s.model.lines.single { it.row.tag == 11 }.unjudged)
        assertTrue(
            s.model.lines.single { it.row.tag == 41 }.unjudged,
            "the scope has no answer for a history reference — that row must stay unjudged, not go red",
        )
    }

    /** Swapping the reference swaps the scope with it — and takes a stale highlight along. */
    @Test
    fun `swapping to a scope-less reference re-judges reference rows to unjudged and clears the highlight`() {
        val expectation =
            Expectation(fields = listOf(FieldExpectation(11, Matcher.Reference("\${id0}"))), messageType = "8")
        val message = wireView(35 to "8", 11 to "A1")
        val s = ReconcileSession(expectation, ref(message).copy(variables = scoped("id0" to "A1")), dictionary)
        assertFalse(s.model.lines.single { it.row.tag == 11 }.unjudged)
        s.highlightedVariable = "id0"

        val rebuiltBefore = s.rebuilds
        s.swapReference(ReferenceMessage.golden(RawMessageView("35=8\u000111=A1\u0001")))
        assertTrue(
            s.model.lines.single { it.row.tag == 11 }.unjudged,
            "a golden ran under no scope — its reference rows are unjudged again",
        )
        assertTrue(s.rebuilds > rebuiltBefore, "the memo keys on the scope, so the swap rebuilt the model")
        assertNull(s.highlightedVariable, "the highlight named a variable of the old scope")
    }

    /** The strip's highlight: by name in a reference expression, or by carrying the value on either side. */
    @Test
    fun `highlight names lines that reference the variable or carry its value, and no others`() {
        val expectation =
            Expectation(
                fields =
                    listOf(
                        FieldExpectation(11, Matcher.Reference("\${id0}")),
                        FieldExpectation(41, Matcher.Exact("A1")),
                        FieldExpectation(55, Matcher.Exact("EURUSD")),
                    ),
                messageType = "8",
            )
        val message = wireView(35 to "8", 11 to "A1", 41 to "A1", 55 to "EURUSD")
        val s = ReconcileSession(expectation, ref(message).copy(variables = scoped("id0" to "A1")), dictionary)

        assertFalse(s.highlights(s.model.lines.single { it.row.tag == 11 }), "nothing highlighted yet")
        s.highlightedVariable = "id0"
        assertTrue(s.highlights(s.model.lines.single { it.row.tag == 11 }), "mentions \${id0} by name")
        assertTrue(s.highlights(s.model.lines.single { it.row.tag == 41 }), "carries the value A1")
        assertFalse(s.highlights(s.model.lines.single { it.row.tag == 55 }), "nothing to do with id0")
    }

    // ------------------------------------------------------------------ the track offer

    /**
     * `$` on a failing row whose actual IS the run's variable: applying it swaps the pinned literal for
     * the correlation, and — because the scope rides the reference — the row is judged green on the spot.
     * A failing value that matches no variable gets no such offer: value-equality is the whole gate.
     */
    @Test
    fun `track is offered exactly where the scope proves the value, and applying it makes the row a judged reference`() {
        val expectation =
            Expectation(
                fields =
                    listOf(
                        FieldExpectation(11, Matcher.Exact("STALE")),
                        FieldExpectation(55, Matcher.Exact("GBPUSD")),
                    ),
                messageType = "8",
            )
        val message = wireView(35 to "8", 11 to "A1", 55 to "EURUSD")
        val s = ReconcileSession(expectation, ref(message).copy(variables = scoped("id0" to "A1")), dictionary)

        val track = s.model.lines.single { it.row.tag == 11 }.offers.single { it.kind == OfferKind.TRACK }
        assertTrue("\${id0}" in track.tooltip, "the offer names the variable it would track: ${track.tooltip}")
        assertTrue(
            s.model.lines.single { it.row.tag == 55 }.offers.none { it.kind == OfferKind.TRACK },
            "EURUSD is nobody's variable — a plain value change gets the plain repairs",
        )

        assertIs<EditResult.Applied>(s.apply(track.op))
        val row = s.model.lines.single { it.row.tag == 11 }
        assertEquals(Matcher.Reference("\${id0}"), row.row.matcher)
        assertFalse(row.unjudged, "the scope is aboard, so the new reference is judged, not amber")
        assertTrue(row.row.passed, "and the echo is this run's id, so it is green")

        s.undo()
        assertEquals(expectation, s.draft, "one undo, and the pinned literal is back byte-for-byte")
    }

    /**
     * The offer matters MOST on a green row: an Exact pinning a minted id passes today *because* the id
     * was minted today, and is red on every run after — the first-replay trust killer, one row at a time.
     */
    @Test
    fun `a green literal that IS the run's variable is offered track — the landmine defusal`() {
        val expectation = Expectation(fields = listOf(FieldExpectation(11, Matcher.Exact("A1"))), messageType = "8")
        val message = wireView(35 to "8", 11 to "A1")
        val s = ReconcileSession(expectation, ref(message).copy(variables = scoped("id0" to "A1")), dictionary)
        val line = s.model.lines.single { it.row.tag == 11 }
        assertTrue(line.row.passed, "green today")
        assertTrue(line.offers.any { it.kind == OfferKind.TRACK }, "and red tomorrow, unless tracked")
    }

    /** No scope proves nothing, and a row that already asserts the correlation has nothing to gain. */
    @Test
    fun `no scope, no track — and a reference row is never offered it`() {
        val expectation =
            Expectation(
                fields =
                    listOf(
                        FieldExpectation(11, Matcher.Exact("STALE")),
                        FieldExpectation(41, Matcher.Reference("\${id0}")),
                    ),
                messageType = "8",
            )
        val message = wireView(35 to "8", 11 to "A1", 41 to "A1")

        val bare = ReconcileSession(expectation, ref(message), dictionary)
        assertTrue(
            bare.model.lines.flatMap { it.offers }.none { it.kind == OfferKind.TRACK },
            "without a scope, value-equality cannot be proved, so nothing is offered",
        )

        val s = ReconcileSession(expectation, ref(message).copy(variables = scoped("id0" to "A1")), dictionary)
        assertTrue(
            s.model.lines.single { it.row.tag == 41 }.offers.none { it.kind == OfferKind.TRACK },
            "already the correlation",
        )
    }

    // ------------------------------------------------------------------ the capture offer

    /**
     * `↧`: capture the venue's value into the scope, named from the dictionary, without changing what the
     * row asserts. Independent of the run scope — authoring against the golden is exactly where the
     * dealer echo gets wired. Offered on red rows too, exactly as the runner captures (a failed row that
     * paired still captures) — but there the tooltip must say the one thing a red-row click most wants to
     * believe and must not: that capture is the repair.
     */
    @Test
    fun `capture is offered wherever the row paired, named from the dictionary, and changes no assertion`() {
        val expectation =
            Expectation(
                fields =
                    listOf(
                        FieldExpectation(37, Matcher.Presence),
                        FieldExpectation(39, Matcher.Exact("0")),
                    ),
                messageType = "8",
            )
        val message = wireView(35 to "8", 37 to "VENUE-77", 39 to "2")
        val s = ReconcileSession(expectation, ref(message), dictionary)

        val capture = s.model.lines.single { it.row.tag == 37 }.offers.single { it.kind == OfferKind.CAPTURE }
        assertTrue("\${orderID}" in capture.tooltip, "named from the dictionary before the click: ${capture.tooltip}")
        assertTrue("Asserts nothing new" in capture.tooltip, "a green row's capture has no warning to carry")
        val onRed = s.model.lines.single { it.row.tag == 39 }.offers.single { it.kind == OfferKind.CAPTURE }
        assertTrue(
            "repairs nothing" in onRed.tooltip,
            "39 is red — the offer must say it is not the repair: ${onRed.tooltip}",
        )

        assertIs<EditResult.Applied>(s.apply(capture.op))
        assertEquals("orderID", s.draft.fields[0].bindAs)
        assertEquals(expectation.fields[0].matcher, s.draft.fields[0].matcher, "asserts nothing new")

        // The captured row now offers the un-capture; taking it is the toggle, and undo works too.
        val stop = s.model.lines.single { it.row.tag == 37 }.offers.single { it.kind == OfferKind.CAPTURE }
        assertTrue("Stop capturing" in stop.tooltip)
        assertIs<EditResult.Applied>(s.apply(stop.op))
        assertNull(s.draft.fields[0].bindAs)

        s.undo()
        assertEquals("orderID", s.draft.fields[0].bindAs)
        s.undo()
        assertEquals(expectation, s.draft, "back to byte-equal, two undos for two edits")
    }

    /** A second capture of the same tag elsewhere gets a suffixed name, never a silent re-assignment. */
    @Test
    fun `capture names collide by suffix, not by reuse`() {
        val expectation =
            Expectation(
                fields =
                    listOf(
                        FieldExpectation(37, Matcher.Presence, bindAs = "orderID"),
                        FieldExpectation(37, Matcher.Presence),
                    ),
                messageType = "8",
            )
        val message = wireView(35 to "8", 37 to "A", 37 to "B")
        val s = ReconcileSession(expectation, ref(message), dictionary)

        val second = s.model.lines.single { it.row.tag == 37 && it.row.occurrence == 1 }
        val capture = second.offers.single { it.kind == OfferKind.CAPTURE }
        assertTrue("\${orderID2}" in capture.tooltip, "suffixed: ${capture.tooltip}")
    }

    /**
     * The venue minted a fresh id against a pinned literal — the row an author most wants to capture, and
     * the one the green-only gate used to hide it on. Capturing stages the wiring and repairs nothing:
     * the row is exactly as red after the click, which is the screen making the tooltip's point.
     */
    @Test
    fun `capture on a failing row captures the paired value and leaves the row exactly as red`() {
        val expectation = Expectation(fields = listOf(FieldExpectation(37, Matcher.Exact("VENUE-OLD"))), messageType = "8")
        val s = ReconcileSession(expectation, ref(wireView(35 to "8", 37 to "VENUE-77")), dictionary)

        val line = s.model.lines.single { it.row.tag == 37 }
        assertFalse(line.row.passed, "a fresh venue id against a pinned literal: red")
        assertIs<EditResult.Applied>(s.apply(line.offers.single { it.kind == OfferKind.CAPTURE }.op))

        assertEquals("orderID", s.draft.fields[0].bindAs)
        assertEquals(expectation.fields[0].matcher, s.draft.fields[0].matcher, "asserts nothing new")
        assertFalse(s.model.lines.single { it.row.tag == 37 }.row.passed, "capture is not the repair — still red")
    }

    /**
     * A stale capture on a row the venue stopped sending must be removable without repairing the row
     * first — a MISSING row pairs with nothing, so the un-capture is the only capture business it has.
     */
    @Test
    fun `a capture whose tag went missing still offers the un-capture`() {
        val expectation =
            Expectation(
                fields =
                    listOf(
                        FieldExpectation(11, Matcher.Presence, bindAs = "clOrdID"),
                        FieldExpectation(39, Matcher.Exact("0")),
                    ),
                messageType = "8",
            )
        val s = ReconcileSession(expectation, ref(wireView(35 to "8", 39 to "0")), dictionary)

        val line = s.model.lines.single { it.row.tag == 11 }
        assertNull(line.row.actual, "the reply no longer carries 11: nothing paired")
        val stop = line.offers.single { it.kind == OfferKind.CAPTURE }
        assertTrue("Stop capturing" in stop.tooltip, "nothing paired, so the un-capture is the only offer")
        assertIs<EditResult.Applied>(s.apply(stop.op))
        assertNull(s.draft.fields[0].bindAs)
    }
}
