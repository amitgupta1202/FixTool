package com.knapsack.fixtool.model

import com.knapsack.fixtool.service.RunSetStats
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * **The latency panel's percentiles are nearest-rank, the definition a load report uses.**
 *
 * `sorted[(n * p).toInt()]` was one rank high. With ten samples p90 was the slowest of them, and the
 * median of 100, 200, 300 and 400 was 300 where a load run's p50 over the same four is 200. Two panels
 * reading the same round trips have to agree on what p50 means.
 */
class LatencyStatsAccumulatorTest {
    private fun statsOf(samples: List<Long>): LatencyStatistics =
        LatencyStatsAccumulator().apply { samples.forEach { addSample(it) } }.getStatistics()

    @Test
    fun `the median of four samples is the second, as a load report's p50 is`() {
        val stats = statsOf(listOf(400, 100, 300, 200))

        assertEquals(200L, stats.medianMicros)
        assertEquals(assertNotNull(RunSetStats.of(longArrayOf(100, 200, 300, 400))).p50, stats.medianMicros)
    }

    @Test
    fun `with ten samples p90 is the ninth and not the slowest`() {
        val stats = statsOf((1L..10L).map { it * 100 })

        assertEquals(900L, stats.p90Micros)
        assertEquals(1_000L, stats.p95Micros, "the ninety-fifth percentile of ten is the tenth")
        assertEquals(1_000L, stats.maxMicros)
    }

    @Test
    fun `over a hundred samples each percentile is the sample at its own rank`() {
        val stats = statsOf((100L downTo 1L).toList())

        assertEquals(50L, stats.medianMicros)
        assertEquals(90L, stats.p90Micros)
        assertEquals(95L, stats.p95Micros)
        assertEquals(99L, stats.p99Micros)
    }
}
