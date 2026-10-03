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
 * So: the wall clock supplies the **origin** at start, and `nanoTime` supplies every **increment** after it.
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
 *
 * **Winning means becoming the new origin.** The wall clock and the counter's reading at that instant
 * replace the pair taken at start, together, and every stamp after it counts the counter's increments
 * again. Handing back the wall clock itself, with the origin left where it was, never closed the gap: every
 * stamp after the first sleep was a whole millisecond, and a 300µs round trip read 0 or 1,000 until the
 * app was restarted.
 */
object CaptureClock {
    private val process = Anchored(wallMillis = System::currentTimeMillis, nanos = System::nanoTime)

    /** Microseconds since the epoch: this process's own counter, corrected back onto civil time if it slept. */
    fun micros(): Long = process.micros()

    /**
     * The arithmetic behind [micros], with its two sources passed in so a test can put the machine to
     * sleep. [CaptureClock] is the one instance the process stamps with.
     */
    internal class Anchored(
        private val wallMillis: () -> Long,
        private val nanos: () -> Long,
    ) {
        /** A wall-clock reading and the counter's reading at the same instant: what a stamp counts from. */
        private class Origin(
            val micros: Long,
            val nanos: Long,
        )

        /** One reference rather than two fields, so a re-anchor moves both halves at once. */
        private val origin =
            java.util.concurrent.atomic
                .AtomicReference(Origin(wallMillis() * 1_000, nanos()))

        /** The last stamp issued — the guard that keeps the sequence monotonic across threads and re-anchors. */
        private val last =
            java.util.concurrent.atomic
                .AtomicLong(0)

        fun micros(): Long {
            val now = nanos()
            val wall = wallMillis() * 1_000
            val from =
                origin.updateAndGet { o -> if (wall - counted(o, now) > REANCHOR_MICROS) Origin(wall, now) else o }
            return last.updateAndGet { previous -> maxOf(previous, counted(from, now)) }
        }

        private fun counted(from: Origin, now: Long): Long = from.micros + (now - from.nanos) / 1_000
    }

    /**
     * How far the counter may fall behind civil time before the wall clock takes over. A second is far
     * more than scheduling noise and far less than any suspension worth correcting for; below it, the
     * counter's microseconds are what a latency is measured with.
     */
    private const val REANCHOR_MICROS = 1_000_000L
}
