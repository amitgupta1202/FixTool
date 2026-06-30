package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.GroupPath
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
 * `docs/fixtool-assert-spec.md`) and the typed [Matcher] / [FieldExpectation] / [Expectation]
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
        val path = obj["path"]?.jsonObject?.let { parseGroupPath(it) }
        return FieldExpectation(tag = tag, matcher = parseMatcher(matcherObj), path = path)
    }

    private fun parseGroupPath(obj: JsonObject): GroupPath =
        GroupPath(
            groupTag = requireInt(obj, "groupTag"),
            identityTag = requireInt(obj, "identityTag"),
            identityValue = requireStr(obj, "identityValue"),
        )

    private fun requireInt(obj: JsonObject, key: String): Int =
        obj[key]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("missing integer '$key'")

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

    fun fieldExpectationToJson(fe: FieldExpectation): JsonObject =
        buildJsonObject {
            put("tag", fe.tag)
            put("matcher", matcherToJson(fe.matcher))
            fe.path?.let { p ->
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
