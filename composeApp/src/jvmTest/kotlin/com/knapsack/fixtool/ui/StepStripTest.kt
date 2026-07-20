package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.withIds
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The strip is derived, and it answers one question: what is left to do.**
 *
 * It is what a window scoped to a single step could never show — the shape of the pass — so its colours have to
 * be right about steps the author has not opened, has already fixed, or never ran.
 */
class StepStripTest {
    private fun expect(tag: Int) =
        ScenarioStep.Expect(
            expectation =
                Expectation(fields = listOf(FieldExpectation(tag, Matcher.Exact("0"))), messageType = "8"),
        )

    private val seed =
        Scenario(
            id = "sc-1",
            name = "rfq flow",
            steps = listOf(ScenarioStep.Send("35=D|"), expect(150), expect(151), expect(152)),
        ).withIds()

    private fun ids() = seed.steps.drop(1).map { it.stepId }

    private fun results(vararg passed: Pair<String, Boolean>): Map<String, StepResult> =
        passed.associate { (id, ok) -> id to StepResult(0, "expect", "steps", ok, stepId = id) }

    private fun strip(
        draft: Scenario = seed,
        current: String = ids()[0],
        results: Map<String, StepResult> = emptyMap(),
        armed: String? = null,
    ) = stepStripOf(ScenarioDraft(draft, seed), current, results, armed)

    /** Sends are not reconcilable, so they are not on a strip that exists to be clicked. */
    @Test
    fun `the strip is the Expect steps, numbered by where they sit among all of them`() {
        val chips = strip()

        assertEquals(3, chips.size, "three Expects, and the Send is not one of them")
        assertEquals(listOf(1, 2, 3), chips.map { it.index }, "positions among ALL steps, so it agrees with the crumb")
        assertTrue(chips.first().label.startsWith("2"), "the first Expect is step 2 of the scenario")
    }

    @Test
    fun `a run colours what it reached, and says so about what it did not`() {
        val ids = ids()
        val chips = strip(results = results(ids[0] to true, ids[1] to false))

        assertEquals(StepStatus.PASSING, chips[0].status)
        assertEquals(StepStatus.FAILING, chips[1].status)
        assertEquals(StepStatus.NOT_REACHED, chips[2].status, "the run stopped at the failure, so this never ran")
    }

    @Test
    fun `with no run at all, nothing claims to know anything`() {
        assertTrue(strip().all { it.status == StepStatus.NOT_RUN })
    }

    /**
     * **A chip answers "is this still on my list", not "what did the last run say".** A step the author has
     * already repaired is not on the list — it is waiting for the re-run that will confirm it — so REPAIRED
     * outranks the FAILING verdict that is still standing against it.
     */
    @Test
    fun `a repaired step reads as repaired even though the standing verdict still says it failed`() {
        val ids = ids()
        val repaired = seed.copy(steps = seed.steps.map { if (it.stepId == ids[0]) expect(999).copy(stepId = ids[0]) else it })

        val chips = strip(draft = repaired, results = results(ids[0] to false))

        assertEquals(StepStatus.REPAIRED, chips[0].status)
    }

    /**
     * **Measured draft-against-seed, never against the step's session** — because a repair that travelled in
     * from a sibling step (C2) is written straight into the draft and never touches that step's session at
     * all. This is the case that rules out asking the session: the step has no slot in the window whatsoever.
     */
    @Test
    fun `a step repaired by a travelling fix reads as repaired with no session anywhere near it`() {
        val ids = ids()
        val travelled = seed.copy(steps = seed.steps.map { if (it.stepId == ids[2]) expect(999).copy(stepId = ids[2]) else it })

        val chips = stepStripOf(ScenarioDraft(travelled, seed), currentStepId = ids[0], results = emptyMap(), armedStepId = null)

        assertEquals(StepStatus.REPAIRED, chips[2].status, "the draft differs from disk here, whoever put it there")
    }

    @Test
    fun `the current step and an armed step are both marked, and they need not be the same step`() {
        val ids = ids()
        val chips = strip(current = ids[0], armed = ids[2])

        assertTrue(chips[0].current)
        assertTrue(chips[2].armed, "a slot armed on a step the author has walked away from still says so")
        assertTrue(chips[2].tooltip.contains("waiting for a grid click"), chips[2].tooltip)
    }

    @Test
    fun `the summary counts what is left and what is waiting to be re-run`() {
        val ids = ids()
        val repaired = seed.copy(steps = seed.steps.map { if (it.stepId == ids[0]) expect(999).copy(stepId = ids[0]) else it })

        val summary = stepStripSummary(stepStripOf(ScenarioDraft(repaired, seed), ids[0], results(ids[1] to false, ids[2] to false), null))

        assertEquals("2 of 3 failing · 1 repaired, not saved", summary)
    }

    @Test
    fun `a green scenario says so, and an empty strip says nothing at all`() {
        val ids = ids()
        assertEquals(
            "all 3 passing",
            stepStripSummary(strip(results = results(ids[0] to true, ids[1] to true, ids[2] to true))),
        )
        assertEquals("", stepStripSummary(emptyList()))
    }

    /** `8` is the wire's word for it, not a reader's — the chip carries the dictionary's name, shortened. */
    @Test
    fun `a chip names the message type rather than its wire code`() {
        val chips = stepStripOf(ScenarioDraft(seed, seed), ids()[0], emptyMap(), null) { if (it == "8") "ExecutionReport" else null }

        assertEquals("2 ExecutionReport", chips[0].label)
        assertTrue(chips[0].tooltip.contains("Expect ExecutionReport"), chips[0].tooltip)
    }

    /** With no dictionary behind it, the raw type is still better than nothing. */
    @Test
    fun `without a dictionary the chip falls back to the wire code`() {
        assertEquals("2 8", stepStripOf(ScenarioDraft(seed, seed), ids()[0], emptyMap(), null)[0].label)
    }
}
