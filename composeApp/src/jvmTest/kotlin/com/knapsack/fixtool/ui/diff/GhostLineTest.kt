package com.knapsack.fixtool.ui.diff

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.compare.ChunkKind
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.service.wireView
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The ghost line, as the surface holds it** — the engine-side clauses are pinned in
 * `ReconcileGhostLineTest`; these are the ones the diff model adds on top: the gutter may not offer on it,
 * `n`/`p` walk past it, and the crossing connector finally has a far end to land on.
 */
class GhostLineTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val arrival = Instant.parse("2025-01-01T00:00:00Z")

    private fun session(expectation: Expectation, message: com.knapsack.fixtool.service.MessageView) =
        ReconcileSession(
            expectation,
            ReferenceMessage.live(message, ReferenceMessage.Provenance.THIS_RUN, "this run", arrival),
            dictionary,
        )

    private val reply = wireView(35 to "8", 11 to "ORD-1", 150 to "2")

    /** Captured in wire order, then row 11 moved to the end — the edit that used to vanish the field. */
    private val movedRow =
        Expectation(
            listOf(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(150, Matcher.Exact("2")),
                FieldExpectation(11, Matcher.Exact("ORD-1")),
            ),
            mode = MatchMode.STRICT,
        )

    @Test
    fun `the ghost line faces the field, gaps on the left, and is offered nothing`() {
        val model = session(movedRow, reply).model

        val ghost = model.lines.single { it.row.ghost }
        assertTrue(ghost.leftIsGap, "the expectation has no row at this position — the left is a gap")
        assertEquals(11, ghost.right?.tag, "the right cell holds the field the venue sent")
        assertEquals(1, ghost.right?.wireIndex)
        assertTrue(ghost.offers.isEmpty(), "an offer here would seed a second row for an asserted tag")
        assertEquals(ChunkKind.SAME, ghost.kind, "the divergence is the moved row's to report, not the ghost's")

        // The invariant, at the surface: the right column is the reply, whole, in wire order.
        assertEquals(
            reply.fields().indices.toList(),
            model.lines.mapNotNull { it.right?.wireIndex },
            "every field the venue sent has a line, once, in wire order",
        )
    }

    @Test
    fun `the crossing connector has a far end — the ghost is the line facing the landing`() {
        val model = session(movedRow, reply).model

        val movedChunk = model.chunks.single { it.kind == ChunkKind.MOVED }
        assertEquals(listOf(1), movedChunk.landing, "the engine proved where the row lands")

        val farEnd = model.itemFacingWire(movedChunk.landing.first())
        assertNotNull(farEnd, "before the ghost existed, nothing faced the landing and no connector could draw")
        val item = model.items[farEnd]
        assertTrue(item is DiffItem.Line && item.line.row.ghost, "and the line it finds is the ghost")

        // The connector's far end is the LANDED ROWS — every landing wire index mapped through the line
        // facing it — never the chunk those lines sit in. The ghost is a SAME line inside the big block of
        // passing rows; centring the curve on that whole chunk would aim it at the middle of the green rows
        // instead of at the field that travelled. So the destination must be exactly the ghost, one line.
        val landedItems = movedChunk.landing.mapNotNull { model.itemFacingWire(it) }
        assertEquals(listOf(farEnd), landedItems, "the connector lands on the ghost alone, not the passing block")
        val enclosingChunk = (item as DiffItem.Line).line.chunkId
        assertTrue(
            model.itemsOfChunk(enclosingChunk).size > landedItems.size,
            "the ghost's chunk is the whole passing block — proof that expanding to it would mis-aim the curve",
        )
    }

    @Test
    fun `n and p walk past the ghost to the row that owns the failure`() {
        val model = session(movedRow, reply).model
        val ghostChunk = model.lines.single { it.row.ghost }.chunkId
        assertTrue(
            ghostChunk !in model.diffChunks,
            "the ghost is not a difference to stop at — the moved row's chunk is",
        )
        assertTrue(model.chunks.any { it.id in model.diffChunks && it.kind == ChunkKind.MOVED })
    }

    @Test
    fun `dropping the moved row releases the field — the ghost becomes an extra with the offer`() {
        val session = session(movedRow, reply)
        session.apply(EditOp.drop(index = 2, tag = 11))

        val lines = session.model.lines
        assertTrue(lines.none { it.row.ghost }, "nothing speaks for the field any more")
        val extra = lines.single { it.row.unasserted && it.row.tag == 11 }
        assertTrue(
            extra.offers.any { it.kind == OfferKind.ASSERT_IT },
            "unclaimed for real now, it earns the « like any extra",
        )
    }
}
