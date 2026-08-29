package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.model.scenario.RunEntry
import com.knapsack.fixtool.model.scenario.RunSet
import com.knapsack.fixtool.model.scenario.RunSource
import com.knapsack.fixtool.model.scenario.RunState
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.StepResult
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

    /**
     * **The send steps are not the venue's number.** Every real flow sends before it expects, and a Send's
     * latency is the time to hand the message to the session — about a millisecond, every time. Counted
     * as samples they drag the p50 down to that millisecond and the report says the venue answered in 1ms
     * when it took 106. The helper above builds only Expects, which is exactly why this went unseen.
     */
    @Test
    fun `a send's local hand-over is not counted as a round trip`() {
        val set =
            laneSet(latencies = listOf(106L, 106L, 105L), durations = listOf(608L, 678L, 550L)).let { s ->
                s.copy(
                    entries =
                        s.entries.map { entry ->
                            val result = entry.result!!
                            entry.copy(
                                result =
                                    result.copy(
                                        steps =
                                            listOf(
                                                StepResult(0, "clear", "setup", passed = true),
                                                StepResult(0, "send", "steps", passed = true, latencyMs = 1L),
                                            ) + result.steps,
                                    ),
                            )
                        },
                )
            }

        val steps = assertNotNull(RunSetStats.stepLatency(set))

        assertEquals(3, steps.samples, "three replies were measured, not six steps")
        assertEquals(106L, steps.p50, "the p50 is the venue's, not the hand-over's")
        assertEquals(106L, steps.max)
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

    /**
     * **The distribution has to leave the screen.** Every number here was computed for the Compose rail
     * alone, so a build step running `--fan-out` against the venue under test — the one consumer the
     * feature exists for — could not read the number the run was for.
     */
    @Test
    fun `the distribution serializes for the consumers that are not a screen`() {
        val set = laneSet(latencies = listOf(106L, 106L, 105L), durations = listOf(608L, 678L, 550L))

        val json = assertNotNull(RunSetStats.toJson(set))

        val reply = json["replyLatency"]!!.jsonObject
        assertEquals(106L, reply["p50"]!!.jsonPrimitive.long)
        assertEquals(106L, reply["max"]!!.jsonPrimitive.long)
        assertEquals(3, reply["samples"]!!.jsonPrimitive.int)
        assertEquals(678L, json["wallClock"]!!.jsonObject["max"]!!.jsonPrimitive.long)
        assertNull(json["failedLanes"], "no lane failed, so the key is absent rather than empty")
    }

    /**
     * **A reopened set has no results to compute from.** `set.json` keeps entries but not their reports,
     * so the round trip is the only way the rail can show a distribution for a set from Recent runs —
     * which is to say, for every set whose run has finished and been closed.
     */
    @Test
    fun `the distribution survives the round trip a reopened set depends on`() {
        val set =
            laneSet(latencies = listOf(106L, 106L, 105L), durations = listOf(608L, 678L, 550L)).let { s ->
                s.copy(entries = s.entries.mapIndexed { i, e -> if (i == 1) e.copy(state = RunState.FAILED) else e })
            }
        val live = assertNotNull(RunSetStats.of(set))

        val back = assertNotNull(RunSetStats.fromJson(RunSetStats.toJson(live)))

        assertEquals(live, back)
        assertEquals(listOf(2), back.failedLanes, "the lane that failed is named on the way back too")
    }

    /**
     * **The reopened set is the case a plain elvis gets wrong**, and the one the rail is for.
     *
     * `set.json` keeps each entry's `durationMs` but none of their reports, so a set read back from it
     * still computes a wall clock — non-null, enough to satisfy `?:`, and carrying no reply latency. The
     * venue's number would stay hidden behind the flow's, which is the number the doc says never to
     * quote. Merged field by field instead.
     */
    @Test
    fun `a reopened set takes reply latency from disk and is not satisfied by its wall clock`() {
        val stored = assertNotNull(RunSetStats.of(laneSet(latencies = listOf(106L, 106L, 105L), durations = listOf(608L, 678L, 550L))))
        // What a set read back from set.json looks like: durations survive, results do not.
        val reopened = assertNotNull(RunSetStats.of(laneSet(latencies = emptyList(), durations = listOf(608L, 678L, 550L))))
        assertNull(reopened.replyLatency, "the premise: a disk set computes a wall clock and nothing else")

        val shown = assertNotNull(RunSetStats.merge(reopened, stored))

        assertEquals(106L, assertNotNull(shown.replyLatency).p50, "the venue's number comes from disk")
        assertEquals(678L, assertNotNull(shown.wallClock).max, "and the wall clock is still the set's own")
    }

    /** Nothing measured means no block at all — an invented zero would be worse than the silence. */
    @Test
    fun `a set that measured nothing serializes to nothing`() {
        assertNull(RunSetStats.toJson(laneSet(latencies = emptyList(), durations = emptyList())))
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
