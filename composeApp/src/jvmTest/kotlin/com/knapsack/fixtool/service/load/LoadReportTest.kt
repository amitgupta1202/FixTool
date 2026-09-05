package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.service.load.LoadFixtures.burstReport
import com.knapsack.fixtool.service.load.LoadFixtures.shortfall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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

    @Test
    fun `a record missing the fields added later reads with their absence, not as unreadable`() {
        val json = LoadReportCodec.toJson(burstReport())
        val stripped =
            JsonObject(
                json.filterKeys { it !in setOf("perSecond", "strictRate", "settleLeftMs", "unmatchedTotal") } +
                    ("replies" to JsonObject(json["replies"]!!.jsonObject.filterKeys { it != "strays" })) +
                    ("tool" to JsonObject(json["tool"]!!.jsonObject.filterKeys { it != "pendingPeak" })),
            )

        val back = LoadReportCodec.fromJson(Json.parseToJsonElement(stripped.toString()).jsonObject)

        assertEquals(emptyList(), back.perSecond)
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
        assertTrue(unmatched.contains("""<testcase name="rate" classname="load: NOS EUR/USD 1M ×4,000 on LOADGEN"><skipped/>"""), "a burst has no rate to hold: $unmatched")
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
}
