package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepOrigin
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

    /**
     * The editor normalizes a Send on the way through (parse → re-join) and does it on every document
     * open, not just on an edit — so anything the round trip cannot represent is deleted from the author's
     * scenario silently, and the dirty flag, measured against the normalized seed, never notices. An
     * excluded field is exactly the kind of thing that would go that way. This is the guard.
     */
    @Test
    fun `send round-trips excluded fields, so opening a scenario cannot quietly delete them`() {
        val step = ScenarioStep.Send("35=D|11=ORD|#9303=1|38=100|", "QUOTE")

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
        val clearBook = ScenarioStep.ClearOrderBook("D")

        assertEquals(wait, wait.toEditStep().toStep())
        assertEquals(clear, clear.toEditStep().toStep())
        assertEquals(reset, reset.toEditStep().toStep())
        assertEquals(clearBook, clearBook.toEditStep().toStep())
    }

    /**
     * Muted and origin ride the same round trip. Origin is the one with teeth: `toStep()` used to build
     * every step with the default LIVE, so saving a scenario whose steps were PASTED quietly laundered
     * the provenance badge away — the exact "more trust than the bytes carry" the badge exists to stop.
     */
    @Test
    fun `muted and origin survive the editor round-trip on every step kind`() {
        val steps = listOf(
            ScenarioStep.Send("35=D|11=X|", "A", origin = StepOrigin.PASTED, muted = true),
            ScenarioStep.Wait("A", "LOGGED_ON", null, 3_000, origin = StepOrigin.PASTED, muted = true),
            ScenarioStep.Expect(
                session = "A",
                expectation = Expectation(emptyList()),
                origin = StepOrigin.PASTED,
                muted = true,
            ),
            ScenarioStep.ClearMessages("A", origin = StepOrigin.PASTED, muted = true),
            ScenarioStep.ResetSeqNum("A", sender = 1, target = 2, origin = StepOrigin.PASTED, muted = true),
            ScenarioStep.ClearOrderBook("A", origin = StepOrigin.PASTED, muted = true),
        )
        steps.forEach { step -> assertEquals(step, step.toEditStep().toStep()) }
    }
}
