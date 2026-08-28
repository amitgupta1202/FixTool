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
 * **And it re-anchors when the two clocks genuinely part company.** `nanoTime` does not tick while the
 * machine is asleep, and a wall clock does — a laptop shut for four minutes leaves the counter four
 * minutes behind civil time, and every stamp taken after it says the wrong *when*. Found by a test that
 * asserted the two stay close and came back 280 seconds apart. So past a second of divergence the wall
 * clock wins: it is the one telling the truth about *when*, while the counter is still the one telling
 * the truth about *how long*. A stamp never goes backwards either way, because a latency that comes out
 * negative is the one number nobody can act on.
 */
object CaptureClock {
    private val originMicros = System.currentTimeMillis() * 1_000
    private val originNanos = System.nanoTime()

    /** The last stamp issued — the guard that keeps the sequence monotonic across threads and re-anchors. */
    private val last = java.util.concurrent.atomic.AtomicLong(0)

    /** Microseconds since the epoch: this process's own counter, corrected back onto civil time if it slept. */
    fun micros(): Long {
        val counted = originMicros + (System.nanoTime() - originNanos) / 1_000
        val wall = System.currentTimeMillis() * 1_000
        val candidate = if (wall - counted > REANCHOR_MICROS) wall else counted
        return last.updateAndGet { previous -> maxOf(previous, candidate) }
    }

    /**
     * How far the counter may fall behind civil time before the wall clock takes over. A second is far
     * more than scheduling noise and far less than any suspension worth correcting for; below it, the
     * counter's microseconds are what a latency is measured with.
     */
    private const val REANCHOR_MICROS = 1_000_000L
}
