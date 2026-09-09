package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadStage
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.model.load.RoundTripHistogram
import com.knapsack.fixtool.model.load.SetOutcome
import com.knapsack.fixtool.service.load.LoadFixtures.burstReport
import com.knapsack.fixtool.service.load.LoadFixtures.shortfall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Three separate judgements and one exit code**, round-tripped through the JSON and rendered as the
 * JUnit file a build gates on. The exit code and the XML's `failures` must always agree.
 */
class LoadReportTest {
    private val clean = LoadReport.Tool(0, 0, 0, 100)
    private val limited = LoadReport.Tool(discarded = 12, neverLeftSocket = 3, issueFailures = 0, pendingPeak = 100)

    @Test
    fun `every cell of the exit code table`() {
        val done = LoadStatus.DONE
        val answered = LoadReport.Replies(4_000, 0, 0, 0, 0, null)
        val missing = LoadReport.Replies(3_996, 4, 0, 0, 0, null)

        assertEquals(0, LoadReport.verdict(done, answered, null, clean, strictRate = false).exitCode)
        assertEquals(1, LoadReport.verdict(done, missing, null, clean, strictRate = false).exitCode)
        assertEquals(1, LoadReport.verdict(done, answered, null, limited, strictRate = false).exitCode)
        assertEquals(0, LoadReport.verdict(done, answered, shortfall, clean, strictRate = false).exitCode, "a shortfall is reported, and exits 0 unless asked")
        assertEquals(1, LoadReport.verdict(done, answered, shortfall, clean, strictRate = true).exitCode)
        assertEquals(1, LoadReport.verdict(LoadStatus.STOPPED, answered, null, clean, strictRate = false).exitCode, "a stopped run proved nothing whole")
        assertNull(LoadReport.verdict(LoadStatus.RUNNING, answered, null, clean, strictRate = false).exitCode)

        val v = LoadReport.verdict(done, answered, shortfall, limited, strictRate = false)
        assertEquals(LoadReport.Completeness.COMPLETE, v.completeness)
        assertEquals(LoadReport.RateVerdict.SHORTFALL, v.rate)
        assertEquals(LoadReport.ToolVerdict.LIMITED, v.tool)
    }

    @Test
    fun `the JSON round-trips a whole report`() {
        val report = burstReport(rate = shortfall, strictRate = true)

        val back = LoadReportCodec.fromJson(LoadReportCodec.toJson(report))

        assertEquals(report, back)
    }

    /**
     * **One shape on disk.** A run is a set with one phase, so there is one reader, one schema and one
     * Compare — and a load set later is not a second of each.
     */
    @Test
    fun `a run is a one-phase record, and the record round-trips`() {
        val record = LoadRecord.of(burstReport(rate = shortfall, strictRate = true))

        val back = LoadReportCodec.recordFromJson(LoadReportCodec.recordToJson(record))

        assertEquals(record, back)
        assertEquals(record.only, back.only)
        assertEquals(1, LoadReportCodec.recordToJson(record)["phases"]!!.jsonArray.size)
    }

    /** Every record already in ~/.fixtool/loads is a bare report, and none of them needs rewriting. */
    @Test
    fun `a record written before phases existed reads as the one phase it describes`() {
        val report = burstReport(unmatched = 4)

        val old = LoadReportCodec.toJson(report)
        val back = LoadReportCodec.recordFromJson(old)

        assertNull(old["phases"], "the fixture is the old shape, or this test proves nothing")
        assertEquals(listOf(report), back.phases)
        assertEquals(report.id, back.id)
        assertEquals(report.startedAt, back.startedAt)
        assertEquals(LoadStatus.DONE, back.status)
        assertEquals(1, back.exitCode)
    }

    @Test
    fun `a set is still running while any phase is, and stopped if any phase was`() {
        val done = burstReport(unmatched = 0)
        val running = burstReport(unmatched = 590, status = LoadStatus.RUNNING)
        val stopped = burstReport(unmatched = 590, status = LoadStatus.STOPPED)

        assertEquals(LoadStatus.RUNNING, LoadRecord(done.id, done.label, 0, null, listOf(done, running)).status)
        assertEquals(LoadStatus.STOPPED, LoadRecord(done.id, done.label, 0, null, listOf(done, stopped)).status)
        assertEquals(LoadStatus.DONE, LoadRecord(done.id, done.label, 0, null, listOf(done, done)).status)
        assertNull(LoadRecord(done.id, done.label, 0, null, listOf(done, running)).exitCode, "no verdict while one is going")
    }

