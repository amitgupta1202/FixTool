package com.knapsack.fixtool.service

/**
 * **The clock every captured message is stamped with — one origin, one tick, one process.**
 *
 * The stamp used to be `System.currentTimeMillis() * 1000 + (System.nanoTime() % 1_000_000) / 1000`,
 * which is not a clock: the millisecond part comes from the wall clock and the sub-millisecond part is
 * a remainder of an unrelated monotonic counter, so the two are uncorrelated. Two messages 200µs apart
 * could be stamped almost a millisecond apart, **in the wrong order** — and everything that subtracts
 * two stamps was reading that noise as a measurement. On a loopback venue, where a reply comes back
 * inside the same millisecond as the order, it was the dominant term.
 *
 * So: the wall clock supplies the **origin** once, and `nanoTime` supplies every **increment** after it.
 * The result is monotonic within the process, immune to an NTP step mid-run (a latency that goes
 * backwards because the clock was corrected is the one number nobody can act on), and comparable across
 * sessions — which is what lets a run record put two sessions' messages in one arrival order.
 *
 * It is deliberately *not* a wall clock for display. Over hours it drifts from civil time by whatever
 * the machine's oscillator drifts; a message's shown timestamp is its own `LocalDateTime`, and this is
 * only ever used for differences and ordering.
 */
object CaptureClock {
    private val originMicros = System.currentTimeMillis() * 1_000
    private val originNanos = System.nanoTime()

    /** Microseconds since the epoch, as measured from this process's own origin. */
    fun micros(): Long = originMicros + (System.nanoTime() - originNanos) / 1_000
}
