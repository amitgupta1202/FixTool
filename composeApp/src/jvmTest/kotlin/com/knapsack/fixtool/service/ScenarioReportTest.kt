package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.GroupPath
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
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
    @Test
    fun `tag json carries the group path when present and omits it when not`() {
        val pathed = TagResult(448, "exact BROKER-A", "BROKER-A", "CLIENT-X", passed = false, path = GroupPath(453, 452, "1"))
        val json = ScenarioReport.tagToJson(pathed)
        assertEquals(448, json["tag"]?.jsonPrimitive?.content?.toInt())
        val path = json["path"]?.jsonObject ?: error("path object missing")
        assertEquals("453", path["groupTag"]?.jsonPrimitive?.content)
        assertEquals("452", path["identityTag"]?.jsonPrimitive?.content)
        assertEquals("1", path["identityValue"]?.jsonPrimitive?.content)

        val flat = ScenarioReport.tagToJson(TagResult(35, "exact 8", "8", "8", passed = true))
        assertNull(flat["path"])
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
                                    TagResult(448, "exact B", "B", "X", passed = false, path = GroupPath(453, 452, "1")),
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
