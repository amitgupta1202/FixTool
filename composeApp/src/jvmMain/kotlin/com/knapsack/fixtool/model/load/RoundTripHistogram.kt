package com.knapsack.fixtool.model.load

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * **Thirty integers that describe a round-trip distribution of any size.**
 *
 * The report used to reduce every matched round trip to seven numbers ([com.knapsack.fixtool.service
 * .RunSetStats.Distribution]), and no sample reached `load.json` at all. Seven numbers cannot be drawn:
 * neither a bucket histogram nor a share-still-outstanding curve can be recovered from a p50 and a p99.
 * Persisting 300,000 samples to get them back would be the disk problem the memory store exists to remove,
 * moved one directory over.
 *
 * So: a fixed, log-spaced histogram. [BUCKETS] buckets, [PER_DECADE] to a decade, from [FLOOR_MICROS]
 * (0.1ms) to 100 seconds. Fixed means two runs are comparable bucket for bucket without agreeing on
 * anything first. Log-spaced means the shape survives: a distribution whose body is at 3ms and whose tail
 * is at 400ms is one picture here and two pixels on a linear axis. And incremental means the picture is
 * correct *during* a run as well as after one, which is what the live document needs.
 *
 * The exact percentiles keep coming from the sorted sample array at the end. This is for drawing, never
 * for judging: nothing already reported is computed from it.
 */
object RoundTripHistogram {
    const val BUCKETS = 30
    const val PER_DECADE = 5

    /** 0.1ms. Anything faster lands in bucket 0, which on a socket-to-socket round trip is a rarity. */
    const val FLOOR_MICROS = 100L

    /** Which bucket a round trip falls in. Under [FLOOR_MICROS] is 0; over 100s is the last, which is open. */
    fun indexOf(micros: Long): Int {
        if (micros <= FLOOR_MICROS) return 0
        val decades = log10(micros.toDouble() / FLOOR_MICROS)
        return floor(decades * PER_DECADE).toInt().coerceIn(0, BUCKETS - 1)
    }

    /**
     * The first whole microsecond that lands in a bucket.
     *
     * Rounded up, not truncated: bucket 1 begins at 158.49µs, and the truncated 158 belongs to bucket 0.
     * An edge that is not in its own bucket is a label that lies about the bar it sits under.
     */
    fun lowerMicros(index: Int): Long = ceil(FLOOR_MICROS * TEN.pow(index.toDouble() / PER_DECADE)).toLong()

    /** The upper edge, in microseconds. The last bucket has no upper edge and reports [Long.MAX_VALUE]. */
    fun upperMicros(index: Int): Long = if (index >= BUCKETS - 1) Long.MAX_VALUE else lowerMicros(index + 1)

    /** An empty histogram, for a report that has nothing to say yet. */
    fun empty(): List<Int> = List(BUCKETS) { 0 }

    private const val TEN = 10.0
}
