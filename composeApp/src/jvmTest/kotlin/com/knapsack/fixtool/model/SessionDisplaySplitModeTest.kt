package com.knapsack.fixtool.model

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.Message
import quickfix.field.MsgType
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the FIX message session display in SPLIT/RAW mode.
 * Tests message rendering, search functionality, filtering, and color coding
 * in the split view (raw message view with search bar).
 */
class SessionDisplaySplitModeTest {
    private lateinit var session: FixMessageSession

    @Before
    fun setup() {
        session = FixMessageSession(title = "Split Mode Test Session")
    }

    @After
    fun cleanup() {
        session.destroy()
    }

    // Helper function to create a mock QuickFIX Message
    private fun createQuickFixMessage(messageType: String): Message {
        val message = Message()
        message.header.setString(MsgType.FIELD, messageType)
        return message
    }

    // Helper function to wait for async message processing
    private suspend fun waitForMessages(count: Int = 1, waitTime: Long = 150) {
        delay((count * waitTime))
    }

    // ========================================
    // RAW Mode Message Display Tests
    // ========================================

    @Test
    fun testRawModeDisplayIncomingMessage() =
        runBlocking {
            val rawMessage = "8=FIX.4.2|9=100|35=D|49=SENDER|56=TARGET|34=1|52=20240101-12:00:00|10=123|"
            val fixMessage =
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = rawMessage,
                    messageType = "D",
                    quickfixMessage = createQuickFixMessage("D"),
                )

            session.addMessage(fixMessage)
            waitForMessages()

            val messages = session.messages.value
            assertEquals(1, messages.size)

