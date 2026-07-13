package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A scenario is captured on one environment and replayed on another. The tags that say **who**,
 * **where** and **when** differ between those environments while the behaviour under test does not.
 *
 * There are three wrong answers here and we have shipped two of them:
 * - Assert their **value** (the original): every step of a DEV-captured scenario goes red on QA
 *   because the desk is called something else there. Non-portable.
 * - Assert **nothing** (the obvious repair): routing coverage silently leaves the scenario. The venue
 *   mis-routes to the wrong desk, or stops populating DeliverToCompID, and the suite that exists to
 *   catch exactly that reports green. A **false green**, which is the one outcome this tool must never
 *   produce.
 * - Assert their **presence**: portable, still covered, and the row is on screen with its captured
 *   value beside it, so a scenario that really is about routing tightens it to exact in one click.
 *
 * The envelope — the frame's bookkeeping and the two CompIDs — is asserted not at all: it is on every
 * message by definition, so a presence row on it could never fail and would only be noise.
 */
class SessionTagsPortabilityTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    /** An ExecutionReport as it arrives from a venue that routes on SubIDs, via a third party. */
    private val captured =
        listOf(
            35 to "8",
            49 to "VENUE", // SenderCompID — envelope
            56 to "FIXTOOL", // TargetCompID — envelope
            34 to "42", // MsgSeqNum — envelope
            52 to "20260713-09:15:02.000", // SendingTime — envelope
            369 to "41", // LastMsgSeqNumProcessed — envelope
            50 to "DESK7", // SenderSubID — address
            57 to "ALGO", // TargetSubID — address
            142 to "LONDON", // SenderLocationID — address
            143 to "NEWYORK", // TargetLocationID — address
            115 to "CLIENTA", // OnBehalfOfCompID — address
            128 to "CUSTB", // DeliverToCompID — address
            144 to "PARIS", // OnBehalfOfLocationID — address
            145 to "TOKYO", // DeliverToLocationID — address
            11 to "ORD-1", // ClOrdID — behaviour
            39 to "2", // OrdStatus — behaviour
        )

    private fun viewOf(fields: List<Pair<Int, String>>) =
        object : MessageView {
            override fun valueOfTag(tag: Int): String? = fields.firstOrNull { it.first == tag }?.second

            override fun presentTags(): Set<Int> = fields.map { it.first }.toSet()

            override fun groupEntries(groupTag: Int): List<MessageView> = emptyList()
        }

    private val envelope = listOf(8, 9, 10, 34, 49, 52, 56, 369)
    private val addressing = listOf(50, 57, 115, 128, 142, 143, 144, 145)

    @Test
    fun `the envelope is not asserted at all`() {
        val seeded = ExpectationSeeder.seed(captured, dictionary).fields.associateBy { it.tag }

        for (tag in envelope) {
            assertNull(seeded[tag], "tag $tag is the envelope — it must not be seeded")
        }
    }

    @Test
    fun `every addressing tag is asserted for presence, never for value`() {
        val seeded = ExpectationSeeder.seed(captured, dictionary).fields.associateBy { it.tag }

        for (tag in addressing) {
            assertEquals(
                Matcher.Presence,
                seeded[tag]?.matcher,
                "tag $tag must be asserted for presence: seeding its value makes the scenario non-portable, " +
                    "and omitting it lets a mis-routed message pass green",
            )
        }
        // The behaviour is still asserted by value.
        assertEquals(Matcher.Exact("2"), seeded[39]?.matcher)
        assertEquals(Matcher.Exact("ORD-1"), seeded[11]?.matcher)
    }

    @Test
    fun `a venue that stops sending a routing tag goes red`() {
        val expectation = ExpectationSeeder.seed(captured, dictionary)
        val withoutSubId = captured.filterNot { it.first == 57 }

        val failed = ExpectationEvaluator.evaluate(viewOf(withoutSubId), expectation).filterNot { it.passed }

        assertEquals(listOf(57), failed.map { it.tag }, "dropping TargetSubID must fail the step, not pass it")
    }

    @Test
    fun `the same scenario passes on an environment that routes through different desks`() {
        val expectation = ExpectationSeeder.seed(captured, dictionary)
        // Same venue behaviour, different environment: every address differs, the business fields do not.
        val onQa =
            captured.map { (tag, value) ->
                when (tag) {
                    49 -> tag to "VENUE_QA"
                    56 -> tag to "FIXTOOL_QA"
                    34 -> tag to "9001"
                    369 -> tag to "9000"
                    in addressing -> tag to "$value-QA"
                    else -> tag to value
                }
            }

        val failed = ExpectationEvaluator.evaluate(viewOf(onQa), expectation).filterNot { it.passed }

        assertEquals(emptyList(), failed.map { it.tag }, "a scenario captured on DEV must not go red on QA")
    }

    @Test
    fun `STRICT still reports an addressing tag the venue did not send at capture`() {
        // Captured without third-party routing; the venue turns it on. Its *presence* is the change,
        // and STRICT exists to report exactly that.
        val withoutThirdParty = captured.filterNot { it.first == 128 }
        val expectation = ExpectationSeeder.seed(withoutThirdParty, dictionary).copy(mode = MatchMode.STRICT)

        val failed = ExpectationEvaluator.evaluate(viewOf(captured), expectation).filterNot { it.passed }

        assertEquals(
            listOf(128),
            failed.map { it.tag },
            "a venue that starts stamping DeliverToCompID has changed who the message is for — STRICT must say so",
        )
    }

    @Test
    fun `a routing scenario tightens the row to exact and it is checked`() {
        val seeded = ExpectationSeeder.seed(captured, dictionary)
        // What the editor does when the author picks "exact" on the TargetSubID row.
        val tightened =
            seeded.copy(
                fields = seeded.fields.map { if (it.tag == 57) it.copy(matcher = Matcher.Exact("ALGO")) else it },
            )

        assertTrue(ExpectationEvaluator.evaluate(viewOf(captured), tightened).all { it.passed })

        val misrouted = captured.map { if (it.first == 57) 57 to "DESK9" else it }
        val failed = ExpectationEvaluator.evaluate(viewOf(misrouted), tightened).filterNot { it.passed }
        assertEquals(listOf(57), failed.map { it.tag })
    }

    @Test
    fun `OrigSendingTime is asserted for presence, not against the wall clock`() {
        // A resend's OrigSendingTime is the *original* message's, by definition hours old. Seeded from
        // its UTCTIMESTAMP type it became "~now ±60s" and the resend scenario went red on every run —
        // on the very environment it was captured on.
        val resend = listOf(35 to "8", 43 to "Y", 122 to "20260713-06:02:11.000", 11 to "ORD-1")

        val seeded = ExpectationSeeder.seed(resend, dictionary).fields.associateBy { it.tag }

        assertEquals(Matcher.Presence, seeded[122]?.matcher, "OrigSendingTime's value is the past, not now")
        // PossDupFlag is behaviour, and identical on every environment — it stays exact.
        assertEquals(Matcher.Exact("Y"), seeded[43]?.matcher)

        assertTrue(ExpectationEvaluator.evaluate(viewOf(resend), ExpectationSeeder.seed(resend, dictionary)).all { it.passed })
    }

    @Test
    fun `an addressing tag can still be asserted absent`() {
        val noThirdParty = Expectation(listOf(FieldExpectation(128, Matcher.Absent)))

        assertTrue(ExpectationEvaluator.evaluate(viewOf(captured.filterNot { it.first == 128 }), noThirdParty).single().passed)
        assertFalse(ExpectationEvaluator.evaluate(viewOf(captured), noThirdParty).single().passed)
    }
}
