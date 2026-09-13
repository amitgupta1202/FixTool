package com.knapsack.fixtool.control

import com.knapsack.fixtool.model.LegQuote
import com.knapsack.fixtool.model.RfqBookView
import com.knapsack.fixtool.model.RfqEntry
import com.knapsack.fixtool.model.RfqLeg
import com.knapsack.fixtool.model.RfqLife
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals

/**
 * **A quote is live only while its RFQ is.** An agent reading `/acceptor/rfqs` decides whether a lift can still
 * trade from `live`: a quote within its own validity on an RFQ that expired, traded or passed cannot be dealt, and
 * the book must not say it can. Found live, on an RFQ that expired 11 seconds before its only quote would have.
 */
class RfqBookJsonTest {
    private val now = 1_000_000L

    private fun rfq(
        life: RfqLife,
        expireAt: Long? = null,
    ) = RfqEntry(
        rfqId = "RFQ-1",
        requesterKey = "FIBUY1",
        requesterCompId = "FIBUY1",
        requesterQuoteReqId = "BUY-1",
        openingUid = null,
        opening = mapOf(55 to "T 4.25 08/15/36"),
        openedAt = now - 20_000,
        expireAt = expireAt,
        life = life,
        legs =
            listOf(
                RfqLeg(
                    "FIDLR1",
                    "FIDLR1",
                    quotes = listOf(LegQuote("D1-Q", "V-Q-1", offer = "98.515625", validUntil = now + 11_000)),
                ),
            ),
    )

    private fun live(entry: RfqEntry): Boolean =
        rfqBookJson("FI RFQ Platform", RfqBookView(listOf(entry)), now)["rfqs"]!!
            .jsonArray
            .single()
            .jsonObject["legs"]!!
            .jsonArray
            .single()
            .jsonObject["quotes"]!!
            .jsonArray
            .single()
            .jsonObject["live"]!!
            .jsonPrimitive.boolean

    @Test
    fun `a quote within its validity is live on an open RFQ`() {
        assertEquals(true, live(rfq(RfqLife.OPEN)))
    }

    @Test
    fun `a quote within its validity is not live once the RFQ is over, however it ended`() {
        RfqLife.entries.filterNot { it.live }.forEach { life ->
            assertEquals(false, live(rfq(life)), "an RFQ $life")
        }
    }

    /** Expired by the clock before the timer has marked it: the book reads the clock, and so does `live`. */
    @Test
    fun `a quote is not live on an open RFQ whose time has run out`() {
        assertEquals(false, live(rfq(RfqLife.OPEN, expireAt = now - 1)))
    }
}
