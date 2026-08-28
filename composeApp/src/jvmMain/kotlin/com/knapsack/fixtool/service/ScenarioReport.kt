package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioVariable
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
            // Additive, and only when the run minted anything: a report from before the scope was
            // reported — or a scenario with no `${...}` at all — keeps its exact old shape.
            if (result.variables.isNotEmpty()) {
                put("variables", buildJsonArray { result.variables.forEach { add(variableToJson(it)) } })
            }
        }

    /** `{name, value, mintedAtStepId?}` — what a `${name}` held this run, and which step wrote it. */
    fun variableToJson(variable: ScenarioVariable): JsonObject =
        buildJsonObject {
            put("name", variable.name)
            put("value", variable.value)
            variable.mintedAtStepId?.let { put("mintedAtStepId", it) }
        }

    fun stepToJson(step: StepResult): JsonObject =
        buildJsonObject {
            put("stepIndex", step.stepIndex)
            // Which step, as opposed to which slot. The index is where it sat during *this* run; the id is
            // what a consumer can still resolve after the scenario has been edited underneath it.
            step.stepId?.let { put("stepId", it) }
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
            // Which row, and which occurrence of the tag it refers to. Without them two failures on the
            // same tag — the two party entries — read as the same row reported twice, and a reader has
            // no way to say which assertion to go and fix.
            tag.index?.let { put("index", it) }
            put("occurrence", tag.occurrence)
            put("status", tag.status.name.lowercase())
        }

    /**
     * Renders a [ScenarioResult] as a single-suite JUnit XML document for CI consumption.
     *
     * **The suite's verdict is the run's verdict.** Teardown is best-effort cleanup and is exempt from
     * the verdict (`ScenarioRunner`: a teardown Expect that times out, or a clear whose tab was closed
     * mid-run, must not flip an otherwise-green run). This counted every failed step, so the same run
     * could exit 0 and hand CI a report saying `failures="1"` — two halves of one run disagreeing about
     * whether it passed, and the build believing the half that was not the verdict.
     *
     * A failed teardown is still **reported**, under `<system-out>` on its own testcase: a reader sees
     * exactly what went wrong with the cleanup, and a build gate does not trip on it. Silence would have
     * been the other way to make the two agree, and the wrong one.
     *
     * Run-level rows — preflight, connect, the strict-traffic verdict, the binding and ingest caveats —
     * carry index -1 because no step produced them (there is nothing to blame, and nothing to edit).
     * They rendered as `step -1 traffic (steps)`; they name themselves by kind instead.
     */
    fun toJUnitXml(result: ScenarioResult): String {
        val failures = result.steps.count { !it.passed && !it.exemptFromVerdict }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<testsuite name=\"").append(esc(result.scenario)).append("\" tests=\"")
            .append(result.steps.size).append("\" failures=\"").append(failures).append("\">\n")
        for (step in result.steps) {
            sb.append("  <testcase name=\"").append(esc(caseName(step))).append("\" classname=\"")
                .append(esc(result.scenario)).append("\">")
            if (!step.passed) {
                val message = esc(failureMessage(step))
                if (step.exemptFromVerdict) {
                    sb.append("\n    <system-out>").append(message).append("</system-out>\n  ")
                } else {
                    sb.append("\n    <failure message=\"").append(message).append("\"/>\n  ")
                }
            }
            sb.append("</testcase>\n")
        }
        sb.append("</testsuite>\n")
        return sb.toString()
    }

    /** Exactly the runner's own rule, so the XML and the verdict cannot come to disagree. */
    private val StepResult.exemptFromVerdict: Boolean get() = phase == "teardown"

    /** `step 3 expect (steps)` for a step; `traffic (steps)` for a row no step produced. */
    private fun caseName(step: StepResult): String =
        if (step.stepIndex < 0) "${step.kind} (${step.phase})" else "step ${step.stepIndex} ${step.kind} (${step.phase})"

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
                .joinToString("; ") { "tag ${name(it)}: expected ${it.expected}, actual ${it.actual}" }
        return listOf(step.detail, diff.ifEmpty { null })
            .filterNotNull()
            .joinToString(" — ")
            .ifEmpty { "step failed" }
    }

    /**
     * "452" for a tag that appears once, "452#2" for the second of several.
     *
     * A message with two party entries fails on "tag 452" twice, and an engineer reading that in CI
     * cannot tell whether one assertion is broken or two, let alone which. The occurrence is the row's
     * only address under the sequence model, so a report that omits it has not said where to look.
     */
    private fun name(tag: TagResult): String =
        if (tag.occurrence == 0) "${tag.tag}" else "${tag.tag}#${tag.occurrence + 1}"

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
