package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadShape
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The pacer never skips a scheduled message.** It runs late, catches up, and reports where and by how
 * much, so the report can say "fell behind for nineteen seconds" instead of quietly issuing less.
 */
class PacerTest {
    /** Time that moves only when the test says so: waits jump to the mark, and an issue may take a while. */
    private class FakeClock(
        var now: Long = 0,
    ) : Pacer.Clock {
        override fun nanoTime(): Long = now

        override fun awaitUntil(deadlineNanos: Long) {
            if (deadlineNanos > now) now = deadlineNanos
        }
    }

    @Test
    fun `a burst goes round the lanes in order, one message index each, as fast as they return`() {
        val clock = FakeClock()
        val seen = mutableListOf<Pair<Int, Int>>()

        val stats = Pacer(LoadShape.Burst(100), lanes = 4, clock = clock).run({ lane, index -> seen += lane to index; true }, { false })

        assertEquals(100, seen.size)
        assertEquals(listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5), seen.take(5))
        assertEquals(25, seen.count { it.first == 2 })
        assertEquals(100L, stats.handedToEngine)
        assertEquals(0L, stats.maxLagNanos)
        assertEquals(emptyList(), stats.shortfalls)
        assertTrue(!stats.stopped)
    }

    @Test
    fun `a rate schedules every message at its mark and holds the histogram at the requested rate`() {
        val clock = FakeClock(now = 7_000_000_000L)
        val issuedAt = mutableListOf<Long>()

        val stats = Pacer(LoadShape.Rate(perSecond = 1_000, forMs = 3_000), lanes = 2, clock = clock).run({ _, _ -> issuedAt += clock.now; true }, { false })

        assertEquals(3_000, issuedAt.size)
        assertEquals(7_000_000_000L, issuedAt.first())
        assertEquals(1_000_000L, issuedAt[1] - issuedAt[0], "one millisecond between marks at a thousand a second")
        assertEquals(listOf(1_000, 1_000, 1_000), stats.perSecondIssued.toList())
        assertEquals(0L, stats.maxLagNanos)
        assertEquals(emptyList(), stats.shortfalls)
    }

    @Test
    fun `a stall mid-run is one shortfall span, the run catches up, and no index is skipped`() {
        val clock = FakeClock()
        val indexes = mutableListOf<Int>()
        val stalledOn = 150

        val stats =
            Pacer(LoadShape.Rate(perSecond = 100, forMs = 5_000), lanes = 5, clock = clock).run(
                { _, index ->
                    indexes += index
                    if (index == stalledOn) clock.now += 1_500_000_000L
                    true
                },
                { false },
            )

        assertEquals((1..500).toList(), indexes, "every scheduled message went, in order")
        // The stalled message is counted when its send returned, in second 2, which is where it actually went.
        assertEquals(listOf(100, 49, 151, 100, 100), stats.perSecondIssued.toList())
        assertEquals(listOf(Pacer.Shortfall(fromSecond = 1, toSecond = 1, minPerSecond = 49, behind = 51)), stats.shortfalls)
        assertTrue(stats.maxLagNanos in 1_400_000_000L..1_500_000_000L, "the message after the stall went about a second and a half late: ${stats.maxLagNanos}")
    }

    @Test
    fun `cancellation stops after the current message and reports what went`() {
        val clock = FakeClock()
        var count = 0

        val stats = Pacer(LoadShape.Burst(1_000), lanes = 3, clock = clock).run({ _, _ -> count++; true }, { count >= 10 })

        assertEquals(10, count)
        assertEquals(10L, stats.handedToEngine)
        assertTrue(stats.stopped)
    }

    @Test
    fun `a refused send is an issue failure, counted and not retried`() {
        val stats = Pacer(LoadShape.Burst(10), lanes = 1, clock = FakeClock()).run({ _, index -> index % 2 == 0 }, { false })

        assertEquals(5L, stats.handedToEngine)
        assertEquals(5L, stats.issueFailures)
        assertEquals(10L, stats.issued)
    }

    @Test
    fun `consecutive short seconds fold into one span and the final partial second is never judged`() {
        val spans = Pacer.shortfalls(intArrayOf(500, 412, 430, 500, 300, 500, 12), perSecond = 500)

        assertEquals(
            listOf(
                Pacer.Shortfall(1, 2, minPerSecond = 412, behind = 158),
                Pacer.Shortfall(4, 4, minPerSecond = 300, behind = 200),
            ),
            spans,
        )
    }
}
