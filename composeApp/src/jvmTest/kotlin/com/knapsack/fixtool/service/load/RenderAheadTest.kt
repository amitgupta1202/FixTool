package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.scenario.Lane
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * **Each lane renders ahead of its own sends**, so a lane's message is not queued behind every other
 * lane's rendering on the pacer thread — which is what made lane N systematically later than lane 1 and
 * per-lane latency a false finding waiting to be believed.
 */
class RenderAheadTest {
    private val compiled =
        CompiledTemplate.compile(LoadTemplate("NOS", listOf(35 to "D", 11 to "ORD-\${messageIndex}", 55 to "EUR/USD")))

    private fun prototype(slot: Int) =
        compiled.prepare(Lane(slot, "LOADGEN [$slot]", "LG0$slot", ""), emptyMap(), FixDictionaryAdapter.createDefault()) { it }

    /** Message `m` goes to lane `(m − 1) % lanes`, in both shapes. The look-ahead has to agree. */
    @Test
    fun `a lane's indices are its own arithmetic sequence, and the count matches`() {
        assertEquals(4, RenderAhead.countFor(requested = 10, lanes = 3, index = 0), "1, 4, 7, 10")
        assertEquals(3, RenderAhead.countFor(requested = 10, lanes = 3, index = 1), "2, 5, 8")
        assertEquals(3, RenderAhead.countFor(requested = 10, lanes = 3, index = 2), "3, 6, 9")
        assertEquals(10, (0..2).sumOf { RenderAhead.countFor(10, 3, it) }, "and every message falls to exactly one lane")
        assertEquals(0, RenderAhead.countFor(requested = 2, lanes = 5, index = 3), "a lane with nothing to send renders nothing")
    }

    @Test
    fun `a producer hands back the messages its lane asked for, in order`() {
        RenderAhead(prototype(1), firstIndex = 1, stride = 3, count = 4, name = "test-lane-0").use { ahead ->
            listOf(1, 4, 7, 10).forEach { index ->
                val rendered = assertNotNull(ahead.next(index), "the look-ahead has message $index")
                assertEquals("ORD-$index", sent(rendered).getString(11))
            }
        }
    }

    /**
     * Never send the wrong message. One disagreement and the lane renders inline for the rest of the run,
     * which is exactly what it did before this class existed.
     */
    @Test
    fun `a stream that disagrees about the index gives up look-ahead rather than sending the wrong message`() {
        RenderAhead(prototype(1), firstIndex = 1, stride = 3, count = 4, name = "test-lane-x").use { ahead ->
            assertNull(ahead.next(2), "message 2 is not this lane's, so there is nothing to hand back")
            assertNull(ahead.next(1), "and it stays abandoned rather than resynchronising onto a stale queue")
        }
    }

    @Test
    fun `a closed producer answers with nothing, so the caller renders inline`() {
        val ahead = RenderAhead(prototype(1), firstIndex = 1, stride = 1, count = 100, name = "test-lane-closed")
        ahead.close()

        assertNull(ahead.next(1))
    }

    @Test
    fun `one producer per lane, each starting at its own first index`() {
        val prototypes = (1..3).map { prototype(it) }

        RenderAhead.forLanes(prototypes, requested = 9).let { producers ->
            try {
                assertEquals(3, producers.size)
                assertEquals("ORD-1", sent(assertNotNull(producers[0].next(1))).getString(11))
                assertEquals("ORD-2", sent(assertNotNull(producers[1].next(2))).getString(11))
                assertEquals("ORD-3", sent(assertNotNull(producers[2].next(3))).getString(11))
                assertEquals("ORD-4", sent(assertNotNull(producers[0].next(4))).getString(11))
            } finally {
                producers.forEach { it.close() }
            }
        }
    }

    /** The queue carries a render, so a message that could not be addressed rides it too. */
    private fun sent(rendered: CompiledTemplate.Rendered): quickfix.Message =
        assertIs<CompiledTemplate.Rendered.Message>(rendered, "expected a message, got $rendered").message

    /**
     * A capture the earlier phase never filled comes off the queue as the name that was missing, so the
     * pacer can count it rather than the lane putting `${'$'}{quoteId}` on the wire.
     */
    @Test
    fun `a missing capture rides the queue as the name that was not there`() {
        val compiled = CompiledTemplate.compile(LoadTemplate("hit", listOf(35 to "AJ", 117 to "\${quoteId}")))
        val prototype =
            compiled.prepare(
                lane = Lane(1, "LOADGEN [1]", "LG01", ""),
                seed = emptyMap(),
                dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4),
                lookups = mapOf("quoteId" to { index: Int -> if (index == 2) null else "Q-$index" }),
            ) { it }

        RenderAhead(prototype, firstIndex = 1, stride = 1, count = 3, name = "test-lane-capture").use { ahead ->
            assertEquals("Q-1", sent(assertNotNull(ahead.next(1))).getString(117))
            val refused = assertIs<CompiledTemplate.Rendered.Unaddressable>(assertNotNull(ahead.next(2)))
            assertEquals(2, refused.index)
            assertEquals("quoteId", refused.missing)
            assertEquals("Q-3", sent(assertNotNull(ahead.next(3))).getString(117))
        }
    }
}
