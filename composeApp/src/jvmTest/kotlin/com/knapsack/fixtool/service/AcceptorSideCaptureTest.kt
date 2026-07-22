package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.MintingSide
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.Test
import quickfix.Message
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Capture from the venue's chair.**
 *
 * Every id rule capture applies is party-relative, and capture used to resolve them against a fixed
 * assumption that FixTool is forever the client dialling out. Run against an acceptor session — where
 * FixTool *is* the venue — that assumption inverted all three of them at once, and said nothing:
 *
 *  - the counterparty's `ClOrdID(11)`, arriving on their order, was asserted `Exact`, so the scenario
 *    went red on its second run for the only reason a real client ever gives it — a new ClOrdID;
 *  - our reply's `ClOrdID(11)` was minted **fresh**, so the ExecutionReport answered no order at all;
 *  - our `OrderID(37)` and `ExecID(17)` were replayed as **literals**, the duplicate that
 *    parameterization exists to prevent.
 *
 * The middle two never go red — nothing asserts a Send step — so the scenario reported green while
 * emitting a reply no client could correlate. That is what this test exists to keep fixed. The mirror
 * assertions for the initiator side are [ScenarioCaptureTest]'s, and they must both hold: the same
 * capture, read from the two ends, must invert exactly and in nothing else.
 */
class AcceptorSideCaptureTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private val session = "VENUE ACCEPTOR"

    /** As [ScenarioCaptureTest.msg]: the display string and the SOH wire bytes are both always present. */
    private fun msg(raw: String, dir: FixMessage.Direction, second: Int): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.of(2026, 7, 22, 10, 0, second),
            direction = dir,
            rawMessage = raw,
            quickfixMessage = Message(),
            wireRaw = raw.replace('|', ''),
        )

    /** Their NewOrderSingle in, our ExecutionReport out — the acceptor's half of an order. */
    private fun orderAndReply() =
        ScenarioCapture.CapturedSession(
            session,
            listOf(
                msg(
                    "8=FIX.4.4|35=D|34=2|49=CLIENT|52=20260722-10:00:01|56=VENUE|11=CLI-ORD-001|55=EUR/USD|" +
                        "54=1|38=1000000|40=2|44=1.0850|60=20260722-10:00:01.000|10=093|",
                    FixMessage.Direction.INCOMING,
                    1,
                ),
                msg(
                    "8=FIX.4.4|35=8|34=2|49=VENUE|52=20260722-10:00:02|56=CLIENT|37=VEN-ORD-77|17=VEN-EXEC-77|" +
                        "150=0|39=0|11=CLI-ORD-001|55=EUR/USD|54=1|38=1000000|14=0|151=1000000|" +
                        "60=20260722-10:00:02.000|10=071|",
                    FixMessage.Direction.OUTGOING,
                    2,
                ),
            ),
        )

    private fun capture(side: MintingSide): Pair<ScenarioStep.Expect, ScenarioStep.Send> {
        val scenario =
            ScenarioCapture.capture(
                id = "acceptor-capture",
                name = "acceptor capture",
                profile = null,
                sessions = listOf(orderAndReply()),
                dictionary = dictionary,
                sides = mapOf(session to side),
            )
        val expect = scenario.steps.filterIsInstance<ScenarioStep.Expect>().single()
        val send = scenario.steps.filterIsInstance<ScenarioStep.Send>().single()
        return expect to send
    }

    private fun matcherFor(expect: ScenarioStep.Expect, tag: Int): Matcher? =
        expect.expectation.fields.firstOrNull { it.tag == tag }?.matcher

    private fun valueFor(send: ScenarioStep.Send, tag: Int): String? =
        FixMessageHelper.parseFixMessage(send.raw).firstOrNull { it.first == tag }?.second

    /**
     * Their id is theirs: presence, never a literal. Asserted `Exact`, the step passes exactly once —
     * against the ClOrdID of the capture — and is red for every run after it.
     */
    @Test
    fun `an acceptor asserts the counterparty's ClOrdID for presence, not its value`() {
        val (expect, _) = capture(MintingSide.VENUE)
        assertEquals(Matcher.Presence, matcherFor(expect, 11), "their ClOrdID must not be pinned to a literal")
    }

    /**
     * The echo, which is what makes a reply a reply. The inbound value is bound on the Expect and quoted
     * by name on the Send — never re-minted, or the ExecutionReport answers an order nobody sent.
     */
    @Test
    fun `an acceptor echoes the counterparty's ClOrdID on its reply rather than minting a new one`() {
        val (expect, send) = capture(MintingSide.VENUE)
        val bound = expect.expectation.fields.firstOrNull { it.tag == 11 }?.bindAs
        assertNotNull(bound, "the inbound ClOrdID must be captured into scope for the reply to quote")
        assertEquals("\${$bound}", valueFor(send, 11), "the reply must echo the bound ClOrdID")
    }

    /** Ours are ours: fresh every run, because a venue enforces uniqueness on exactly these. */
    @Test
    fun `an acceptor mints its own OrderID and ExecID fresh on every run`() {
        val (_, send) = capture(MintingSide.VENUE)
        for (tag in listOf(37, 17)) {
            val value = valueFor(send, tag)
            assertNotNull(value, "tag $tag missing from the reply")
            assertTrue(
                value.startsWith("\${") && "uuid" in value,
                "tag $tag must be parameterized, not replayed as the captured literal — was '$value'",
            )
        }
    }

    /**
     * The inversion is total and it is symmetric: the same two messages read from the client's chair
     * must produce the exact opposite of the three assertions above. A fix that made the acceptor right
     * by making the initiator wrong would pass every test before this one.
     */
    @Test
    fun `the same capture read as the client inverts every one of those answers`() {
        val (expect, send) = capture(MintingSide.CLIENT)
        assertEquals(
            Matcher.Exact("CLI-ORD-001"),
            matcherFor(expect, 11),
            "as the client, an inbound ClOrdID is our own id echoed back — a literal until correlation rewrites it",
        )
        assertEquals("VEN-ORD-77", valueFor(send, 37), "as the client we replay their OrderID, never invent one")
        assertEquals("VEN-EXEC-77", valueFor(send, 17), "as the client we replay their ExecID, never invent one")
    }

    /** Absent from the map means CLIENT — the historical default, so no existing capture changes shape. */
    @Test
    fun `a session with no declared side is captured as the client`() {
        val declared = capture(MintingSide.CLIENT)
        val undeclared =
            ScenarioCapture.capture(
                id = "acceptor-capture",
                name = "acceptor capture",
                profile = null,
                sessions = listOf(orderAndReply()),
                dictionary = dictionary,
            )
        assertEquals(declared.second.raw, undeclared.steps.filterIsInstance<ScenarioStep.Send>().single().raw)
    }
}
