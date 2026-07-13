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
            // Same shape as the scenario codec's FieldExpectation path, so a report row can be
            // traced back to the exact group-entry assertion that produced it.
            tag.path?.let { p ->
                put(
                    "path",
                    buildJsonObject {
                        put("groupTag", p.groupTag)
                        put("identityTag", p.identityTag)
                        put("identityValue", p.identityValue)
                    },
                )
            }
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
                sb.append("\n    <failure message=\"").append(esc(failureMessage(step))).append("\"/>\n  ")
            }
            sb.append("</testcase>\n")
        }
        sb.append("</testsuite>\n")
        return sb.toString()
    }

    /**
     * What failed, and why. The per-tag diff is the whole point of the engine, and it used to be
     * dropped: the message was `detail ?: <tag diff>`, but an expect step always carries a detail
     * ("messageType=8"), so CI only ever saw that and an engineer had to re-run the scenario locally
     * to find out which tag mismatched. Both now — the context *and* the diff.
     */
    private fun failureMessage(step: StepResult): String {
        val diff =
            step.tags
                .filter { !it.passed }
                .joinToString("; ") { "tag ${it.tag}: expected ${it.expected}, actual ${it.actual}" }
        return listOf(step.detail, diff.ifEmpty { null })
            .filterNotNull()
            .joinToString(" — ")
            .ifEmpty { "step failed" }
    }

    /**
     * XML-escapes, and drops what XML 1.0 cannot carry at all.
     *
     * A FIX value may hold a newline or a control character; an XML attribute may not. Since the
     * failure message now always contains actual wire values, one such byte would make a CI parser
     * reject the whole report — turning "one test failed" into "the report is unreadable", which is a
     * far worse thing for a build to say.
     */
    /** XML 1.0 cannot carry most control characters at all, whether escaped or not. */
    private fun carriable(c: Char): Boolean = c.code >= 0x20 || c in "\t\n\r"

    private fun esc(s: String): String =
        s.map { c -> if (carriable(c)) c else ' ' }
            .joinToString("")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("\n", "&#10;")
            .replace("\r", "&#13;")
            .replace("\t", "&#9;")
}
