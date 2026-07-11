package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.TagValue
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The editor's draft model must round-trip **everything** the step model holds. The old builder
 * dropped the Expect/Wait `match` predicate and the expectation's `golden` on save — editing a
 * captured scenario silently destroyed its bind predicates. These pin the fix.
 */
class ScenarioEditStepTest {
    @Test
    fun `send round-trips raw including expressions`() {
        val raw = "35=D|11=\${id0 = UUID.randomUUID()}|131=\${id0}|60=\${LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}|"
        val step = ScenarioStep.Send(raw, "QUOTE")

        assertEquals(step, step.toEditStep().toStep())
    }

    @Test
    fun `expect round-trips match predicate, timeout, direction, and golden`() {
        val step = ScenarioStep.Expect(
            session = "TRADE",
            direction = "in",
            match = MatchPredicate("8", null, listOf(TagValue(150, "F"), TagValue(39, "1"))),
            timeoutMs = 5_000,
            expectation = Expectation(
                fields = listOf(FieldExpectation(11, Matcher.Reference("\${id0}"))),
                messageType = "8",
                mode = MatchMode.STRICT,
                golden = "8=FIX.4.4|35=8|11=ORD-1|10=000|",
            ),
        )

        assertEquals(step, step.toEditStep().toStep())
    }

    @Test
    fun `wait, clear, and reset round-trip`() {
        val wait = ScenarioStep.Wait("A", "LOGGED_ON", MatchPredicate("8"), 3_000)
        val clear = ScenarioStep.ClearMessages("B")
        val reset = ScenarioStep.ResetSeqNum("C", sender = 5, target = 7)

        assertEquals(wait, wait.toEditStep().toStep())
        assertEquals(clear, clear.toEditStep().toStep())
        assertEquals(reset, reset.toEditStep().toStep())
    }
}
