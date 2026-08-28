package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioVariable
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the report wire shape consumed by the control surface, MCP, and CI. The `path` field is
 * additive: present only for group-entry assertions, absent (not null) for top-level ones, so
 * pre-existing consumers keep parsing.
 */
class ScenarioReportTest {
    /**
     * `variables` is additive in exactly the way `path` was: present only when the run minted something,
     * absent (not an empty array) otherwise, so a report from a scenario with no `${...}` — and every
     * pre-scope consumer — keeps its byte-for-byte shape.
     */
    @Test
    fun `variables are reported with their values and minting step, and the key is absent without them`() {
        val bare = ScenarioResult("s", true, steps = listOf(StepResult(0, "send", "steps", true)))
        assertNull(ScenarioReport.toJson(bare)["variables"], "no scope, no key")

        val scoped =
            bare.copy(
                variables =
                    listOf(
                        ScenarioVariable("id0", "A1B2", "step-uuid"),
                        ScenarioVariable("sym", "EURUSD", null),
                    ),
            )
        val json = ScenarioReport.toJson(scoped)["variables"]!!.jsonArray
        assertEquals(2, json.size)
        assertEquals("id0", json[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("A1B2", json[0].jsonObject["value"]?.jsonPrimitive?.content)
        assertEquals("step-uuid", json[0].jsonObject["mintedAtStepId"]?.jsonPrimitive?.content)
        assertNull(json[1].jsonObject["mintedAtStepId"], "an unattributed mint carries no id key")
    }

    @Test
    fun `tag json says which row failed and which occurrence it checked`() {
        // Two party entries fail on the same tag. Without the row index and the occurrence, the report
        // says "tag 448" twice and a reader cannot tell which assertion to go and fix.
        val second = TagResult(448, "exact BROKER-A", "BROKER-A", "CLIENT-X", passed = false, index = 5, occurrence = 1)
        val json = ScenarioReport.tagToJson(second)
        assertEquals(448, json["tag"]?.jsonPrimitive?.content?.toInt())
        assertEquals("5", json["index"]?.jsonPrimitive?.content)
        assertEquals("1", json["occurrence"]?.jsonPrimitive?.content)
        assertEquals("ok", json["status"]?.jsonPrimitive?.content)

        // A row that is not a duplicate carries no index only when it is an unexpected extra.
        val flat = ScenarioReport.tagToJson(TagResult(35, "exact 8", "8", "8", passed = true))
        assertNull(flat["index"])
        assertEquals("0", flat["occurrence"]?.jsonPrimitive?.content)
    }

    /**
     * A real expect step always carries a detail ("messageType=8") — ScenarioRunner sets it on every
     * match. The failure message used to be `detail ?: <tag diff>`, so the diff was dead code and CI
     * showed `<failure message="messageType=8"/>`: a red build with no reason, and an engineer having
     * to re-run the scenario locally to find out which tag mismatched. The fixture below carries a
     * detail precisely because the old test did not, which is how the bug survived.
     */
    @Test
    fun `junit xml names the failing tag, not just the matched message type`() {
        val result =
            ScenarioResult(
                scenario = "rfq",
                passed = false,
                steps =
                    listOf(
                        StepResult(
                            stepIndex = 2,
                            kind = "expect",
                            phase = "steps",
                            passed = false,
                            detail = "messageType=8", // what every real run sets
                            tags =
                                listOf(
                                    TagResult(39, "exact 2", "2", "8", passed = false),
                                    TagResult(448, "exact B", "B", "X", passed = false, index = 5, occurrence = 1),
                                ),
                        ),
                    ),
            )
        val xml = ScenarioReport.toJUnitXml(result)

        assertTrue(xml.contains("failures=\"1\""))
        assertTrue(xml.contains("messageType=8"), "keep the context: $xml")
        assertTrue(xml.contains("tag 39: expected 2, actual 8"), "the diff is the point of the report: $xml")
        assertTrue(xml.contains("tag 448"), xml)
    }

    /**
     * A FIX value may hold a newline or a control character; an XML attribute may not. Now that the
     * failure message always carries actual wire values, one such byte would make a CI parser reject
     * the whole report — "the report is unreadable" being a far worse thing for a build to say than
     * "one test failed".
     */
    @Test
    fun `a wire value with a newline or control character does not break the xml`() {
        val result =
            ScenarioResult(
                scenario = "rfq",
                passed = false,
                steps =
                    listOf(
                        StepResult(
                            1,
                            "expect",
                            "steps",
                            passed = false,
                            detail = "messageType=8",
                            tags = listOf(TagResult(58, "exact ok", "ok", "line1\nline2", passed = false)),
                        ),
                    ),
            )

        val xml = ScenarioReport.toJUnitXml(result)

        assertTrue(xml.lineSequence().none { it.contains("line2") && !it.contains("<failure") }, "no raw newline: $xml")
        assertFalse(xml.contains(""), "control characters must not reach the attribute: $xml")
        assertTrue(xml.contains("tag 58"), xml)
    }

    /**
     * The two halves of one run must agree about whether it passed. Teardown is best-effort cleanup and
     * the verdict exempts it — but the XML counted it, so `fixtool run` exited 0 while the report it
     * wrote said `failures="1"` and the build went red on a run the tool called green.
     */
    @Test
    fun `a failed teardown is reported without failing the suite`() {
        val result =
            ScenarioResult(
                scenario = "rfq",
                passed = true, // the verdict: teardown does not decide it
                steps =
                    listOf(
                        StepResult(0, "send", "steps", passed = true),
                        StepResult(0, "clear", "teardown", passed = false, detail = "session 'CLI' not found"),
                    ),
            )

        val xml = ScenarioReport.toJUnitXml(result)

        assertTrue(xml.contains("failures=\"0\""), "the suite's verdict is the run's verdict: $xml")
        assertFalse(xml.contains("<failure"), "a cleanup problem must not trip a build gate: $xml")
        // Reported, not swallowed: silence was the other way to make the two agree, and the wrong one.
        assertTrue(xml.contains("<system-out>"), "the cleanup problem is still visible: $xml")
        assertTrue(xml.contains("session 'CLI' not found"), xml)
        assertTrue(xml.contains("tests=\"2\""), "and the row is still a testcase: $xml")
    }

    /** A run-level row is nobody's step, and `step -1 traffic (steps)` said otherwise. */
    @Test
    fun `a run-level row names itself by kind, not by a step index it does not have`() {
        val result =
            ScenarioResult(
                scenario = "rfq",
                passed = false,
                steps =
                    listOf(
                        StepResult(-1, "traffic", "steps", passed = false, detail = "2 incoming message(s) were never bound"),
                        StepResult(2, "expect", "steps", passed = true),
                    ),
            )

        val xml = ScenarioReport.toJUnitXml(result)

        assertFalse(xml.contains("step -1"), "no step produced it, so it cannot be named for one: $xml")
        assertTrue(xml.contains("name=\"traffic (steps)\""), xml)
        assertTrue(xml.contains("name=\"step 2 expect (steps)\""), "a real step still says where it sat: $xml")
        assertTrue(xml.contains("failures=\"1\""), "and a run-level red is still a red: $xml")
    }

    @Test
    fun `a step that failed with no tag diff still explains itself`() {
        val result =
            ScenarioResult(
                scenario = "rfq",
                passed = false,
                steps = listOf(StepResult(1, "expect", "steps", passed = false, detail = "timeout after 8000ms")),
            )
        assertTrue(ScenarioReport.toJUnitXml(result).contains("timeout after 8000ms"))
    }
}
