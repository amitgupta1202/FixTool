package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.LegOutcome
import com.knapsack.fixtool.model.LegQuote
import com.knapsack.fixtool.model.RfqBookView
import com.knapsack.fixtool.model.RfqEntry
import com.knapsack.fixtool.model.RfqLeg
import com.knapsack.fixtool.model.RfqLife
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * **The venue's RFQ book, as the pane draws it**: one row per negotiation, one per leg, each id both ways, and a
 * Treasury's level in thirty-seconds.
 */
class RfqBookPanelTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val now = 1_800_000_000_000L

    private val traded =
        RfqEntry(
            rfqId = "RFQ-1",
            requesterKey = "k-buy",
            requesterCompId = "FIBUY1",
            requesterQuoteReqId = "BUY-RFQ-7",
            openingUid = 1,
            opening = mapOf(55 to "T 4.25 08/15/36", 48 to "91282CMF5", 167 to "TNOTE", 54 to "1", 38 to "10000000"),
            openedAt = now - 20_000,
            expireAt = null,
            life = RfqLife.DONE,
            legs =
                listOf(
                    RfqLeg(
                        responderKey = "k-d1",
                        compId = "FIDLR1",
                        venueQuoteReqId = "V-RFQ-1-1",
                        quotes = listOf(LegQuote("D1-Q-88", "V-Q-1-1", offer = "98.515625")),
                        outcome = LegOutcome.LIFTED,
                    ),
                    RfqLeg(
                        responderKey = "k-d2",
                        compId = "FIDLR2",
                        venueQuoteReqId = "V-RFQ-1-2",
                        quotes = listOf(LegQuote("D2-551", "V-Q-1-2", offer = "98.53125")),
                        outcome = LegOutcome.COVER,
                    ),
                ),
        )

    private val open =
        traded.copy(
            rfqId = "RFQ-2",
            requesterQuoteReqId = "BUY-RFQ-8",
            openedAt = now - 5_000,
            life = RfqLife.OPEN,
            legs =
                listOf(
                    RfqLeg("k-d1", "FIDLR1", "V-RFQ-2-1", listOf(LegQuote("D1-Q-91", validUntil = now + 18_000, bid = "99.25"))),
                    RfqLeg("k-d2", "FIDLR2", "V-RFQ-2-2", outcome = LegOutcome.PASSED),
                    RfqLeg("k-d3", "FIDLR3", null, outcome = LegOutcome.NOT_DELIVERED),
                ),
        )

    @Test
    fun `the book draws each RFQ and its legs, newest first, and Clear clears it`() {
        var cleared = false
        composeTestRule.setContent {
            Box(Modifier.width(900.dp)) {
                RfqBookPanel(view = RfqBookView(listOf(traded, open), evicted = 2), onClear = { cleared = true }, now = now)
            }
        }

        composeTestRule.onNodeWithTag("rfq-book-summary").assertTextEquals("2 RFQs · 1 open · 2 evicted")
        composeTestRule.onNodeWithTag("rfq-state-RFQ-1").assertTextEquals("done · FIDLR1 lifted")
        composeTestRule.onNodeWithTag("rfq-leg-state-RFQ-1-FIDLR2").assertTextEquals("cover")
        composeTestRule.onNodeWithTag("rfq-leg-state-RFQ-2-FIDLR3").assertTextEquals("not delivered")
        composeTestRule.onNodeWithText("D1-Q-88 ↔ V-Q-1-1").assertExists()
        composeTestRule.onNodeWithText("98-16+ (98.515625) offer").assertExists()

        composeTestRule.onNodeWithTag("rfq-book-clear").performClick()
        assertEquals(true, cleared)
    }

    @Test
    fun `on a narrow pane the rows stack, and still say how each RFQ and leg ended`() {
        composeTestRule.setContent {
            Box(Modifier.width(400.dp)) {
                RfqBookPanel(view = RfqBookView(listOf(traded)), onClear = {}, now = now)
            }
        }

        composeTestRule.onNodeWithTag("rfq-state-RFQ-1").assertTextEquals("done · FIDLR1 lifted")
        composeTestRule.onNodeWithTag("rfq-leg-state-RFQ-1-FIDLR1").assertTextEquals("lifted")
        composeTestRule.onNodeWithText("T 4.25 08/15/36 91282CMF5 · Buy · 10mm").assertExists()
        composeTestRule.onNodeWithText("98-16+ (98.515625) offer").assertExists()
    }

    @Test
    fun `an empty book says what opens an RFQ, and offers nothing to clear`() {
        composeTestRule.setContent { RfqBookPanel(view = RfqBookView(), onClear = {}, now = now) }

        composeTestRule.onNodeWithTag("rfq-book-summary").assertTextEquals("0 RFQs")
        composeTestRule.onNodeWithTag("rfq-book-clear").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ the words

    @Test
    fun `a Treasury price reads in thirty-seconds, and a price that is not one is not rounded into one`() {
        assertEquals("98-16+", thirtySeconds("98.515625"))
        assertEquals("98-17", thirtySeconds("98.53125"))
        assertEquals("99-24", thirtySeconds("99.75"))
        assertEquals("100-00", thirtySeconds("100"))
        assertEquals("98-162", thirtySeconds("98.5078125"), "a quarter of a 32nd is its own digit")
        assertNull(thirtySeconds("98.51"))
        assertNull(thirtySeconds("abc"))
    }

    @Test
    fun `only a Treasury coupon security is quoted in 32nds`() {
        val quote = LegQuote("Q", bid = "99.25", offer = "99.265625")

        assertEquals("99-08 (99.25) bid / 99-08+ (99.265625) offer", levelLabel(quote, "TNOTE"))
        assertEquals("99.25 bid / 99.265625 offer", levelLabel(quote, "CORP"))
        assertEquals("99.25 bid / 99.265625 offer", levelLabel(quote, "TBILL"), "a bill trades on discount yield")
    }

    @Test
    fun `side and size read the way a rates desk says them`() {
        assertEquals("Buy · 10mm", sideAndSize(traded))
        assertEquals("Two-way · 25mm", sideAndSize(traded.copy(opening = mapOf(38 to "25000000"))))
        assertEquals("Sell · 2500000.5", sideAndSize(traded.copy(opening = mapOf(54 to "2", 38 to "2500000.5"))))
    }

    @Test
    fun `a leg says how far it got`() {
        assertEquals("asked", legStateLabel(RfqLeg("k", "FIDLR1", "V-1"), now))
        assertEquals("lapsed", legStateLabel(RfqLeg("k", "FIDLR1", "V-1", listOf(LegQuote("Q", validUntil = now - 1))), now))
        assertEquals("quoted", legStateLabel(RfqLeg("k", "FIDLR1", "V-1", listOf(LegQuote("Q"))), now))
        assertEquals("asked as V-1", legIds(RfqLeg("k", "FIDLR1", "V-1")))
        assertEquals("Q ↔ not shown yet", legIds(RfqLeg("k", "FIDLR1", "V-1", listOf(LegQuote("Q")))))
    }

    @Test
    fun `an RFQ past its expiry reads expired though nobody has said so`() {
        assertEquals("expired", rfqStateLabel(open.copy(expireAt = now - 1), now))
    }

    // ------------------------------------------------------------------ counterparties against the book

    @Test
    fun `each counterparty is counted once, against the entry that decides its role`() {
        val counterparties =
            listOf(
                Counterparty("FIBUY1", "requester"),
                Counterparty("FIDLR1", "responder"),
                Counterparty("FIDLR2", "responder"),
                Counterparty("FIDLR3", "responder"),
                Counterparty("FIDLR*", "responder"),
            )
        val clients = listOf("FIBUY1" to true, "FIDLR1" to true, "FIDLR2" to false, "FIDLR7" to true, "FIDLR8" to false, "ROGUE" to true)

        val rows = counterpartyActivity(counterparties, clients, RfqBookView(listOf(traded, open))).associateBy { it.compId }

        assertEquals("2 opened", rows.getValue("FIBUY1").activity)
        assertEquals("logged on", rows.getValue("FIBUY1").session)
        assertEquals("2 asked · 2 quoted · 1 lifted", rows.getValue("FIDLR1").activity)
        assertEquals("logged out", rows.getValue("FIDLR2").session)
        assertEquals("2 asked · 1 quoted · 1 passed", rows.getValue("FIDLR2").activity)
        assertEquals(1, rows.getValue("FIDLR3").notDelivered)
        assertEquals("not logged on", rows.getValue("FIDLR3").session)
        assertEquals("1 of 2 logged on", rows.getValue("FIDLR*").session, "FIDLR1-3 are their own rows, not the family's")
        assertEquals(listOf("ROGUE"), unlistedLogons(counterparties, clients))
    }
}
