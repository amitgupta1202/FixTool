package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.TAG_CL_ORD_ID
import com.knapsack.fixtool.model.TAG_CUM_QTY
import com.knapsack.fixtool.model.TAG_EXEC_TYPE
import com.knapsack.fixtool.model.TAG_LAST_QTY
import com.knapsack.fixtool.model.TAG_LEAVES_QTY
import com.knapsack.fixtool.model.TAG_MSG_TYPE
import com.knapsack.fixtool.model.TAG_ORDER_ID
import com.knapsack.fixtool.model.TAG_ORDER_QTY
import com.knapsack.fixtool.model.TAG_ORD_STATUS
import com.knapsack.fixtool.model.TAG_SIDE
import com.knapsack.fixtool.model.TAG_SYMBOL
import com.knapsack.fixtool.service.OrderBookService
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * The panel, asked the two questions the design says it must never dodge: **can a reader check a
 * number**, and **does it admit when it might be wrong**.
 *
 * Everything else here — what a fill does to a quantity, whose order is whose — is settled in
 * `OrderBookTest` and `OrderBookServiceTest`. What only a rendered panel can say is that the evidence
 * is actually reachable from the claim.
 */
class OrderBookPanelTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var clock = LocalDateTime.of(2026, 7, 31, 9, 14, 22)

    private fun tick(): LocalDateTime {
        clock = clock.plusNanos(400_000_000)
        return clock
    }

    private fun service(): OrderBookService {
        val service = OrderBookService()
        service.record(
            sessionKey = "ALPHA",
            at = tick(),
            sent = false,
            fields =
                mapOf(
                    TAG_MSG_TYPE to "D", TAG_CL_ORD_ID to "ORD-5000", TAG_SYMBOL to "VOD.L",
                    TAG_SIDE to "1", TAG_ORDER_QTY to "5000",
                ),
            raw = "35=D|11=ORD-5000|55=VOD.L|54=1|38=5000",
            messageUid = 11,
        )
        service.record(
            sessionKey = "ALPHA",
            at = tick(),
            sent = true,
            fields =
                mapOf(
                    TAG_MSG_TYPE to "8", TAG_CL_ORD_ID to "ORD-5000", TAG_ORDER_ID to "EX-100005",
                    TAG_EXEC_TYPE to "0", TAG_ORD_STATUS to "0", TAG_CUM_QTY to "0", TAG_LEAVES_QTY to "5000",
                ),
            raw = "35=8|11=ORD-5000|150=0",
            messageUid = 12,
        )
        service.record(
            sessionKey = "ALPHA",
            at = tick(),
            sent = true,
            fields =
                mapOf(
                    TAG_MSG_TYPE to "8", TAG_CL_ORD_ID to "ORD-5000", TAG_ORDER_ID to "EX-100005",
                    TAG_EXEC_TYPE to "F", TAG_ORD_STATUS to "1", TAG_CUM_QTY to "2500",
                    TAG_LEAVES_QTY to "2500", TAG_LAST_QTY to "2500",
                ),
            raw = "35=8|11=ORD-5000|150=F|32=2500",
            messageUid = 13,
        )
        return service
    }

    private fun panel(
        service: OrderBookService = service(),
        onClear: () -> Unit = {},
        onOpenMessage: (Long) -> Unit = {},
    ) {
        composeTestRule.setContent {
            OrderBookPanel(
                book = service.view("ALPHA"),
                title = "VENUE ← ALPHA",
                onClear = onClear,
                onClose = {},
                onOpenMessage = onOpenMessage,
            )
        }
    }

    @Test
    fun `an order shows what the venue is holding`() {
        panel()

        composeTestRule.onNodeWithTag("order-book-summary").assertTextContains("1 order · 1 working", substring = true)
        composeTestRule.onNodeWithText("EX-100005").assertExists()
        composeTestRule.onNodeWithText("VOD.L").assertExists()
        composeTestRule.onNodeWithText("working").assertExists()
    }

    /**
     * The claim opens to its evidence. `CumQty 2500` on its own is something a reader has to take on
     * trust; the two lines under it are something they can count, which is the whole of decision 6b.
     */
    @Test
    fun `a row expands to the messages that made it`() {
        panel()

        composeTestRule.onNodeWithTag("order-row-ORD-5000").performClick()

        composeTestRule.onNodeWithTag("order-trail-ORD-5000-0").assertTextContains("order received", substring = true)
        composeTestRule.onNodeWithTag("order-trail-ORD-5000-1").assertTextContains("ack", substring = true)
        composeTestRule.onNodeWithTag("order-trail-ORD-5000-2").assertTextContains("fill 2500", substring = true)
        // Each line carries the state *after* it, which is what makes the fold visible rather than
        // merely claimed.
        composeTestRule.onNodeWithTag("order-trail-ORD-5000-1").assertTextContains("cum 0", substring = true)
        composeTestRule.onNodeWithTag("order-trail-ORD-5000-2").assertTextContains("cum 2500", substring = true)
    }

    @Test
    fun `a trail line leads back to the message it came from`() {
        var opened: Long? = null
        panel(onOpenMessage = { opened = it })

        composeTestRule.onNodeWithTag("order-row-ORD-5000").performClick()
        composeTestRule.onNodeWithTag("order-trail-ORD-5000-2").performClick()

        assertEquals(13L, opened, "the trail has to lead to *that* message — the question a trail raises is 'what did we send?'")
    }

    /**
     * A count says something is wrong and nothing whatever about what, so it opens.
     */
    @Test
    fun `an unattributed report is counted, and the count opens to the reason`() {
        val service = service()
        service.record(
            sessionKey = "ALPHA",
            at = tick(),
            sent = true,
            fields = mapOf(TAG_MSG_TYPE to "8", TAG_CL_ORD_ID to "GHOST-9", TAG_EXEC_TYPE to "F", TAG_CUM_QTY to "10"),
            raw = "35=8|11=GHOST-9|150=F|14=10",
            messageUid = 99,
        )
        panel(service)

        composeTestRule.onNodeWithTag("order-book-unattributed").assertTextContains("1 unattributed", substring = true)
        composeTestRule.onNodeWithTag("order-book-unattributed").performClick()
        composeTestRule.onNodeWithText("GHOST-9", substring = true).assertExists()
    }

    /**
     * The distinction an empty panel exists to make. "No orders" would be true of both a venue nobody
     * has traded with and one whose book was wiped thirty seconds ago, and those send a tester in
     * opposite directions.
     */
    @Test
    fun `an empty book says whether it was cleared or never filled`() {
        val fresh = OrderBookService()
        composeTestRule.setContent {
            OrderBookPanel(book = fresh.view("ALPHA"), title = "VENUE ← ALPHA", onClear = {}, onClose = {})
        }

        composeTestRule.onNodeWithTag("order-book-empty").assertTextContains("No orders yet", substring = true)
    }

    @Test
    fun `a cleared book says so, and by whom`() {
        val service = service()
        service.clear("ALPHA", by = "manually", at = clock)
        panel(service)

        composeTestRule.onNodeWithTag("order-book-empty").assertTextContains("Cleared", substring = true)
        composeTestRule.onNodeWithTag("order-book-empty").assertTextContains("manually", substring = true)
    }

    @Test
    fun `evictions are on the panel, so a capped book never looks merely quiet`() {
        val service = OrderBookService(cap = 1)
        repeat(3) { i ->
            service.record(
                sessionKey = "ALPHA",
                at = tick(),
                sent = false,
                fields = mapOf(TAG_MSG_TYPE to "D", TAG_CL_ORD_ID to "ORD-$i", TAG_ORDER_QTY to "10"),
                raw = "35=D|11=ORD-$i",
            )
            service.record(
                sessionKey = "ALPHA",
                at = tick(),
                sent = true,
                fields =
                    mapOf(
                        TAG_MSG_TYPE to "8", TAG_CL_ORD_ID to "ORD-$i", TAG_EXEC_TYPE to "2",
                        TAG_ORD_STATUS to "2", TAG_CUM_QTY to "10", TAG_LEAVES_QTY to "0",
                    ),
                raw = "35=8|11=ORD-$i|150=2",
            )
        }
        panel(service)

        composeTestRule.onNodeWithTag("order-book-evicted").assertTextContains("2 evicted of 1", substring = true)
    }

    @Test
    fun `clearing is a button, because starting a test from a known state is a thing testers do`() {
        var cleared = false
        panel(onClear = { cleared = true })

        composeTestRule.onNodeWithTag("order-book-clear").performClick()

        assertEquals(true, cleared)
    }
}
