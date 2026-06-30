package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.GroupPath
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import com.knapsack.fixtool.model.scenario.TagValue
import com.knapsack.fixtool.model.scenario.TemporalKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScenarioCodecTest {
    @Test
    fun `scenario round-trips through json unchanged`() {
        val scenario =
            Scenario(
                id = "sc-1",
                name = "book-a-trade",
                profile = "demo",
                setup = listOf(ScenarioStep.ClearMessages("CLI"), ScenarioStep.ResetSeqNum("CLI", sender = 1, target = 1)),
                steps = listOf(
                    ScenarioStep.Send("35=D|11=\${id=\"ORD-1\"}|", session = "CLI"),
                    ScenarioStep.Wait(session = "CLI", state = "LOGGED_ON", timeoutMs = 5_000),
                    ScenarioStep.Expect(
                        session = "CLI",
                        direction = "in",
                        match = MatchPredicate(messageType = "8", direction = "in", fields = listOf(TagValue(150, "F"))),
                        timeoutMs = 8_000,
                        expectation = Expectation(
                            fields = listOf(
                                FieldExpectation(35, Matcher.Exact("8")),
                                FieldExpectation(39, Matcher.OneOf(listOf("1", "2"))),
                                FieldExpectation(31, Matcher.Numeric(1.2345, 0.00005)),
                                FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 30)),
                                FieldExpectation(11, Matcher.Reference("\${out.D.11}")),
                                FieldExpectation(448, Matcher.Exact("BROKER-A"), GroupPath(453, 452, "1")),
                            ),
                            messageType = "8",
                            mode = MatchMode.STRICT,
                        ),
                    ),
                ),
                teardown = listOf(ScenarioStep.Send("35=5|", session = "CLI")),
                userTags = listOf("demo"),
            )
        val restored = ScenarioCodec.fromJson(ScenarioCodec.toJson(scenario))
        assertEquals(scenario, restored)
    }

    @Test
    fun `junit xml reports a failing step`() {
        val result =
            ScenarioResult(
                scenario = "book-a-trade",
                passed = false,
                steps = listOf(
                    StepResult(0, "send", "steps", passed = true),
                    StepResult(
                        1,
                        "expect",
                        "steps",
                        passed = false,
                        tags = listOf(TagResult(39, "exact 2", "2", "0", passed = false)),
                    ),
                ),
            )
        val xml = ScenarioReport.toJUnitXml(result)
        assertTrue(xml.contains("<testsuite name=\"book-a-trade\" tests=\"2\" failures=\"1\""), xml)
        assertTrue(xml.contains("<failure"), xml)
        assertTrue(xml.contains("tag 39"), xml)
    }
}
