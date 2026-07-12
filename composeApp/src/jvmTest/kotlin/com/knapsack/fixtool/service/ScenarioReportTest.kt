package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.GroupPath
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
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

    @Test
    fun `junit xml still renders for pathed failures`() {
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
                            tags = listOf(TagResult(448, "exact B", "B", "X", passed = false, path = GroupPath(453, 452, "1"))),
                        ),
                    ),
            )
        val xml = ScenarioReport.toJUnitXml(result)
        assertTrue(xml.contains("failures=\"1\""))
        assertTrue(xml.contains("tag 448"))
    }
}
