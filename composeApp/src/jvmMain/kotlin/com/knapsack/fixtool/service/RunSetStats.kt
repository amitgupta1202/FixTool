package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.RunSet
import com.knapsack.fixtool.model.scenario.RunState

/**
 * **What fifty lanes have to say, which is not fifty rows.**
 *
 * Nobody clicks through fifty lanes. "Did all fifty pass" is the weaker question; *what did the ninety-fifth
 * percentile cost* is the one a venue is being asked, and it has to be measured between the bytes that left
 * and the bytes that answered — which is [com.knapsack.fixtool.model.scenario.StepResult.latencyMs], not a
 * lane's wall clock.
 *
 * The wall clock is reported too, and separately, because it answers a different question: a flow that
 * passes fifty times while drifting from two seconds to eight has said something no pass/fail can. But it
 * carries preflight, the strict settle window, the pause between entries and every Expect timeout, so it is
 * never offered as the venue's number.
 */
object RunSetStats {
    /** A distribution over one set's samples. Empty when nothing was measured — never zeroes. */
    data class Distribution(
        val p50: Long,
        val p95: Long,
        val max: Long,
        val samples: Int,
    )

    /**
     * The venue's number, across every entry of the set: the latency of each step that measured one.
     *
     * Null when no step did — a set of sends with no expectations has no round trip to report, and an
     * invented zero would be worse than the silence.
     */
    fun stepLatency(set: RunSet): Distribution? =
        distribution(
            set.entries.flatMap { entry ->
                entry.result
                    ?.steps
                    .orEmpty()
                    .mapNotNull { it.latencyMs }
            },
        )

    /** The flow's number: how long each entry took, start to verdict. */
    fun wallClock(set: RunSet): Distribution? = distribution(set.entries.mapNotNull { it.durationMs })

    /** The lanes that failed, by slot — what a fifty-lane report points at instead of listing fifty rows. */
    fun failedLanes(set: RunSet): List<Int> =
        set.entries.filter { it.state == RunState.FAILED || it.state == RunState.STOPPED }.mapNotNull { it.lane?.slot }

    /**
     * `p50 214ms · p95 1.9s · max 8.4s` — the one line a load run is read from.
     *
     * Seconds past a thousand milliseconds, because "1900ms" and "8400ms" are numbers a reader has to
     * convert before they mean anything, and this line exists to be understood at a glance.
     */
    fun describe(distribution: Distribution): String =
        "p50 ${humanMs(distribution.p50)} · p95 ${humanMs(distribution.p95)} · max ${humanMs(distribution.max)}"

    fun humanMs(ms: Long): String = if (ms >= 1_000) "${"%.1f".format(ms / 1000.0)}s" else "${ms}ms"

    /**
     * The **nearest-rank** percentile: the sample at position ⌈p·n⌉, which is always a measurement that
     * actually happened. Interpolating between two samples would report a latency no message ever had — a
     * fine choice for a smooth distribution and the wrong one for a report whose whole claim is that these
     * are the numbers the venue produced.
     */
    private fun distribution(samples: List<Long>): Distribution? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        return Distribution(
            p50 = sorted.nearestRank(P50),
            p95 = sorted.nearestRank(P95),
            max = sorted.last(),
            samples = sorted.size,
        )
    }

    private fun List<Long>.nearestRank(percentile: Double): Long {
        val rank = Math.ceil(percentile * size).toInt().coerceIn(1, size)
        return this[rank - 1]
    }

    private const val P50 = 0.50
    private const val P95 = 0.95
}
