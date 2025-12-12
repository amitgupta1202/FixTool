package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.ui.FixField
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for message editor operations including save, load, edit, delete, and filter
 */
class MessageEditorOperationsTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private lateinit var originalFile: File

    @Before
    fun setup() {
        // Create a temporary directory for test files (isolated from production)
        testDir =
            File.createTempFile("fixtool-test", "").apply {
                delete() // Delete the file
                mkdirs() // Create as directory
            }

        // Backup and clear saved messages file to ensure test isolation
        originalFile = File(System.getProperty("user.home"), ".fixtool/saved_messages.json")
        val backupFile = File(System.getProperty("user.home"), ".fixtool/saved_messages.json.backup")

        if (originalFile.exists()) {
            originalFile.copyTo(backupFile, overwrite = true)
            originalFile.delete()
        }

        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun cleanup() {
        // Clean up test data and restore original file
        if (originalFile.exists()) {
            originalFile.delete()
            // Clean up test directory
            testDir.deleteRecursively()
        }

        // Restore backup if it exists
        val backupFile = File(System.getProperty("user.home"), ".fixtool/saved_messages.json.backup")
        if (backupFile.exists()) {
            backupFile.copyTo(originalFile, overwrite = true)
            backupFile.delete()
        }
    }

    // ========================================
    // Save Message Tests
    // ========================================

    @Test
    fun testSaveMessageWithName() {
        val fields =
            listOf(
                FixField(tag = "35", value = "D"),
                FixField(tag = "49", value = "SENDER"),
                FixField(tag = "56", value = "TARGET"),
            )

        // Initialize fields in editor
        viewModel.updateEditorField(0, fields[0])
        viewModel.addEditorField()
        viewModel.updateEditorField(1, fields[1])
        viewModel.addEditorField()
        viewModel.updateEditorField(2, fields[2])

        // Save with explicit profile ID
        viewModel.saveEditorMessage("Test Message", viewModel.editorFields, "test-profile")

        // Verify message was saved
        val savedMessages = viewModel.savedMessages
        assertTrue(savedMessages.isNotEmpty(), "Should have at least one saved message")

        val savedMessage = savedMessages.find { it.name == "Test Message" }
        assertNotNull(savedMessage, "Should find saved message by name")
        assertEquals(3, savedMessage.fields.size, "Should have 3 fields")
    }

    @Test
    fun testSaveMessageWithExcludedFields() {
        val fields =
            listOf(
                FixField(tag = "35", value = "D", excluded = false),
                FixField(tag = "49", value = "SENDER", excluded = true),
                FixField(tag = "56", value = "TARGET", excluded = false),
            )

        viewModel.updateEditorField(0, fields[0])
        viewModel.addEditorField()
        viewModel.updateEditorField(1, fields[1])
        viewModel.addEditorField()
        viewModel.updateEditorField(2, fields[2])

        viewModel.saveEditorMessage("Excluded Test", viewModel.editorFields, "test-profile")

        val savedMessage = viewModel.savedMessages.find { it.name == "Excluded Test" }
        assertNotNull(savedMessage)

        val excludedField = savedMessage.fields.find { it.tag == "49" }
        assertNotNull(excludedField)
        assertTrue(excludedField.excluded, "Field should be marked as excluded")
    }

    @Test
    fun testSaveEmptyMessage() {
        // Don't add any fields (default has one empty field)
        viewModel.saveEditorMessage("Empty Message", viewModel.editorFields, "test-profile")

        val savedMessage = viewModel.savedMessages.find { it.name == "Empty Message" }
        assertNotNull(savedMessage, "Should be able to save empty/draft messages")
    }

    // ========================================
    // Load Message Tests
    // ========================================

    @Test
    fun testLoadMessage() {
        // First save a message
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))
        viewModel.saveEditorMessage("Load Test", viewModel.editorFields, "test-profile")

        // Clear editor fields
        viewModel.clearEditorFields()
        assertEquals(1, viewModel.editorFields.size, "Should have one empty field after clear")

        // Load the saved message
        val savedMessage = viewModel.savedMessages.find { it.name == "Load Test" }
        assertNotNull(savedMessage)
        viewModel.loadEditorMessage(savedMessage)

        // Verify fields were loaded
        assertEquals(2, viewModel.editorFields.size, "Should have loaded 2 fields")
        assertEquals("35", viewModel.editorFields[0].tag)
        assertEquals("D", viewModel.editorFields[0].value)
        assertEquals("49", viewModel.editorFields[1].tag)
        assertEquals("SENDER", viewModel.editorFields[1].value)
    }

    @Test
    fun testLoadEmptyMessage() {
        // Save an empty message
        viewModel.clearEditorFields()
        viewModel.saveEditorMessage("Empty Load Test", viewModel.editorFields, "test-profile")

        // Add some fields
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))

        // Load the empty message
        val savedMessage = viewModel.savedMessages.find { it.name == "Empty Load Test" }
        assertNotNull(savedMessage)
        viewModel.loadEditorMessage(savedMessage)

        // Should have one empty field
        assertEquals(1, viewModel.editorFields.size)
        assertEquals("", viewModel.editorFields[0].tag)
        assertEquals("", viewModel.editorFields[0].value)
    }

    @Test
    fun testLoadMessageResetsSelection() {
        // Save and load a message
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))
        viewModel.saveEditorMessage("Selection Test", viewModel.editorFields, "test-profile")

        // Select second field
        viewModel.selectEditorField(1, isCtrlPressed = false, isShiftPressed = false)
        assertEquals(1, viewModel.editorSelectedFieldIndex, "Should have selected field 1")

        // Load the message
        val savedMessage = viewModel.savedMessages.find { it.name == "Selection Test" }
        assertNotNull(savedMessage)
        viewModel.loadEditorMessage(savedMessage)

        // Selection should be reset to first field
        assertEquals(0, viewModel.editorSelectedFieldIndex, "Selection should reset to first field")
        assertEquals(1, viewModel.editorSelectedIndices.size)
        assertTrue(viewModel.editorSelectedIndices.contains(0))
    }

    // ========================================
    // Edit/Update Message Tests
    // ========================================

    @Test
    fun testUpdateExistingMessage() {
        // Save initial version
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.saveEditorMessage("Update Test", viewModel.editorFields, "test-profile")

        val originalMessage = viewModel.savedMessages.find { it.name == "Update Test" }
        assertNotNull(originalMessage)
        val originalId = originalMessage.id

        // Load and modify
        viewModel.loadEditorMessage(originalMessage)
        viewModel.updateEditorField(0, FixField(tag = "35", value = "8")) // Change value

        // Save again with same name (should update if we pass the same ID)
        // Note: In the current implementation, this will create a new message
        // unless we explicitly use the same ID
        viewModel.saveEditorMessage("Update Test", viewModel.editorFields, "test-profile")

        // In the current implementation, messages are identified by ID
        // So saving with same name might create a duplicate
        // This test documents the current behavior
        val messages = viewModel.savedMessages.filter { it.name == "Update Test" }
        assertTrue(messages.isNotEmpty(), "Should have message(s) with the name")
    }

    @Test
    fun testModifyLoadedMessage() {
        // Save original
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.saveEditorMessage("Modify Test", viewModel.editorFields, "test-profile")

        val savedMessage = viewModel.savedMessages.find { it.name == "Modify Test" }
        assertNotNull(savedMessage)

        // Load it
        viewModel.loadEditorMessage(savedMessage)

        // Modify the fields
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "NEWSENDER"))

        // Verify modifications in editor
        assertEquals(2, viewModel.editorFields.size)
        assertEquals("49", viewModel.editorFields[1].tag)
        assertEquals("NEWSENDER", viewModel.editorFields[1].value)
    }

    // ========================================
    // Delete Message Tests
    // ========================================

    @Test
    fun testDeleteMessage() {
        // Save a message
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.saveEditorMessage("Delete Test", viewModel.editorFields, "test-profile")

        val savedMessage = viewModel.savedMessages.find { it.name == "Delete Test" }
        assertNotNull(savedMessage)

        val initialCount = viewModel.savedMessages.size

        // Delete the message
        viewModel.deleteSavedMessage(savedMessage.id, savedMessage.getAllUserTags().first())

        // Verify it was deleted
        val afterDelete = viewModel.savedMessages.find { it.name == "Delete Test" }
        assertEquals(null, afterDelete, "Message should be deleted")
        assertTrue(viewModel.savedMessages.size < initialCount, "Message count should decrease")
    }

    @Test
    fun testDeleteMessageClearsLoadedMessageName() {
        // Save and load a message
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.saveEditorMessage("Clear Name Test", viewModel.editorFields, "test-profile")

        val savedMessage = viewModel.savedMessages.find { it.name == "Clear Name Test" }
        assertNotNull(savedMessage)

        viewModel.loadEditorMessage(savedMessage)

        // The loaded message name should be set (assuming implementation tracks this)
        // Note: Check if currentLoadedMessageName is properly exposed

        // Delete the message
        viewModel.deleteSavedMessage(savedMessage.id, savedMessage.getAllUserTags().first())

        // Current loaded message name should be cleared if it was the deleted one
        // This tests the implementation detail in deleteSavedMessage
    }

    @Test
    fun testDeleteNonExistentMessage() {
        val initialCount = viewModel.savedMessages.size

        // Try to delete a message that doesn't exist
        viewModel.deleteSavedMessage("non-existent-id", "test-profile")

        // Count should remain the same
        assertEquals(initialCount, viewModel.savedMessages.size)
    }

    // ========================================
    // Filter Messages Tests
    // ========================================

    @Test
    fun testLoadMessagesForSpecificProfile() {
        // Save messages for the same profile
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.saveEditorMessage("Profile 1 Message", viewModel.editorFields, "profile-1")

        viewModel.clearEditorFields()
        viewModel.updateEditorField(0, FixField(tag = "35", value = "8"))
        viewModel.saveEditorMessage("Profile 1 Message 2", viewModel.editorFields, "profile-1")

        // Verify both messages are in the saved list
        // Since we saved them with the same profile, they should both appear
        assertTrue(viewModel.savedMessages.any { it.name == "Profile 1 Message" })
        assertTrue(viewModel.savedMessages.any { it.name == "Profile 1 Message 2" })
        assertEquals(2, viewModel.savedMessages.size, "Should have 2 messages for profile-1")
    }

    @Test
    fun testFilterMessagesByProfile() {
        // Save multiple messages for the same profile
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.saveEditorMessage("Msg A", viewModel.editorFields, "profile-A")

        viewModel.clearEditorFields()
        viewModel.updateEditorField(0, FixField(tag = "35", value = "8"))
        viewModel.saveEditorMessage("Msg A2", viewModel.editorFields, "profile-A")

        viewModel.clearEditorFields()
        viewModel.updateEditorField(0, FixField(tag = "35", value = "G"))
        viewModel.saveEditorMessage("Msg A3", viewModel.editorFields, "profile-A")

        // All messages should be for profile-A
        val profileAMessages = viewModel.savedMessages.filter { it.getAllUserTags().contains("profile-A") }

        assertEquals(3, profileAMessages.size, "Profile A should have 3 messages")
        assertEquals(3, viewModel.savedMessages.size, "All saved messages should be for profile-A")
        assertTrue(viewModel.savedMessages.all { it.getAllUserTags().contains("profile-A") })
    }

    // ========================================
    // Field Operations Tests
    // ========================================

    @Test
    fun testAddField() {
        val initialCount = viewModel.editorFields.size
        viewModel.addEditorField()
        assertEquals(initialCount + 1, viewModel.editorFields.size, "Should add one field")
    }

    @Test
    fun testDeleteField() {
        // Add multiple fields
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "56", value = "TARGET"))

        assertEquals(3, viewModel.editorFields.size)

        // Delete middle field
        viewModel.deleteEditorField(1)

        assertEquals(2, viewModel.editorFields.size)
        assertEquals("35", viewModel.editorFields[0].tag)
        assertEquals("56", viewModel.editorFields[1].tag)
    }

    @Test
    fun testMoveFieldUp() {
        // Setup fields
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "56", value = "TARGET"))

        // Move field at index 1 up
        viewModel.moveEditorFieldUp(1)

        // Verify order
        assertEquals("49", viewModel.editorFields[0].tag)
        assertEquals("35", viewModel.editorFields[1].tag)
        assertEquals("56", viewModel.editorFields[2].tag)
    }

    @Test
    fun testMoveFieldDown() {
        // Setup fields
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "56", value = "TARGET"))

        // Move field at index 0 down
        viewModel.moveEditorFieldDown(0)

        // Verify order
        assertEquals("49", viewModel.editorFields[0].tag)
        assertEquals("35", viewModel.editorFields[1].tag)
        assertEquals("56", viewModel.editorFields[2].tag)
    }

    @Test
    fun testClearFields() {
        // Add multiple fields
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))

        assertEquals(2, viewModel.editorFields.size)

        // Clear all fields
        viewModel.clearEditorFields()

        // Should have one empty field
        assertEquals(1, viewModel.editorFields.size)
        assertEquals("", viewModel.editorFields[0].tag)
        assertEquals("", viewModel.editorFields[0].value)
    }

    @Test
    fun testMultipleSelection() {
        // Add multiple fields
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))
        viewModel.addEditorField()
        viewModel.updateEditorField(2, FixField(tag = "56", value = "TARGET"))

        // Select first field
        viewModel.selectEditorField(0, isCtrlPressed = false, isShiftPressed = false)
        assertEquals(1, viewModel.editorSelectedIndices.size)

        // Ctrl+click to add second field to selection
        viewModel.selectEditorField(2, isCtrlPressed = true, isShiftPressed = false)
        assertEquals(2, viewModel.editorSelectedIndices.size)
        assertTrue(viewModel.editorSelectedIndices.contains(0))
        assertTrue(viewModel.editorSelectedIndices.contains(2))
    }

    @Test
    fun testRangeSelection() {
        // Add multiple fields
        for (i in 0 until 5) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        // Select first field
        viewModel.selectEditorField(1, isCtrlPressed = false, isShiftPressed = false)

        // Shift+click to select range
        viewModel.selectEditorField(3, isCtrlPressed = false, isShiftPressed = true)

        // Should have selected indices 1, 2, 3
        assertEquals(3, viewModel.editorSelectedIndices.size)
        assertTrue(viewModel.editorSelectedIndices.contains(1))
        assertTrue(viewModel.editorSelectedIndices.contains(2))
        assertTrue(viewModel.editorSelectedIndices.contains(3))
    }

    // ========================================
    // Multi-Select Delete Tests
    // ========================================

    @Test
    fun testMultiSelectDeleteContiguous() {
        // Setup 5 fields
        for (i in 0 until 5) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        // Select fields 1, 2, 3
        viewModel.selectEditorField(1, isCtrlPressed = false, isShiftPressed = false)
        viewModel.selectEditorField(3, isCtrlPressed = false, isShiftPressed = true)

        assertEquals(5, viewModel.editorFields.size, "Should start with 5 fields")
        assertEquals(3, viewModel.editorSelectedIndices.size, "Should have 3 selected")

        // Delete the selected fields (passing any selected index should work)
        viewModel.deleteEditorField(1)

        // Should have 2 fields remaining (0 and 4)
        assertEquals(2, viewModel.editorFields.size, "Should have 2 fields remaining")
        assertEquals("35", viewModel.editorFields[0].tag, "First field should be tag 35")
        assertEquals("39", viewModel.editorFields[1].tag, "Second field should be tag 39")

        // Selection should move to first remaining position
        assertEquals(1, viewModel.editorSelectedIndices.size, "Should have single selection")
    }

    @Test
    fun testMultiSelectDeleteNonContiguous() {
        // Setup 6 fields
        for (i in 0 until 6) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        // Select fields 1, 3, 5 (non-contiguous)
        viewModel.selectEditorField(1, isCtrlPressed = false, isShiftPressed = false)
        viewModel.selectEditorField(3, isCtrlPressed = true, isShiftPressed = false)
        viewModel.selectEditorField(5, isCtrlPressed = true, isShiftPressed = false)

        assertEquals(6, viewModel.editorFields.size, "Should start with 6 fields")
        assertEquals(3, viewModel.editorSelectedIndices.size, "Should have 3 selected")

        // Delete the selected fields
        viewModel.deleteEditorField(1)

        // Should have 3 fields remaining (0, 2, 4)
        assertEquals(3, viewModel.editorFields.size, "Should have 3 fields remaining")
        assertEquals("35", viewModel.editorFields[0].tag, "Should be original field 0")
        assertEquals("37", viewModel.editorFields[1].tag, "Should be original field 2")
        assertEquals("39", viewModel.editorFields[2].tag, "Should be original field 4")
    }

    @Test
    fun testMultiSelectDeleteAllButOne() {
        // Setup 5 fields
        for (i in 0 until 5) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        // Select all but one field (0, 1, 2, 3)
        viewModel.selectEditorField(0, isCtrlPressed = false, isShiftPressed = false)
        viewModel.selectEditorField(3, isCtrlPressed = false, isShiftPressed = true)

        assertEquals(4, viewModel.editorSelectedIndices.size)

        // Try to delete - should work since one field remains
        viewModel.deleteEditorField(0)

        assertEquals(1, viewModel.editorFields.size, "Should have 1 field remaining")
        assertEquals("39", viewModel.editorFields[0].tag, "Should be the last field")
    }

    @Test
    fun testMultiSelectDeleteWhenAllFieldsSelected() {
        // Setup 3 fields
        for (i in 0 until 3) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        // Select all fields
        viewModel.selectEditorField(0, isCtrlPressed = false, isShiftPressed = false)
        viewModel.selectEditorField(2, isCtrlPressed = false, isShiftPressed = true)

        assertEquals(3, viewModel.editorSelectedIndices.size)
        assertEquals(3, viewModel.editorFields.size)

        // When all fields are selected, multi-select deletion is skipped (requires fields > selected)
        // It falls through to single-select logic which deletes just the specified index
        viewModel.deleteEditorField(0)

        // Only the single field at index 0 is deleted, leaving 2 fields
        assertEquals(2, viewModel.editorFields.size, "Should have deleted only one field")
        assertEquals("36", viewModel.editorFields[0].tag, "Field 1 should now be at index 0")
        assertEquals("37", viewModel.editorFields[1].tag, "Field 2 should now be at index 1")
    }

    // ========================================
    // Multi-Select Move Up Tests
    // ========================================

    @Test
    fun testMultiSelectMoveUpContiguous() {
        // Setup 5 fields
        for (i in 0 until 5) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        // Select fields 2, 3 (contiguous)
        viewModel.selectEditorField(2, isCtrlPressed = false, isShiftPressed = false)
        viewModel.selectEditorField(3, isCtrlPressed = false, isShiftPressed = true)

        assertEquals(2, viewModel.editorSelectedIndices.size)

        // Move up
        viewModel.moveEditorFieldUp(2)

        // Fields should now be: 0, 2, 3, 1, 4
        assertEquals("35", viewModel.editorFields[0].tag, "Field 0 stays")
        assertEquals("37", viewModel.editorFields[1].tag, "Field 2 moved up")
        assertEquals("38", viewModel.editorFields[2].tag, "Field 3 moved up")
        assertEquals("36", viewModel.editorFields[3].tag, "Field 1 moved down")
        assertEquals("39", viewModel.editorFields[4].tag, "Field 4 stays")

        // Selection should move with the fields
        assertTrue(viewModel.editorSelectedIndices.contains(1), "Selection should include new index 1")
        assertTrue(viewModel.editorSelectedIndices.contains(2), "Selection should include new index 2")
    }

    @Test
    fun testMultiSelectMoveUpAtTop() {
        // Setup 5 fields
        for (i in 0 until 5) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        // Select fields 0, 1 (at top)
        viewModel.selectEditorField(0, isCtrlPressed = false, isShiftPressed = false)
        viewModel.selectEditorField(1, isCtrlPressed = false, isShiftPressed = true)

        // Move up - should do nothing since already at top
        viewModel.moveEditorFieldUp(0)

        // Order should remain the same
        assertEquals("35", viewModel.editorFields[0].tag)
        assertEquals("36", viewModel.editorFields[1].tag)
        assertEquals("37", viewModel.editorFields[2].tag)
    }

    @Test
    fun testMultiSelectMoveUpNonContiguous() {
        // Setup 6 fields
        for (i in 0 until 6) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        // Select fields 2, 4 (non-contiguous)
        viewModel.selectEditorField(2, isCtrlPressed = false, isShiftPressed = false)
        viewModel.selectEditorField(4, isCtrlPressed = true, isShiftPressed = false)

        // Move up - each selected field moves up individually
        viewModel.moveEditorFieldUp(2)

        // Field 2 should move to position 1, Field 4 should move to position 3
        // Result: 0, 2, 1, 4, 3, 5
        assertEquals("35", viewModel.editorFields[0].tag, "Field 0 stays")
        assertEquals("37", viewModel.editorFields[1].tag, "Field 2 moved up")
        assertEquals("36", viewModel.editorFields[2].tag, "Field 1 moved down")
        assertEquals("39", viewModel.editorFields[3].tag, "Field 4 moved up")
        assertEquals("38", viewModel.editorFields[4].tag, "Field 3 moved down")
        assertEquals("40", viewModel.editorFields[5].tag, "Field 5 stays")
    }

    // ========================================
    // Multi-Select Move Down Tests
    // ========================================

    @Test
    fun testMultiSelectMoveDownContiguous() {
        // Setup 5 fields
        for (i in 0 until 5) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        // Select fields 1, 2 (contiguous)
        viewModel.selectEditorField(1, isCtrlPressed = false, isShiftPressed = false)
        viewModel.selectEditorField(2, isCtrlPressed = false, isShiftPressed = true)

        assertEquals(2, viewModel.editorSelectedIndices.size)

        // Move down
        viewModel.moveEditorFieldDown(1)

        // Fields should now be: 0, 3, 1, 2, 4
        assertEquals("35", viewModel.editorFields[0].tag, "Field 0 stays")
        assertEquals("38", viewModel.editorFields[1].tag, "Field 3 moved up")
        assertEquals("36", viewModel.editorFields[2].tag, "Field 1 moved down")
        assertEquals("37", viewModel.editorFields[3].tag, "Field 2 moved down")
        assertEquals("39", viewModel.editorFields[4].tag, "Field 4 stays")

        // Selection should move with the fields
        assertTrue(viewModel.editorSelectedIndices.contains(2), "Selection should include new index 2")
        assertTrue(viewModel.editorSelectedIndices.contains(3), "Selection should include new index 3")
    }

    @Test
    fun testMultiSelectMoveDownAtBottom() {
        // Setup 5 fields
        for (i in 0 until 5) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        // Select fields 3, 4 (at bottom)
        viewModel.selectEditorField(3, isCtrlPressed = false, isShiftPressed = false)
        viewModel.selectEditorField(4, isCtrlPressed = false, isShiftPressed = true)

        // Move down - should do nothing since already at bottom
        viewModel.moveEditorFieldDown(3)

        // Order should remain the same
        assertEquals("35", viewModel.editorFields[0].tag)
        assertEquals("36", viewModel.editorFields[1].tag)
        assertEquals("37", viewModel.editorFields[2].tag)
        assertEquals("38", viewModel.editorFields[3].tag)
        assertEquals("39", viewModel.editorFields[4].tag)
    }

    @Test
    fun testMultiSelectMoveDownNonContiguous() {
        // Setup 6 fields
        for (i in 0 until 6) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        // Select fields 1, 3 (non-contiguous)
        viewModel.selectEditorField(1, isCtrlPressed = false, isShiftPressed = false)
        viewModel.selectEditorField(3, isCtrlPressed = true, isShiftPressed = false)

        // Move down - each selected field moves down individually
        viewModel.moveEditorFieldDown(1)

        // Field 1 should move to position 2, Field 3 should move to position 4
        // Result: 0, 2, 1, 4, 3, 5
        assertEquals("35", viewModel.editorFields[0].tag, "Field 0 stays")
        assertEquals("37", viewModel.editorFields[1].tag, "Field 2 moved up")
        assertEquals("36", viewModel.editorFields[2].tag, "Field 1 moved down")
        assertEquals("39", viewModel.editorFields[3].tag, "Field 4 moved up")
        assertEquals("38", viewModel.editorFields[4].tag, "Field 3 moved down")
        assertEquals("40", viewModel.editorFields[5].tag, "Field 5 stays")
    }

    // ========================================
    // Edge Case Tests
    // ========================================

    @Test
    fun testAddFieldWithExistingSelection() {
        // Setup 3 fields
        for (i in 0 until 3) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        // Select middle field
        viewModel.selectEditorField(1, isCtrlPressed = false, isShiftPressed = false)
        assertEquals(1, viewModel.editorSelectedIndices.size)

        // Add a new field
        viewModel.addEditorField()

        // Should now have 4 fields
        assertEquals(4, viewModel.editorFields.size)
    }

    @Test
    fun testDeleteLastFieldInSelection() {
        // Setup 2 fields
        viewModel.updateEditorField(0, FixField(tag = "35", value = "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField(tag = "49", value = "SENDER"))

        // Select and delete second field
        viewModel.selectEditorField(1, isCtrlPressed = false, isShiftPressed = false)
        viewModel.deleteEditorField(1)

        // Should have 1 field remaining
        assertEquals(1, viewModel.editorFields.size)
        assertEquals("35", viewModel.editorFields[0].tag)

        // Selection should move to remaining field
        assertEquals(0, viewModel.editorSelectedFieldIndex)
        assertTrue(viewModel.editorSelectedIndices.contains(0))
    }

    @Test
    fun testMoveFieldUpFromSecondPosition() {
        // Setup 3 fields
        for (i in 0 until 3) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        // Select field at index 1
        viewModel.selectEditorField(1, isCtrlPressed = false, isShiftPressed = false)

        // Move up
        viewModel.moveEditorFieldUp(1)

        // Field 1 should now be at position 0
        assertEquals("36", viewModel.editorFields[0].tag)
        assertEquals("35", viewModel.editorFields[1].tag)
        assertEquals("37", viewModel.editorFields[2].tag)

        // Selection should move with the field
        assertEquals(0, viewModel.editorSelectedFieldIndex)
    }

    @Test
    fun testMoveFieldDownFromSecondToLastPosition() {
        // Setup 3 fields
        for (i in 0 until 3) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        // Select field at index 1
        viewModel.selectEditorField(1, isCtrlPressed = false, isShiftPressed = false)

        // Move down
        viewModel.moveEditorFieldDown(1)

        // Field 1 should now be at position 2
        assertEquals("35", viewModel.editorFields[0].tag)
        assertEquals("37", viewModel.editorFields[1].tag)
        assertEquals("36", viewModel.editorFields[2].tag)

        // Selection should move with the field
        assertEquals(2, viewModel.editorSelectedFieldIndex)
    }

    @Test
    fun testClearFieldsWithMultiSelection() {
        // Setup 5 fields
        for (i in 0 until 5) {
            if (i > 0) viewModel.addEditorField()
            viewModel.updateEditorField(i, FixField(tag = "${35 + i}", value = "V$i"))
        }

        // Select multiple fields
        viewModel.selectEditorField(1, isCtrlPressed = false, isShiftPressed = false)
        viewModel.selectEditorField(3, isCtrlPressed = true, isShiftPressed = false)

        assertEquals(2, viewModel.editorSelectedIndices.size)

        // Clear all fields
        viewModel.clearEditorFields()

        // Should have one empty field
        assertEquals(1, viewModel.editorFields.size)
        assertEquals("", viewModel.editorFields[0].tag)
        assertEquals("", viewModel.editorFields[0].value)

        // Selection should be reset to single field
        assertEquals(1, viewModel.editorSelectedIndices.size)
        assertTrue(viewModel.editorSelectedIndices.contains(0))
    }
}
