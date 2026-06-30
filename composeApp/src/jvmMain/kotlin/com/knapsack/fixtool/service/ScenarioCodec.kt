package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.TagValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Converts a [Scenario] (and its steps, predicates, and expectations) to/from JSON, building on
 * [MatcherCodec] for the matcher overlay. Manual rather than `@Serializable` so it reuses the same
 * matcher wire format the control surface already speaks. Parsing throws [IllegalArgumentException]
 * on malformed input.
 */
object ScenarioCodec {
    fun toJson(scenario: Scenario): JsonObject =
        buildJsonObject {
            put("id", scenario.id)
            put("name", scenario.name)
            scenario.profile?.let { put("profile", it) }
            put("setup", buildJsonArray { scenario.setup.forEach { add(stepToJson(it)) } })
            put("steps", buildJsonArray { scenario.steps.forEach { add(stepToJson(it)) } })
            put("teardown", buildJsonArray { scenario.teardown.forEach { add(stepToJson(it)) } })
            put("userTags", buildJsonArray { scenario.userTags.forEach { add(it) } })
            put("version", scenario.version)
        }

    fun fromJson(obj: JsonObject): Scenario =
        Scenario(
            id = str(obj, "id"),
            name = str(obj, "name"),
            profile = obj["profile"]?.jsonPrimitive?.contentOrNull,
            setup = obj["setup"]?.jsonArray?.map { stepFromJson(it.jsonObject) } ?: emptyList(),
            steps = obj["steps"]?.jsonArray?.map { stepFromJson(it.jsonObject) } ?: emptyList(),
            teardown = obj["teardown"]?.jsonArray?.map { stepFromJson(it.jsonObject) } ?: emptyList(),
            userTags = obj["userTags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            version = obj["version"]?.jsonPrimitive?.intOrNull ?: 1,
        )

    // ----------------------------------------------------------------- steps

    private fun str(obj: JsonObject, key: String): String =
        obj[key]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("scenario missing '$key'")

    private fun reqObj(obj: JsonObject, key: String): JsonObject =
        obj[key]?.jsonObject ?: throw IllegalArgumentException("step missing '$key'")

    @Suppress("CyclomaticComplexMethod")
    fun stepToJson(step: ScenarioStep): JsonObject =
        buildJsonObject {
            when (step) {
                is ScenarioStep.Send -> {
                    put("type", "send"); put("raw", step.raw); step.session?.let { put("session", it) }
                }
                is ScenarioStep.Wait -> {
                    put("type", "wait")
                    step.session?.let { put("session", it) }
                    step.state?.let { put("state", it) }
                    step.match?.let { put("match", predicateToJson(it)) }
                    put("timeoutMs", step.timeoutMs)
                }
                is ScenarioStep.Expect -> {
                    put("type", "expect")
                    step.session?.let { put("session", it) }
                    put("direction", step.direction)
                    step.match?.let { put("match", predicateToJson(it)) }
                    put("timeoutMs", step.timeoutMs)
                    put("expectation", expectationToJson(step.expectation))
                }
                is ScenarioStep.ClearMessages -> {
                    put("type", "clearMessages"); step.session?.let { put("session", it) }
                }
                is ScenarioStep.ResetSeqNum -> {
                    put("type", "resetSeqNum")
                    step.session?.let { put("session", it) }
                    step.sender?.let { put("sender", it) }
                    step.target?.let { put("target", it) }
                }
            }
        }

    @Suppress("ThrowsCount")
    fun stepFromJson(obj: JsonObject): ScenarioStep {
        val session = obj["session"]?.jsonPrimitive?.contentOrNull
        return when (val type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "send" -> ScenarioStep.Send(raw = str(obj, "raw"), session = session)
            "wait" -> ScenarioStep.Wait(
                session = session,
                state = obj["state"]?.jsonPrimitive?.contentOrNull,
                match = obj["match"]?.jsonObject?.let { predicateFromJson(it) },
                timeoutMs = obj["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 10_000,
            )
            "expect" -> ScenarioStep.Expect(
                session = session,
                direction = obj["direction"]?.jsonPrimitive?.contentOrNull ?: "in",
                match = obj["match"]?.jsonObject?.let { predicateFromJson(it) },
                timeoutMs = obj["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 10_000,
                expectation = expectationFromJson(reqObj(obj, "expectation")),
            )
            "clearmessages" -> ScenarioStep.ClearMessages(session)
            "resetseqnum" -> ScenarioStep.ResetSeqNum(
                session = session,
                sender = obj["sender"]?.jsonPrimitive?.intOrNull,
                target = obj["target"]?.jsonPrimitive?.intOrNull,
            )
            else -> throw IllegalArgumentException("unknown step type '$type'")
        }
    }

    // ----------------------------------------------------------------- predicate & expectation

    fun predicateToJson(p: MatchPredicate): JsonObject =
        buildJsonObject {
            p.messageType?.let { put("messageType", it) }
            p.direction?.let { put("direction", it) }
            put(
                "fields",
                buildJsonArray {
                    p.fields.forEach { add(buildJsonObject { put("tag", it.tag); put("value", it.value) }) }
                },
            )
        }

    fun predicateFromJson(obj: JsonObject): MatchPredicate =
        MatchPredicate(
            messageType = obj["messageType"]?.jsonPrimitive?.contentOrNull,
            direction = obj["direction"]?.jsonPrimitive?.contentOrNull,
            fields = obj["fields"]?.jsonArray?.map {
                val f = it.jsonObject
                TagValue(f["tag"]!!.jsonPrimitive.int, f["value"]!!.jsonPrimitive.content)
            } ?: emptyList(),
        )

    fun expectationToJson(e: Expectation): JsonObject =
        buildJsonObject {
            e.messageType?.let { put("messageType", it) }
            put("mode", if (e.mode == MatchMode.STRICT) "strict" else "open")
            e.golden?.let { put("golden", it) }
            put("fields", buildJsonArray { e.fields.forEach { add(MatcherCodec.fieldExpectationToJson(it)) } })
        }

    fun expectationFromJson(obj: JsonObject): Expectation {
        val strict = obj["mode"]?.jsonPrimitive?.contentOrNull?.lowercase() == "strict"
        val mode = if (strict) MatchMode.STRICT else MatchMode.OPEN
        val fields = obj["fields"]?.jsonArray ?: JsonArray(emptyList())
        return Expectation(
            fields = fields.map { MatcherCodec.parseFieldExpectation(it.jsonObject) },
            messageType = obj["messageType"]?.jsonPrimitive?.contentOrNull,
            mode = mode,
            golden = obj["golden"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
