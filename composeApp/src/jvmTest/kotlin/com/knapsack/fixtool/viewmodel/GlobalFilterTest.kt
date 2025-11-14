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

    @Before
    fun setup() {
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
        """.trimIndent())

        viewModel = FixMessageViewModel()

        // Load dictionary
        val dictionary = FixDictionaryAdapter.fromFile(tempDictFile)
        viewModel.saveAppSettings(viewModel.appSettings.copy(defaultDataDictionary = tempDictFile.absolutePath))
    }

    @After
    fun cleanup() {
        // Clean up temp file
        if (tempDictFile.exists()) {
            tempDictFile.delete()
        }
    }

    // ========================================
    // Global Filter Regex Tests
    // ========================================

    @Test
    fun testSetGlobalFilterRegexAppliesToAllSessions() = runBlocking {
        // Create multiple sessions
        val session1 = viewModel.createSessionForTest("Session1")
        val session2 = viewModel.createSessionForTest("Session2")

        // Set global filter regex
        viewModel.setGlobalFilterRegex("ORDER123")

        delay(100)

        // Verify filter is applied to all sessions
        assertEquals("ORDER123", session1.filterRegex.value, "Filter should be applied to Session1")
        assertEquals("ORDER123", session2.filterRegex.value, "Filter should be applied to Session2")
    }

    @Test
    fun testGlobalFilterRegexClearsFromAllSessions() = runBlocking {
        val session1 = viewModel.createSessionForTest("Session1")
        val session2 = viewModel.createSessionForTest("Session2")

        // Set filter first
        viewModel.setGlobalFilterRegex("TESTFILTER")
        delay(100)

        // Clear filter
        viewModel.setGlobalFilterRegex("")
        delay(100)

        // Verify filter is cleared from all sessions
        assertEquals("", session1.filterRegex.value, "Filter should be cleared from Session1")
        assertEquals("", session2.filterRegex.value, "Filter should be cleared from Session2")
    }

    @Test
    fun testGlobalFilterRegexAppliedToNewSessions() = runBlocking {
        // Set global filter before creating sessions
        viewModel.setGlobalFilterRegex("PREFILTER")
        delay(100)

        // Create new session
        val newSession = viewModel.createSessionForTest("NewSession")
        delay(100)

        // Verify existing sessions have the filter
        // Note: New sessions don't automatically inherit the global filter,
        // they only get it when setGlobalFilterRegex is called again
        viewModel.setGlobalFilterRegex("NEWFILTER")
        delay(100)

        assertEquals("NEWFILTER", newSession.filterRegex.value, "New session should receive global filter update")
    }

    // ========================================
    // Global Filter Direction Tests
    // ========================================

    @Test
    fun testSetGlobalFilterShowIncomingAppliesToAllSessions() = runBlocking {
        val session1 = viewModel.createSessionForTest("Session1")
        val session2 = viewModel.createSessionForTest("Session2")

        // By default, incoming should be shown (true)
        assertTrue(session1.filterShowIncoming.value)
        assertTrue(session2.filterShowIncoming.value)

        // Hide incoming messages globally
        viewModel.setGlobalFilterShowIncoming(false)
        delay(100)

        assertFalse(session1.filterShowIncoming.value, "Incoming filter should be false in Session1")
        assertFalse(session2.filterShowIncoming.value, "Incoming filter should be false in Session2")

        // Show incoming messages again
        viewModel.setGlobalFilterShowIncoming(true)
        delay(100)

        assertTrue(session1.filterShowIncoming.value, "Incoming filter should be true in Session1")
        assertTrue(session2.filterShowIncoming.value, "Incoming filter should be true in Session2")
    }

    @Test
    fun testSetGlobalFilterShowOutgoingAppliesToAllSessions() = runBlocking {
        val session1 = viewModel.createSessionForTest("Session1")
        val session2 = viewModel.createSessionForTest("Session2")

        // By default, outgoing should be shown (true)
        assertTrue(session1.filterShowOutgoing.value)
        assertTrue(session2.filterShowOutgoing.value)

        // Hide outgoing messages globally
        viewModel.setGlobalFilterShowOutgoing(false)
        delay(100)

        assertFalse(session1.filterShowOutgoing.value, "Outgoing filter should be false in Session1")
        assertFalse(session2.filterShowOutgoing.value, "Outgoing filter should be false in Session2")

        // Show outgoing messages again
        viewModel.setGlobalFilterShowOutgoing(true)
        delay(100)

        assertTrue(session1.filterShowOutgoing.value, "Outgoing filter should be true in Session1")
        assertTrue(session2.filterShowOutgoing.value, "Outgoing filter should be true in Session2")
    }

    @Test
    fun testGlobalFilterDirectionIndependent() = runBlocking {
        val session = viewModel.createSessionForTest("TestSession")

        // Set incoming to false
        viewModel.setGlobalFilterShowIncoming(false)
        delay(100)

        // Outgoing should still be true
        assertFalse(session.filterShowIncoming.value)
        assertTrue(session.filterShowOutgoing.value)

        // Set outgoing to false
        viewModel.setGlobalFilterShowOutgoing(false)
        delay(100)

        // Both should be false
        assertFalse(session.filterShowIncoming.value)
        assertFalse(session.filterShowOutgoing.value)
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
    fun testGlobalFilterRegexAndDirectionCombined() = runBlocking {
        val session1 = viewModel.createSessionForTest("Session1")
        val session2 = viewModel.createSessionForTest("Session2")

        // Set both regex and direction filters
        viewModel.setGlobalFilterRegex("ORDER.*")
        viewModel.setGlobalFilterShowIncoming(false)
        viewModel.setGlobalFilterShowOutgoing(true)
        delay(100)

        // Verify both sessions have both filters applied
        assertEquals("ORDER.*", session1.filterRegex.value)
        assertEquals("ORDER.*", session2.filterRegex.value)
        assertFalse(session1.filterShowIncoming.value)
        assertFalse(session2.filterShowIncoming.value)
        assertTrue(session1.filterShowOutgoing.value)
        assertTrue(session2.filterShowOutgoing.value)
    }

    @Test
    fun testGlobalFilterWithMultipleSessionsIndependentModification() = runBlocking {
        val session1 = viewModel.createSessionForTest("Session1")
        val session2 = viewModel.createSessionForTest("Session2")

        // Set global filter
        viewModel.setGlobalFilterRegex("GLOBAL")
        delay(100)

        // Manually override one session
        session1.setFilterRegex("LOCAL")
        delay(100)

        // Verify session1 has local filter, session2 has global
        assertEquals("LOCAL", session1.filterRegex.value)
        assertEquals("GLOBAL", session2.filterRegex.value)

        // Apply global filter again - should override both
        viewModel.setGlobalFilterRegex("NEWGLOBAL")
        delay(100)

        assertEquals("NEWGLOBAL", session1.filterRegex.value)
        assertEquals("NEWGLOBAL", session2.filterRegex.value)
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
        direction: FixMessage.Direction = FixMessage.Direction.INCOMING
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
            direction = direction,
            rawMessage = rawMessage,
            messageType = msgType,
            quickfixMessage = message
        )

        // Add to session
        session.addMessage(fixMessage)
    }
}
