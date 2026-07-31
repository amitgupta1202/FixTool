package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.AcceptorResponder
import com.knapsack.fixtool.service.ReplyShape
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * "Reply With…" as a *click*, which is the only way this path can be verified — it is the mouse half
 * of the acceptor on purpose, so there is no control-surface hook to drive it through (see #40).
 *
 * What the panel is asked here is narrow and it is the whole of its job: offer the shapes it is given,
 * hand back the one that was picked, and refuse to hand back one that cannot be built. Which shapes
 * those are, and why one is refused, is decided in `AcceptorReplyShapesTest` — the panel is given the
 * answer because it cannot see the session a message arrived on and must not guess.
 */
class ReplyWithMenuTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun message(raw: String) =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw,
            quickfixMessage = AcceptorResponder.buildMessage(raw),
        )

    private val limitOrder = "35=D|11=ORD-1|55=ACME|54=1|38=1000|40=2|44=185.25"
    private val marketOrder = "35=D|11=ORD-2|55=ACME|54=1|38=1000|40=1"

    private fun panel(raw: String, onPick: (ReplyShape) -> Unit) {
        val msg = message(raw)
        composeTestRule.setContent {
            MessageDetailPanel(
                message = msg,
                dictionary = FixDictionary.createDefault(),
                onClose = {},
                replyOffers = AcceptorResponder.offersFor(msg.quickfixMessage),
                onReplyWith = onPick,
            )
        }
    }

    @Test
    fun `a shape picked from the menu is the shape handed back`() {
        var picked: ReplyShape? = null
        panel(limitOrder) { picked = it }

        composeTestRule.onNodeWithTag("detail-reply-with").performClick()
        composeTestRule.onNodeWithTag("reply-shape-fill").performClick()

        assertEquals("fill", picked?.id, "the editor would have opened on a different reply than the one clicked")
    }

    @Test
    fun `the menu offers what this message can be answered with, by name`() {
        panel(limitOrder) {}

        composeTestRule.onNodeWithTag("detail-reply-with").performClick()

        composeTestRule.onNodeWithText("Acknowledge").assertExists()
        composeTestRule.onNodeWithText("Fill").assertExists()
        composeTestRule.onNodeWithText("Reject the order").assertExists()
        // A cancel's replies belong to a cancel, and this is an order.
        composeTestRule.onNodeWithText("Canceled").assertDoesNotExist()
    }

    /**
     * The refusal is the answer, not the absence. A market order cannot be filled at `${req.44}`, and
     * a menu that simply omitted Fill would leave the tester wondering where it went — so it is shown,
     * disabled, wearing the reason in place of its summary.
     */
    @Test
    fun `a fill a market order cannot support is shown, disabled, and says why`() {
        var picked: ReplyShape? = null
        panel(marketOrder) { picked = it }

        composeTestRule.onNodeWithTag("detail-reply-with").performClick()

        // Every shape that prices a fill is refused, and each says so on its own line — hence the tag.
        composeTestRule
            .onNodeWithTag("reply-shape-fill")
            .assertTextContains("this message carries no 44, and the reply reads it")
        composeTestRule.onNodeWithTag("reply-shape-fill").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("reply-shape-fill").performClick()
        assertNull(picked, "a refused shape must not open an editor on a message that cannot be built")
    }

    @Test
    fun `no offers, no button — the initiator half of the app never sees this`() {
        val msg = message("35=8|37=EX-1|17=E-1|150=0|39=0|11=ORD-1")
        composeTestRule.setContent {
            MessageDetailPanel(
                message = msg,
                dictionary = FixDictionary.createDefault(),
                onClose = {},
                replyOffers = emptyList(),
                onReplyWith = {},
            )
        }

        composeTestRule.onNodeWithTag("detail-reply-with").assertDoesNotExist()
    }
}
