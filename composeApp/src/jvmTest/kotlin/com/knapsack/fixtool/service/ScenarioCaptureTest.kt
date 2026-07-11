package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.Test
import quickfix.Message
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Captures a multi-session RFQ flow (a quote session + a trade session, FixTool as the client on
 * both) and verifies: chronological multi-session ordering, volatile parameterization, and
 * cross-session id correlation via scenario variables.
 */
class ScenarioCaptureTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private fun msg(raw: String, dir: FixMessage.Direction, second: Int): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.of(2026, 6, 30, 10, 0, second),
            direction = dir,
            rawMessage = raw,
            quickfixMessage = Message(),
        )

    @Test
    fun `captures a multi-session rfq with parameterization and cross-session correlation`() {
        val quote = ScenarioCapture.CapturedSession(
            "QUOTE",
            listOf(
                // out QuoteRequest (QuoteReqID QR-1)
                msg("8=FIX.4.4|35=R|34=2|49=CLI|52=20260630-10:00:00|56=QV|131=QR-1|55=EUR/USD|10=001|", FixMessage.Direction.OUTGOING, 0),
                // in Quote echoing QR-1
                msg("8=FIX.4.4|35=S|34=2|49=QV|52=20260630-10:00:01|56=CLI|131=QR-1|117=Q-9|55=EUR/USD|132=1.0850|60=20260630-10:00:01.000|10=002|", FixMessage.Direction.INCOMING, 1),
            ),
        )
        val trade = ScenarioCapture.CapturedSession(
            "TRADE",
            listOf(
                // out NewOrderSingle (ClOrdID ORD-1) that re-uses the quote's QR-1
                msg("8=FIX.4.4|35=D|34=2|49=CLI|52=20260630-10:00:02|56=TV|11=ORD-1|131=QR-1|55=EUR/USD|54=1|38=1000000|40=2|44=1.0851|60=20260630-10:00:02.000|10=003|", FixMessage.Direction.OUTGOING, 2),
                // in ExecutionReport echoing ORD-1 and QR-1
                msg("8=FIX.4.4|35=8|34=2|49=TV|52=20260630-10:00:03|56=CLI|11=ORD-1|131=QR-1|17=EXEC-1|150=2|39=2|55=EUR/USD|31=1.0851|32=1000000|60=20260630-10:00:03.000|10=004|", FixMessage.Direction.INCOMING, 3),
            ),
        )

        val scenario = ScenarioCapture.capture("sc1", "RFQ", profile = null, sessions = listOf(quote, trade), dictionary = dictionary)

        // setup clears both sessions
        val cleared = scenario.setup.filterIsInstance<ScenarioStep.ClearMessages>().map { it.session }.toSet()
        assertEquals(setOf("QUOTE", "TRADE"), cleared)

        // chronological multi-session order: send R, expect S, send D, expect 8
        val kinds = scenario.steps.map { it::class.simpleName to sessionOf(it) }
        assertEquals(
            listOf("Send" to "QUOTE", "Expect" to "QUOTE", "Send" to "TRADE", "Expect" to "TRADE"),
            kinds,
        )

        val sendR = scenario.steps[0] as ScenarioStep.Send
        val expectS = scenario.steps[1] as ScenarioStep.Expect
        val sendD = scenario.steps[2] as ScenarioStep.Send
        val expect8 = scenario.steps[3] as ScenarioStep.Expect

        // QuoteRequest: QuoteReqID parameterized to a fresh scenario variable; transport tags stripped.
        assertTrue(sendR.raw.contains("35=R"))
        assertTrue(sendR.raw.contains("131=\${id0 = UUID.randomUUID()}"), "QuoteReqID should mint a variable; got ${sendR.raw}")
        assertTrue(!sendR.raw.contains("49=") && !sendR.raw.contains("8=FIX"), "transport tags should be stripped")

        // Quote echoes QR-1 -> reference matcher on the same scenario variable.
        assertEquals(Matcher.Reference("\${id0}"), matcher(expectS, 131))
        // ...and the echo also BINDS the step: on a busy session this Expect must pick the quote
        // answering this run's request, not the first quote of any kind.
        assertEquals(listOf(com.knapsack.fixtool.model.scenario.TagValue(131, "\${id0}")), expectS.match?.fields)

        // NewOrderSingle: fresh ClOrdID variable, and it re-uses the quote's variable for QuoteReqID
        // (cross-session correlation), plus a templated TransactTime.
        assertTrue(sendD.raw.contains("11=\${id1 = UUID.randomUUID()}"), "ClOrdID should mint a variable; got ${sendD.raw}")
        assertTrue(sendD.raw.contains("131=\${id0}"), "QuoteReqID should re-reference the quote variable across sessions; got ${sendD.raw}")
        assertTrue(sendD.raw.contains("60=\${LocalDateTime.now()"), "TransactTime should be templated")

        // ExecutionReport echoes both ids -> reference matchers (cross-session for QR-1).
        assertEquals(Matcher.Reference("\${id1}"), matcher(expect8, 11))
        assertEquals(Matcher.Reference("\${id0}"), matcher(expect8, 131))
        // a smart-seeded field survives where there's no echo (TransactTime -> temporal).
        assertTrue(matcher(expect8, 60) is Matcher.Temporal, "TransactTime should smart-seed temporal")
    }

    @Test
    fun `candidates lists business messages chronologically across sessions and excludes admin`() {
        val a = ScenarioCapture.CapturedSession(
            "A",
            listOf(
                msg("8=FIX.4.4|35=A|34=1|49=CLI|56=SRV|10=001|", FixMessage.Direction.OUTGOING, 0), // Logon: admin
                msg("8=FIX.4.4|35=D|34=2|49=CLI|56=SRV|11=X-1|10=002|", FixMessage.Direction.OUTGOING, 1),
            ),
        )
        val b = ScenarioCapture.CapturedSession(
            "B",
            listOf(msg("8=FIX.4.4|35=8|34=2|49=SRV|56=CLI|11=X-1|10=003|", FixMessage.Direction.INCOMING, 2)),
        )

        val candidates = ScenarioCapture.candidates(listOf(b, a))

        assertEquals(listOf("A" to "D", "B" to "8"), candidates.map { it.session to it.message.messageType })
    }

    @Test
    fun `captureFrom builds only the curated selection and scopes setup to its sessions`() {
        val noise = msg("8=FIX.4.4|35=D|34=2|49=CLI|56=SRV|11=OLD-1|10=001|", FixMessage.Direction.OUTGOING, 0)
        val send = msg("8=FIX.4.4|35=D|34=3|49=CLI|56=SRV|11=ORD-1|10=002|", FixMessage.Direction.OUTGOING, 1)
        val reply = msg("8=FIX.4.4|35=8|34=3|49=SRV|56=CLI|11=ORD-1|17=E-1|10=003|", FixMessage.Direction.INCOMING, 2)
        val all = ScenarioCapture.candidates(
            listOf(ScenarioCapture.CapturedSession("A", listOf(noise, send, reply))),
        )

        // The review screen unticked the stale first order.
        val scenario = ScenarioCapture.captureFrom("sc2", "curated", null, all.drop(1), dictionary)

        assertEquals(2, scenario.steps.size)
        val sendStep = scenario.steps[0] as ScenarioStep.Send
        assertTrue(sendStep.raw.contains("11=\${id0 = UUID.randomUUID()}"), "curated send should mint id0; got ${sendStep.raw}")
        assertEquals(Matcher.Reference("\${id0}"), matcher(scenario.steps[1] as ScenarioStep.Expect, 11))
        assertEquals(listOf("A"), scenario.setup.filterIsInstance<ScenarioStep.ClearMessages>().map { it.session })
    }

    private fun sessionOf(step: ScenarioStep): String? =
        when (step) {
            is ScenarioStep.Send -> step.session
            is ScenarioStep.Expect -> step.session
            is ScenarioStep.ClearMessages -> step.session
            else -> null
        }

    private fun matcher(expect: ScenarioStep.Expect, tag: Int): Matcher =
        expect.expectation.fields.first { it.tag == tag }.matcher
}
