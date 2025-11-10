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
 * Integration tests for the FIX message session display in GRID/PARSED mode.
 * Tests hierarchical grid view rendering, protocol tag hiding, search functionality,
 * and parsed message structure display.
 */
class SessionDisplayGridModeTest {
    private lateinit var session: FixMessageSession

    @Before
    fun setup() {
        session = FixMessageSession(title = "Grid Mode Test Session")
        // Default is now PARSED mode (grid view) - no need to toggle
        assertEquals(FixMessageSession.ViewMode.PARSED, session.viewMode.value)
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
    private suspend fun waitForMessages(count: Int = 1, waitTimePerMessage: Long = 150) {
        delay((count * waitTimePerMessage))
    }

    // ========================================
    // PARSED Mode View Tests
    // ========================================

    @Test
    fun testParsedModeEnabled() {
        assertEquals(
            FixMessageSession.ViewMode.PARSED,
            session.viewMode.value,
            "Session should be in PARSED mode for grid view",
        )
    }

    @Test
    fun testToggleBetweenRawAndParsed() {
        assertEquals(FixMessageSession.ViewMode.PARSED, session.viewMode.value)

        session.toggleViewMode()
        assertEquals(FixMessageSession.ViewMode.RAW, session.viewMode.value)

        session.toggleViewMode()
        assertEquals(FixMessageSession.ViewMode.PARSED, session.viewMode.value)
    }

    // ========================================
    // Protocol Tag Hiding Tests
    // ========================================

    @Test
    fun testHideProtocolTagsDefaultState() {
        assertTrue(
            session.hideProtocolTags.value,
            "Protocol tags should be hidden by default in grid view",
        )
    }

    @Test
    fun testToggleProtocolTagsVisibility() {
        assertTrue(session.hideProtocolTags.value)

        session.toggleHideProtocolTags()
        assertFalse(
            session.hideProtocolTags.value,
            "Protocol tags should be visible after toggle",
        )

        session.toggleHideProtocolTags()
        assertTrue(
            session.hideProtocolTags.value,
            "Protocol tags should be hidden again after second toggle",
        )
    }

    // ========================================
    // Grid Mode Message Display Tests
    // ========================================

    @Test
    fun testGridModeDisplaysSingleMessage() =
        runBlocking {
            val rawMessage =
                "8=FIX.4.2|9=100|35=D|49=SENDER|56=TARGET|11=ORDER123|55=EUR/USD|54=1|38=1000000|40=2|10=123|"
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
            assertTrue(messages[0] is FixMessage)

            val msg = messages[0] as FixMessage
            assertEquals("D", msg.messageType)
            assertEquals(FixMessage.Direction.INCOMING, msg.direction)
        }

    @Test
    fun testGridModeDisplaysMultipleMessages() =
        runBlocking {
            // Add New Order Single
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = "8=FIX.4.2|9=100|35=D|49=SENDER|56=TARGET|11=ORDER1|55=EUR/USD|10=123|",
                    messageType = "D",
                    quickfixMessage = createQuickFixMessage("D"),
                ),
            )

