package com.knapsack.fixtool.service

import org.junit.Test
import kotlin.test.assertTrue

/**
 * The stamp every latency in the tool is a difference of.
 *
 * It used to be `currentTimeMillis() * 1000 + (nanoTime() % 1_000_000) / 1000` — a millisecond from one
 * clock and a sub-millisecond remainder from an unrelated one. Two messages microseconds apart could be
 * stamped a millisecond apart in the wrong order, which on a loopback venue was the dominant term in
 * every measurement taken from them.
 */
class CaptureClockTest {
    @Test
    fun `stamps never go backwards`() {
        var previous = CaptureClock.micros()
        repeat(20_000) {
            val next = CaptureClock.micros()
            assertTrue(next >= previous, "a clock that goes backwards makes a latency negative: $previous then $next")
            previous = next
        }
    }

    /** Anchored to civil time, so a stamp is still a point in the day and not merely an offset. */
    @Test
    fun `stamps sit close to the wall clock`() {
        val wallMicros = System.currentTimeMillis() * 1_000
        val stamp = CaptureClock.micros()
        assertTrue(
            kotlin.math.abs(stamp - wallMicros) < 60_000_000L,
            "the stamp should be within a minute of civil time, was ${(stamp - wallMicros) / 1_000_000}s away",
        )
    }

    /**
     * The property a run record depends on: one clock for the whole process, so two sessions' messages
     * can be put in one arrival order.
     */
    @Test
    fun `elapsed time between two stamps is the time that actually elapsed`() {
        val before = CaptureClock.micros()
        Thread.sleep(50)
        val after = CaptureClock.micros()
        val elapsedMs = (after - before) / 1_000
        assertTrue(elapsedMs in 40..5_000, "50ms of sleep should read as roughly 50ms, read ${elapsedMs}ms")
    }
}
