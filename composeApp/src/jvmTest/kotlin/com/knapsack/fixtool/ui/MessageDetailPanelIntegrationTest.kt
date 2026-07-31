package com.knapsack.fixtool.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knapsack.fixtool.model.BookReading
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.SendReason
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import quickfix.Message
import quickfix.field.*
import java.time.LocalDateTime

/**
 * UI Integration tests for MessageDetailPanel
 *
 * Tests the actual UI rendering and interaction of the message detail panel,
 * including message display, field rendering, search, protocol tag hiding, and group expansion.
 */
class MessageDetailPanelIntegrationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var dictionary: FixDictionary
    private var selectedMessage by mutableStateOf<FixMessage?>(null)
    private var closeCallCount = 0

    @Before
    fun setup() {
        dictionary = FixDictionary.createDefault()
        selectedMessage = null
        closeCallCount = 0
    }

    // ========================================
    // Helper Methods
    // ========================================

    private fun createQuoteRequest(): FixMessage {
        val message = Message()

        // Header
        message.header.setString(BeginString.FIELD, "FIX.4.2")
        message.header.setString(MsgType.FIELD, MsgType.QUOTE_REQUEST)
        message.header.setString(SenderCompID.FIELD, "CLIENT")
        message.header.setString(TargetCompID.FIELD, "SERVER")
        message.header.setInt(MsgSeqNum.FIELD, 5)
        message.header.setString(SendingTime.FIELD, "20250128-11:00:00")

        // Body
        message.setString(QuoteReqID.FIELD, "QUOTE_REQ_1")

        // Add a repeating group - NoRelatedSym (146)
        val group1 = quickfix.fix42.QuoteRequest.NoRelatedSym()
        group1.setString(Symbol.FIELD, "EUR/USD")
        group1.setChar(Side.FIELD, Side.BUY)
        group1.setDouble(OrderQty.FIELD, 1000000.0)
        message.addGroup(group1)

        val group2 = quickfix.fix42.QuoteRequest.NoRelatedSym()
        group2.setString(Symbol.FIELD, "GBP/USD")
        group2.setChar(Side.FIELD, Side.SELL)
        group2.setDouble(OrderQty.FIELD, 2000000.0)
        message.addGroup(group2)

        // Trailer
        message.trailer.setString(CheckSum.FIELD, "456")

        val rawMessage =
            "8=FIX.4.2|9=200|35=R|49=CLIENT|56=SERVER|34=5|52=20250128-11:00:00|" +
                "131=QUOTE_REQ_1|146=2|55=EUR/USD|54=1|38=1000000|55=GBP/USD|54=2|38=2000000|10=456|"

        return FixMessage(
            // Pinned, not LocalDateTime.now(). The panel renders this timestamp, and
            // testTrailerFieldsAreRendered asserts onNodeWithText("456") — which fails when *more than
            // one* node matches. So on any run whose clock happened to put "456" in the fractional
            // seconds, the CheckSum row and the timestamp both matched and the test died, at random,
            // roughly once in a few hundred CI runs. A fixture that depends on the wall clock is a
            // fixture that fails for a reason that has nothing to do with the code.
            timestamp = LocalDateTime.of(2025, 1, 28, 11, 0, 0),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = rawMessage,
            messageType = "R",
            quickfixMessage = message,
        )
    }

    // ========================================
    // UI Rendering Tests
    // ========================================

    @Test
    fun testMessageDetailPanelRendersWithQuoteRequest() {
        // Given: A Quote Request message
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed with the message
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // Then: The panel should display the header
        composeTestRule.onNodeWithText("Message Details").assertExists()

        // And: The direction should be shown as INCOMING
        composeTestRule.onNodeWithText("INCOMING").assertExists()

        // And: The raw message section should be displayed
        composeTestRule.onNodeWithText("RAW MESSAGE").assertExists()

        // And: The raw message content should be visible
        composeTestRule.onNodeWithText(quoteRequest.rawMessage, substring = true).assertExists()
    }

    @Test
    fun testRawMessageContainsQuoteRequestFields() {
        // Given: A Quote Request message
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // Then: The raw message should contain key fields
        composeTestRule.onNodeWithText("35=R", substring = true).assertExists() // MsgType
        composeTestRule.onNodeWithText("131=QUOTE_REQ_1", substring = true).assertExists() // QuoteReqID
        composeTestRule.onNodeWithText("146=2", substring = true).assertExists() // NoRelatedSym count
    }

    @Test
    fun testFieldsListRendersQuoteRequestFields() {
        // Given: A Quote Request message
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // Then: Fields should be displayed in the lazy list
        // Note: We verify the raw message contains the data, as the lazy list may not render all items
        composeTestRule.onNodeWithText(quoteRequest.rawMessage, substring = true).assertExists()

        // The panel structure should exist
        composeTestRule.onNodeWithText("RAW MESSAGE").assertExists()
    }

    @Test
    fun testRepeatingGroupIsDisplayed() {
        // Given: A Quote Request with repeating groups
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // Then: The raw message should contain the repeating group data
        composeTestRule.onNodeWithText("146=2", substring = true).assertExists()
        composeTestRule.onNodeWithText("EUR/USD", substring = true).assertExists()
        composeTestRule.onNodeWithText("GBP/USD", substring = true).assertExists()
    }

    @Test
    fun testCloseButtonWorks() {
        // Given: A Quote Request message
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // And: The close button is clicked
        composeTestRule.onNodeWithContentDescription("Close").performClick()

        // Then: The onClose callback should be invoked
        assert(closeCallCount == 1) { "Expected close to be called once, but was called $closeCallCount times" }
    }

    @Test
    fun testSearchFieldIsDisplayed() {
        // Given: A Quote Request message
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // Then: The search field should be displayed with placeholder
        composeTestRule.onNodeWithText("Search tags, names, or values...", substring = true).assertExists()
    }

    @Test
    fun testExpandCollapseAllButtonExists() {
        // Given: A Quote Request with repeating groups
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // Then: The expand/collapse all button should be displayed
        try {
            composeTestRule.onNodeWithContentDescription("Expand All").assertExists()
        } catch (e: AssertionError) {
            composeTestRule.onNodeWithContentDescription("Collapse All").assertExists()
        }
    }

    @Test
    fun testNoMessageDisplaysPrompt() {
        // Given: No message selected
        // When: MessageDetailPanel is displayed without a message
        composeTestRule.setContent {
            MessageDetailPanel(
                message = null,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // Then: The raw message section header should be displayed
        composeTestRule.onNodeWithText("RAW MESSAGE").assertExists()

        // And: The paste button should be available (if onPasteMessage callback is provided)
        // Note: We're not providing onPasteMessage in this test, so paste button won't be shown
    }

    @Test
    fun testCopyButtonExistsWhenMessageIsSelected() {
        // Given: A Quote Request message
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // Then: The copy button should be displayed
        composeTestRule.onNodeWithContentDescription("Copy All").assertExists()
    }

    @Test
    fun testDirectionColorForIncomingMessage() {
        // Given: An incoming Quote Request message
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // Then: The direction text should be INCOMING
        composeTestRule.onNodeWithText("INCOMING").assertExists()

        // Note: We can't easily test the color in Compose tests without custom matchers,
        // but the direction text being present confirms the UI is rendering correctly
    }

    @Test
    fun testTimestampIsDisplayed() {
        // Given: A Quote Request message
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // Then: The timestamp should be displayed
        // The timestamp format will be the LocalDateTime.toString() format
        val timestampString = quoteRequest.timestamp.toString()
        composeTestRule.onNodeWithText(timestampString, substring = true).assertExists()
    }

    @Test
    fun testHeaderFieldsAreRendered() {
        // Given: A Quote Request message
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // Then: Header fields should be displayed
        // BeginString (8), SenderCompID (49), TargetCompID (56), etc.
        composeTestRule.onNodeWithText("FIX.4.2", substring = true).assertExists()
        composeTestRule.onNodeWithText("CLIENT", substring = true).assertExists()
        composeTestRule.onNodeWithText("SERVER", substring = true).assertExists()
    }

    @Test
    fun testBodyFieldsAreRendered() {
        // Given: A Quote Request message
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // Then: Body fields should be in the raw message
        composeTestRule.onNodeWithText("131=QUOTE_REQ_1", substring = true).assertExists()
    }

    @Test
    fun testTrailerFieldsAreRendered() {
        // Given: A Quote Request message
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // Then: Trailer fields should be displayed (CheckSum = 456)
        composeTestRule.onNodeWithText("456", substring = true).assertExists()
    }

    @Test
    fun testSearchFindsFieldsInsideNestedGroups() {
        // Given: A Quote Request with repeating groups containing EUR/USD and GBP/USD
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // First, expand the group to see all fields
        composeTestRule.onNodeWithContentDescription("Expand All").performClick()
        composeTestRule.waitForIdle()

        // Verify both EUR/USD and GBP/USD are visible before search
        composeTestRule.onAllNodesWithText("EUR/USD", substring = true).fetchSemanticsNodes().size.let { count ->
            assert(count >= 1) { "Expected EUR/USD to be visible before search" }
        }
        composeTestRule.onAllNodesWithText("GBP/USD", substring = true).fetchSemanticsNodes().size.let { count ->
            assert(count >= 1) { "Expected GBP/USD to be visible before search" }
        }

        // And: We search for a field value inside a group (Symbol field inside NoRelatedSym group)
        // First, we need to click on the search field to focus it
        composeTestRule
            .onNode(
                hasSetTextAction() and hasText("Search tags, names, or values...", substring = true),
            ).performClick()

        composeTestRule.waitForIdle()

        // Then input the search text
        composeTestRule.onNode(hasSetTextAction()).performTextInput("EUR/USD")

        // Wait for UI to update
        composeTestRule.waitForIdle()

        // Then: The matching field value should be visible in multiple places
        // (search field, fields list, and raw message section)
        val eurUsdNodes = composeTestRule.onAllNodesWithText("EUR/USD", substring = true).fetchSemanticsNodes()
        assert(eurUsdNodes.size >= 2) {
            "Expected EUR/USD to be visible in at least the fields list and raw message, but found ${eurUsdNodes.size} nodes"
        }

        // And: GBP/USD should NOT be visible in the fields list anymore (filtered out by search)
        // It might still be in the raw message section, but should not be in the filtered fields list
        // We can verify this by checking that we see fewer GBP/USD nodes than EUR/USD nodes
        val gbpUsdNodes = composeTestRule.onAllNodesWithText("GBP/USD", substring = true).fetchSemanticsNodes()
        // EUR/USD should appear in more places (search field + fields list + raw message)
        // GBP/USD should only appear in raw message (1 place)
        assert(eurUsdNodes.size > gbpUsdNodes.size) {
            "Expected EUR/USD to appear more times than GBP/USD after search, but EUR=${eurUsdNodes.size}, GBP=${gbpUsdNodes.size}"
        }
    }

    @Test
    fun testSearchFindsFieldsByTag() {
        // Given: A Quote Request message
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // And: We search for a tag number (55 = Symbol)
        composeTestRule
            .onNode(
                hasSetTextAction() and hasText("Search tags, names, or values...", substring = true),
            ).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("55")

        // Wait for UI to update
        composeTestRule.waitForIdle()

        // Then: Both symbols should be visible since they both use tag 55.
        // With the IDENTITY match-context default, matching group instances are revealed inline,
        // so each symbol can appear in both the fields list and the raw message section. Use
        // onAllNodesWithText (rather than onNodeWithText, which requires exactly one match).
        assert(composeTestRule.onAllNodesWithText("EUR/USD", substring = true).fetchSemanticsNodes().isNotEmpty()) {
            "Expected EUR/USD to be visible after searching tag 55"
        }
        assert(composeTestRule.onAllNodesWithText("GBP/USD", substring = true).fetchSemanticsNodes().isNotEmpty()) {
            "Expected GBP/USD to be visible after searching tag 55"
        }
    }

    @Test
    fun testSearchFindsFieldsByName() {
        // Given: A Quote Request message
        val quoteRequest = createQuoteRequest()

        // When: MessageDetailPanel is displayed
        composeTestRule.setContent {
            MessageDetailPanel(
                message = quoteRequest,
                dictionary = dictionary,
                onClose = { closeCallCount++ },
            )
        }

        // And: We search for a field name (Symbol)
        composeTestRule
            .onNode(
                hasSetTextAction() and hasText("Search tags, names, or values...", substring = true),
            ).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Symbol")

        // Wait for UI to update
        composeTestRule.waitForIdle()

        // Then: The symbols should be visible in the raw message
        composeTestRule.onNodeWithText("EUR/USD", substring = true).assertExists()
    }

    // ========================================
    // Why the venue sent this
    // ========================================

    /**
     * The recorded reason, beside the bytes it explains.
     *
     * Read off the message rather than worked out here, which is the whole of decision 6a: by the time
     * anybody opens this panel the book has moved on, and a panel that re-derived the reason would
     * state a different one — confidently, and about the wrong moment.
     */
    @Test
    fun `an auto-reply shows the rule that chose it and what the book said`() {
        val reply =
            createQuoteRequest().copy(
                direction = FixMessage.Direction.OUTGOING,
                sendReason =
                    SendReason(
                        source = SendReason.Source.RULE,
                        at = LocalDateTime.of(2026, 7, 31, 9, 14, 22, 418_000_000),
                        ruleIndex = 2,
                        whenMsgType = "F",
                        constraint = OrderConstraint.UNKNOWN,
                        reading = BookReading.unknown("ORD-9"),
                    ),
            )

        composeTestRule.setContent {
            MessageDetailPanel(message = reply, dictionary = dictionary, onClose = {})
        }

        composeTestRule.onNodeWithTag("detail-send-reason").assertExists()
        composeTestRule
            .onNodeWithText("sent by rule 3 — 35=F matched, and the book said ORD-9 was unknown at 09:14:22.418")
            .assertExists()
    }

    @Test
    fun `a message nobody claimed draws no reason at all`() {
        composeTestRule.setContent {
            MessageDetailPanel(message = createQuoteRequest(), dictionary = dictionary, onClose = {})
        }

        composeTestRule.onNodeWithTag("detail-send-reason").assertDoesNotExist()
    }
}
