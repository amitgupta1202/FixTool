package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.withIds
import com.knapsack.fixtool.model.scenario.withSession
import com.knapsack.fixtool.model.scenario.withSessions
import org.junit.Test
import kotlin.test.assertEquals

/**
 * [withSessions] is the "same flow, other environment" primitive: a run input that re-aims steps
 * without being an edit. The tests pin the two halves of that promise — every phase's sessions go
 * through the map, and nothing else about the steps (identity above all) moves.
 */
class ScenarioSessionMapTest {
    private val scenario =
        Scenario(
            id = "x",
            name = "envs",
            setup = listOf(ScenarioStep.ClearMessages(session = "dev-buyside"), ScenarioStep.ClearMessages(session = "dev-sellside")),
            steps =
                listOf(
                    ScenarioStep.Send("35=D|", session = "dev-buyside"),
                    ScenarioStep.Expect(session = "dev-sellside", expectation = Expectation(fields = emptyList(), messageType = "8")),
                    ScenarioStep.Send("35=D|", session = "other"),
                    ScenarioStep.Send("35=D|", session = null),
                ),
            teardown = listOf(ScenarioStep.ClearMessages(session = "dev-buyside")),
        )

    private val map = mapOf("dev-buyside" to "qa-buyside", "dev-sellside" to "qa-sellside")

    @Test
    fun `maps every phase's sessions, and only the sessions the map names`() {
        val mapped = scenario.withSessions(map)
        assertEquals(listOf("qa-buyside", "qa-sellside"), mapped.setup.map { it.session })
        // "other" is not in the map and stays; the null (active) session is not remappable at all.
        assertEquals(listOf("qa-buyside", "qa-sellside", "other", null), mapped.steps.map { it.session })
        assertEquals(listOf("qa-buyside"), mapped.teardown.map { it.session })
    }

    @Test
    fun `remapping changes nothing about a step but its session`() {
        val mapped = scenario.withSessions(map)
        assertEquals(
            scenario.steps.map { it.withSession("normalized") },
            mapped.steps.map { it.withSession("normalized") },
            "raw, expectation, mute state — everything but the session must survive the remap untouched",
        )
    }

    @Test
    fun `step identity survives a remap — reconcile must find the same steps a plain run reports`() {
        val plain = scenario.withIds()
        val mapped = scenario.withIds().withSessions(map)
        assertEquals(plain.steps.map { it.stepId }, mapped.steps.map { it.stepId })
        assertEquals(plain.setup.map { it.stepId }, mapped.setup.map { it.stepId })
    }

    @Test
    fun `an empty map is the identity`() {
        assertEquals(scenario, scenario.withSessions(emptyMap()))
    }
}
