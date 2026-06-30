package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Serializes a [ScenarioResult] for its two consumers: JSON for the control surface / the in-app
 * red-green overlay, and JUnit XML for CI. The same per-tag results drive both.
 */
object ScenarioReport {
    fun toJson(result: ScenarioResult): JsonObject =
        buildJsonObject {
            put("scenario", result.scenario)
            put("passed", result.passed)
            put("steps", buildJsonArray { result.steps.forEach { add(stepToJson(it)) } })
        }

    fun stepToJson(step: StepResult): JsonObject =
        buildJsonObject {
            put("stepIndex", step.stepIndex)
            put("kind", step.kind)
            put("phase", step.phase)
            put("passed", step.passed)
            step.detail?.let { put("detail", it) }
            put("tags", buildJsonArray { step.tags.forEach { add(tagToJson(it)) } })
        }

    fun tagToJson(tag: TagResult): JsonObject =
        buildJsonObject {
            put("tag", tag.tag)
            put("matcher", tag.matcher)
            put("expected", tag.expected)
            put("actual", tag.actual)
            put("passed", tag.passed)
        }

    /** Renders a [ScenarioResult] as a single-suite JUnit XML document for CI consumption. */
    fun toJUnitXml(result: ScenarioResult): String {
        val failures = result.steps.count { !it.passed }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<testsuite name=\"").append(esc(result.scenario)).append("\" tests=\"")
            .append(result.steps.size).append("\" failures=\"").append(failures).append("\">\n")
        for (step in result.steps) {
            val caseName = "step ${step.stepIndex} ${step.kind} (${step.phase})"
            sb.append("  <testcase name=\"").append(esc(caseName)).append("\" classname=\"")
                .append(esc(result.scenario)).append("\">")
            if (!step.passed) {
                val failed = step.tags.filter { !it.passed }
                val msg =
                    step.detail
                        ?: failed.joinToString("; ") { "tag ${it.tag}: expected ${it.expected}, actual ${it.actual}" }
                        .ifEmpty { "step failed" }
                sb.append("\n    <failure message=\"").append(esc(msg)).append("\"/>\n  ")
            }
            sb.append("</testcase>\n")
        }
        sb.append("</testsuite>\n")
        return sb.toString()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
