package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.RunSet
import com.knapsack.fixtool.model.scenario.RunState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

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
     * The venue's number, across every entry of the set: the latency of each step that measured a
     * **round trip**.
     *
     * Not every step that carries a `latencyMs` measured one. A Send's latency is the time to hand the
     * message to the session — local work, and by construction about a millisecond — so counting it here
     * halves the p50 of the commonest flow there is (one send, one expect) and reports the venue as
     * twice as fast as it is. Only an Expect, and a Wait that a message satisfied, span the bytes that
     * left and the bytes that answered.
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
                    .filter { it.kind in ROUND_TRIP_KINDS }
                    .mapNotNull { it.latencyMs }
            },
        )

    /** The flow's number: how long each entry took, start to verdict. */
    fun wallClock(set: RunSet): Distribution? = distribution(set.entries.mapNotNull { it.durationMs })

    /**
     * **The distribution, as a report artifact rather than a screen.**
     *
     * Every number here was computed only for the Compose rail, so the one consumer that most needs it —
     * a build step running `--fan-out` against the venue under test — could not read it at all, and the
     * documentation promising "a p50/p95/max distribution rather than N rows" was true of exactly one
     * surface. Written into `set.json` when the set is saved, which is the only moment the per-step
     * latencies are all in hand: they live in the sibling record files afterwards, not in the set.
     *
     * Null when nothing was measured — a set of sends with no expectations has no round trip, and an
     * invented zero would be worse than the silence.
     */
    fun toJson(set: RunSet): JsonObject? = of(set)?.let(::toJson)

    /** Everything a set has to say about its timings, or null when it measured nothing. */
    data class Stats(
        val replyLatency: Distribution?,
        val wallClock: Distribution?,
        val failedLanes: List<Int> = emptyList(),
    )

    /** Computed from a set that still holds its results — a live one, mid-run or just finished. */
    fun of(set: RunSet): Stats? {
        val steps = stepLatency(set)
        val wall = wallClock(set)
        if (steps == null && wall == null) return null
        return Stats(steps, wall, failedLanes(set))
    }

    fun toJson(stats: Stats): JsonObject =
        buildJsonObject {
            stats.replyLatency?.let { put("replyLatency", distributionJson(it)) }
            stats.wallClock?.let { put("wallClock", distributionJson(it)) }
            stats.failedLanes.takeIf { it.isNotEmpty() }?.let { lanes ->
                put("failedLanes", buildJsonArray { lanes.forEach { add(it) } })
            }
        }

    /**
     * **What to show, given a set in hand and a block on disk.**
     *
     * Not an elvis. A set read from `set.json` keeps every entry's `durationMs`, so [of] answers it with a
     * wall clock and no reply latency — non-null, and therefore enough to swallow a plain `?:` and leave
     * the venue's number hidden behind the flow's. The two sources are merged field by field: whichever
     * has the measurement wins, and the stored block supplies exactly what the reopened set cannot
     * recompute.
     */
    fun merge(live: Stats?, stored: Stats?): Stats? {
        if (live == null) return stored
        if (stored == null) return live
        return Stats(
            replyLatency = live.replyLatency ?: stored.replyLatency,
            wallClock = live.wallClock ?: stored.wallClock,
            failedLanes = live.failedLanes.ifEmpty { stored.failedLanes },
        )
    }

    /**
     * **Read back, for a set that no longer holds its results.**
     *
     * `set.json` stores entries but not their reports — those live in the sibling record files — so a set
     * reopened from Recent runs cannot recompute any of this. Without this the distribution was a thing
     * you could see only while the run that produced it was still on screen.
     */
    fun fromJson(obj: JsonObject): Stats? {
        val reply = (obj["replyLatency"] as? JsonObject)?.let(::distributionFrom)
        val wall = (obj["wallClock"] as? JsonObject)?.let(::distributionFrom)
        if (reply == null && wall == null) return null
        return Stats(
            reply,
            wall,
            (obj["failedLanes"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull },
        )
    }

    private fun distributionFrom(obj: JsonObject): Distribution? {
        val p50 = obj["p50"]?.jsonPrimitive?.longOrNull ?: return null
        val p95 = obj["p95"]?.jsonPrimitive?.longOrNull ?: return null
        val max = obj["max"]?.jsonPrimitive?.longOrNull ?: return null
        return Distribution(p50, p95, max, obj["samples"]?.jsonPrimitive?.intOrNull ?: 0)
    }

    private fun distributionJson(d: Distribution): JsonObject =
        buildJsonObject {
            put("p50", d.p50)
            put("p95", d.p95)
            put("max", d.max)
            put("samples", d.samples)
        }

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

    /**
     * The step kinds whose `latencyMs` is a round trip. A Wait satisfied by a connection state rather
     * than a message reports no latency at all, so it drops out on its own.
     */
    private val ROUND_TRIP_KINDS = setOf("expect", "wait")
}
