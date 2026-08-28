package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.BindScope
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.MatchOp
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepOrigin
import com.knapsack.fixtool.model.scenario.TagValue
import com.knapsack.fixtool.model.scenario.TrafficMode
import com.knapsack.fixtool.model.scenario.withIds
import com.knapsack.fixtool.service.compare.SemanticsRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    /**
     * The scenario wire version this build writes and is willing to read. A file at this version or below
     * loads; a file written by a NEWER FixTool is refused (see [fromJson]). Keep this in lock-step with the
     * version [Scenario] writes, and hang any real migrate() off the gap between an old file's version and this.
     */
    const val CURRENT_SCENARIO_VERSION = 1

    fun toJson(scenario: Scenario): JsonObject =
        buildJsonObject {
            put("id", scenario.id)
            put("name", scenario.name)
            scenario.profile?.let { put("profile", it) }
            // Additive and default-omitting, the stepId/origin/muted bargain again: a file that never went
            // strict never grows the key, and every pre-traffic file keeps loading (and re-saving) unchanged.
            scenario.traffic.takeIf { it != TrafficMode.OPEN }?.let { put("traffic", it.name.lowercase()) }
            scenario.binding.takeIf { it != BindScope.ANY }?.let { put("binding", it.name.lowercase()) }
            // Additive and default-omitting: a file authored before createdAt existed grows no key by being
            // read and re-saved, so it stays byte-identical (and old FixTools keep loading new files — the
            // key is unknown to them, and an unknown key is ignored, never refused). Version stays 1.
            scenario.createdAt?.let { put("createdAt", it) }
            put("setup", buildJsonArray { scenario.setup.forEach { add(stepToJson(it)) } })
            put("steps", buildJsonArray { scenario.steps.forEach { add(stepToJson(it)) } })
            put("teardown", buildJsonArray { scenario.teardown.forEach { add(stepToJson(it)) } })
            put("userTags", buildJsonArray { scenario.userTags.forEach { add(it) } })
            put("version", scenario.version)
        }

    /**
     * Loading is where a step gets its identity, if the file did not give it one.
     *
     * [withIds] is deterministic, so a scenario written before ids existed reads back with the *same*
     * ids every time — which is what lets `reconcileRoute` compare the step that ran against the step on
     * disk after loading each of them separately. A random id here would make those two loads disagree
     * about every step of every pre-id file.
     */
    fun fromJson(obj: JsonObject): Scenario {
        val name = str(obj, "name")
        // `version` now earns its keep: it guards against opening a file written by a NEWER FixTool, whose
        // steps or modes this build could otherwise silently mis-read. A missing version defaults to 1 and
        // loads; a version <= CURRENT loads unchanged. This is also the seam a real migrate() would hook into.
        val version = obj["version"]?.jsonPrimitive?.intOrNull ?: 1
        if (version > CURRENT_SCENARIO_VERSION) {
            throw IllegalArgumentException(
                "scenario '$name' is version $version, but this FixTool understands scenarios up to version " +
                    "$CURRENT_SCENARIO_VERSION — upgrade FixTool to open it.",
            )
        }
        return Scenario(
            id = str(obj, "id"),
            name = name,
            profile = obj["profile"]?.jsonPrimitive?.contentOrNull,
            setup = obj["setup"]?.jsonArray?.map { stepFromJson(it.jsonObject) } ?: emptyList(),
            steps = obj["steps"]?.jsonArray?.map { stepFromJson(it.jsonObject) } ?: emptyList(),
            teardown = obj["teardown"]?.jsonArray?.map { stepFromJson(it.jsonObject) } ?: emptyList(),
            userTags = obj["userTags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            version = version,
            traffic = trafficFrom(obj["traffic"]?.jsonPrimitive?.contentOrNull),
            binding = bindingFrom(obj["binding"]?.jsonPrimitive?.contentOrNull),
            // Absent = unknown (a file older than the field): read as null, and the rail sorts it by mtime.
            createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull,
        ).withIds()
    }

    // ----------------------------------------------------------------- steps

    private fun str(obj: JsonObject, key: String): String =
        obj[key]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("scenario missing '$key'")

    private fun reqObj(obj: JsonObject, key: String): JsonObject =
        obj[key]?.jsonObject ?: throw IllegalArgumentException("step missing '$key'")

    @Suppress("CyclomaticComplexMethod")
    fun stepToJson(step: ScenarioStep): JsonObject =
        buildJsonObject {
            // Additive, and written only once assigned: a file that never had ids is not given them by the
            // act of reading it, only by the act of saving it.
            step.stepId.takeIf { it.isNotBlank() }?.let { put("stepId", it) }
            // The same bargain: written only when it is not the default, so a file that never carried an
            // origin does not grow the key by being read. Invariant 5 — the wire format is frozen except
            // additively, and a file that loads today loads identically after every phase.
            step.origin.takeIf { it != StepOrigin.LIVE }?.let { put("origin", it.name.lowercase()) }
            // And once more for muted: only a parked step writes the key.
            if (step.muted) put("muted", true)
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
                is ScenarioStep.ClearOrderBook -> {
                    put("type", "clearOrderBook"); step.session?.let { put("session", it) }
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
        val stepId = obj["stepId"]?.jsonPrimitive?.contentOrNull ?: ""
        // NOT a refusal, unlike an unknown `mode` (which changes what is checked, and so fails the load).
        // An origin this build does not recognise degrades toward LESS trust: see StepOrigin.from.
        val origin = StepOrigin.from(obj["origin"]?.jsonPrimitive?.contentOrNull)
        // Absent = not muted: every file written before the key existed is entitled to that reading, and an
        // unparseable value reads as false rather than silently parking a step the author expects to run.
        val muted = obj["muted"]?.jsonPrimitive?.booleanOrNull ?: false
        return when (val type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "send" -> ScenarioStep.Send(raw = str(obj, "raw"), session = session, stepId = stepId, origin = origin, muted = muted)
            "wait" -> ScenarioStep.Wait(
                session = session,
                state = obj["state"]?.jsonPrimitive?.contentOrNull,
                match = obj["match"]?.jsonObject?.let { predicateFromJson(it) },
                timeoutMs = obj["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 10_000,
                stepId = stepId,
                origin = origin,
                muted = muted,
            )
            "expect" -> ScenarioStep.Expect(
                session = session,
                direction = obj["direction"]?.jsonPrimitive?.contentOrNull ?: "in",
                match = obj["match"]?.jsonObject?.let { predicateFromJson(it) },
                timeoutMs = obj["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 10_000,
                expectation = expectationFromJson(reqObj(obj, "expectation")),
                stepId = stepId,
                origin = origin,
                muted = muted,
            )
            "clearmessages" -> ScenarioStep.ClearMessages(session, stepId, origin, muted)
            "clearorderbook" -> ScenarioStep.ClearOrderBook(session, stepId, origin, muted)
            "resetseqnum" -> ScenarioStep.ResetSeqNum(
                session = session,
                sender = obj["sender"]?.jsonPrimitive?.intOrNull,
                target = obj["target"]?.jsonPrimitive?.intOrNull,
                stepId = stepId,
                origin = origin,
                muted = muted,
            )
            else -> throw IllegalArgumentException("unknown step type '$type'")
        }
    }

    // ----------------------------------------------------------------- predicate & expectation

    fun predicateToJson(p: MatchPredicate): JsonObject =
        buildJsonObject {
            p.messageType?.let { put("messageType", it) }
            p.direction?.let { put("direction", it) }
            // Additive and default-omitting: an EQ constraint still writes exactly `{tag, value}`, so a file
            // written before ops existed round-trips byte-identical. `op` and `occurrence` appear only when set.
            p.occurrence?.let { put("occurrence", it) }
            put(
                "fields",
                buildJsonArray {
                    p.fields.forEach { tv ->
                        add(
                            buildJsonObject {
                                put("tag", tv.tag)
                                if (tv.op != MatchOp.EQ) put("op", tv.op.name.lowercase())
                                // EQ compares the value, so it is always written; present/absent ignore it and
                                // write it only if the author happened to leave one behind.
                                if (tv.op == MatchOp.EQ || tv.value.isNotBlank()) put("value", tv.value)
                            },
                        )
                    }
                },
            )
        }

    fun predicateFromJson(obj: JsonObject): MatchPredicate =
        MatchPredicate(
            messageType = obj["messageType"]?.jsonPrimitive?.contentOrNull,
            direction = obj["direction"]?.jsonPrimitive?.contentOrNull,
            occurrence = obj["occurrence"]?.let { el ->
                val n = el.jsonPrimitive.intOrNull ?: throw IllegalArgumentException(
                    "match predicate 'occurrence' must be an integer, was '${el.jsonPrimitive.content}'",
                )
                require(n >= 1) { "match predicate 'occurrence' must be >= 1, was $n" }
                n
            },
            fields = obj["fields"]?.jsonArray?.map {
                val f = it.jsonObject
                val tag = f["tag"]?.jsonPrimitive?.intOrNull
                    ?: throw IllegalArgumentException("match predicate field missing integer 'tag'")
                val op = matchOpFrom(f["op"]?.jsonPrimitive?.contentOrNull, tag)
                val value = f["value"]?.jsonPrimitive?.contentOrNull
                // Only EQ needs a value; a present/absent constraint is complete without one.
                if (op == MatchOp.EQ && value == null) {
                    throw IllegalArgumentException("match predicate field on tag $tag missing 'value'")
                }
                TagValue(tag, value ?: "", op)
            } ?: emptyList(),
        )

    /**
     * **A match op this codec does not know is a refusal, not a default** — the same stance as [modeFrom].
     * Silently reading an unknown op as EQ would change which message a step binds (an EQ on a blank value can
     * match nothing, or the wrong thing), so it fails the load loudly. An *absent* op is not unknown: it is the
     * model default EQ, and files written before the key existed are entitled to it.
     */
    private fun matchOpFrom(raw: String?, tag: Int): MatchOp =
        when (raw?.lowercase()) {
            null, "eq" -> MatchOp.EQ
            "present" -> MatchOp.PRESENT
            "absent" -> MatchOp.ABSENT
            else -> throw IllegalArgumentException(
                "unknown match op '$raw' on tag $tag — this scenario was written by something that knows a " +
                    "binding rule this build does not, and guessing at it would silently change which message " +
                    "this step binds. Known ops: eq, present, absent.",
            )
        }

    fun expectationToJson(e: Expectation): JsonObject =
        buildJsonObject {
            e.messageType?.let { put("messageType", it) }
            put("mode", SemanticsRegistry.semanticsId(e.mode))
            e.golden?.let { put("golden", it) }
            put("fields", buildJsonArray { e.fields.forEach { add(MatcherCodec.fieldExpectationToJson(it)) } })
        }

    fun expectationFromJson(obj: JsonObject): Expectation {
        val fields = obj["fields"]?.jsonArray ?: JsonArray(emptyList())
        return Expectation(
            fields = fields.map { MatcherCodec.parseFieldExpectation(it.jsonObject) },
            messageType = obj["messageType"]?.jsonPrimitive?.contentOrNull,
            mode = modeFrom(obj["mode"]?.jsonPrimitive?.contentOrNull),
            golden = obj["golden"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * **A mode this codec does not know is a refusal, not a default.**
     *
     * It used to read `strict = (mode == "strict")` and take everything else — a typo, a mode from a
     * future build, a hand-edited file — as OPEN. That is the quietest possible way to weaken an
     * assertion: STRICT becomes OPEN, every tag the venue adds stops being reported, and the scenario goes
     * on passing while checking less than it says. A scenario that means something different after an
     * upgrade is the exact failure this whole area exists to prevent, so an unknown mode fails the load,
     * loudly, by name.
     *
     * An *absent* mode is not unknown: it is the model's default, and files written before the key existed
     * are entitled to it.
     */
    /**
     * The same stance as [modeFrom], for the same reason at the stream level: reading an unknown traffic
     * mode as OPEN is the quietest possible way to weaken a scenario — "and nothing else" silently stops
     * being checked while every run keeps passing. Absent is not unknown: it is the model default, and
     * every file written before the key existed is entitled to it.
     */
    private fun trafficFrom(raw: String?): TrafficMode =
        when (raw?.lowercase()) {
            null -> TrafficMode.OPEN
            "open" -> TrafficMode.OPEN
            "strict" -> TrafficMode.STRICT
            else -> throw IllegalArgumentException(
                "unknown traffic mode '$raw' — this scenario was written by something that knows a stream " +
                    "check this build does not, and guessing at it would silently change what the scenario " +
                    "checks. Known traffic modes: strict, open.",
            )
        }

    /**
     * [trafficFrom]'s stance once more: an unknown value is refused, because reading it as the permissive
     * default is how a scenario that asked to bind only this run's traffic quietly goes back to binding
     * anything — and passes on a reply to a run that finished yesterday.
     */
    private fun bindingFrom(raw: String?): BindScope =
        when (raw?.lowercase()) {
            null -> BindScope.ANY
            "any" -> BindScope.ANY
            "this_run", "thisrun" -> BindScope.THIS_RUN
            else -> throw IllegalArgumentException(
                "unknown binding scope '$raw' — this scenario was written by something that knows a binding " +
                    "rule this build does not, and guessing at it would silently change which messages its " +
                    "steps may bind. Known binding scopes: any, this_run.",
            )
        }

    private fun modeFrom(raw: String?): MatchMode =
        when (raw?.lowercase()) {
            null -> MatchMode.OPEN
            "strict" -> MatchMode.STRICT
            "open" -> MatchMode.OPEN
            else -> throw IllegalArgumentException(
                "unknown match mode '$raw' — this scenario was written by something that knows a comparison " +
                    "this build does not, and guessing at it would silently change what the scenario checks. " +
                    "Known modes: strict, open.",
            )
        }
}
