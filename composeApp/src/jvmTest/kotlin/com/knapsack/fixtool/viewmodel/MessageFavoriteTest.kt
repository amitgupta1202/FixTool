package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.ui.FixField
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for message favorite toggle functionality in the ViewModel.
 */
class MessageFavoriteTest {
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

    private fun createAndSaveTestMessage(name: String, profileId: String = "test-profile"): String {
        val fields =
            listOf(
                FixField(tag = "35", value = "D"),
                FixField(tag = "49", value = "SENDER"),
            )

        // Initialize fields in editor
        viewModel.clearEditorFields()
        viewModel.updateEditorField(0, fields[0])
        viewModel.addEditorField()
        viewModel.updateEditorField(1, fields[1])

        // Save with explicit profile ID
        viewModel.saveEditorMessage(name, viewModel.editorFields, profileId)

        // Find and return the message ID
        val savedMessage = viewModel.savedMessages.find { it.name == name }
        assertNotNull(savedMessage, "Message should be saved")
        return savedMessage.id
    }

    // ========================================
    // Toggle Favorite Tests
    // ========================================

    @Test
    fun testToggleFavoriteFromFalseToTrue() {
        val messageId = createAndSaveTestMessage("Test Favorite Message")

        // Verify initial state is not favorite
        val messageBefore = viewModel.savedMessages.find { it.id == messageId }
        assertNotNull(messageBefore)
        assertFalse(messageBefore.isFavorite, "New message should not be favorited")

        // Toggle favorite
        viewModel.toggleMessageFavorite(messageId)

        // Verify favorite state changed
        val messageAfter = viewModel.savedMessages.find { it.id == messageId }
        assertNotNull(messageAfter)
        assertTrue(messageAfter.isFavorite, "Message should be favorited after toggle")
    }

    @Test
    fun testToggleFavoriteTwiceReturnsToOriginalState() {
        val messageId = createAndSaveTestMessage("Test Double Toggle")

        // Toggle twice
        viewModel.toggleMessageFavorite(messageId)
        viewModel.toggleMessageFavorite(messageId)

        // Verify returned to original state
        val message = viewModel.savedMessages.find { it.id == messageId }
        assertNotNull(message)
        assertFalse(message.isFavorite, "Message should be unfavorited after double toggle")
    }

    @Test
    fun testToggleFavoritePreservesOtherFields() {
        val messageId = createAndSaveTestMessage("Test Preserve Fields")

        val messageBefore = viewModel.savedMessages.find { it.id == messageId }
        assertNotNull(messageBefore)

        // Toggle favorite
        viewModel.toggleMessageFavorite(messageId)

        val messageAfter = viewModel.savedMessages.find { it.id == messageId }
        assertNotNull(messageAfter)

        // Verify other fields are preserved
        assertEquals(messageBefore.id, messageAfter.id)
        assertEquals(messageBefore.name, messageAfter.name)
        assertEquals(messageBefore.fields.size, messageAfter.fields.size)
        assertEquals(messageBefore.userTags, messageAfter.userTags)
    }

    @Test
    fun testToggleFavoriteNonexistentMessageDoesNothing() {
        createAndSaveTestMessage("Real Message")
        val countBefore = viewModel.savedMessages.size

        // Try to toggle a non-existent message
        viewModel.toggleMessageFavorite("non-existent-id")

        // Verify nothing changed
        assertEquals(countBefore, viewModel.savedMessages.size)
    }

    @Test
    fun testMultipleMessagesFavoriteIndependent() {
        // Use same profile so both messages are in savedMessages list
        val messageId1 = createAndSaveTestMessage("Message 1", "test-profile")
        val messageId2 = createAndSaveTestMessage("Message 2", "test-profile")

        // Favorite only message 1
        viewModel.toggleMessageFavorite(messageId1)

        // Verify only message 1 is favorited
        val message1 = viewModel.savedMessages.find { it.id == messageId1 }
        val message2 = viewModel.savedMessages.find { it.id == messageId2 }

        assertNotNull(message1, "Message 1 should exist in savedMessages")
        assertNotNull(message2, "Message 2 should exist in savedMessages")
        assertTrue(message1.isFavorite, "Message 1 should be favorited")
        assertFalse(message2.isFavorite, "Message 2 should not be favorited")
    }

    // ========================================
    // Filter by Favorites Tests
    // ========================================

    @Test
    fun testFilterFavoriteMessages() {
        // Use same profile so both messages are in savedMessages list
        val messageId1 = createAndSaveTestMessage("Favorite Message", "test-profile")
        createAndSaveTestMessage("Non-Favorite Message", "test-profile")

        // Favorite only message 1
        viewModel.toggleMessageFavorite(messageId1)

        // Filter favorites
        val favorites = viewModel.savedMessages.filter { it.isFavorite }

        assertEquals(1, favorites.size)
        assertEquals("Favorite Message", favorites.first().name)
    }

    @Test
    fun testNoFavoriteMessagesReturnsEmptyList() {
        createAndSaveTestMessage("Message 1")
        createAndSaveTestMessage("Message 2")

        val favorites = viewModel.savedMessages.filter { it.isFavorite }

        assertTrue(favorites.isEmpty(), "Should have no favorites")
    }
}
