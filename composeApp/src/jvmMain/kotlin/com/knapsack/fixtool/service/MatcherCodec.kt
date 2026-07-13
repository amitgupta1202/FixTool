package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Converts between the JSON encodings used on the control surface (see
 * `docs/scenario-assertion-model.md`) and the typed [Matcher] / [FieldExpectation] / [Expectation]
 * model. Parsing throws [IllegalArgumentException] on malformed input so the caller can return a
 * clean error.
 */
object MatcherCodec {
    fun parseExpectation(fields: JsonArray, messageType: String?, mode: MatchMode): Expectation =
        Expectation(
            fields = fields.map { parseFieldExpectation(it.jsonObject) },
            messageType = messageType,
            mode = mode,
        )

    fun parseFieldExpectation(obj: JsonObject): FieldExpectation {
        val tag = obj["tag"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("field expectation missing integer 'tag'")
        val matcherObj = obj["matcher"]?.jsonObject
            ?: throw IllegalArgumentException("field expectation for tag $tag missing 'matcher'")
        rejectGroupPath(obj, tag)
        return FieldExpectation(tag = tag, matcher = parseMatcher(matcherObj))
    }

    /**
     * A row that still carries `path` or `occurrence` was written by the group-path model, and it is
     * **refused**, loudly, rather than read as if the key were not there.
     *
     * `path` meant "the entry whose PartyID is FIRMA". A row without it means "the k-th occurrence of
     * this tag". Those are different assertions, and on a message with two entries for the same firm
     * they point at different fields. Dropping the key silently would leave the author with a scenario
     * that loads, runs, goes green, and checks something they never wrote — which is the exact failure
     * this whole model exists to remove, arriving through the back door of an upgrade.
     */
    private fun rejectGroupPath(obj: JsonObject, tag: Int) {
        val stale = listOf("path", "occurrence").firstOrNull { obj.containsKey(it) } ?: return
        throw IllegalArgumentException(
            "the assertion on tag $tag carries '$stale', from the group-path model this build replaced. " +
                "An expectation is now an ordered list of rows — the k-th row for a tag asserts the k-th " +
                "occurrence of it — so this row cannot be read without changing what it asserts. " +
                "Re-capture the step.",
        )
    }

    /**
     * A pattern that does not compile is a **bad assertion, not a corrupt file**, so the codec carries
     * it verbatim in both directions and judges it nowhere.
     *
     * It used to be refused on both sides. Refusing on encode meant a single half-typed character
     * class — `EXEC-[`, mid-keystroke — threw out of `Save` and failed the write of the *entire*
     * scenario: every other step, every other assertion, gone, for a row the author was still editing.
     * Refusing only on read is the bug that the encode-side check was added to fix (a file we wrote
     * and could never load back, vanishing from the list on next start). Both are the same mistake in
     * different clothes: enforcing an editable value as if it were a structural invariant.
     *
     * The pattern is checked in the one place the author can do something about it — live in the
     * editor, via `Matcher.validationError()` — and a bad one that reaches a run fails its own row
     * with the reason on it (see `ExpectationEvaluator.matchRegex`). Nobody loses work; nothing
     * silently passes.
     */
    @Suppress("CyclomaticComplexMethod", "ThrowsCount")
    fun parseMatcher(obj: JsonObject): Matcher {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?: throw IllegalArgumentException("matcher missing 'type'")
        return when (type) {
            "exact" -> Matcher.Exact(requireStr(obj, "value"))
            "presence" -> Matcher.Presence
            "absent" -> Matcher.Absent
            "regex" -> Matcher.Regex(requireStr(obj, "pattern"))
            "oneof" -> Matcher.OneOf(
                (obj["values"]?.jsonArray ?: throw IllegalArgumentException("oneOf matcher missing 'values'"))
                    .map { it.jsonPrimitive.content },
            )
            "numeric" -> Matcher.Numeric(
                expected = obj["value"]?.jsonPrimitive?.doubleOrNull
                    ?: throw IllegalArgumentException("numeric matcher needs numeric 'value'"),
                tolerance = obj["tolerance"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )
            "temporal" -> Matcher.Temporal(
                kind = when (obj["kind"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                    "today" -> TemporalKind.TODAY
                    "now_within_tolerance", null -> TemporalKind.NOW_WITHIN_TOLERANCE
                    else -> throw IllegalArgumentException("temporal kind must be today|now_within_tolerance")
                },
                toleranceSeconds = obj["toleranceSeconds"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
            "reference" -> Matcher.Reference(requireStr(obj, "expression"))
            else -> throw IllegalArgumentException("unknown matcher type '$type'")
        }
    }

    private fun requireStr(obj: JsonObject, key: String): String =
        obj[key]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("matcher missing '$key'")

    // ----------------------------------------------------------------- serialization (for capture)

    /**
     * A row is a tag and a matcher. Its **position in the array is its address** — the k-th row for a
     * tag asserts the k-th occurrence of that tag — so the array's order is load-bearing and nothing
     * may sort it.
     */
    fun fieldExpectationToJson(fe: FieldExpectation): JsonObject =
        buildJsonObject {
            put("tag", fe.tag)
            put("matcher", matcherToJson(fe.matcher))
        }

    fun matcherToJson(matcher: Matcher): JsonObject =
        buildJsonObject {
            when (matcher) {
                is Matcher.Exact -> {
                    put("type", "exact"); put("value", matcher.value)
                }
                is Matcher.Presence -> put("type", "presence")
                is Matcher.Absent -> put("type", "absent")
                is Matcher.Regex -> {
                    put("type", "regex"); put("pattern", matcher.pattern)
                }
                is Matcher.OneOf -> {
                    put("type", "oneOf"); put("values", buildJsonArray { matcher.values.forEach { add(it) } })
                }
                is Matcher.Numeric -> {
                    put("type", "numeric"); put("value", matcher.expected); put("tolerance", matcher.tolerance)
                }
                is Matcher.Temporal -> {
                    put("type", "temporal")
                    put("kind", if (matcher.kind == TemporalKind.TODAY) "today" else "now_within_tolerance")
                    put("toleranceSeconds", matcher.toleranceSeconds)
                }
                is Matcher.Reference -> {
                    put("type", "reference"); put("expression", matcher.expression)
                }
            }
        }
}
