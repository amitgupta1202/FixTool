package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPhase
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.model.load.humanDuration
import com.knapsack.fixtool.service.RunSetStats
import com.knapsack.fixtool.service.ScenarioReport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * **The load report as `load.json`, and as the JUnit file a build ingests.**
 *
 * Hand-written like the run set's codec, so the shape on disk is the shape the design note shows and a
 * field added later reads back as its absence rather than as an unreadable record.
 */
@Suppress("TooManyFunctions")
object LoadReportCodec {
    fun toJson(r: LoadReport): JsonObject =
        buildJsonObject {
            put("id", r.id)
            put("label", r.label)
            put("status", r.status.name)
            put("phase", r.phase.name)
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
            put(
                "roundTrip",
                r.roundTrip?.let { RunSetStats.toJson(RunSetStats.Stats(replyLatency = it, wallClock = null))["replyLatency"] } ?: JsonNull,
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
                    put("discarded", r.tool.discarded)
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
            put(
                "verdict",
                buildJsonObject {
                    put("completeness", r.verdict.completeness.name)
                    put("rate", r.verdict.rate.name)
                    put("tool", r.verdict.tool.name)
                    put("exitCode", r.verdict.exitCode?.let { JsonPrimitive(it) } ?: JsonNull)
                },
            )
        }

    private fun ints(list: List<Int>): JsonArray = buildJsonArray { list.forEach { add(it) } }

    private fun shapeJson(shape: LoadShape): JsonObject =
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
            }
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
            phase = enumOr(o.strOrNull("phase"), LoadPhase.DONE),
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
            roundTrip =
                (o["roundTrip"] as? JsonObject)?.let { d ->
                    RunSetStats.fromJson(buildJsonObject { put("replyLatency", d) })?.replyLatency
                },
            perSecond =
                (o["perSecond"] as? JsonArray).orEmpty().map { e ->
                    val s = e.jsonObject
                    LoadReport.Second(s.int("second"), s.int("issued"), s.int("matched"), s.longOrNull("p95Us"))
                },
            tool =
                LoadReport.Tool(
                    discarded = tool.longOrNull("discarded") ?: 0,
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
            verdict =
                LoadReport.Verdict(
                    completeness = enumOr(verdict.strOrNull("completeness"), LoadReport.Completeness.PENDING),
                    rate = enumOr(verdict.strOrNull("rate"), LoadReport.RateVerdict.NOT_APPLICABLE),
                    tool = enumOr(verdict.strOrNull("tool"), LoadReport.ToolVerdict.CLEAN),
                    exitCode = verdict.intOrNull("exitCode"),
                ),
        )
    }

    private fun shapeFrom(s: JsonObject): LoadShape =
        if (s.strOrNull("kind") == "rate") {
            LoadShape.Rate(s.int("perSecond"), s.long("forMs"))
        } else {
            LoadShape.Burst(s.int("count"))
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
        )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())

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
    fun toJUnitXml(r: LoadReport): String {
        val suite = "load: ${r.label}"
        val cases = listOf(completenessCase(r), rateCase(r), toolCase(r))
        val sb = StringBuilder(XML_DECLARATION)
        sb.append("<testsuite name=\"").append(ScenarioReport.esc(suite)).append("\" tests=\"").append(cases.size)
            .append("\" failures=\"").append(cases.count { it.failure != null })
            .append("\" skipped=\"").append(cases.count { it.skipped })
            .append("\"").append(ScenarioReport.timeAttr(r.timing?.elapsedMs)).append(">\n")
        for (case in cases) {
            sb.append("  <testcase name=\"").append(case.name).append("\" classname=\"").append(ScenarioReport.esc(suite)).append("\">")
            when {
                case.skipped -> sb.append("<skipped/>")
                case.failure != null -> sb.append("\n    <failure message=\"").append(ScenarioReport.esc(case.failure)).append("\"/>\n  ")
                case.note != null -> sb.append("\n    <system-out>").append(ScenarioReport.esc(case.note)).append("</system-out>\n  ")
            }
            sb.append("</testcase>\n")
        }
        sb.append("</testsuite>\n")
        return sb.toString()
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
            LoadReport.Completeness.UNMATCHED -> Case("completeness", failure = unmatchedSentence(r))
            LoadReport.Completeness.PENDING -> Case("completeness", failure = "the run did not finish")
        }

    private fun rateCase(r: LoadReport): Case {
        val rate = r.rate ?: return Case("rate", skipped = true)
        val sentence = rateSentence(rate)
        val shortfall = r.verdict.rate == LoadReport.RateVerdict.SHORTFALL
        return if (shortfall && r.strictRate) Case("rate", failure = sentence) else Case("rate", note = sentence)
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

    /** "500/s requested · held 9m 41s · behind 14:21:07 → 14:21:26 (19s, min 412/s, 1,672 behind) · max lag 2.3s" */
    fun rateSentence(rate: LoadReport.RateReport): String {
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
                    tool.discarded.takeIf { it > 0 }?.let { "${fmt(it)} discarded by the panes" },
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

    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
    private const val UNMATCHED_NAMED = 20
    private const val MILLIS_PER_SECOND = 1_000L
}
