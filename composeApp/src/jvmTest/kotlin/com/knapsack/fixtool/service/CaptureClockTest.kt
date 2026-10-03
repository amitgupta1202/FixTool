package com.knapsack.fixtool.service

import org.junit.Test
import kotlin.test.assertEquals
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

    /**
     * Anchored to civil time, so a stamp is a point in the day and not merely an offset — **including
     * after the machine has been asleep**, which is what the counter alone cannot survive. This test
     * failed at -280s on a laptop that shut mid-run, which is how the re-anchor came to exist.
     */
    @Test
    fun `stamps sit close to the wall clock, even after the counter has fallen behind`() {
        val wallMicros = System.currentTimeMillis() * 1_000
        val stamp = CaptureClock.micros()
        assertTrue(
            kotlin.math.abs(stamp - wallMicros) < 2_000_000L,
            "the stamp should be within a couple of seconds of civil time, was ${(stamp - wallMicros) / 1_000_000}s away",
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

    /**
     * **A re-anchor moves the origin, so the stamps after a sleep count microseconds again.**
     *
     * It used to hand back the wall clock itself on every call after the first sleep, because the origin
     * never moved and the gap never closed. Every stamp was then a whole millisecond, and a 300µs round
     * trip on a loopback venue read 0 or 1,000 until the app was restarted.
     */
    @Test
    fun `after the machine sleeps, stamps count the counter's microseconds again`() {
        var wallMillis = 1_800_000_000_000L
        var nanos = 5_000_000_000L
        val clock = CaptureClock.Anchored(wallMillis = { wallMillis }, nanos = { nanos })

        // Five minutes with the lid shut: the wall clock moves and the counter does not.
        wallMillis += 300_000
        val woke = clock.micros()
        assertEquals(wallMillis * 1_000, woke, "the first stamp after the sleep is back on civil time")

        // A 300µs round trip inside one wall-clock millisecond, then another across a millisecond edge.
        nanos += 300_000
        val reply = clock.micros()
        nanos += 300_000
        wallMillis += 1
        val second = clock.micros()

        assertEquals(300L, reply - woke, "a round trip after the sleep is measured by the counter, not the wall clock")
        assertEquals(300L, second - reply, "and so is the next one, whichever millisecond the wall clock is in")
    }
}
