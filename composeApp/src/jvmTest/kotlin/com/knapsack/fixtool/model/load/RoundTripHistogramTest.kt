package com.knapsack.fixtool.model.load

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Thirty buckets that hold a distribution of any size, and are the same thirty for every run.**
 *
 * Fixed edges are the whole point: two runs compare bucket for bucket without first agreeing on a scale,
 * and a record written by the CLI overnight lines up with one fired in the app an hour later.
 */
class RoundTripHistogramTest {
    @Test
    fun `the edges are five to a decade from a tenth of a millisecond`() {
        assertEquals(0, RoundTripHistogram.indexOf(RoundTripHistogram.FLOOR_MICROS))
        assertEquals(RoundTripHistogram.PER_DECADE, RoundTripHistogram.indexOf(1_000))
        assertEquals(2 * RoundTripHistogram.PER_DECADE, RoundTripHistogram.indexOf(10_000))
        assertEquals(3 * RoundTripHistogram.PER_DECADE, RoundTripHistogram.indexOf(100_000))
    }

    /** Six decades of range: 0.1ms to 100s. Anything faster or slower is still the run's own business. */
    @Test
    fun `everything lands in a bucket, however fast or slow`() {
        assertEquals(0, RoundTripHistogram.indexOf(0), "a zero round trip is not an index out of bounds")
        assertEquals(0, RoundTripHistogram.indexOf(-1))
        assertEquals(RoundTripHistogram.BUCKETS - 1, RoundTripHistogram.indexOf(100_000_000), "100s is the last bucket")
        assertEquals(RoundTripHistogram.BUCKETS - 1, RoundTripHistogram.indexOf(Long.MAX_VALUE), "and so is anything past it")
    }

    @Test
    fun `each bucket's own edges bracket it`() {
        for (i in 0 until RoundTripHistogram.BUCKETS) {
            val lower = RoundTripHistogram.lowerMicros(i)
            assertEquals(i, RoundTripHistogram.indexOf(lower), "the lower edge belongs to its own bucket")
            if (i < RoundTripHistogram.BUCKETS - 1) {
                assertTrue(RoundTripHistogram.upperMicros(i) > lower, "and the upper edge is above it")
            }
        }
    }

    @Test
    fun `an empty histogram is the right length`() {
        assertEquals(RoundTripHistogram.BUCKETS, RoundTripHistogram.empty().size)
        assertTrue(RoundTripHistogram.empty().all { it == 0 })
    }
}