            // Add Execution Report
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=8|49=TARGET|56=SENDER|11=ORDER1|39=0|10=124|",
                    messageType = "8",
                    quickfixMessage = createQuickFixMessage("8"),
                ),
            )

            // Add Quote Request
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = "8=FIX.4.2|9=100|35=R|49=SENDER|56=TARGET|131=QUOTE1|10=125|",
                    messageType = "R",
                    quickfixMessage = createQuickFixMessage("R"),
                ),
            )

            waitForMessages(3)

            val messages = session.messages.value
            assertEquals(3, messages.size)

            assertEquals("D", (messages[0] as FixMessage).messageType)
            assertEquals("8", (messages[1] as FixMessage).messageType)
            assertEquals("R", (messages[2] as FixMessage).messageType)
        }

    // ========================================
    // Search in Grid Mode Tests
    // ========================================

    @Test
    fun testSearchInGridMode() {
        assertFalse(session.searchVisible.value)

        session.toggleSearch()
        assertTrue(
            session.searchVisible.value,
            "Search should be available in grid mode",
        )
    }

    @Test
    fun testSearchFindsMessagesByContent() =
        runBlocking {
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = "8=FIX.4.2|9=100|35=D|49=BUYER|56=SELLER|55=EUR/USD|10=123|",
                    messageType = "D",
                    quickfixMessage = createQuickFixMessage("D"),
                ),
            )

            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=8|49=SELLER|56=BUYER|55=GBP/USD|10=124|",
                    messageType = "8",
                    quickfixMessage = createQuickFixMessage("8"),
                ),
            )

            waitForMessages(2)

            val messages = session.messages.value
            val msg1Display = (messages[0] as FixMessage).toDisplayString()
            val msg2Display = (messages[1] as FixMessage).toDisplayString()

            // Verify search would find these messages by content
            assertTrue(msg1Display.contains("EUR/USD"))
            assertTrue(msg1Display.contains("BUYER"))
            assertTrue(msg2Display.contains("GBP/USD"))
            assertTrue(msg2Display.contains("SELLER"))
        }

    // ========================================
    // Filter Tests in Grid Mode
    // ========================================

    @Test
    fun testFiltersWorkInGridMode() {
        // Filters should work the same in grid mode
        session.toggleFilter()
        assertTrue(session.filterVisible.value)

        session.setFilterMessageTypes("D,8,R")
        assertEquals("D,8,R", session.filterMessageTypes.value)

        session.setFilterShowIncoming(true)
        session.setFilterShowOutgoing(false)
        assertTrue(session.filterShowIncoming.value)
        assertFalse(session.filterShowOutgoing.value)
    }

    @Test
    fun testFilterByMessageTypeInGrid() =
        runBlocking {
            // Set filter to show only New Order Single (D) and Execution Report (8)
            session.setFilterMessageTypes("D,8")

            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = "8=FIX.4.2|9=100|35=D|10=123|",
                    messageType = "D",
                    quickfixMessage = createQuickFixMessage("D"),
                ),
            )

            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=R|10=124|",
                    messageType = "R",
                    quickfixMessage = createQuickFixMessage("R"),
                ),
            )

            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=8|10=125|",
                    messageType = "8",
                    quickfixMessage = createQuickFixMessage("8"),
                ),
            )

            waitForMessages(3)

            // All messages are added, but UI would filter to show only D and 8
            val messages = session.messages.value
            assertEquals(3, messages.size, "All messages are stored")

            // The filter would be applied in the UI layer
            val types =
                session.filterMessageTypes.value
                    .split(",")
                    .map { it.trim() }
            assertTrue(types.contains("D"))
            assertTrue(types.contains("8"))
            assertFalse(types.contains("R"))
        }

    // ========================================
    // Message Structure Tests
    // ========================================

    @Test
    fun testMessageWithRepeatingGroups() =
        runBlocking {
            // Quote Request with multiple symbols (repeating group)
            val rawMessage =
                "8=FIX.4.2|9=200|35=R|131=REQ1|146=2|" +
                    "55=EUR/USD|54=1|38=1000000|" +
                    "55=GBP/USD|54=1|38=2000000|10=123|"

            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = rawMessage,
                    messageType = "R",
                    quickfixMessage = createQuickFixMessage("R"),
                ),
            )

            waitForMessages()

            val messages = session.messages.value
            assertEquals(1, messages.size)

            val msg = messages[0] as FixMessage
            assertEquals("R", msg.messageType)
            assertTrue(msg.rawMessage.contains("146=2"), "Should contain NoRelatedSym tag")
        }

    @Test
    fun testMessageWithNestedGroups() =
        runBlocking {
            // Market Data Request with nested repeating groups
            val rawMessage =
                "8=FIX.4.2|9=200|35=V|262=MDR1|263=1|264=1|" +
                    "267=2|269=0|269=1|" +
                    "146=1|55=EUR/USD|10=123|"

            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = rawMessage,
                    messageType = "V",
                    quickfixMessage = createQuickFixMessage("V"),
                ),
            )

            waitForMessages()

            val msg = session.messages.value[0] as FixMessage
            assertEquals("V", msg.messageType)
            assertTrue(msg.rawMessage.contains("267=2"), "Should contain MDEntryType count")
        }

    // ========================================
    // Direction Indicator Tests in Grid
    // ========================================

    @Test
    fun testGridShowsMessageDirection() =
        runBlocking {
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=D|10=123|",
                    messageType = "D",
                    quickfixMessage = createQuickFixMessage("D"),
                ),
            )

            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = "8=FIX.4.2|9=100|35=8|10=124|",
                    messageType = "8",
                    quickfixMessage = createQuickFixMessage("8"),
                ),
            )

            waitForMessages(2)

            val messages = session.messages.value
            assertEquals(FixMessage.Direction.INCOMING, (messages[0] as FixMessage).direction)
            assertEquals(FixMessage.Direction.OUTGOING, (messages[1] as FixMessage).direction)
        }

    // ========================================
    // Message Type Display Tests
    // ========================================

    @Test
    fun testGridDisplaysCommonMessageTypes() =
        runBlocking {
            val messageTypes =
                listOf(
                    "D" to "New Order Single",
                    "8" to "Execution Report",
                    "0" to "Heartbeat",
                    "A" to "Logon",
                    "5" to "Logout",
                    "3" to "Reject",
                    "R" to "Quote Request",
                    "S" to "Quote",
                    "V" to "Market Data Request",
                    "W" to "Market Data Snapshot",
                )

            messageTypes.forEach { (type, _) ->
                session.addMessage(
                    FixMessage(
                        timestamp = LocalDateTime.now(),
                        direction = FixMessage.Direction.INCOMING,
                        rawMessage = "8=FIX.4.2|9=100|35=$type|10=123|",
                        messageType = type,
                        quickfixMessage = createQuickFixMessage(type),
                    ),
                )
            }

            waitForMessages(messageTypes.size)

            val messages = session.messages.value
            assertEquals(messageTypes.size, messages.size)

            messageTypes.forEachIndexed { index, (type, _) ->
                assertEquals(type, (messages[index] as FixMessage).messageType)
            }
        }

    // ========================================
    // Timestamp Display Tests
    // ========================================

    @Test
    fun testGridDisplaysMessageTimestamps() =
        runBlocking {
            val timestamp1 = LocalDateTime.of(2024, 3, 15, 10, 30, 45, 123000000)
            val timestamp2 = LocalDateTime.of(2024, 3, 15, 10, 31, 50, 456000000)

            session.addMessage(
                FixMessage(
                    timestamp = timestamp1,
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=D|10=123|",
                    messageType = "D",
                    quickfixMessage = createQuickFixMessage("D"),
                ),
            )

            session.addMessage(
                FixMessage(
                    timestamp = timestamp2,
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = "8=FIX.4.2|9=100|35=8|10=124|",
                    messageType = "8",
                    quickfixMessage = createQuickFixMessage("8"),
                ),
            )

            waitForMessages(2)

            val messages = session.messages.value
            assertEquals(timestamp1, (messages[0] as FixMessage).timestamp)
            assertEquals(timestamp2, (messages[1] as FixMessage).timestamp)

            // Verify display strings contain timestamps
            assertTrue((messages[0] as FixMessage).toDisplayString().contains("10:30:45.123"))
            assertTrue((messages[1] as FixMessage).toDisplayString().contains("10:31:50.456"))
        }

    // ========================================
    // Clear and Buffer Tests in Grid Mode
    // ========================================

    @Test
    fun testClearMessagesInGridMode() =
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

    @Test
    fun testBufferLimitInGridMode() =
        runBlocking {
            val bufferSize = System.getProperty("noOfMsgToBuffer", "1000").toInt()

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

            // Wait for the buffer to be populated (not the full count since it will be trimmed)
            waitForMessages(bufferSize + 10, 2)
            delay(5)

            assertTrue(
                session.messages.value.size <= bufferSize,
                "Grid mode should respect buffer size (expected <= $bufferSize, got ${session.messages.value.size})",
            )
        }

    // ========================================
    // Rejection Messages in Grid Mode
    // ========================================

    @Test
    fun testRejectionMessagesInGrid() =
        runBlocking {
            // Session-level reject
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=3|58=Invalid tag|10=123|",
                    messageType = "3",
                    quickfixMessage = createQuickFixMessage("3"),
                ),
            )

            // Business reject
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=j|380=2|10=124|",
                    messageType = "j",
                    quickfixMessage = createQuickFixMessage("j"),
                ),
            )

            // Order cancel reject
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=9|11=ORDER123|10=125|",
                    messageType = "9",
                    quickfixMessage = createQuickFixMessage("9"),
                ),
            )

            waitForMessages(3)

            val messages = session.messages.value
            assertEquals(3, messages.size)

            // All should be identified as rejections
            assertTrue((messages[0] as FixMessage).isRejectionOrLogout())
            assertTrue((messages[1] as FixMessage).isRejectionOrLogout())
            assertTrue((messages[2] as FixMessage).isRejectionOrLogout())
        }

    // ========================================
    // Separator Display in Grid Mode
    // ========================================

    @Test
    fun testSeparatorInGridMode() =
        runBlocking {
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=D|10=123|",
                    messageType = "D",
                    quickfixMessage = createQuickFixMessage("D"),
                ),
            )

            session.addSeparator()

            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = "8=FIX.4.2|9=100|35=8|10=124|",
                    messageType = "8",
                    quickfixMessage = createQuickFixMessage("8"),
                ),
            )

            waitForMessages(3)

            val messages = session.messages.value
            assertEquals(3, messages.size)
            assertTrue(messages[1] is Separator)
            assertEquals("", messages[1].toDisplayString())
        }

    // ========================================
    // Complex Message Scenarios
    // ========================================

    @Test
    fun testOrderLifecycleInGrid() =
        runBlocking {
            val orderId = "ORD123"

            // New Order Single
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.of(2024, 3, 15, 10, 0, 0),
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = "8=FIX.4.2|9=100|35=D|11=$orderId|55=EUR/USD|54=1|38=1000000|10=123|",
                    messageType = "D",
                    quickfixMessage = createQuickFixMessage("D"),
                ),
            )

            // Execution Report - New
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.of(2024, 3, 15, 10, 0, 1),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=8|11=$orderId|39=0|150=0|10=124|",
                    messageType = "8",
                    quickfixMessage = createQuickFixMessage("8"),
                ),
            )

            // Execution Report - Partial Fill
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.of(2024, 3, 15, 10, 0, 2),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=8|11=$orderId|39=1|150=F|31=500000|10=125|",
                    messageType = "8",
                    quickfixMessage = createQuickFixMessage("8"),
                ),
            )

            // Execution Report - Filled
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.of(2024, 3, 15, 10, 0, 3),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = "8=FIX.4.2|9=100|35=8|11=$orderId|39=2|150=F|32=1000000|10=126|",
                    messageType = "8",
                    quickfixMessage = createQuickFixMessage("8"),
                ),
            )

            waitForMessages(4)

            val messages = session.messages.value
            assertEquals(4, messages.size)

            // Verify message types and order
            assertEquals("D", (messages[0] as FixMessage).messageType)
            assertEquals("8", (messages[1] as FixMessage).messageType)
            assertEquals("8", (messages[2] as FixMessage).messageType)
            assertEquals("8", (messages[3] as FixMessage).messageType)

            // All execution reports should contain the order ID
            for (i in 1..3) {
                assertTrue((messages[i] as FixMessage).rawMessage.contains(orderId))
            }
        }
}
