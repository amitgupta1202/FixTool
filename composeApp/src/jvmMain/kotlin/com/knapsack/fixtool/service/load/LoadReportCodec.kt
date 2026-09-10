package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStage
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.model.load.RoundTripHistogram
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.model.load.humanDuration
import com.knapsack.fixtool.service.RunSetStats
import com.knapsack.fixtool.service.ScenarioReport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
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
 * **The load report as `load.json`, and as the JUnit file a build ingests.**
 *
 * Hand-written like the run set's codec, so the shape on disk is the shape the design note shows and a
 * field added later reads back as its absence rather than as an unreadable record.
 */
@Suppress("TooManyFunctions", "LargeClass")
object LoadReportCodec {
    /**
     * **The record on disk: a set with one phase.**
     *
     * The [SCHEMA] number is here so a reader can tell "written before phases existed" from "written by
     * something newer than me", which a missing key alone cannot say.
     */
    fun recordToJson(record: LoadRecord): JsonObject =
        buildJsonObject {
            put("schema", SCHEMA)
            put("id", record.id)
            put("label", record.label)
            put("startedAt", record.startedAt)
            put("finishedAt", record.finishedAt?.let { JsonPrimitive(it) } ?: JsonNull)
            put("status", record.status.name)
            put("exitCode", record.exitCode?.let { JsonPrimitive(it) } ?: JsonNull)
            record.set?.let { set ->
                put(
                    "set",
                    buildJsonObject {
                        put("name", set.name)
                        put("onFailure", set.onFailure.name)
                    },
                )
            }
            put("seed", buildJsonObject { record.seed.forEach { (k, v) -> put(k, v) } })
            // Only when the panes lost something, the same bargain `stopped` and `muted` strike below, so
            // a clean set writes what it always wrote and a reader can tell nothing from nothing.
            if (record.discarded > 0) put("discarded", record.discarded)
            put(
                "verdict",
                record.verdict.let { v ->
                    buildJsonObject {
                        put("outcome", v.outcome.name)
                        put("phase", v.phase?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("passed", v.passed)
                        put("failed", v.failed)
                        if (v.stopped > 0) put("stopped", v.stopped)
                        put("skipped", v.skipped)
                        // Only when non-zero, as `stopped` is, so a record with nothing muted is byte for
                        // byte what it was, and a reader of `.verdict.skipped` keeps its meaning.
                        if (v.muted > 0) put("muted", v.muted)
                    }
                },
            )
            put("phases", buildJsonArray { record.phases.forEach { add(toJson(it)) } })
        }

    /**
     * A record, whichever shape it was written in.
     *
     * A file with no `phases` array is a report from before this schema, and is read as the one phase it
     * describes. Every record already in `~/.fixtool/loads` is one of those, and none of them needs
     * rewriting to stay readable.
     */
    fun recordFromJson(o: JsonObject): LoadRecord {
        val phases = (o["phases"] as? JsonArray)?.map { fromJson(it.jsonObject) }
        if (phases.isNullOrEmpty()) return LoadRecord.of(fromJson(o))
        return LoadRecord(
            id = o.strOrNull("id") ?: phases.first().id,
            label = o.strOrNull("label") ?: phases.first().label,
            startedAt = o.longOrNull("startedAt") ?: phases.first().startedAt,
            finishedAt = o.longOrNull("finishedAt"),
            phases = phases,
            set =
                (o["set"] as? JsonObject)?.let { set ->
                    LoadRecord.SetInfo(
                        name = set.strOrNull("name") ?: "",
                        onFailure = enumOr(set.strOrNull("onFailure"), OnFailure.STOP),
                    )
                },
            // The set's seed is every phase's seed, so a record written before it existed reads its own
            // first phase rather than coming back empty.
            seed = (o["seed"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: phases.first().seed,
            discarded = o.longOrNull("discarded") ?: 0,
        )
    }

    fun toJson(r: LoadReport): JsonObject =
        buildJsonObject {
            put("id", r.id)
            put("label", r.label)
            put("status", r.status.name)
            put("stage", r.stage.name)
            put(
                "template",
                buildJsonObject {
                    put("name", r.template.name)
                    put("msgType", r.template.msgType)
                    put("perMessageTags", ints(r.template.perMessageTags))
                    put("fixedTags", ints(r.template.fixedTags))
                    put("onceTags", ints(r.template.onceTags))
                },
            )
            put("profile", r.profileName)
            put("lanes", r.lanes)
            put("listen", buildJsonArray { r.listen.forEach { add(it) } })
            put("shape", shapeJson(r.shape))
            if (r.indexFrom != 1) put("indexFrom", r.indexFrom)
            put(
                "match",
                buildJsonObject {
                    put("requestTag", r.match.requestTag)
                    put("replyTag", r.match.replyTag)
                    r.match.replyType?.let { put("replyType", it) }
                },
            )
            put("settleMs", r.settleMs)
            put("seed", buildJsonObject { r.seed.forEach { (k, v) -> put(k, v) } })
            put(
                "storeAndLog",
                r.storeAndLog?.let { o -> buildJsonObject { put("store", o.store.name); put("log", o.log.name) } } ?: JsonNull,
            )
            put("strictRate", r.strictRate)
            put("startedAt", r.startedAt)
            put("finishedAt", r.finishedAt?.let { JsonPrimitive(it) } ?: JsonNull)
            put("settleLeftMs", r.settleLeftMs?.let { JsonPrimitive(it) } ?: JsonNull)
            put(
                "issue",
                buildJsonObject {
                    put("requested", r.issue.requested)
                    put("handedToEngine", r.issue.handedToEngine)
                    put("leftSocket", r.issue.leftSocket)
                    put("firstSendAt", r.issue.firstSendAt?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("lastSendAt", r.issue.lastSendAt?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("spanMs", r.issue.spanMs?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("achievedPerSecond", r.issue.achievedPerSecond?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("prepareMs", r.issue.prepareMs)
                    if (r.issue.unaddressable > 0) put("unaddressable", r.issue.unaddressable)
                },
            )
            put("rate", r.rate?.let(::rateJson) ?: JsonNull)
            put(
                "replies",
                buildJsonObject {
                    put("matched", r.replies.matched)
                    put("unmatched", r.replies.unmatched)
                    put("duplicates", r.replies.duplicates)
                    put("late", r.replies.late)
                    put("strays", r.replies.strays)
                    put("lastMatchedAt", r.replies.lastMatchedAt?.let { JsonPrimitive(it) } ?: JsonNull)
                },
            )
            put("timing", r.timing?.let { t -> buildJsonObject { put("elapsedMs", t.elapsedMs); put("drainMs", t.drainMs) } } ?: JsonNull)
            put("roundTrip", r.roundTrip?.let(::distributionJson) ?: JsonNull)
            // Thirty counts on one line: the shape of the distribution, at a size that does not grow with
            // the run. Written unconditionally, so a reader never has to tell "no samples" from "old record".
            put("roundTripHistogram", buildJsonArray { r.roundTripHistogram.forEach { add(JsonPrimitive(it)) } })
            put(
                "perLane",
                buildJsonArray {
                    r.perLane.forEach { l ->
                        add(
                            buildJsonObject {
                                put("lane", l.slot)
                                put("matched", l.matched)
                                put("unanswered", l.unanswered)
                                put("duplicates", l.duplicates)
                                put("p50Us", l.p50Us?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("p95Us", l.p95Us?.let { JsonPrimitive(it) } ?: JsonNull)
                            },
                        )
                    }
                },
            )
            put(
                "perSecond",
                buildJsonArray {
                    r.perSecond.forEach { s ->
                        add(
                            buildJsonObject {
                                put("second", s.second)
                                put("issued", s.issued)
                                put("matched", s.matched)
                                put("p95Us", s.p95Us?.let { JsonPrimitive(it) } ?: JsonNull)
                            },
                        )
                    }
                },
            )
            put(
                "tool",
                buildJsonObject {
                    // Only when this run counted its own. A phase of a set does not: the set holds the
                    // sessions and carries one delta for all of them, and an absent key reads back as
                    // "not this phase's to say" rather than as nought.
                    r.tool.discarded?.let { put("discarded", it) }
                    put("neverLeftSocket", r.tool.neverLeftSocket)
                    put("issueFailures", r.tool.issueFailures)
                    put("pendingPeak", r.tool.pendingPeak)
                },
            )
            put(
                "unmatched",
                buildJsonArray {
                    r.unmatched.forEach { u -> add(buildJsonObject { put("id", u.id); put("lane", u.lane); put("sentAt", u.sentAt) }) }
                },
            )
            put("unmatchedTotal", r.unmatchedTotal)
            r.capture?.let { c ->
                put(
                    "capture",
                    buildJsonObject {
                        put("names", buildJsonObject { c.names.forEach { (name, tag) -> put(name, tag) } })
                        put("captured", buildJsonObject { c.captured.forEach { (name, count) -> put(name, count) } })
                    },
                )
            }
            if (r.unaddressable.isNotEmpty()) {
                put(
                    "unaddressable",
                    buildJsonArray {
                        r.unaddressable.forEach { u ->
                            add(
                                buildJsonObject {
                                    put("index", u.index)
                                    put("missing", u.missing)
                                },
                            )
                        }
                    },
                )
            }
            put(
                "evidence",
                r.evidence?.let { e ->
                    buildJsonObject {
                        put("unmatched", e.unmatched)
                        put("specimens", e.specimens)
                        e.captured?.let { put("captured", it) }
                    }
                } ?: JsonNull,
            )
            // Only the phase a chain ends at grows the key, and no phase of a set with no reacting phase
            // does, so a staged set's record is the record it always was.
            r.chain?.let { put("chain", chainJson(it)) }
            r.note?.let { put("note", it) }
            // A phase that has not run is not judged, and says so rather than carrying a verdict of
            // zeroes that a reader would have to know to disbelieve.
            put(
                "verdict",
                if (r.status == LoadStatus.SKIPPED || r.status == LoadStatus.PENDING) {
                    JsonNull
                } else {
                    buildJsonObject {
                        put("completeness", r.verdict.completeness.name)
                        put("rate", r.verdict.rate.name)
                        put("tool", r.verdict.tool.name)
                        put("exitCode", r.verdict.exitCode?.let { JsonPrimitive(it) } ?: JsonNull)
                    }
                },
            )
        }

    private fun ints(list: List<Int>): JsonArray = buildJsonArray { list.forEach { add(it) } }

    fun shapeJson(shape: LoadShape): JsonObject =
        buildJsonObject {
            when (shape) {
                is LoadShape.Burst -> {
                    put("kind", "burst")
                    put("count", shape.count)
                }
                is LoadShape.Rate -> {
                    put("kind", "rate")
                    put("perSecond", shape.perSecond)
                    put("forMs", shape.forMs)
                }
                is LoadShape.Triggered -> {
                    put("kind", "triggered")
                    // An uncapped phase never grows the key, the same bargain a phase's `muted` strikes.
                    shape.cap?.let { put("cap", it) }
                }
            }
        }

    /**
     * **A chain, as the block its last phase carries.**
     *
     * Written whole rather than as references to the phases it spans, because the record is read by things
     * that hold one phase at a time and a leg that has to be resolved against a sibling is a leg every
     * reader has to reassemble.
     */
    private fun chainJson(chain: LoadReport.Chain): JsonObject =
        buildJsonObject {
            put("requested", chain.requested)
            put("complete", chain.complete)
            put("endToEnd", distributionJson(chain.endToEnd))
            put(
                "legs",
                buildJsonArray {
                    chain.legs.forEach { leg ->
                        add(
                            buildJsonObject {
                                put("phase", leg.phase)
                                put("label", leg.label)
                                put("answered", leg.answered)
                                put("roundTrip", distributionJson(leg.roundTrip))
                                // The chain's first phase waited for nothing, so it has no handover and
                                // says so by absence rather than by a distribution of noughts.
                                leg.handover?.let { put("handover", distributionJson(it)) }
                            },
                        )
                    }
                },
            )
        }

    private fun chainFrom(o: JsonObject): LoadReport.Chain? {
        val endToEnd = distributionFrom(o["endToEnd"]) ?: return null
        val legs =
            (o["legs"] as? JsonArray).orEmpty().mapNotNull { e ->
                val l = e.jsonObject
                val roundTrip = distributionFrom(l["roundTrip"]) ?: return@mapNotNull null
                LoadReport.Leg(
                    phase = l.intOrNull("phase") ?: 0,
                    label = l.str("label"),
                    handover = distributionFrom(l["handover"]),
                    roundTrip = roundTrip,
                    answered = l.longOrNull("answered") ?: 0,
                )
            }
        if (legs.isEmpty()) return null
        return LoadReport.Chain(
            legs = legs,
            endToEnd = endToEnd,
            complete = o.longOrNull("complete") ?: 0,
            requested = o.longOrNull("requested") ?: 0,
        )
    }

    /** A distribution in the shape the round trip already writes one, so a chain's reads the same. */
    private fun distributionJson(d: RunSetStats.Distribution): JsonElement =
        RunSetStats.toJson(RunSetStats.Stats(replyLatency = d, wallClock = null))["replyLatency"] ?: JsonNull

    private fun distributionFrom(element: JsonElement?): RunSetStats.Distribution? {
        val obj = element as? JsonObject ?: return null
        return RunSetStats.fromJson(buildJsonObject { put("replyLatency", obj) })?.replyLatency
    }

    private fun rateJson(rate: LoadReport.RateReport): JsonObject =
        buildJsonObject {
            put("requestedPerSecond", rate.requestedPerSecond)
            put("heldForMs", rate.heldForMs)
            put(
                "shortfalls",
                buildJsonArray {
                    rate.shortfalls.forEach { s ->
                        add(
                            buildJsonObject {
                                put("fromSecond", s.fromSecond)
                                put("toSecond", s.toSecond)
                                put("minPerSecond", s.minPerSecond)
                                put("behind", s.behind)
                            },
                        )
                    }
                },
            )
            put("maxLagMs", rate.maxLagMs)
            put("tolerance", rate.tolerance)
            // A schedule never grows the two keys a ceiling needs, the same bargain a phase's `muted`
            // strikes, and a reader with no `ceiling` key is reading a schedule, which is what every
            // record written before a phase could be reactive is.
            if (rate.ceiling) {
                put("ceiling", true)
                put("starvedForMs", rate.starvedForMs)
            }
        }

    @Suppress("LongMethod")
    fun fromJson(o: JsonObject): LoadReport {
        val template = o.obj("template")
        val issue = o.obj("issue")
        val replies = o.obj("replies")
        val tool = o.obj("tool")
        val verdict = o.obj("verdict")
        return LoadReport(
            id = o.str("id"),
            label = o.str("label"),
            status = enumOr(o.strOrNull("status"), LoadStatus.DONE),
            // `stage` since schema 3. `phase` is what schema 2 called the same enum, on one machine.
            stage = enumOr(o.strOrNull("stage") ?: o.strOrNull("phase"), LoadStage.DONE),
            template =
                LoadReport.TemplateInfo(
                    name = template.str("name"),
                    msgType = template.str("msgType"),
                    perMessageTags = template.ints("perMessageTags"),
                    fixedTags = template.ints("fixedTags"),
                    onceTags = template.ints("onceTags"),
                ),
            profileName = o.str("profile"),
            lanes = o.int("lanes"),
            listen = (o["listen"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
            shape = shapeFrom(o.obj("shape")),
            indexFrom = o.intOrNull("indexFrom") ?: 1,
            match =
                o.obj("match").let { m ->
                    LoadMatch(m.int("requestTag"), m.intOrNull("replyTag") ?: m.int("requestTag"), m.strOrNull("replyType"))
                },
            settleMs = o.long("settleMs"),
            seed = (o["seed"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content },
            storeAndLog =
                (o["storeAndLog"] as? JsonObject)?.let { s ->
                    StoreAndLogOverride(
                        FixConnectionConfig.MessageStoreKind.valueOf(s.str("store")),
                        FixConnectionConfig.MessageLogKind.valueOf(s.str("log")),
                    )
                },
            strictRate = (o["strictRate"] as? JsonPrimitive)?.contentOrNull == "true",
            startedAt = o.long("startedAt"),
            finishedAt = o.longOrNull("finishedAt"),
            settleLeftMs = o.longOrNull("settleLeftMs"),
            issue =
                LoadReport.Issue(
                    requested = issue.long("requested"),
                    handedToEngine = issue.long("handedToEngine"),
                    leftSocket = issue.long("leftSocket"),
                    firstSendAt = issue.longOrNull("firstSendAt"),
                    lastSendAt = issue.longOrNull("lastSendAt"),
                    prepareMs = issue.longOrNull("prepareMs") ?: 0,
                    unaddressable = issue.longOrNull("unaddressable") ?: 0,
                ),
            rate = (o["rate"] as? JsonObject)?.let(::rateFrom),
            replies =
                LoadReport.Replies(
                    matched = replies.long("matched"),
                    unmatched = replies.long("unmatched"),
                    duplicates = replies.longOrNull("duplicates") ?: 0,
                    late = replies.longOrNull("late") ?: 0,
                    strays = replies.longOrNull("strays") ?: 0,
                    lastMatchedAt = replies.longOrNull("lastMatchedAt"),
                ),
            timing = (o["timing"] as? JsonObject)?.let { t -> LoadReport.Timing(t.long("elapsedMs"), t.long("drainMs")) },
            roundTrip = distributionFrom(o["roundTrip"]),
            // A record written before the histogram existed reads back as an empty one, which is the same
            // thing a run with no matched reply says, and is what the charts already have to handle.
            roundTripHistogram =
                (o["roundTripHistogram"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.intOrNull }
                    ?.takeIf { it.size == RoundTripHistogram.BUCKETS }
                    ?: RoundTripHistogram.empty(),
            perLane =
                (o["perLane"] as? JsonArray).orEmpty().map { e ->
                    val l = e.jsonObject
                    LoadReport.LaneCounts(
                        slot = l.intOrNull("lane") ?: 0,
                        matched = l.longOrNull("matched") ?: 0,
                        unanswered = l.longOrNull("unanswered") ?: 0,
                        duplicates = l.longOrNull("duplicates") ?: 0,
                        p50Us = l.longOrNull("p50Us"),
                        p95Us = l.longOrNull("p95Us"),
                    )
                },
            perSecond =
                (o["perSecond"] as? JsonArray).orEmpty().map { e ->
                    val s = e.jsonObject
                    LoadReport.Second(s.int("second"), s.int("issued"), s.int("matched"), s.longOrNull("p95Us"))
                },
            tool =
                LoadReport.Tool(
                    discarded = tool.longOrNull("discarded"),
                    neverLeftSocket = tool.longOrNull("neverLeftSocket") ?: 0,
                    issueFailures = tool.longOrNull("issueFailures") ?: 0,
                    pendingPeak = tool.intOrNull("pendingPeak") ?: 0,
                ),
            unmatched =
                (o["unmatched"] as? JsonArray).orEmpty().map { e ->
                    val u = e.jsonObject
                    LoadReport.UnmatchedRequest(u.str("id"), u.intOrNull("lane") ?: 0, u.longOrNull("sentAt") ?: 0)
                },
            unmatchedTotal = o.intOrNull("unmatchedTotal") ?: 0,
            capture =
                (o["capture"] as? JsonObject)?.let { c ->
                    LoadReport.Capture(names = intMap(c["names"]), captured = intMap(c["captured"]))
                },
            unaddressable =
                (o["unaddressable"] as? JsonArray).orEmpty().map { e ->
                    val u = e.jsonObject
                    LoadReport.Unaddressable(u.intOrNull("index") ?: 0, u.strOrNull("missing") ?: "")
                },
            chain = (o["chain"] as? JsonObject)?.let(::chainFrom),
            note = o.strOrNull("note"),
            evidence =
                (o["evidence"] as? JsonObject)?.let { e ->
                    LoadReport.Evidence(
                        unmatched = e.strOrNull("unmatched") ?: LoadRecordStore.UNMATCHED_FILE,
                        specimens = e.strOrNull("specimens") ?: LoadRecordStore.SPECIMENS_FILE,
                        captured = e.strOrNull("captured"),
                    )
                },
            verdict =
                LoadReport.Verdict(
                    completeness = enumOr(verdict.strOrNull("completeness"), LoadReport.Completeness.PENDING),
                    rate = enumOr(verdict.strOrNull("rate"), LoadReport.RateVerdict.NOT_APPLICABLE),
                    tool = enumOr(verdict.strOrNull("tool"), LoadReport.ToolVerdict.CLEAN),
                    exitCode = verdict.intOrNull("exitCode"),
                ),
        )
    }

    /**
     * **A shape is read by its `kind` or not at all.**
     *
     * This was `if (kind == "rate") Rate else Burst(count)`, and `int()` answers 0 for a key that is not
     * there, so any kind this version did not know read back as a burst of nothing, ran an empty loop and
     * passed COMPLETE. A load set written by a later FixTool and an inline set posted to `POST /load` both
     * arrive here, so the quiet answer was reachable from a build box. Every caller already treats a throw
     * as "this file cannot be read", which is the true answer.
     */
    fun shapeFrom(s: JsonObject): LoadShape =
        when (val kind = s.strOrNull("kind")) {
            "burst" -> LoadShape.Burst(s.int("count"))
            "rate" -> LoadShape.Rate(s.int("perSecond"), s.long("forMs"))
            "triggered" -> LoadShape.Triggered(s.intOrNull("cap"))
            else ->
                throw IllegalArgumentException(
                    "a shape says kind '${kind ?: ""}', and this version knows burst, rate and triggered.",
                )
        }

    private fun rateFrom(r: JsonObject): LoadReport.RateReport =
        LoadReport.RateReport(
            requestedPerSecond = r.int("requestedPerSecond"),
            heldForMs = r.longOrNull("heldForMs") ?: 0,
            shortfalls =
                (r["shortfalls"] as? JsonArray).orEmpty().map { e ->
                    val s = e.jsonObject
                    LoadReport.Shortfall(s.int("fromSecond"), s.int("toSecond"), s.int("minPerSecond"), s.long("behind"))
                },
            maxLagMs = r.longOrNull("maxLagMs") ?: 0,
            tolerance = r["tolerance"]?.jsonPrimitive?.doubleOrNull ?: Pacer.TOLERANCE,
            ceiling = r["ceiling"]?.jsonPrimitive?.booleanOrNull ?: false,
            starvedForMs = r.longOrNull("starvedForMs") ?: 0,
        )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())

    /** `{"quoteId": 117}` as a map, which is the shape both halves of a capture block are written in. */
    private fun intMap(element: JsonElement?): Map<String, Int> =
        (element as? JsonObject)
            .orEmpty()
            .mapNotNull { (name, value) -> (value as? JsonPrimitive)?.intOrNull?.let { name to it } }
            .toMap()

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0

    private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0

    private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.ints(key: String): List<Int> = (this[key] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }

    /**
     * **One `<testsuite>` with three cases**, so a build that already ingests `fixtool run`'s XML needs no
     * change and can gate on any one of the three judgements.
     *
     * `completeness` fails with the unanswered ids in its message. `rate` is skipped for a burst, fails
     * under `--strict-rate`, and otherwise reports the shortfall in `system-out` where a reader finds it
     * and a gate does not trip on it. `tool` fails when FixTool limited the run and says which counter.
     */
    fun toJUnitXml(r: LoadReport): String = XML_DECLARATION + suiteXml(r, "load: ${r.label}", ScenarioReport.esc("load: ${r.label}"), indent = "")

    /**
     * **A set as one `<testsuites>` with a `<testsuite>` per phase**, the way `fixtool run --set` wraps its
     * entries.
     *
     * A one-phase record keeps writing the bare `<testsuite>` it writes now, mirroring what `fixtool run`
     * does for one scenario and for a set, so nobody's existing pipeline changes shape. A skipped phase is
     * three `<skipped/>` cases carrying its note, which every build server draws as grey rather than as
     * missing.
     *
     * **A set-level discard is a suite of its own, and only when there is one.** It belongs to no phase,
     * because the sessions belong to the set and every phase was on them, so charging it to one would name
     * a phase the counter never named. A clean set's file is exactly the file it was.
     */
    fun toJUnitXml(record: LoadRecord): String {
        if (record.phases.size == 1) return toJUnitXml(record.only)
        val setName = record.set?.name ?: record.label
        val sessions = sessionCases(record)
        val cases = record.phases.map { casesFor(it) } + listOfNotNull(sessions)
        val time = record.finishedAt?.let { it - record.startedAt }
        val head =
            "<testsuites name=\"" + ScenarioReport.esc("load set: ${record.label}") +
                "\" tests=\"" + cases.sumOf { it.size } +
                "\" failures=\"" + cases.sumOf { c -> c.count { it.failure != null } } +
                "\" skipped=\"" + cases.sumOf { c -> c.count { it.skipped } } +
                "\"" + ScenarioReport.timeAttr(time) + ">\n"
        val suites =
            record.phases.mapIndexed { index, phase ->
                val n = index + 1
                suiteXml(phase, "load: $n · ${phase.label}", ScenarioReport.esc("load.$setName.$n"), indent = "  ")
            } +
                listOfNotNull(
                    sessions?.let {
                        suiteXml(it, "load: the set's sessions", ScenarioReport.esc("load.$setName.sessions"), "  ", time)
                    },
                )
        return XML_DECLARATION + head + suites.joinToString("") + "</testsuites>\n"
    }

    /** The one judgement that belongs to the whole set rather than to a phase, or null when it passed. */
    private fun sessionCases(record: LoadRecord): List<Case>? =
        record.discarded.takeIf { it > 0 }?.let {
            listOf(Case("tool", failure = "${fmt(it)} discarded by the panes on the set's sessions, at least"))
        }

    private fun suiteXml(r: LoadReport, name: String, classname: String, indent: String): String =
        suiteXml(casesFor(r), name, classname, indent, r.timing?.elapsedMs)

    private fun suiteXml(cases: List<Case>, name: String, classname: String, indent: String, timeMs: Long?): String {
        val head =
            indent + "<testsuite name=\"" + ScenarioReport.esc(name) +
                "\" tests=\"" + cases.size +
                "\" failures=\"" + cases.count { it.failure != null } +
                "\" skipped=\"" + cases.count { it.skipped } +
                "\"" + ScenarioReport.timeAttr(timeMs) + ">\n"
        return head + cases.joinToString("") { caseXml(it, classname, indent) } + indent + "</testsuite>\n"
    }

    private fun caseXml(case: Case, classname: String, indent: String): String {
        val open = indent + "  <testcase name=\"" + case.name + "\" classname=\"" + classname + "\">"
        val message = case.note?.let { " message=\"" + ScenarioReport.esc(it) + "\"" } ?: ""
        val body =
            when {
                case.skipped -> "<skipped" + message + "/>"
                case.failure != null ->
                    "\n" + indent + "    <failure message=\"" + ScenarioReport.esc(case.failure) +
                        "\"/>\n" + indent + "  "
                case.note != null ->
                    "\n" + indent + "    <system-out>" + ScenarioReport.esc(case.note) +
                        "</system-out>\n" + indent + "  "
                else -> ""
            }
        return open + body + "</testcase>\n"
    }

    /** The three judgements as cases, or three skipped ones carrying the note for a phase that never ran. */
    private fun casesFor(r: LoadReport): List<Case> =
        if (r.status == LoadStatus.SKIPPED || r.status == LoadStatus.PENDING) {
            val note = r.note ?: "this phase did not run"
            listOf("completeness", "rate", "tool").map { Case(it, note = note, skipped = true) }
        } else {
            listOf(completenessCase(r), rateCase(r), toolCase(r))
        }

    private class Case(
        val name: String,
        val failure: String? = null,
        val note: String? = null,
        val skipped: Boolean = false,
    )

    private fun completenessCase(r: LoadReport): Case =
        if (r.status == LoadStatus.STOPPED) {
            Case("completeness", failure = "stopped after ${fmt(r.issue.leftSocket)} of ${fmt(r.issue.requested)} issued: " + unmatchedSentence(r))
        } else when (r.verdict.completeness) {
            LoadReport.Completeness.COMPLETE -> Case("completeness", note = "${fmt(r.replies.matched)} of ${fmt(r.issue.leftSocket)} answered")
            LoadReport.Completeness.INCOMPLETE -> Case("completeness", failure = unaddressableSentence(r))
            LoadReport.Completeness.UNMATCHED -> Case("completeness", failure = unmatchedSentence(r))
            LoadReport.Completeness.PENDING -> Case("completeness", failure = "the run did not finish")
        }

    private fun rateCase(r: LoadReport): Case {
        val rate = r.rate ?: return Case("rate", note = noScheduleSentence(r), skipped = true)
        // A ceiling is described and never scored: see LoadReport.RateVerdict.CAPPED. A phase under its
        // cap is waiting on its trigger, and --strict-rate promotes a shortfall, which a ceiling never
        // produces, so there is nothing here for it to fail the build on.
        if (rate.ceiling) return Case("rate", note = rateSentence(rate))
        val sentence = rateSentence(rate)
        val shortfall = r.verdict.rate == LoadReport.RateVerdict.SHORTFALL
        return if (shortfall && r.strictRate) Case("rate", failure = sentence) else Case("rate", note = sentence)
    }

    /**
     * Why there was no schedule to judge, which is a different fact for each shape that has none.
     *
     * This said "not applicable to a burst" for every one of them, and the moment a phase could be
     * reactive that was a build log calling a reactive phase a burst.
     */
    private fun noScheduleSentence(r: LoadReport): String =
        when (r.shape) {
            is LoadShape.Burst -> "not applicable to a burst"
            is LoadShape.Triggered -> "not applicable to a reactive phase, which is released by replies"
            is LoadShape.Rate -> "not applicable: the run did not finish, so its schedule was never judged"
        }

    private fun toolCase(r: LoadReport): Case =
        if (r.tool.limited) Case("tool", failure = toolSentence(r.tool)) else Case("tool", note = "clean")

    /** "4 of 4,000 unanswered within 60s: ORD-1187 (lane 37), ORD-2410 (lane 10), …" */
    fun unmatchedSentence(r: LoadReport): String {
        val named = r.unmatched.take(UNMATCHED_NAMED).joinToString(", ") { "${it.id} (lane ${it.lane})" }
        val more = r.unmatchedTotal - minOf(r.unmatchedTotal, UNMATCHED_NAMED)
        return "${fmt(r.replies.unmatched)} of ${fmt(r.issue.leftSocket)} unanswered within ${humanDuration(r.settleMs)}" +
            (if (named.isNotEmpty()) ": $named" else "") +
            (if (more > 0) " and $more more" else "")
    }

    /** "4 of 2,000 not sent: no quoteId for index 412, no quoteId for index 931 and 2 more" */
    fun unaddressableSentence(r: LoadReport): String {
        val named =
            r.unaddressable
                .take(UNMATCHED_NAMED)
                .joinToString(", ") { "no ${it.missing} for index ${fmt(it.index.toLong())}" }
        val more = r.issue.unaddressable - minOf(r.issue.unaddressable, UNMATCHED_NAMED.toLong())
        return "${fmt(r.issue.unaddressable)} of ${fmt(r.issue.requested)} not sent" +
            (if (named.isNotEmpty()) ": $named" else "") +
            (if (more > 0) " and $more more" else "")
    }

    /**
     * "500/s requested · held 9m 41s · behind 14:21:07 → 14:21:26 (19s, min 412/s, 1,672 behind) · max lag 2.3s"
     *
     * And for a ceiling, "never above 200/s · at the cap 4s · starved 4s". A different sentence and not
     * a different wording of the same one, because every clause of a schedule's is a claim a ceiling
     * cannot make: nothing was requested, nothing was held, and nothing went late.
     */
    fun rateSentence(rate: LoadReport.RateReport): String {
        if (rate.ceiling) {
            return listOf(
                "never above ${rate.requestedPerSecond}/s",
                "at the cap ${humanDuration(rate.heldForMs)}",
                "starved ${humanDuration(rate.starvedForMs)}",
            ).joinToString(" · ")
        }
        val held = "held ${humanDuration(rate.heldForMs)}"
        val spans =
            rate.shortfalls.joinToString(" · ") { s ->
                val seconds = s.toSecond - s.fromSecond + 1
                "behind from second ${s.fromSecond} for ${humanDuration(seconds * MILLIS_PER_SECOND)} " +
                    "(min ${s.minPerSecond}/s, ${fmt(s.behind)} behind)"
            }
        return listOf("${rate.requestedPerSecond}/s requested", held, spans.ifEmpty { null }, "max lag ${RunSetStats.humanMs(rate.maxLagMs)}")
            .filterNotNull()
            .joinToString(" · ")
    }

    fun toolSentence(tool: LoadReport.Tool): String =
        if (!tool.limited) {
            "clean"
        } else {
            "FixTool limited the run: " +
                listOfNotNull(
                    tool.discarded?.takeIf { it > 0 }?.let { "${fmt(it)} discarded by the panes" },
                    tool.neverLeftSocket.takeIf { it > 0 }?.let { "${fmt(it)} handed to the engine never left the socket" },
                    tool.issueFailures.takeIf { it > 0 }?.let { "${fmt(it)} refused by the engine" },
                ).joinToString(", ")
        }

    fun fmt(n: Long): String = "%,d".format(n)

    /** `912µs`, `1.4ms`, `212ms`, `1.88s`: a round trip as a person reads it. */
    fun humanMicros(us: Long): String =
        when {
            us < MICROS_PER_MILLI -> "${us}µs"
            us < TEN_MILLIS_IN_MICROS -> "%.1fms".format(us / MICROS_PER_MILLI.toDouble())
            us < MICROS_PER_SECOND -> "${us / MICROS_PER_MILLI}ms"
            else -> "%.2fs".format(us / MICROS_PER_SECOND.toDouble())
        }

    private const val MICROS_PER_MILLI = 1_000L
    private const val TEN_MILLIS_IN_MICROS = 10_000L
    private const val MICROS_PER_SECOND = 1_000_000L

    /**
     * The record schema. 1 was the bare report, which had no number and is recognised by its absence. 2
     * added `phases`. 3 renamed the lifecycle key to `stage`, added the SKIPPED and PENDING statuses, and
     * named each phase's evidence files inside the phase.
     */
    const val SCHEMA = 3

    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
    private const val UNMATCHED_NAMED = 20
    private const val MILLIS_PER_SECOND = 1_000L
}
