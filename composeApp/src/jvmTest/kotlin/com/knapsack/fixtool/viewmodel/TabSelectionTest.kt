package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.Message
import quickfix.field.ClOrdID
import quickfix.field.MsgType
import quickfix.field.SenderCompID
import quickfix.field.TargetCompID
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * Tests for tab/session selection functionality
 */
class TabSelectionTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private lateinit var testDictionaryFile: File

    @Before
    fun setup() {
        // Create a temporary directory for test files (isolated from production)
        testDir =
            File.createTempFile("fixtool-test", "").apply {
                delete() // Delete the file
                mkdirs() // Create as directory
            }

        // Create a minimal test dictionary
        testDictionaryFile = File.createTempFile("test_dict", ".xml")
        testDictionaryFile.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<fix major="4" minor="4">
    <header>
        <field name="BeginString" required="Y"/>
        <field name="BodyLength" required="Y"/>
        <field name="MsgType" required="Y"/>
        <field name="SenderCompID" required="Y"/>
        <field name="TargetCompID" required="Y"/>
        <field name="MsgSeqNum" required="Y"/>
        <field name="SendingTime" required="Y"/>
    </header>
    <trailer>
        <field name="CheckSum" required="Y"/>
    </trailer>
    <messages>
        <message name="NewOrderSingle" msgtype="D" msgcat="app">
            <field name="ClOrdID" required="Y"/>
        </message>
    </messages>
    <fields>
        <field name="BeginString" number="8" type="STRING"/>
        <field name="BodyLength" number="9" type="LENGTH"/>
        <field name="MsgType" number="35" type="STRING">
            <value enum="D" description="NewOrderSingle"/>
        </field>
        <field name="MsgSeqNum" number="34" type="SEQNUM"/>
        <field name="SenderCompID" number="49" type="STRING"/>
        <field name="TargetCompID" number="56" type="STRING"/>
        <field name="SendingTime" number="52" type="UTCTIMESTAMP"/>
        <field name="CheckSum" number="10" type="STRING"/>
        <field name="ClOrdID" number="11" type="STRING"/>
    </fields>
</fix>""",
        )

        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun cleanup() {
        if (testDictionaryFile.exists()) {
            testDictionaryFile.delete()
            // Clean up test directory
            testDir.deleteRecursively()
        }
    }

    private fun createTestMessage(clOrdId: String): FixMessage {
        val msg = Message()
        msg.header.setField(MsgType("D"))
        msg.header.setField(SenderCompID("SENDER1"))
        msg.header.setField(TargetCompID("TARGET1"))
        msg.setField(ClOrdID(clOrdId))

        return FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = msg.toString(),
            messageType = "D",
            quickfixMessage = msg,
        )
    }

    @Test
    fun testFirstSessionIsNotAutoSelectedOnConnect() {
        // Given: No sessions exist
        assertEquals(-1, viewModel.activeSessionIndex, "Initially no session should be selected")

        // When: First profile is connected (which creates a session)
        val profile = viewModel.connectionProfiles.firstOrNull()
        if (profile != null) {
            viewModel.connectProfile(profile.id, profile)

            // Then: Session should NOT be auto-selected (user or template loading will do it)
            assertEquals(-1, viewModel.activeSessionIndex, "Session should not be auto-selected on connect")
        } else {
            // If no profiles exist, just verify initial state
            assertEquals(-1, viewModel.activeSessionIndex)
        }
    }

    @Test
    fun testSelectingNullMessageDoesNotChangeTab() {
        // Given: ViewModel is initialized
        val initialIndex = viewModel.activeSessionIndex

        // When: Select null message
        viewModel.selectMessage(null)

        // Then: Active session should remain unchanged
        assertEquals(initialIndex, viewModel.activeSessionIndex, "Tab should not change when selecting null message")
    }

    @Test
    fun testSelectMessageWithNoSessions() {
        // Given: No sessions exist
        assertEquals(0, viewModel.sessions.size, "Should start with no sessions")

        val message = createTestMessage("ORD001")

        // When: Try to select a message
        viewModel.selectMessage(message)

        // Then: Should not crash and activeIndex should remain -1
        assertEquals(-1, viewModel.activeSessionIndex, "Should remain at -1 with no sessions")
    }
}
