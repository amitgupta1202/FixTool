package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPhaseSpec
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * **A load set as `load-sets/<name>.json`.**
 *
 * Hand-written, like the run set's codec and the load report's, so the shape on disk is the shape the
 * design note shows and a field added later reads back as its absence rather than as an unreadable file.
 *
 * ```jsonc
 * { "schema": 1,
 *   "name": "rfq-round-trip", "label": "RFQ round trip",
 *   "seed": { "run": "${uuid:4}", "desk": "LDN" },        // rendered once per run, then frozen
 *   "storeAndLog": { "store": "MEMORY", "log": "NONE" },  // once, for every lane the set opens
 *   "onFailure": "STOP",
 *   "phases": [ { "label", "template", "profile", "listen", "match", "shape", "indexFrom", "settleMs" } ] }
 * ```
 */
object LoadSetCodec {
    fun toJson(set: LoadSet): JsonObject =
        buildJsonObject {
            put("schema", LoadSet.SCHEMA)
            put("name", set.name)
            put("label", set.label)
            put("seed", buildJsonObject { set.seed.forEach { (k, v) -> put(k, v) } })
            set.storeAndLog?.let { o ->
                put(
                    "storeAndLog",
                    buildJsonObject {
                        put("store", o.store.name)
                        put("log", o.log.name)
                    },
                )
            }
            put("onFailure", set.onFailure.name)
            put("phases", buildJsonArray { set.phases.forEach { add(phaseToJson(it)) } })
        }

    private fun phaseToJson(spec: LoadPhaseSpec): JsonObject =
        buildJsonObject {
            put("label", spec.label)
            put("template", spec.template)
            put("profile", spec.profile)
            if (spec.listen.isNotEmpty()) put("listen", buildJsonArray { spec.listen.forEach { add(it) } })
            spec.match?.let { m ->
                put(
                    "match",
                    buildJsonObject {
                        put("requestTag", m.requestTag)
                        put("replyTag", m.replyTag)
                        m.replyType?.let { put("replyType", it) }
                    },
                )
            }
            put("shape", LoadReportCodec.shapeJson(spec.shape))
            if (spec.indexFrom != 1) put("indexFrom", spec.indexFrom)
            put("settleMs", spec.settleMs)
            if (spec.strictRate) put("strictRate", true)
        }

    fun fromJson(o: JsonObject): LoadSet {
        val name = o.str("name")
        return LoadSet(
            name = name,
            label = o.strOrNull("label")?.takeIf { it.isNotBlank() } ?: name,
            seed = (o["seed"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
            storeAndLog =
                (o["storeAndLog"] as? JsonObject)?.let { s ->
                    StoreAndLogOverride(
                        FixConnectionConfig.MessageStoreKind.valueOf(s.str("store")),
                        FixConnectionConfig.MessageLogKind.valueOf(s.str("log")),
                    )
                },
            onFailure = enumOr(o.strOrNull("onFailure"), OnFailure.STOP),
            phases = (o["phases"] as? JsonArray).orEmpty().map { phaseFromJson(it.jsonObject) },
        )
    }

    private fun phaseFromJson(o: JsonObject): LoadPhaseSpec =
        LoadPhaseSpec(
            label = o.str("label"),
            template = o.str("template"),
            profile = o.str("profile"),
            listen = (o["listen"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
            match =
                (o["match"] as? JsonObject)?.let { m ->
                    val request = m.intOrNull("requestTag") ?: return@let null
                    LoadMatch(request, m.intOrNull("replyTag") ?: request, m.strOrNull("replyType"))
                },
            shape = LoadReportCodec.shapeFrom(o.obj("shape")),
            indexFrom = (o.intOrNull("indexFrom") ?: 1).coerceAtLeast(1),
            settleMs = o.longOrNull("settleMs") ?: LoadPlan.DEFAULT_SETTLE_MS,
            strictRate = (o["strictRate"] as? JsonPrimitive)?.contentOrNull == "true",
        )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name.equals(n, ignoreCase = true) } } ?: default

    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
