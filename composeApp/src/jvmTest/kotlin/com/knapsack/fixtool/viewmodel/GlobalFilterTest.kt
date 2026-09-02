package com.knapsack.fixtool.viewmodel

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
import kotlin.test.assertTrue

/**
 * Integration tests for global filter functionality across all sessions
 */
class GlobalFilterTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var tempDictFile: File
    private lateinit var testDir: File

    @Before
    fun setup() {
        // Create a temporary directory for test files (isolated from production)
        testDir =
            File.createTempFile("fixtool-test", "").apply {
                delete() // Delete the file
                mkdirs() // Create as directory
            }

        // Create temporary data dictionary for testing
        tempDictFile = File.createTempFile("test_dict", ".xml")
        tempDictFile.writeText(
            """
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
                </messages>
                <fields>
                    <field name="MsgType" number="35" type="STRING">
                        <value enum="D" description="NewOrderSingle"/>
                        <value enum="8" description="ExecutionReport"/>
                    </field>
                    <field name="MsgSeqNum" number="34" type="SEQNUM"/>
                    <field name="SenderCompID" number="49" type="STRING"/>
                    <field name="TargetCompID" number="56" type="STRING"/>
                    <field name="ClOrdID" number="11" type="STRING"/>
                </fields>
            </fix>
            """.trimIndent(),
        )

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
    // Global Filter Regex Tests
    //
    // The global filter is ViewModel state ANDed into what a pane shows; it is NOT written into each
    // session. These tests used to assert the opposite — that typing in the toolbar set every pane's
    // own `filterRegex` — which is exactly the defect: it destroyed a filter its owner had set, and
    // clearing the toolbar then blanked every pane rather than restoring what each had.
    // ========================================

    @Test
    fun testSetGlobalFilterRegexLeavesEachPanesOwnFilterAlone() =
        runBlocking {
            val session1 = viewModel.createSessionForTest("Session1")
            val session2 = viewModel.createSessionForTest("Session2")
            session1.setFilterRegex("MINE")

            viewModel.setGlobalFilterRegex("ORDER123")
            delay(100)

            assertEquals("ORDER123", viewModel.globalFilterRegex.value, "The global filter is held here")
            assertEquals("MINE", session1.filterRegex.value, "Session1 keeps the filter its owner set")
            assertEquals("", session2.filterRegex.value, "Session2 never had one and does not gain one")
        }

    @Test
    fun testClearingGlobalFilterRestoresNothingBecauseItTookNothing() =
        runBlocking {
            val session1 = viewModel.createSessionForTest("Session1")
            session1.setFilterRegex("LOCAL")

            viewModel.setGlobalFilterRegex("TESTFILTER")
            delay(100)
            viewModel.setGlobalFilterRegex("")
            delay(100)

            assertEquals("", viewModel.globalFilterRegex.value)
            assertEquals("LOCAL", session1.filterRegex.value, "A pane's own filter survives both edits")
        }

    @Test
    fun testGlobalFilterAppliesToSessionsCreatedAfterIt() =
        runBlocking {
            viewModel.setGlobalFilterRegex("PREFILTER")
            delay(100)

            val newSession = viewModel.createSessionForTest("NewSession")
            delay(100)

            // Nothing is copied into the session — the filter applies because every pane renders
            // through MessageFilters, which reads the one global value. So a session created later is
            // filtered by it without ever having been told about it.
            assertEquals("PREFILTER", viewModel.globalFilterRegex.value)
            assertEquals("", newSession.filterRegex.value, "No write-through, so nothing to inherit")
        }

    // ========================================
    // Global Filter Direction Tests
    // ========================================

    @Test
    fun testSetGlobalFilterShowIncomingLeavesPaneDirectionAlone() =
        runBlocking {
            val session1 = viewModel.createSessionForTest("Session1")
            val session2 = viewModel.createSessionForTest("Session2")

            assertTrue(session1.filterShowIncoming.value)
            assertTrue(session2.filterShowIncoming.value)

            viewModel.setGlobalFilterShowIncoming(false)
            delay(100)

            assertFalse(viewModel.globalFilterShowIncoming.value)
            assertTrue(session1.filterShowIncoming.value, "The pane's own direction box is untouched")
            assertTrue(session2.filterShowIncoming.value, "The pane's own direction box is untouched")
        }

    @Test
    fun testSetGlobalFilterShowOutgoingLeavesPaneDirectionAlone() =
        runBlocking {
            val session = viewModel.createSessionForTest("Session1")
            session.setFilterShowOutgoing(false)

            viewModel.setGlobalFilterShowOutgoing(false)
            delay(100)
            viewModel.setGlobalFilterShowOutgoing(true)
            delay(100)

            assertTrue(viewModel.globalFilterShowOutgoing.value)
            assertFalse(session.filterShowOutgoing.value, "Turning the global one back on does not re-enable a pane's")
        }

    @Test
    fun testGlobalFilterDirectionIndependent() =
        runBlocking {
            viewModel.createSessionForTest("TestSession")

            viewModel.setGlobalFilterShowIncoming(false)
            delay(100)

            assertFalse(viewModel.globalFilterShowIncoming.value)
            assertTrue(viewModel.globalFilterShowOutgoing.value)

            viewModel.setGlobalFilterShowOutgoing(false)
            delay(100)

            assertFalse(viewModel.globalFilterShowIncoming.value)
            assertFalse(viewModel.globalFilterShowOutgoing.value)
        }

    // ========================================
    // Global Filter State Tests
    // ========================================

    @Test
    fun testGlobalFilterRegexStateTracking() {
        // Initially empty
        assertEquals("", viewModel.globalFilterRegex.value)

        // Set filter
        viewModel.setGlobalFilterRegex("TEST123")
        assertEquals("TEST123", viewModel.globalFilterRegex.value)

        // Clear filter
        viewModel.setGlobalFilterRegex("")
        assertEquals("", viewModel.globalFilterRegex.value)
    }

    @Test
    fun testGlobalFilterIncomingStateTracking() {
        // Initially true
        assertTrue(viewModel.globalFilterShowIncoming.value)

        // Toggle to false
        viewModel.setGlobalFilterShowIncoming(false)
        assertFalse(viewModel.globalFilterShowIncoming.value)

        // Toggle back to true
        viewModel.setGlobalFilterShowIncoming(true)
        assertTrue(viewModel.globalFilterShowIncoming.value)
    }

    @Test
    fun testGlobalFilterOutgoingStateTracking() {
        // Initially true
        assertTrue(viewModel.globalFilterShowOutgoing.value)

        // Toggle to false
        viewModel.setGlobalFilterShowOutgoing(false)
        assertFalse(viewModel.globalFilterShowOutgoing.value)

        // Toggle back to true
        viewModel.setGlobalFilterShowOutgoing(true)
        assertTrue(viewModel.globalFilterShowOutgoing.value)
    }

    // ========================================
    // Combined Filter Tests
    // ========================================

    @Test
    fun testGlobalFilterRegexAndDirectionAreHeldTogetherAndOwnedHere() =
        runBlocking {
            val session1 = viewModel.createSessionForTest("Session1")
            val session2 = viewModel.createSessionForTest("Session2")
            session2.setFilterRegex("SESSION2-ONLY")

            viewModel.setGlobalFilterRegex("ORDER.*")
            viewModel.setGlobalFilterShowIncoming(false)
            viewModel.setGlobalFilterShowOutgoing(true)
            delay(100)

            assertEquals("ORDER.*", viewModel.globalFilterRegex.value)
            assertFalse(viewModel.globalFilterShowIncoming.value)
            assertTrue(viewModel.globalFilterShowOutgoing.value)
            assertEquals("", session1.filterRegex.value)
            assertEquals("SESSION2-ONLY", session2.filterRegex.value)
            assertTrue(session1.filterShowIncoming.value)
            assertTrue(session2.filterShowIncoming.value)
        }

    // ========================================
    // Helper Methods
    // ========================================

    private fun addTestMessage(
        session: com.knapsack.fixtool.model.FixMessageSession,
        msgType: String,
        seqNum: Int,
        sender: String,
        rawMessage: String,
        direction: FixMessage.Direction = FixMessage.Direction.INCOMING,
    ) {
        // Create QuickFIX message
        val message = quickfix.Message()
        message.header.setField(MsgType(msgType))
        message.header.setField(MsgSeqNum(seqNum))
        message.header.setField(SenderCompID(sender))
        message.header.setField(TargetCompID("TARGET"))

        // Create FixMessage
        val fixMessage =
            FixMessage(
                timestamp = java.time.LocalDateTime.now(),
                direction = direction,
                rawMessage = rawMessage,
                messageType = msgType,
                quickfixMessage = message,
            )

        // Add to session
        session.addMessage(fixMessage)
    }
}
