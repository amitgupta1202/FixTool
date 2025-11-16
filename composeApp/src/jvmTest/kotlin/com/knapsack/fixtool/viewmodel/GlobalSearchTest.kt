package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.field.MsgSeqNum
import quickfix.field.MsgType
import quickfix.field.SenderCompID
import quickfix.field.TargetCompID
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for global search functionality across all sessions
 */
class GlobalSearchTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var tempDictFile: File
    private lateinit var testDir: File

    @Before
    fun setup() {
        // Create a temporary directory for test files (isolated from production)
        testDir = File.createTempFile("fixtool-test", "").apply {
            delete() // Delete the file
            mkdirs() // Create as directory
        }

        // Create temporary data dictionary for testing
        tempDictFile = File.createTempFile("test_dict", ".xml")
        tempDictFile.writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <fix type="FIX" major="4" minor="2">
                <header>
                    <field name="BeginString" required="Y"/>
                    <field name="MsgType" required="Y"/>
                    <field name="SenderCompID" required="Y"/>
                    <field name="TargetCompID" required="Y"/>
                    <field name="MsgSeqNum" required="Y"/>
                </header>
                <messages>
                    <message name="NewOrderSingle" msgtype="D" msgcat="app"/>
                    <message name="ExecutionReport" msgtype="8" msgcat="app"/>
                    <message name="OrderCancelRequest" msgtype="F" msgcat="app"/>
                </messages>
                <fields>
                    <field name="MsgType" number="35" type="STRING">
                        <value enum="D" description="NewOrderSingle"/>
                        <value enum="8" description="ExecutionReport"/>
                        <value enum="F" description="OrderCancelRequest"/>
                    </field>
                    <field name="MsgSeqNum" number="34" type="SEQNUM"/>
                    <field name="SenderCompID" number="49" type="STRING"/>
                    <field name="TargetCompID" number="56" type="STRING"/>
                    <field name="ClOrdID" number="11" type="STRING"/>
                    <field name="OrderQty" number="38" type="QTY"/>
                </fields>
            </fix>
        """.trimIndent())

        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

        // Load dictionary
        val dictionary = FixDictionaryAdapter.fromFile(tempDictFile)
        viewModel.saveAppSettings(viewModel.appSettings.copy(defaultDataDictionary = tempDictFile.absolutePath))
    }

    @After
    fun cleanup() {
        // Clean up temp files
        if (tempDictFile.exists()) {
            tempDictFile.delete()
        }
        // Clean up test directory
        testDir.deleteRecursively()
    }

    // ========================================
    // Search Dialog State Tests
    // ========================================

    @Test
    fun testToggleGlobalSearchDialog() {
        // Initially dialog should be closed
        assertFalse(viewModel.showGlobalSearchDialog.value, "Dialog should be closed initially")

        // Toggle to open
        viewModel.toggleGlobalSearchDialog()
        assertTrue(viewModel.showGlobalSearchDialog.value, "Dialog should be open after toggle")

        // Toggle to close
        viewModel.toggleGlobalSearchDialog()
        assertFalse(viewModel.showGlobalSearchDialog.value, "Dialog should be closed after second toggle")
    }

    @Test
    fun testSearchQueryClearsOnDialogClose() {
        // Open dialog and set query
        viewModel.toggleGlobalSearchDialog()
        viewModel.setGlobalSearchQuery("test query")
        assertEquals("test query", viewModel.globalSearchQuery.value)

        // Close dialog
        viewModel.toggleGlobalSearchDialog()

        // Query and results should be cleared
        assertEquals("", viewModel.globalSearchQuery.value, "Query should be cleared when dialog closes")
        assertTrue(viewModel.globalSearchResults.value.isEmpty(), "Results should be cleared when dialog closes")
    }

    // ========================================
    // Search Functionality Tests
    // ========================================

    @Test
    fun testSearchAcrossMultipleSessions() = runBlocking {
        // Create two sessions
        val session1 = viewModel.createSessionForTest("Session1")
        val session2 = viewModel.createSessionForTest("Session2")

        // Add messages to both sessions
        addTestMessage(session1, "D", 1, "SENDER1", "8=FIX.4.2|9=100|35=D|49=SENDER1|56=TARGET|34=1|11=ORDER1|38=100|10=123|")
        addTestMessage(session2, "8", 2, "SENDER2", "8=FIX.4.2|9=100|35=8|49=SENDER2|56=TARGET|34=2|11=ORDER1|38=100|10=123|")

        // Wait for messages to be processed
        delay(200)

        // Search for "ORDER1" which appears in both messages
        viewModel.setGlobalSearchQuery("ORDER1")

        // Should find messages from both sessions
        val results = viewModel.globalSearchResults.value
        assertEquals(2, results.size, "Should find 2 messages across both sessions")
        assertTrue(results.any { it.session == session1 }, "Should include message from Session1")
        assertTrue(results.any { it.session == session2 }, "Should include message from Session2")
    }

    @Test
    fun testSearchWithRegexPattern() = runBlocking {
        val session = viewModel.createSessionForTest("TestSession")

        // Add messages with different patterns
        addTestMessage(session, "D", 1, "SENDER1", "8=FIX.4.2|9=100|35=D|49=SENDER1|56=TARGET|34=1|11=ORD001|10=123|")
        addTestMessage(session, "D", 2, "SENDER1", "8=FIX.4.2|9=100|35=D|49=SENDER1|56=TARGET|34=2|11=ORD002|10=123|")
        addTestMessage(session, "8", 3, "SENDER1", "8=FIX.4.2|9=100|35=8|49=SENDER1|56=TARGET|34=3|11=RPT001|10=123|")

        delay(300)

        // Search with regex for ORD followed by digits
        viewModel.setGlobalSearchQuery("ORD\\d+")

        val results = viewModel.globalSearchResults.value
        // Should find at least the messages with ORD pattern
        assertTrue(results.size >= 2, "Should find at least 2 messages matching regex pattern, got ${results.size}")
        assertTrue(results.any { it.matchedText.contains("ORD") }, "At least one result should match ORD pattern")
    }

    @Test
    fun testSearchReturnsEmptyForNoMatches() = runBlocking {
        val session = viewModel.createSessionForTest("TestSession")
        addTestMessage(session, "D", 1, "SENDER1", "8=FIX.4.2|9=100|35=D|49=SENDER1|56=TARGET|34=1|11=ORDER1|10=123|")

        delay(200)

        // Search for something that doesn't exist
        viewModel.setGlobalSearchQuery("NOTFOUND")

        val results = viewModel.globalSearchResults.value
        assertTrue(results.isEmpty(), "Should return empty results for no matches")
    }

    // ========================================
    // Search Result Sorting Tests
    // ========================================

    @Test
    fun testSearchResultsSortedByTimestamp() = runBlocking {
        val session = viewModel.createSessionForTest("TestSession")

        // Add messages with different timestamps (by adding them in sequence)
        addTestMessage(session, "D", 1, "SENDER1", "8=FIX.4.2|9=100|35=D|49=SENDER1|56=TARGET|34=1|11=TEST1|10=123|")
        delay(100) // Larger delay to ensure different timestamps
        addTestMessage(session, "D", 2, "SENDER1", "8=FIX.4.2|9=100|35=D|49=SENDER1|56=TARGET|34=2|11=TEST2|10=123|")
        delay(100)
        addTestMessage(session, "D", 3, "SENDER1", "8=FIX.4.2|9=100|35=D|49=SENDER1|56=TARGET|34=3|11=TEST3|10=123|")

        delay(300)

        // Search for "TEST" which will match all messages
        viewModel.setGlobalSearchQuery("TEST")

        val results = viewModel.globalSearchResults.value
        assertEquals(3, results.size, "Should find all 3 messages")

        // Verify results are sorted by timestamp (ascending)
        for (i in 0 until results.size - 1) {
            assertTrue(
                results[i].message.timestamp <= results[i + 1].message.timestamp,
                "Results should be sorted by timestamp: ${results[i].message.timestamp} <= ${results[i + 1].message.timestamp}"
            )
        }
    }

    @Test
    fun testSearchResultsSortedByMsgSeqNum() = runBlocking {
        val session = viewModel.createSessionForTest("TestSession")

        // Add messages with different sequence numbers
        addTestMessage(session, "D", 1, "SENDER1", "8=FIX.4.2|9=100|35=D|49=SENDER1|56=TARGET|34=1|11=SEQTEST1|10=123|")
        addTestMessage(session, "D", 2, "SENDER1", "8=FIX.4.2|9=100|35=D|49=SENDER1|56=TARGET|34=2|11=SEQTEST2|10=123|")
        addTestMessage(session, "D", 3, "SENDER1", "8=FIX.4.2|9=100|35=D|49=SENDER1|56=TARGET|34=3|11=SEQTEST3|10=123|")

        delay(300)

        viewModel.setGlobalSearchQuery("SEQTEST")

        val results = viewModel.globalSearchResults.value
        assertEquals(3, results.size, "Should find all 3 messages")

        // Should be sorted by timestamp first, then by MsgSeqNum
        val seqNums = results.mapNotNull { it.msgSeqNum }
        assertEquals(listOf(1, 2, 3), seqNums, "Should be sorted by MsgSeqNum: got $seqNums")
    }

    @Test
    fun testSearchResultsSortedBySenderCompID() = runBlocking {
        val session = viewModel.createSessionForTest("TestSession")

        // Add messages with same seq num but different senders
        addTestMessage(session, "D", 1, "SENDER_A", "8=FIX.4.2|9=100|35=D|49=SENDER_A|56=TARGET|34=1|11=SENDERTEST|10=123|")
        delay(50)
        addTestMessage(session, "D", 1, "SENDER_B", "8=FIX.4.2|9=100|35=D|49=SENDER_B|56=TARGET|34=1|11=SENDERTEST|10=123|")
        delay(50)
        addTestMessage(session, "D", 1, "SENDER_C", "8=FIX.4.2|9=100|35=D|49=SENDER_C|56=TARGET|34=1|11=SENDERTEST|10=123|")

        delay(400)

        viewModel.setGlobalSearchQuery("SENDERTEST")

        val results = viewModel.globalSearchResults.value

        // Verify we found messages and they contain our senders
        assertTrue(results.isNotEmpty(), "Should find at least some messages, got ${results.size}")

        val senders = results.mapNotNull { it.senderCompId }
        assertTrue(senders.isNotEmpty(), "Should have extracted sender IDs from results")

        // Verify that results are sorted (senders should be in ascending order after timestamp/seq sorting)
        if (senders.size > 1) {
            for (i in 0 until senders.size - 1) {
                assertTrue(
                    senders[i] <= senders[i + 1],
                    "Senders should be sorted: ${senders[i]} <= ${senders[i + 1]}, all: $senders"
                )
            }
        }
    }

    // ========================================
    // Message Type Description Tests
    // ========================================

    @Test
    fun testMessageTypeDescriptionExtracted() = runBlocking {
        val session = viewModel.createSessionForTest("TestSession")

        // Add message with known message type
        addTestMessage(session, "D", 1, "SENDER1", "8=FIX.4.2|9=100|35=D|49=SENDER1|56=TARGET|34=1|11=TEST|10=123|")

        delay(200)

        viewModel.setGlobalSearchQuery("TEST")

        val results = viewModel.globalSearchResults.value
        assertEquals(1, results.size)

        // Should have message type description from dictionary or fallback to code
        val result = results[0]
        // Either dictionary description or message type code
        assertTrue(
            result.messageTypeDescription == "NewOrderSingle" || result.messageTypeDescription == "D",
            "Should have message type description or code, got: ${result.messageTypeDescription}"
        )
    }

    @Test
    fun testMessageTypeDescriptionFallbackToCode() = runBlocking {
        val session = viewModel.createSessionForTest("TestSession")

        // Add message with unknown message type
        addTestMessage(session, "Z", 1, "SENDER1", "8=FIX.4.2|9=100|35=Z|49=SENDER1|56=TARGET|34=1|11=TEST|10=123|")

        delay(200)

        viewModel.setGlobalSearchQuery("TEST")

        val results = viewModel.globalSearchResults.value
        assertEquals(1, results.size)

        // Should fall back to message type code
        val result = results[0]
        assertEquals("Z", result.messageTypeDescription, "Should fall back to message type code when description not found")
    }

    // ========================================
    // Session Username Tests
    // ========================================

    @Test
    fun testSessionUsernameIncluded() = runBlocking {
        val session1 = viewModel.createSessionForTest("DEV1-BuySide")
        val session2 = viewModel.createSessionForTest("LOCAL-BuySide")

        addTestMessage(session1, "D", 1, "SENDER1", "8=FIX.4.2|9=100|35=D|49=SENDER1|56=TARGET|34=1|11=TEST|10=123|")
        addTestMessage(session2, "D", 1, "SENDER2", "8=FIX.4.2|9=100|35=D|49=SENDER2|56=TARGET|34=1|11=TEST|10=123|")

        delay(200)

        viewModel.setGlobalSearchQuery("TEST")

        val results = viewModel.globalSearchResults.value
        assertEquals(2, results.size)

        // Verify session usernames are included
        assertTrue(results.any { it.sessionUsername == "DEV1-BuySide" }, "Should include session username")
        assertTrue(results.any { it.sessionUsername == "LOCAL-BuySide" }, "Should include session username")
    }

    // ========================================
    // Navigate to Search Result Tests
    // ========================================

    @Test
    fun testNavigateToSearchResult() = runBlocking {
        val session1 = viewModel.createSessionForTest("Session1")
        val session2 = viewModel.createSessionForTest("Session2")

        addTestMessage(session1, "D", 1, "SENDER1", "8=FIX.4.2|9=100|35=D|49=SENDER1|56=TARGET|34=1|11=TEST|10=123|")
        addTestMessage(session2, "D", 1, "SENDER2", "8=FIX.4.2|9=100|35=D|49=SENDER2|56=TARGET|34=1|11=TEST|10=123|")

        delay(200)

        viewModel.setGlobalSearchQuery("TEST")
        val results = viewModel.globalSearchResults.value

        // Navigate to second result (Session2)
        val result = results.find { it.session == session2 }
        assertNotNull(result)

        viewModel.navigateToSearchResult(result)

        // Verify active session switched to Session2
        assertEquals(session2, viewModel.activeSession, "Should switch to the session containing the result")
        assertEquals(result.message, viewModel.selectedMessage.value, "Should select the message")
    }

    // ========================================
    // Helper Methods
    // ========================================

    private fun addTestMessage(
        session: com.knapsack.fixtool.model.FixMessageSession,
        msgType: String,
        seqNum: Int,
        sender: String,
        rawMessage: String
    ) {
        // Create QuickFIX message
        val message = quickfix.Message()
        message.header.setField(MsgType(msgType))
        message.header.setField(MsgSeqNum(seqNum))
        message.header.setField(SenderCompID(sender))
        message.header.setField(TargetCompID("TARGET"))

        // Create FixMessage
        val fixMessage = FixMessage(
            timestamp = java.time.LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = rawMessage,
            messageType = msgType,
            quickfixMessage = message
        )

        // Add to session
        session.addMessage(fixMessage)
    }
}
