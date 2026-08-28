package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioVariable
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.model.scenario.VariableSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
            // Additive, and only when the run timed itself: a report assembled by something that did not
            // keeps its exact old shape. See [ScenarioResult.durationMs] for why this is not the number a
            // venue's latency is measured with.
            result.durationMs?.let { put("durationMs", it) }
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
            // Additive: a run that seeded nothing reports every name as a step's, which is what it is.
            variable.source.takeIf { it != VariableSource.STEP }?.let { put("source", it.name.lowercase()) }
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
            // The venue's number, when there was one to take: absent means not measured, which is not the
            // same as fast. See [StepResult.latencyMs].
            step.latencyMs?.let { put("latencyMs", it) }
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
     * **The inverse of [toJson]** — a report read back from a run record.
     *
     * Phase 2's viewer renders an entry that ran an hour ago from exactly the bytes CI was handed, so the
     * two cannot drift into describing the same run differently. Unknown values degrade the way the
     * codec's do: a tag status this build does not recognise reads as OK rather than failing the whole
     * record, because a record is evidence, and refusing to show evidence over one unfamiliar enum name
     * is the worse failure.
     */
    fun fromJson(obj: JsonObject): ScenarioResult =
        ScenarioResult(
            scenario = obj["scenario"]?.jsonPrimitive?.content.orEmpty(),
            passed = obj["passed"]?.jsonPrimitive?.booleanOrNull ?: false,
            steps = obj["steps"]?.jsonArray.orEmpty().map { stepFromJson(it.jsonObject) },
            variables = obj["variables"]?.jsonArray.orEmpty().map { variableFromJson(it.jsonObject) },
            durationMs = obj["durationMs"]?.jsonPrimitive?.longOrNull,
        )

    private fun variableFromJson(obj: JsonObject): ScenarioVariable =
        ScenarioVariable(
            name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
            value = obj["value"]?.jsonPrimitive?.content.orEmpty(),
            mintedAtStepId = obj["mintedAtStepId"]?.jsonPrimitive?.contentOrNull,
            source =
                obj["source"]?.jsonPrimitive?.contentOrNull
                    ?.let { name -> VariableSource.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                    ?: VariableSource.STEP,
        )

    private fun stepFromJson(obj: JsonObject): StepResult =
        StepResult(
            stepIndex = obj["stepIndex"]?.jsonPrimitive?.intOrNull ?: -1,
            kind = obj["kind"]?.jsonPrimitive?.content.orEmpty(),
            phase = obj["phase"]?.jsonPrimitive?.content.orEmpty(),
            passed = obj["passed"]?.jsonPrimitive?.booleanOrNull ?: false,
            detail = obj["detail"]?.jsonPrimitive?.contentOrNull,
            tags = obj["tags"]?.jsonArray.orEmpty().map { tagFromJson(it.jsonObject) },
            stepId = obj["stepId"]?.jsonPrimitive?.contentOrNull,
            latencyMs = obj["latencyMs"]?.jsonPrimitive?.longOrNull,
        )

    private fun tagFromJson(obj: JsonObject): TagResult =
        TagResult(
            tag = obj["tag"]?.jsonPrimitive?.intOrNull ?: 0,
            matcher = obj["matcher"]?.jsonPrimitive?.content.orEmpty(),
            expected = obj["expected"]?.jsonPrimitive?.content.orEmpty(),
            actual = obj["actual"]?.jsonPrimitive?.contentOrNull,
            passed = obj["passed"]?.jsonPrimitive?.booleanOrNull ?: false,
            index = obj["index"]?.jsonPrimitive?.intOrNull,
            occurrence = obj["occurrence"]?.jsonPrimitive?.intOrNull ?: 0,
            status =
                obj["status"]?.jsonPrimitive?.contentOrNull
                    ?.let { name -> TagStatus.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                    ?: TagStatus.OK,
        )

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
    fun toJUnitXml(result: ScenarioResult): String =
        StringBuilder()
            .append(XML_DECLARATION)
            .append(suiteXml(result.scenario, result, indent = ""))
            .toString()

    /**
     * **A whole run set as one document** — `<testsuites>` around the suites this already renders.
     *
     * Each entry names its own suite, because "which one is this" is the question a CI report has to
     * answer about a set: an iteration is `book-a-trade #3`, and a row (Phase 3) will be
     * `book-a-trade [EUR/USD partial fill]`, the way a parameterized test has always named itself. The
     * caller supplies the names rather than this deriving them, because only the caller knows whether
     * two identical scenario names are two iterations or two scenarios.
     */
    fun toJUnitXml(suites: List<Pair<String, ScenarioResult>>): String {
        val tests = suites.sumOf { (_, r) -> r.steps.size }
        val failures = suites.sumOf { (_, r) -> r.steps.count { !it.passed && !it.exemptFromVerdict } }
        // Summed, not measured: the wrapper has no clock of its own, and a set's wall clock is the sum of
        // its entries plus whatever the scheduler paused for — which is not a number about the venue.
        val time = suites.mapNotNull { (_, r) -> r.durationMs }.takeIf { it.isNotEmpty() }?.sum()
        val sb = StringBuilder()
        sb.append(XML_DECLARATION)
        sb.append("<testsuites tests=\"").append(tests).append("\" failures=\"").append(failures).append("\"")
            .append(timeAttr(time)).append(">\n")
        suites.forEach { (name, result) -> sb.append(suiteXml(name, result, indent = "  ")) }
        sb.append("</testsuites>\n")
        return sb.toString()
    }

    private fun suiteXml(name: String, result: ScenarioResult, indent: String): String {
        val failures = result.steps.count { !it.passed && !it.exemptFromVerdict }
        val sb = StringBuilder()
        sb.append(indent).append("<testsuite name=\"").append(esc(name)).append("\" tests=\"")
            .append(result.steps.size).append("\" failures=\"").append(failures).append("\"")
            .append(timeAttr(result.durationMs)).append(">\n")
        for (step in result.steps) {
            sb.append(indent).append("  <testcase name=\"").append(esc(caseName(step))).append("\" classname=\"")
                .append(esc(name)).append("\"").append(timeAttr(step.latencyMs)).append(">")
            if (!step.passed) {
                val message = esc(failureMessage(step))
                if (step.exemptFromVerdict) {
                    sb.append("\n").append(indent).append("    <system-out>").append(message).append("</system-out>\n").append(indent).append("  ")
                } else {
                    sb.append("\n").append(indent).append("    <failure message=\"").append(message).append("\"/>\n").append(indent).append("  ")
                }
            }
            sb.append("</testcase>\n")
        }
        sb.append(indent).append("</testsuite>\n")
        return sb.toString()
    }

    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

    /** Exactly the runner's own rule, so the XML and the verdict cannot come to disagree. */
    private val StepResult.exemptFromVerdict: Boolean get() = phase == "teardown"

    /**
     * JUnit's ` time="1.234"` in seconds, or nothing at all when the duration is unknown.
     *
     * Omitted rather than zeroed: `time="0.000"` is a claim that the step took no time, and a CI report
     * that says so of every step from an older run has quietly invented a measurement. [Locale.ROOT]
     * because a machine whose locale writes `1,234` would emit an attribute no XML consumer can parse.
     */
    private fun timeAttr(ms: Long?): String =
        ms?.let { String.format(java.util.Locale.ROOT, " time=\"%.3f\"", it / 1000.0) } ?: ""

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