    /**
     * **A phase that has not started keeps the set's exit code null**, the same as a phase that is going.
     *
     * `GET /loads` puts `exitCode` on a row only when the record has one, so a poller reads a number
     * there as "the set is over". A set whose phase 1 passed and whose phase 2 has not dialled reported 0
     * and was read as finished and green.
     */
    @Test
    fun `a phase still to come leaves the set with no exit code, whatever the phases that landed said`() {
        val passed = burstReport(unmatched = 0)
        // As the runner writes one: a stub with its plan, no measurements and no verdict of its own.
        val stub = passed.copy(status = LoadStatus.PENDING, verdict = passed.verdict.copy(exitCode = null))
        val record = LoadRecord(passed.id, passed.label, 0, null, listOf(passed, stub))

        assertEquals(LoadStatus.RUNNING, record.status)
        assertNull(record.exitCode, "phase 1 passing is not the set passing while phase 2 is still to come")
        assertEquals(SetOutcome.RUNNING, record.verdict.outcome)

        // And not even when the stub carries a code of its own: still to come is still to come.
        val carrying = LoadRecord(passed.id, passed.label, 0, null, listOf(passed, passed.copy(status = LoadStatus.PENDING)))
        assertEquals(0, passed.verdict.exitCode, "the fixture passes, or this half proves nothing")
        assertNull(carrying.exitCode)

        // The row leads on the phase that has run, not on the stub with its zeroes.
        assertEquals(passed.replies.matched, record.lead.replies.matched)
    }

    /**
     * **A set skipped whole did not pass.** Stop it the moment after the 202 and every phase is skipped
     * before it dials: nothing stopped, nothing failed, and on the count of failures alone that read as
     * PASSED with nothing passed.
     */
    @Test
    fun `a set whose every phase was skipped is stopped or failed, never passed`() {
        val plan = burstReport(unmatched = 0)

        fun skipped(note: String) = plan.copy(status = LoadStatus.SKIPPED, note = note, verdict = plan.verdict.copy(exitCode = null))

        val byHand = LoadRecord(plan.id, plan.label, 0, 1, List(3) { skipped(LoadRecord.STOPPED_NOTE) })
        assertEquals(SetOutcome.STOPPED, byHand.verdict.outcome)
        assertEquals(1, byHand.verdict.phase, "the phase the stop landed on")
        assertEquals("3 skipped", byHand.verdict.counts())
        assertEquals(1, byHand.exitCode, "a build cannot pass on a set that never sent a message")

        val other = LoadRecord(plan.id, plan.label, 0, 1, List(2) { skipped("the set's process ended before this phase") })
        assertEquals(SetOutcome.FAILED, other.verdict.outcome)
        assertEquals(1, other.exitCode)

        // The one thing that must still pass: every phase judged and clean.
        val whole = LoadRecord(plan.id, plan.label, 0, 1, listOf(plan, plan))
        assertEquals(SetOutcome.PASSED, whole.verdict.outcome)
        assertEquals(0, whole.exitCode)
    }

