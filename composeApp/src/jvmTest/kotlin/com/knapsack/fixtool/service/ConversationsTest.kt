package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import org.junit.Test
import quickfix.Message
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grouping engine behind the conversation view. The flow under test is the one that motivated it:
 * two RFQs interleaved on one session, one of which goes on to trade — the question *"did RFQ-B2 ever
 * trade?"* that a flat grid cannot answer at a glance.
 */
class ConversationsTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private var clock = 0

    private fun msg(raw: String, dir: FixMessage.Direction = FixMessage.Direction.INCOMING): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.of(2026, 7, 22, 10, 0, 0).plusNanos(clock++ * 1_000_000L),
            direction = dir,
            rawMessage = raw,
            quickfixMessage = Message(),
            // The named constant, not a literal SOH: an invisible control character in source is a
            // hazard, and every tool between here and the file has a chance to eat it.
            wireRaw = raw.replace('|', FixMessageHelper.SOH),
        )

    private val out = FixMessage.Direction.OUTGOING

    /** QuoteRequest → Quote → NewOrderSingle → ExecutionReport, interleaved with a second RFQ. */
    private fun interleavedRfqs(): List<FixMessage> =
        listOf(
            msg("35=R|131=RFQ-A1|55=EUR/USD|38=10000000|", out),
            msg("35=0|"),
            msg("35=R|131=RFQ-B2|55=GBP/USD|38=5000000|", out),
            msg("35=S|117=Q-77|131=RFQ-A1|"),
            msg("35=S|117=Q-78|131=RFQ-B2|"),
            msg("35=D|11=ORD-9|117=Q-77|", out),
            msg("35=8|37=V-551|11=ORD-9|39=0|"),
            msg("35=S|117=Q-79|131=RFQ-A1|"),
            msg("35=8|37=V-551|11=ORD-9|39=2|"),
        )

    /**
     * The chain, which is the whole point: the NewOrderSingle carries `ClOrdID` and `QuoteID` but no
     * `QuoteReqID`, and still belongs to RFQ-A1 — because the Quote carried both and joined them. No
     * rule anywhere names that path.
     */
    @Test
    fun `an order reaches its RFQ through the quote that linked them`() {
        val grouping = Conversations.group(interleavedRfqs(), dictionary)
        val a1 = grouping.conversations.single { it.label == "RFQ-A1" }
        assertEquals(
            listOf("R", "S", "D", "8", "S", "8"),
            a1.messages.map { it.messageType },
            "RFQ-A1 must contain its quotes, the order placed off one of them, and that order's reports",
        )
        assertTrue("ORD-9" in a1.ids && "Q-77" in a1.ids && "V-551" in a1.ids, "the chain's ids: ${a1.ids}")
    }

    /** The unanswerable question, answered: B2 was quoted and never traded. */
    @Test
    fun `an RFQ that never traded is a conversation with no order in it`() {
        val grouping = Conversations.group(interleavedRfqs(), dictionary)
        val b2 = grouping.conversations.single { it.label == "RFQ-B2" }
        assertEquals(listOf("R", "S"), b2.messages.map { it.messageType })
    }

    /** Named by what opened it, not by the venue's handle — which does not exist until the reply. */
    @Test
    fun `a conversation is labelled by the first id on its earliest message`() {
        val grouping = Conversations.group(interleavedRfqs(), dictionary)
        assertEquals(listOf("RFQ-A1", "RFQ-B2"), grouping.conversations.map { it.label }, "in the order they opened")
        assertEquals(131, grouping.conversations.first().labelTag)
    }

    /**
     * **Nothing is hidden.** The heartbeat carries no correlation id, and it must be visible and counted
     * rather than dropped — a view that silently omits what it did not understand is the failure mode
     * this codebase refuses everywhere else.
     */
    @Test
    fun `a message with no correlation id is kept in the ungrouped bucket`() {
        val messages = interleavedRfqs()
        val grouping = Conversations.group(messages, dictionary)
        assertEquals(listOf("0"), grouping.ungrouped.map { it.messageType })
        assertEquals(messages.size, grouping.total, "every message must land somewhere")
    }

    /** Only declared correlation tags draw edges, or an ordinary Side(54)=1 would blob the whole log. */
    @Test
    fun `ordinary business values that happen to be equal do not join two flows`() {
        val grouping =
            Conversations.group(
                listOf(
                    msg("35=D|11=ORD-1|54=1|40=1|", out),
                    msg("35=D|11=ORD-2|54=1|40=1|", out),
                ),
                dictionary,
            )
        assertEquals(2, grouping.conversations.size, "Side=1 on both must not make them one conversation")
    }

    /**
     * The degenerate case, documented rather than defended against: correlation ids that genuinely
     * collide DO merge, because by the only evidence available they are the same exchange. Recorded so
     * a future guard is a deliberate change and not a surprise — a tester using `11=1` twice sees one
     * conversation, and the header's `ids` is what explains why.
     */
    @Test
    fun `reused correlation id values merge, visibly`() {
        val grouping =
            Conversations.group(
                listOf(msg("35=D|11=1|", out), msg("35=D|11=1|", out)),
                dictionary,
            )
        assertEquals(1, grouping.conversations.size)
        assertEquals(2, grouping.conversations.single().messages.size)
    }
}