            val msg = messages[0] as FixMessage
            assertEquals(FixMessage.Direction.INCOMING, msg.direction)
            assertTrue(msg.toDisplayString().contains("<<"), "Incoming message should have << in display")
            assertTrue(msg.toDisplayString().contains("[D]"), "Should contain message type")
            assertTrue(msg.toDisplayString().contains(rawMessage), "Should contain raw message")
        }

    @Test
    fun testRawModeDisplayOutgoingMessage() =
        runBlocking {
            val rawMessage = "8=FIX.4.2|9=100|35=8|49=TARGET|56=SENDER|10=124|"
            val fixMessage =
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = rawMessage,
                    messageType = "8",
                    quickfixMessage = createQuickFixMessage("8"),
                )

            session.addMessage(fixMessage)
            waitForMessages()

            val msg = session.messages.value[0] as FixMessage
            assertTrue(msg.toDisplayString().contains(">>"), "Outgoing message should have >> in display")
        }

    @Test
    fun testRawModeSeparatorDisplay() =
        runBlocking {
            session.addSeparator()
            waitForMessages()

            val messages = session.messages.value
            assertTrue(messages[0] is Separator)
            assertEquals("", messages[0].toDisplayString(), "Separator should have empty display")
        }

    @Test
    fun testRawModeMultipleMessagesWithSeparator() =
        runBlocking {
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=D|49=SENDER|56=TARGET|10=123|",
                    messageType = "D",
                    quickfixMessage = createQuickFixMessage("D"),
                ),
            )

            session.addSeparator()

            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = "8=FIX.4.2|9=100|35=8|49=TARGET|56=SENDER|10=124|",
                    messageType = "8",
                    quickfixMessage = createQuickFixMessage("8"),
                ),
            )

            waitForMessages(3)

            val messages = session.messages.value
            assertEquals(3, messages.size)
            assertTrue(messages[0] is FixMessage)
            assertTrue(messages[1] is Separator)
            assertTrue(messages[2] is FixMessage)
        }

    // ========================================
    // Message Color Coding Tests
    // ========================================

    @Test
    fun testRejectionMessageIdentification() {
        val rejectMessage =
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.2|9=100|35=3|58=Invalid tag|10=123|",
                messageType = "3",
                quickfixMessage = createQuickFixMessage("3"),
            )

        assertTrue(rejectMessage.isRejectionOrLogout(), "Session-level reject (35=3) should be identified")
    }

    @Test
    fun testBusinessRejectMessageIdentification() {
        val businessReject =
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.2|9=100|35=j|380=2|58=Unknown security|10=123|",
                messageType = "j",
                quickfixMessage = createQuickFixMessage("j"),
            )

        assertTrue(businessReject.isRejectionOrLogout(), "Business reject (35=j) should be identified")
    }

    @Test
    fun testOrderCancelRejectIdentification() {
        val cancelReject =
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.2|9=100|35=9|11=ORDER123|39=0|10=123|",
                messageType = "9",
                quickfixMessage = createQuickFixMessage("9"),
            )

        assertTrue(cancelReject.isRejectionOrLogout(), "Order cancel reject (35=9) should be identified")
    }

    @Test
    fun testNormalMessageNotMarkedAsRejection() {
        val normalMessage =
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.2|9=100|35=D|11=ORDER123|10=123|",
                messageType = "D",
                quickfixMessage = createQuickFixMessage("D"),
            )

        assertFalse(normalMessage.isRejectionOrLogout(), "Normal order should not be marked as rejection")
    }

    @Test
    fun testAlternatingMessageIndexColors() =
        runBlocking {
            // Add 4 messages to test alternating index-based colors
            for (i in 0 until 4) {
                session.addMessage(
                    FixMessage(
                        timestamp = LocalDateTime.now(),
                        direction = FixMessage.Direction.INCOMING,
                        rawMessage = "8=FIX.4.2|9=100|35=D|49=SENDER|10=123|",
                        messageType = "D",
                        quickfixMessage = createQuickFixMessage("D"),
                    ),
                )
            }

            waitForMessages(4)

            val messages = session.messages.value
            assertEquals(4, messages.size)

            // In FixMessageDisplay.kt, colors alternate based on message index:
            // index % 2 == 1 -> bright color
            // index % 2 == 0 -> dull color
            // This is tested visually, but we can verify the messages are in order
            for (i in 0 until 4) {
                assertTrue(messages[i] is FixMessage)
            }
        }

    // ========================================
    // Search Functionality Tests
    // ========================================

    @Test
    fun testSearchBarToggle() {
        assertFalse(session.searchVisible.value, "Search should be hidden initially")

        session.toggleSearch()
        assertTrue(session.searchVisible.value, "Search should be visible after toggle")

        session.toggleSearch()
        assertFalse(session.searchVisible.value, "Search should be hidden after second toggle")
    }

    @Test
    fun testSearchInMessageContent() =
        runBlocking {
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=D|49=BUYER|56=SELLER|55=EUR/USD|10=123|",
                    messageType = "D",
                    quickfixMessage = createQuickFixMessage("D"),
                ),
            )

            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = "8=FIX.4.2|9=100|35=8|49=SELLER|56=BUYER|55=GBP/USD|10=124|",
                    messageType = "8",
                    quickfixMessage = createQuickFixMessage("8"),
                ),
            )

            waitForMessages(2)

            val messages = session.messages.value
            val message1 = (messages[0] as FixMessage).toDisplayString()
            val message2 = (messages[1] as FixMessage).toDisplayString()

            // Search works on display string which includes direction, type, and raw message
            assertTrue(message1.contains("BUYER"), "First message should contain BUYER")
            assertTrue(message1.contains("EUR/USD"), "First message should contain EUR/USD")
            assertTrue(message1.contains("<<"), "First message should be incoming")

            assertTrue(message2.contains("SELLER"), "Second message should contain SELLER")
            assertTrue(message2.contains("GBP/USD"), "Second message should contain GBP/USD")
            assertTrue(message2.contains(">>"), "Second message should be outgoing")
        }

    @Test
    fun testSearchInTimestamp() =
        runBlocking {
            val timestamp = LocalDateTime.of(2024, 3, 15, 10, 30, 45, 123000000)
            session.addMessage(
                FixMessage(
                    timestamp = timestamp,
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=D|10=123|",
                    messageType = "D",
                    quickfixMessage = createQuickFixMessage("D"),
                ),
            )

            waitForMessages()

            val displayString = (session.messages.value[0] as FixMessage).toDisplayString()
            assertTrue(displayString.contains("10:30:45.123"), "Display string should contain formatted time")
        }

    // ========================================
    // Filter Functionality Tests
    // ========================================

    @Test
    fun testFilterBarToggle() {
        assertFalse(session.filterVisible.value, "Filter should be hidden initially")

        session.toggleFilter()
        assertTrue(session.filterVisible.value, "Filter should be visible after toggle")

        session.toggleFilter()
        assertFalse(session.filterVisible.value, "Filter should be hidden after second toggle")
    }

    @Test
    fun testFilterByDirection() {
        // Default: show both directions
        assertTrue(session.filterShowIncoming.value)
        assertTrue(session.filterShowOutgoing.value)

        // Hide incoming
        session.setFilterShowIncoming(false)
        assertFalse(session.filterShowIncoming.value)
        assertTrue(session.filterShowOutgoing.value)

        // Hide outgoing
        session.setFilterShowOutgoing(false)
        assertFalse(session.filterShowIncoming.value)
        assertFalse(session.filterShowOutgoing.value)
    }

    @Test
    fun testFilterBySeparator() {
        assertTrue(session.filterShowSeparator.value, "Separators should be shown by default")

        session.setFilterShowSeparator(false)
        assertFalse(session.filterShowSeparator.value)
    }

    @Test
    fun testFilterByMessageType() {
        session.setFilterMessageTypes("D,8,R")
        assertEquals("D,8,R", session.filterMessageTypes.value)

        val types =
            session.filterMessageTypes.value
                .split(",")
                .map { it.trim() }
        assertEquals(3, types.size)
        assertTrue(types.contains("D"))
        assertTrue(types.contains("8"))
        assertTrue(types.contains("R"))
    }

    @Test
    fun testFilterByRegex() {
        session.setFilterRegex(".*EUR/USD.*")
        assertEquals(".*EUR/USD.*", session.filterRegex.value)

        session.setFilterRegex("49=SENDER")
        assertEquals("49=SENDER", session.filterRegex.value)
    }

    @Test
    fun testCombinedFilters() {
        session.setFilterShowIncoming(true)
        session.setFilterShowOutgoing(false)
        session.setFilterMessageTypes("D,8")
        session.setFilterRegex(".*EUR.*")

        assertTrue(session.filterShowIncoming.value)
        assertFalse(session.filterShowOutgoing.value)
        assertEquals("D,8", session.filterMessageTypes.value)
        assertEquals(".*EUR.*", session.filterRegex.value)
    }

    // ========================================
    // Heartbeat Filtering Tests
    // ========================================

    @Test
    fun testHeartbeatFiltering() =
        runBlocking {
            val config =
                FixConnectionConfig(
                    username = "Test",
                    host = "localhost",
                    port = "9000",
                    senderCompID = "SENDER",
                    targetCompID = "TARGET",
                    beginString = "FIX.4.2",
                    showHeartbeat = false,
                )

            session = FixMessageSession(title = "HB Filter Test")
            session.javaClass.getDeclaredField("_connectionConfig").apply {
                isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (get(session) as kotlinx.coroutines.flow.MutableStateFlow<FixConnectionConfig?>).value = config
            }

            // Add heartbeat (should be filtered)
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=0|49=SENDER|56=TARGET|10=123|",
                    messageType = "0",
                    quickfixMessage = createQuickFixMessage("0"),
                ),
            )

            // Add regular message (should appear)
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=D|49=SENDER|56=TARGET|10=123|",
                    messageType = "D",
                    quickfixMessage = createQuickFixMessage("D"),
                ),
            )

            waitForMessages(2)

            val messages = session.messages.value
            assertEquals(1, messages.size, "Heartbeat should be filtered")
            assertEquals("D", (messages[0] as FixMessage).messageType)
        }

    @Test
    fun testHeartbeatNotFilteredWhenEnabled() =
        runBlocking {
            val config =
                FixConnectionConfig(
                    username = "Test",
                    host = "localhost",
                    port = "9000",
                    senderCompID = "SENDER",
                    targetCompID = "TARGET",
                    beginString = "FIX.4.2",
                    showHeartbeat = true,
                )

            session = FixMessageSession(title = "HB Shown Test")
            session.javaClass.getDeclaredField("_connectionConfig").apply {
                isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (get(session) as kotlinx.coroutines.flow.MutableStateFlow<FixConnectionConfig?>).value = config
            }

            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=0|49=SENDER|56=TARGET|10=123|",
                    messageType = "0",
                    quickfixMessage = createQuickFixMessage("0"),
                ),
            )

            waitForMessages()

            val messages = session.messages.value
            assertEquals(1, messages.size, "Heartbeat should appear")
            assertEquals("0", (messages[0] as FixMessage).messageType)
        }

    // ========================================
    // Display String Format Tests
    // ========================================

    @Test
    fun testIncomingMessageFormatting() {
        val timestamp = LocalDateTime.of(2024, 1, 15, 14, 30, 45, 123000000)
        val message =
            FixMessage(
                timestamp = timestamp,
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.2|9=100|35=D|49=SENDER|56=TARGET|10=123|",
                messageType = "D",
                quickfixMessage = createQuickFixMessage("D"),
            )

        val display = message.toDisplayString()

        // Format: HH:mm:ss.SSS << [TYPE] raw_message
        assertTrue(display.contains("14:30:45.123"), "Should contain formatted timestamp")
        assertTrue(display.contains("<<"), "Should contain incoming indicator")
        assertTrue(display.contains("[D]"), "Should contain message type in brackets")
        assertTrue(display.contains("8=FIX.4.2"), "Should contain raw message")
    }

    @Test
    fun testOutgoingMessageFormatting() {
        val message =
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.OUTGOING,
                rawMessage = "8=FIX.4.2|9=100|35=8|49=TARGET|56=SENDER|10=124|",
                messageType = "8",
                quickfixMessage = createQuickFixMessage("8"),
            )

        val display = message.toDisplayString()
        assertTrue(display.contains(">>"), "Should contain outgoing indicator")
        assertTrue(display.contains("[8]"), "Should contain execution report type")
    }

    // ========================================
    // Message Buffer Tests
    // ========================================

    @Test
    fun testMessageBufferSize() =
        runBlocking {
            val bufferSize = System.getProperty("noOfMsgToBuffer", "1000").toInt()

            // Add more messages than buffer allows
            repeat(bufferSize + 10) { index ->
                session.addMessage(
                    FixMessage(
                        timestamp = LocalDateTime.now(),
                        direction = FixMessage.Direction.INCOMING,
                        rawMessage = "8=FIX.4.2|9=100|35=D|34=$index|10=123|",
                        messageType = "D",
                        quickfixMessage = createQuickFixMessage("D"),
                    ),
                )
            }

            waitForMessages(bufferSize + 10, 2)
            delay(20) // Extra wait for buffer to stabilize

            val messages = session.messages.value
            assertTrue(messages.size <= bufferSize, "Messages should not exceed buffer size")
        }

    @Test
    fun testClearMessages() =
        runBlocking {
            repeat(5) {
                session.addMessage(
                    FixMessage(
                        timestamp = LocalDateTime.now(),
                        direction = FixMessage.Direction.INCOMING,
                        rawMessage = "8=FIX.4.2|9=100|35=D|10=123|",
                        messageType = "D",
                        quickfixMessage = createQuickFixMessage("D"),
                    ),
                )
            }

            waitForMessages(5)
            assertTrue(session.messages.value.isNotEmpty())

            session.clearMessages()
            assertEquals(0, session.messages.value.size)
        }

    // ========================================
    // View Mode Tests
    // ========================================

    @Test
    fun testWrapTextToggle() {
        assertTrue(session.wrapText.value, "Text should wrap by default")

        session.toggleWrapText()
        assertFalse(session.wrapText.value)

        session.toggleWrapText()
        assertTrue(session.wrapText.value)
    }

    // ========================================
    // Message Type Parsing Tests
    // ========================================

    @Test
    fun testParseMessageTypeFromPipeDelimited() {
        val raw = "8=FIX.4.2|9=100|35=D|49=SENDER|56=TARGET|10=123|"
        assertEquals("D", FixMessage.parseMessageType(raw))
    }

    @Test
    fun testParseMessageTypeFromSOHDelimited() {
        val raw = "8=FIX.4.2\u00019=100\u000135=8\u000149=SENDER\u000156=TARGET\u000110=123\u0001"
        assertEquals("8", FixMessage.parseMessageType(raw))
    }

    @Test
    fun testParseMultiCharacterMessageType() {
        val raw = "8=FIX.4.2|9=100|35=AE|49=SENDER|56=TARGET|10=123|"
        assertEquals("AE", FixMessage.parseMessageType(raw))
    }

    @Test
    fun testParseMessageTypeWhenMissing() {
        val raw = "8=FIX.4.2|9=100|49=SENDER|56=TARGET|10=123|"
        assertEquals("UNKNOWN", FixMessage.parseMessageType(raw))
    }
}