    /**
     * **A three-phase set on disk, and back**: the phase that failed, the stub that carries its plan and no
     * verdict, the set block and the seed as rendered once.
     */
    @Test
    fun `a set with a skipped phase round-trips, its note and its absent verdict included`() {
        val one = burstReport(unmatched = 0).copy(label = "Ask for a quote", evidence = LoadReport.Evidence.forPhase(1))
        val two = burstReport(unmatched = 4).copy(label = "Hit the first 2,000", evidence = LoadReport.Evidence.forPhase(2))
        val three =
            one.copy(
                label = "Pass the other 2,000",
                status = LoadStatus.SKIPPED,
                note = "phase 2 did not pass and the set stops on failure",
                evidence = null,
                finishedAt = null,
                verdict = one.verdict.copy(completeness = LoadReport.Completeness.PENDING, exitCode = null),
            )
        val record =
            LoadRecord(
                id = "2026-09-08T10-12-04-rfq-round-trip",
                label = "RFQ round trip",
                startedAt = 1_788_616_324_000,
                finishedAt = 1_788_616_387_100,
                phases = listOf(one, two, three),
                set = LoadRecord.SetInfo("rfq-round-trip", OnFailure.STOP),
                seed = mapOf("run" to "b7f2", "desk" to "LDN"),
            )

        val json = LoadReportCodec.recordToJson(record)
        val back = LoadReportCodec.recordFromJson(json)

        assertEquals(record, back)
        val stub = json["phases"]!!.jsonArray[2].jsonObject
        assertEquals(JsonNull, stub["verdict"], "a phase that never ran is not judged, and says so")
        assertEquals("phase 2 did not pass and the set stops on failure", stub["note"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, stub["evidence"], "and it left no files behind to name")
        assertEquals("rfq-round-trip", json["set"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("STOP", json["set"]!!.jsonObject["onFailure"]!!.jsonPrimitive.content)
        assertEquals("b7f2", json["seed"]!!.jsonObject["run"]!!.jsonPrimitive.content)
        assertEquals(1, json["exitCode"]!!.jsonPrimitive.int, "the phase that failed decides the set's code")
        assertEquals(2, json["verdict"]!!.jsonObject["phase"]!!.jsonPrimitive.int)
        assertEquals(1, json["verdict"]!!.jsonObject["skipped"]!!.jsonPrimitive.int)
        assertEquals("Hit the first 2,000", back.lead.label, "the row leads on the phase the verdict names")
    }

    /**
     * **The keys schema 2 wrote**, on the one machine that wrote them: the lifecycle under `phase` rather
     * than `stage`, and evidence files named without a phase prefix.
     *
     * The reader accepts both because a record on disk is never rewritten to stay readable.
     */
    @Test
    fun `a schema 2 record reads back, its lifecycle key and its bare evidence names and all`() {
        val phase =
            JsonObject(
                LoadReportCodec.toJson(burstReport(unmatched = 4)).filterKeys { it != "stage" } +
                    ("phase" to JsonPrimitive("SETTLING")) +
                    (
                        "evidence" to
                            buildJsonObject {
                                put("unmatched", "unmatched.fix")
                                put("specimens", "specimens.fix")
                            }
                    ),
            )
        val record =
            buildJsonObject {
                put("schema", 2)
                put("id", "20260905-140211-nos-eur-usd-1m")
                put("label", "NOS EUR/USD 1M")
                put("startedAt", LoadFixtures.T0 - 1_000)
                put("phases", buildJsonArray { add(phase) })
            }

        val back = LoadReportCodec.recordFromJson(record)

        assertEquals(LoadStage.SETTLING, back.only.stage, "`phase` is what schema 2 called the lifecycle")
        assertEquals("unmatched.fix", back.only.evidence?.unmatched, "the bare names it has, not guessed per-phase ones")
        assertEquals("specimens.fix", back.only.evidence?.specimens)
        assertNull(back.only.evidence?.captured)
        assertEquals("20260905-140211-nos-eur-usd-1m", back.id)
        assertEquals(1, back.exitCode)
        assertNull(back.set, "a schema 2 record has no set block, and reads as the run it was")
        assertEquals(mapOf("run" to "b7f2"), back.seed, "the set's seed reads phase 1's when the record carries none")
    }

    @Test
    fun `a record missing the fields added later reads with their absence, not as unreadable`() {
        val json = LoadReportCodec.toJson(burstReport())
        val stripped =
            JsonObject(
                json.filterKeys {
                    it !in setOf("perSecond", "strictRate", "settleLeftMs", "unmatchedTotal", "roundTripHistogram", "perLane")
                } +
                    ("replies" to JsonObject(json["replies"]!!.jsonObject.filterKeys { it != "strays" })) +
                    ("tool" to JsonObject(json["tool"]!!.jsonObject.filterKeys { it != "pendingPeak" })),
            )

        val back = LoadReportCodec.fromJson(Json.parseToJsonElement(stripped.toString()).jsonObject)

        assertEquals(emptyList(), back.perSecond)
        assertEquals(RoundTripHistogram.empty(), back.roundTripHistogram, "a record from before the histogram reads as no samples")
        assertEquals(emptyList(), back.perLane)
        assertEquals(0, back.replies.strays)
        assertEquals(0, back.tool.pendingPeak)
        assertEquals(false, back.strictRate)
        assertEquals(3_996, back.replies.matched)
    }

    @Test
    fun `the JUnit file has three cases and its failures agree with the exit code`() {
        fun failures(xml: String) = Regex("""<testsuite [^>]*failures="(\d+)"""").find(xml)!!.groupValues[1].toInt()

        val unmatched = LoadReportCodec.toJUnitXml(burstReport(unmatched = 4))
        assertTrue(unmatched.contains("""<testcase name="completeness""""), unmatched)
        assertTrue(
            unmatched.contains(
                """<testcase name="rate" classname="load: NOS EUR/USD 1M ×4,000 on LOADGEN">""" +
                    """<skipped message="not applicable to a burst"/>""",
            ),
            "a burst has no rate to hold: $unmatched",
        )
        assertTrue(unmatched.contains("4 of 4,000 unanswered within 1m"), unmatched)
        assertTrue(unmatched.contains("ORD-b7f2-1187 (lane 37)"), unmatched)
        assertEquals(1, failures(unmatched))

        val complete = LoadReportCodec.toJUnitXml(burstReport(unmatched = 0))
        assertEquals(0, failures(complete))
        assertTrue(complete.contains("<system-out>4,000 of 4,000 answered</system-out>"), complete)

        val reported = LoadReportCodec.toJUnitXml(burstReport(unmatched = 0, rate = shortfall))
        assertEquals(0, failures(reported), "a shortfall without --strict-rate lives in system-out")
        assertTrue(reported.contains("<system-out>500/s requested · held 9m 41s · behind from second 341 for 19s (min 412/s, 1,672 behind) · max lag 2.3s</system-out>"), reported)

        val gated = LoadReportCodec.toJUnitXml(burstReport(unmatched = 0, rate = shortfall, strictRate = true))
        assertEquals(1, failures(gated))

        val toolLimited = LoadReportCodec.toJUnitXml(burstReport(unmatched = 0, tool = limited))
        assertEquals(1, failures(toolLimited))
        assertTrue(toolLimited.contains("FixTool limited the run: 12 discarded by the panes, 3 handed to the engine never left the socket"), toolLimited)

        val stopped = LoadReportCodec.toJUnitXml(burstReport(unmatched = 0, status = LoadStatus.STOPPED))
        assertEquals(1, failures(stopped), "exit 1 and the XML must say the same thing")
    }

    /**
     * **A muted phase is a SKIPPED phase with a note, and the verdict reads the note three times.**
     *
     * Counted apart from the phases the STOP policy took out, never named as the phase a FAILED set is
     * about, and written into the verdict object only when there is one.
     */
    @Test
    fun `a muted phase is counted apart from a skipped one, and never the phase a verdict names`() {
        val plan = burstReport(unmatched = 0)

        fun stub(note: String, label: String) =
            plan.copy(label = label, status = LoadStatus.SKIPPED, note = note, verdict = plan.verdict.copy(exitCode = null))

        val record =
            LoadRecord(
                plan.id,
                "RFQ round trip",
                0,
                63_100,
                listOf(
                    stub(LoadRecord.MUTED_NOTE, "Ask for a quote"),
                    burstReport(unmatched = 4).copy(label = "Hit the first 2,000"),
                    stub("phase 2 did not pass and the set stops on failure", "Pass the other 2,000"),
                ),
                set = LoadRecord.SetInfo("rfq-round-trip", OnFailure.STOP),
            )

        assertEquals(SetOutcome.FAILED, record.verdict.outcome)
        assertEquals(2, record.verdict.phase, "the phase that failed, not the one that was parked")
        assertEquals("1 failed, 1 skipped, 1 muted", record.verdict.counts())
        assertEquals(1, record.exitCode)

        val verdict = LoadReportCodec.recordToJson(record)["verdict"]!!.jsonObject
        assertEquals(1, verdict["muted"]!!.jsonPrimitive.int)
        assertEquals(1, verdict["skipped"]!!.jsonPrimitive.int, "a mute is not a skip")

        // A record with nothing muted is byte for byte what it was.
        val clean = LoadRecord(plan.id, plan.label, 0, 1, listOf(plan, plan))
        assertNull(LoadReportCodec.recordToJson(clean)["verdict"]!!.jsonObject["muted"])
        assertEquals("2 passed", clean.verdict.counts())

        // Exactly what a build server shows for a disabled test: three grey cases, and the message says why.
        val xml = LoadReportCodec.toJUnitXml(record)
        assertEquals(
            3,
            Regex("""<skipped message="${LoadRecord.MUTED_NOTE}"/>""").findAll(xml).count(),
            xml,
        )
    }

    /** A set that passed on the two phases that ran exits 0, and its one parked phase is not judged. */
    @Test
    fun `a set with a muted phase and the rest passed exits zero`() {
        val plan = burstReport(unmatched = 0)
        val muted =
            plan.copy(status = LoadStatus.SKIPPED, note = LoadRecord.MUTED_NOTE, verdict = plan.verdict.copy(exitCode = null))
        val record = LoadRecord(plan.id, plan.label, 0, 1, listOf(plan, plan, muted))

        assertEquals(SetOutcome.PASSED, record.verdict.outcome)
        assertEquals("2 passed, 1 muted", record.verdict.counts())
        assertEquals(0, record.exitCode)
        assertEquals(plan.label, record.lead.label, "the row leads on a phase that has numbers")
    }
}
