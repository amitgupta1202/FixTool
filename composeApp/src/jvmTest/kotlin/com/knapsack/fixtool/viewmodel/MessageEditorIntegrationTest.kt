package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.ui.FixField
import com.knapsack.fixtool.ui.FixField.Companion.toRawMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the message editor that test the complete workflow
 * from field operations to raw FIX message generation
 */
class MessageEditorIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var originalFile: File

    @Before
    fun setup() {
        // Backup and clear saved messages file to ensure test isolation
        originalFile = File(System.getProperty("user.home"), ".fixtool/saved_messages.json")
        val backupFile = File(System.getProperty("user.home"), ".fixtool/saved_messages.json.backup")

        if (originalFile.exists()) {
            originalFile.copyTo(backupFile, overwrite = true)
            originalFile.delete()
        }

        viewModel = FixMessageViewModel()
    }

    @After
    fun cleanup() {
        // Clean up test data and restore original file
        if (originalFile.exists()) {
            originalFile.delete()
        }

        // Restore backup if it exists
        val backupFile = File(System.getProperty("user.home"), ".fixtool/saved_messages.json.backup")
        if (backupFile.exists()) {
            backupFile.copyTo(originalFile, overwrite = true)
            backupFile.delete()
        }
    }

    // ========================================
    // Basic FIX Message Building Tests
    // ========================================

    @Test
    fun testBuildSimpleNewOrderSingle() {
        // Build a simple New Order Single (35=D) message
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "56", value = "TARGET"))
        viewModel.addEditorField()
        viewModel.updateEditorField(3, FixField(tag = "11", value = "ORDER123"))

        // Generate raw message
        val rawMessage = viewModel.editorFields.toRawMessage()

        // Verify the message format
        assertEquals("35=D|49=SENDER|56=TARGET|11=ORDER123|", rawMessage)
        assertTrue(rawMessage.contains("35=D"), "Should contain message type")
        assertTrue(rawMessage.contains("49=SENDER"), "Should contain sender")
        assertTrue(rawMessage.contains("56=TARGET"), "Should contain target")
        assertTrue(rawMessage.contains("11=ORDER123"), "Should contain order ID")
    }

    @Test
    fun testBuildQuoteRequest() {
        // Build a Quote Request (35=R) message
        viewModel.updateEditorField(0, FixField(tag = "35", value = "R"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "131", value = "QUOTE_REQ_1"))
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "55", value = "EUR/USD"))

        val rawMessage = viewModel.editorFields.toRawMessage()

        assertEquals("35=R|131=QUOTE_REQ_1|55=EUR/USD|", rawMessage)
    }

    // ========================================
    // Field Addition Tests
    // ========================================

    @Test
    fun testAddFieldsIncrementally() {
        // Start with one field
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        assertEquals("35=D|", viewModel.editorFields.toRawMessage())

        // Add second field
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))
        assertEquals("35=D|49=SENDER|", viewModel.editorFields.toRawMessage())

        // Add third field
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "56", value = "TARGET"))
        assertEquals("35=D|49=SENDER|56=TARGET|", viewModel.editorFields.toRawMessage())
    }

    @Test
    fun testAddFieldInMiddle() {
        // Create 3 fields
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "56", value = "TARGET"))

        assertEquals("35=D|56=TARGET|", viewModel.editorFields.toRawMessage())

        // Add a field at the end
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "49", value = "SENDER"))

        assertEquals("35=D|56=TARGET|49=SENDER|", viewModel.editorFields.toRawMessage())

        // Move it to position 1 (between 35 and 56)
        viewModel.moveEditorFieldUp(2)

        assertEquals("35=D|49=SENDER|56=TARGET|", viewModel.editorFields.toRawMessage())
    }

    // ========================================
    // Field Deletion Tests
    // ========================================

    @Test
    fun testDeleteFieldAndVerifyMessage() {
        // Create message with 4 fields
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "56", value = "TARGET"))
        viewModel.addEditorField()
        viewModel.updateEditorField(3, FixField(tag = "11", value = "ORDER123"))

        assertEquals("35=D|49=SENDER|56=TARGET|11=ORDER123|", viewModel.editorFields.toRawMessage())

        // Delete the sender field
        viewModel.deleteEditorField(1)

        assertEquals("35=D|56=TARGET|11=ORDER123|", viewModel.editorFields.toRawMessage())
    }

    @Test
    fun testMultiSelectDeleteAndVerifyMessage() {
        // Create message with 5 fields
        for (i in 0 until 5) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        assertEquals("35=V0|36=V1|37=V2|38=V3|39=V4|", viewModel.editorFields.toRawMessage())

        // Select fields 1, 2, 3 and delete them
        viewModel.selectEditorField(1, isCtrlPressed = false, isShiftPressed = false)
        viewModel.selectEditorField(3, isCtrlPressed = false, isShiftPressed = true)
        viewModel.deleteEditorField(1)

        // Should have only fields 0 and 4 remaining
        assertEquals("35=V0|39=V4|", viewModel.editorFields.toRawMessage())
    }

    // ========================================
    // Field Movement Tests
    // ========================================

    @Test
    fun testMoveFieldUpAndVerifyMessage() {
        // Create message
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "56", value = "TARGET"))

        assertEquals("35=D|49=SENDER|56=TARGET|", viewModel.editorFields.toRawMessage())

        // Move TARGET before SENDER
        viewModel.moveEditorFieldUp(2)

        assertEquals("35=D|56=TARGET|49=SENDER|", viewModel.editorFields.toRawMessage())
    }

    @Test
    fun testMoveFieldDownAndVerifyMessage() {
        // Create message
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "56", value = "TARGET"))

        assertEquals("35=D|49=SENDER|56=TARGET|", viewModel.editorFields.toRawMessage())

        // Move SENDER after TARGET
        viewModel.moveEditorFieldDown(1)

        assertEquals("35=D|56=TARGET|49=SENDER|", viewModel.editorFields.toRawMessage())
    }

    @Test
    fun testMultiSelectMoveUpAndVerifyMessage() {
        // Create message with 5 fields
        for (i in 0 until 5) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        assertEquals("35=V0|36=V1|37=V2|38=V3|39=V4|", viewModel.editorFields.toRawMessage())

        // Select fields 3 and 4, move them up
        viewModel.selectEditorField(3, isCtrlPressed = false, isShiftPressed = false)
        viewModel.selectEditorField(4, isCtrlPressed = false, isShiftPressed = true)
        viewModel.moveEditorFieldUp(3)

        // Fields 3 and 4 should now be at positions 2 and 3
        assertEquals("35=V0|36=V1|38=V3|39=V4|37=V2|", viewModel.editorFields.toRawMessage())
    }

    @Test
    fun testMultiSelectMoveDownAndVerifyMessage() {
        // Create message with 5 fields
        for (i in 0 until 5) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        assertEquals("35=V0|36=V1|37=V2|38=V3|39=V4|", viewModel.editorFields.toRawMessage())

        // Select fields 0 and 1, move them down
        viewModel.selectEditorField(0, isCtrlPressed = false, isShiftPressed = false)
        viewModel.selectEditorField(1, isCtrlPressed = false, isShiftPressed = true)
        viewModel.moveEditorFieldDown(0)

        // Fields 0 and 1 should now be at positions 1 and 2
        assertEquals("37=V2|35=V0|36=V1|38=V3|39=V4|", viewModel.editorFields.toRawMessage())
    }

    // ========================================
    // Field Edit Tests
    // ========================================

    @Test
    fun testEditFieldValueAndVerifyMessage() {
        // Create message
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))

        assertEquals("35=D|49=SENDER|", viewModel.editorFields.toRawMessage())

        // Update the sender value
        viewModel.updateEditorField(1, FixField(tag = "49", value = "NEW_SENDER"))

        assertEquals("35=D|49=NEW_SENDER|", viewModel.editorFields.toRawMessage())
    }

    @Test
    fun testEditFieldTagAndVerifyMessage() {
        // Create message
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))

        assertEquals("35=D|49=SENDER|", viewModel.editorFields.toRawMessage())

        // Change tag 49 to tag 50
        viewModel.updateEditorField(1, FixField(tag = "50", value = "SENDER"))

        assertEquals("35=D|50=SENDER|", viewModel.editorFields.toRawMessage())
    }

    // ========================================
    // Clear Fields Tests
    // ========================================

    @Test
    fun testClearFieldsAndVerifyEmptyMessage() {
        // Create message with multiple fields
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "56", value = "TARGET"))

        assertEquals("35=D|49=SENDER|56=TARGET|", viewModel.editorFields.toRawMessage())

        // Clear all fields
        viewModel.clearEditorFields()

        // Should have one empty field
        assertEquals(1, viewModel.editorFields.size)
        assertEquals("=|", viewModel.editorFields.toRawMessage())
    }

    // ========================================
    // Complex Workflow Tests
    // ========================================

    @Test
    fun testComplexEditingWorkflow() {
        // Step 1: Build initial message
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "56", value = "TARGET"))

        assertEquals("35=D|49=SENDER|56=TARGET|", viewModel.editorFields.toRawMessage())

        // Step 2: Add order fields
        viewModel.addEditorField()
        viewModel.updateEditorField(3, FixField(tag = "11", value = "ORDER123"))
        viewModel.addEditorField()
        viewModel.updateEditorField(4, FixField(tag = "55", value = "EUR/USD"))

        assertEquals("35=D|49=SENDER|56=TARGET|11=ORDER123|55=EUR/USD|", viewModel.editorFields.toRawMessage())

        // Step 3: Delete the sender field (at index 1)
        viewModel.deleteEditorField(1)

        assertEquals("35=D|56=TARGET|11=ORDER123|55=EUR/USD|", viewModel.editorFields.toRawMessage())

        // Step 4: Update order ID
        viewModel.updateEditorField(2, FixField(tag = "11", value = "NEW_ORDER_456"))

        assertEquals("35=D|56=TARGET|11=NEW_ORDER_456|55=EUR/USD|", viewModel.editorFields.toRawMessage())
    }

    @Test
    fun testBuildMessageWithExcludedFields() {
        // Build message with some excluded fields
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D", excluded = false))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER", excluded = true))
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "56", value = "TARGET", excluded = false))

        // The toRawMessage includes all fields (excluded flag is just metadata)
        val rawMessage = viewModel.editorFields.toRawMessage()
        assertEquals("35=D|49=SENDER|56=TARGET|", rawMessage)

        // But when we check the fields, excluded flag should be preserved
        assertTrue(viewModel.editorFields[1].excluded, "Middle field should be marked as excluded")
    }

    @Test
    fun testReorderFieldsToMatchFixStandard() {
        // Build message with fields in wrong order
        viewModel.updateEditorField(0, FixField(tag = "11", value = "ORDER123"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "49", value = "SENDER"))

        assertEquals("11=ORDER123|35=D|49=SENDER|", viewModel.editorFields.toRawMessage())

        // Reorder to standard: MsgType, Sender, Order ID
        // Move 35 to position 0
        viewModel.moveEditorFieldUp(1)
        viewModel.moveEditorFieldUp(0)

        // Move 49 to position 1
        viewModel.moveEditorFieldUp(2)

        assertEquals("35=D|49=SENDER|11=ORDER123|", viewModel.editorFields.toRawMessage())
    }

    @Test
    fun testEmptyFieldHandling() {
        // Test that empty fields are included in the message
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = ""))
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "56", value = "TARGET"))

        // Empty value should still appear in the message
        assertEquals("35=D|49=|56=TARGET|", viewModel.editorFields.toRawMessage())
    }

    @Test
    fun testSpecialCharactersInValues() {
        // Test that special characters are preserved
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "58", value = "Test message with spaces"))
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "100", value = "VALUE=WITH|PIPES"))

        val rawMessage = viewModel.editorFields.toRawMessage()
        assertTrue(rawMessage.contains("58=Test message with spaces"))
        assertTrue(rawMessage.contains("100=VALUE=WITH|PIPES"))
    }
}
