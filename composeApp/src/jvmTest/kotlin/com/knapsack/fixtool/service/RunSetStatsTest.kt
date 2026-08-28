package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.model.scenario.RunEntry
import com.knapsack.fixtool.model.scenario.RunSet
import com.knapsack.fixtool.model.scenario.RunSource
import com.knapsack.fixtool.model.scenario.RunState
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.StepResult
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * **Fifty lanes have to say something that is not fifty rows.** "Did all fifty pass" is the weaker
 * question; what the ninety-fifth percentile cost is the one a venue is being asked — and it is measured
 * between the bytes that left and the bytes that answered, not by a lane's wall clock.
 */
class RunSetStatsTest {
    @Test
    fun `the venue's number comes from step latency, and the flow's from wall clock`() {
        val set = laneSet(latencies = listOf(10L, 20L, 30L, 400L), durations = listOf(1_000L, 1_100L, 1_200L, 5_000L))

        val steps = assertNotNull(RunSetStats.stepLatency(set))
        assertEquals(4, steps.samples)
        assertEquals(400L, steps.max)
        // Nearest-rank: every number reported is one a message actually produced.
        assertEquals(20L, steps.p50)
        assertEquals(400L, steps.p95)

        val wall = assertNotNull(RunSetStats.wallClock(set))
        assertEquals(5_000L, wall.max)
        assertEquals(1_100L, wall.p50)
    }

    /** No round trip means no number — an invented zero would be worse than the silence. */
    @Test
    fun `a set that measured nothing reports nothing`() {
        val set = laneSet(latencies = emptyList(), durations = emptyList())
        assertNull(RunSetStats.stepLatency(set))
        assertNull(RunSetStats.wallClock(set))
    }

    /** A fifty-lane report points at the lanes that failed rather than listing fifty rows. */
    @Test
    fun `failed lanes are named by slot`() {
        val set =
            laneSet(latencies = listOf(1L, 2L), durations = listOf(1L, 2L)).let { s ->
                s.copy(entries = s.entries.mapIndexed { i, e -> if (i == 1) e.copy(state = RunState.FAILED) else e })
            }

        assertEquals(listOf(2), RunSetStats.failedLanes(set))
    }

    /** Seconds past a thousand milliseconds, because this line exists to be read at a glance. */
    @Test
    fun `the summary line speaks in the units a reader thinks in`() {
        val line = RunSetStats.describe(RunSetStats.Distribution(p50 = 214, p95 = 1_900, max = 8_400, samples = 50))

        assertEquals("p50 214ms · p95 1.9s · max 8.4s", line)
    }

    private fun laneSet(latencies: List<Long>, durations: List<Long>): RunSet {
        val count = maxOf(latencies.size, durations.size, 1)
        return RunSet(
            id = "set",
            label = "lanes",
            source = RunSource.FanOut("sc", "prof"),
            entries =
                (0 until count).map { i ->
                    RunEntry(
                        scenarioId = "sc",
                        scenarioName = "book-a-trade",
                        lane = Lane(i + 1, "L [${i + 1}]", "L${i + 1}", "q"),
                        state = RunState.PASSED,
                        durationMs = durations.getOrNull(i),
                        result =
                            latencies.getOrNull(i)?.let {
                                ScenarioResult(
                                    scenario = "book-a-trade",
                                    passed = true,
                                    steps = listOf(StepResult(0, "expect", "steps", passed = true, latencyMs = it)),
                                )
                            },
                    )
                },
        )
    }
}
