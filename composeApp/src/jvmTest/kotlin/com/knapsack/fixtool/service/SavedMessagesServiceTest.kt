package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SavedMessagesServiceTest {
    private lateinit var service: SavedMessagesService
    private lateinit var originalFile: File
    private lateinit var testFile: File

    @Before
    fun setup() {
        // Save reference to the real file and temporarily move it
        originalFile = File(System.getProperty("user.home"), ".fixtool/saved_messages.json")
        val backupFile = File(System.getProperty("user.home"), ".fixtool/saved_messages.json.backup")

        // Backup existing file if it exists
        if (originalFile.exists()) {
            originalFile.copyTo(backupFile, overwrite = true)
            originalFile.delete()
        }

        service = SavedMessagesService()
        testFile = originalFile
    }

    @After
    fun cleanup() {
        // Clean up test data and restore original file
        if (testFile.exists()) {
            testFile.delete()
        }

        // Restore backup if it exists
        val backupFile = File(System.getProperty("user.home"), ".fixtool/saved_messages.json.backup")
        if (backupFile.exists()) {
            backupFile.copyTo(originalFile, overwrite = true)
            backupFile.delete()
        }
    }

    @Test
    fun testSaveNewMessage() {
        val profileId = "test-profile-1"
        val fields =
            listOf(
                SavedFixField(tag = "35", value = "D"),
                SavedFixField(tag = "49", value = "SENDER"),
                SavedFixField(tag = "56", value = "TARGET"),
            )
        val message =
            SavedFixMessage(
                name = "Test Message",
                profileId = profileId,
                fields = fields,
            )

        val result = service.saveMessage(profileId, message)

        assertTrue(result.isNotEmpty(), "Saved messages should not be empty")
        assertEquals(1, result.size, "Should have exactly one message")
        assertEquals("Test Message", result[0].name)
        assertEquals(3, result[0].fields.size)
    }

    @Test
    fun testSaveMultipleMessages() {
        val profileId = "test-profile-multi"

        val message1 =
            SavedFixMessage(
                name = "Message 1",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )

        val message2 =
            SavedFixMessage(
                name = "Message 2",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "8")),
            )

        service.saveMessage(profileId, message1)
        val result = service.saveMessage(profileId, message2)

        assertEquals(2, result.size, "Should have two messages")
        assertTrue(result.any { it.name == "Message 1" })
        assertTrue(result.any { it.name == "Message 2" })
    }

    @Test
    fun testUpdateExistingMessage() {
        val profileId = "test-profile-update"
        val messageId = "fixed-id-123"

        val originalMessage =
            SavedFixMessage(
                id = messageId,
                name = "Original Message",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )

        service.saveMessage(profileId, originalMessage)

        val updatedMessage =
            SavedFixMessage(
                id = messageId,
                name = "Updated Message",
                profileId = profileId,
                fields =
                    listOf(
                        SavedFixField(tag = "35", value = "D"),
                        SavedFixField(tag = "49", value = "NEWSENDER"),
                    ),
            )

        val result = service.saveMessage(profileId, updatedMessage)

        assertEquals(1, result.size, "Should still have only one message")
        assertEquals("Updated Message", result[0].name)
        assertEquals(2, result[0].fields.size, "Should have updated fields")
    }

    @Test
    fun testLoadMessagesForProfile() {
        val profileId = "test-profile-load"
        val message =
            SavedFixMessage(
                name = "Load Test Message",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )

        service.saveMessage(profileId, message)
        val loadedMessages = service.loadMessagesForProfile(profileId)

        assertNotNull(loadedMessages)
        assertEquals(1, loadedMessages.size)
        assertEquals("Load Test Message", loadedMessages[0].name)
    }

    @Test
    fun testLoadMessagesForNonExistentProfile() {
        val messages = service.loadMessagesForProfile("non-existent-profile")

        assertNotNull(messages)
        assertTrue(messages.isEmpty(), "Should return empty list for non-existent profile")
    }

    @Test
    fun testDeleteMessage() {
        val profileId = "test-profile-delete"
        val message =
            SavedFixMessage(
                id = "delete-me",
                name = "Delete Test Message",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )

        service.saveMessage(profileId, message)
        val beforeDelete = service.loadMessagesForProfile(profileId)
        assertEquals(1, beforeDelete.size)

        val result = service.deleteMessage(profileId, "delete-me")

        assertEquals(0, result.size, "Should have no messages after deletion")

        val afterDelete = service.loadMessagesForProfile(profileId)
        assertTrue(afterDelete.isEmpty(), "Profile should have no messages after deletion")
    }

    @Test
    fun testDeleteNonExistentMessage() {
        val profileId = "test-profile-delete-nonexistent"
        val message =
            SavedFixMessage(
                name = "Keep Message",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )

        service.saveMessage(profileId, message)
        val result = service.deleteMessage(profileId, "non-existent-id")

        assertEquals(1, result.size, "Should still have the original message")
        assertEquals("Keep Message", result[0].name)
    }

    @Test
    fun testMarkMessageAsUsed() {
        val profileId = "test-profile-mark-used"
        val messageId = "mark-used-id"
        val message =
            SavedFixMessage(
                id = messageId,
                name = "Mark Used Test",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
                lastUsedAt = 1000L,
            )

        service.saveMessage(profileId, message)
        Thread.sleep(10) // Small delay to ensure timestamp difference

        val result = service.markMessageAsUsed(profileId, messageId)

        assertEquals(1, result.size)
        assertTrue(result[0].lastUsedAt > 1000L, "lastUsedAt should be updated to a more recent timestamp")
    }

    @Test
    fun testMarkNonExistentMessageAsUsed() {
        val profileId = "test-profile-mark-nonexistent"
        val result = service.markMessageAsUsed(profileId, "non-existent-id")

        assertTrue(result.isEmpty(), "Should return empty list when marking non-existent message")
    }

    @Test
    fun testMessagesIsolatedBetweenProfiles() {
        val profile1 = "profile-1"
        val profile2 = "profile-2"

        val message1 =
            SavedFixMessage(
                name = "Profile 1 Message",
                profileId = profile1,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )

        val message2 =
            SavedFixMessage(
                name = "Profile 2 Message",
                profileId = profile2,
                fields = listOf(SavedFixField(tag = "35", value = "8")),
            )

        service.saveMessage(profile1, message1)
        service.saveMessage(profile2, message2)

        val profile1Messages = service.loadMessagesForProfile(profile1)
        val profile2Messages = service.loadMessagesForProfile(profile2)

        assertEquals(1, profile1Messages.size)
        assertEquals(1, profile2Messages.size)
        assertEquals("Profile 1 Message", profile1Messages[0].name)
        assertEquals("Profile 2 Message", profile2Messages[0].name)
    }

    @Test
    fun testSaveMessageWithExcludedFields() {
        val profileId = "test-profile-excluded"
        val fields =
            listOf(
                SavedFixField(tag = "35", value = "D", excluded = false),
                SavedFixField(tag = "49", value = "SENDER", excluded = true),
                SavedFixField(tag = "56", value = "TARGET", excluded = false),
            )
        val message =
            SavedFixMessage(
                name = "Excluded Fields Test",
                profileId = profileId,
                fields = fields,
            )

        val result = service.saveMessage(profileId, message)

        assertEquals(1, result.size)
        val savedMessage = result[0]
        assertEquals(3, savedMessage.fields.size)

        val excludedField = savedMessage.fields.find { it.tag == "49" }
        assertNotNull(excludedField)
        assertTrue(excludedField.excluded, "Field should be marked as excluded")
    }

    @Test
    fun testSaveMessagePreservesTimestamps() {
        val profileId = "test-profile-timestamps"
        val createdAt = System.currentTimeMillis() - 10000
        val lastUsedAt = System.currentTimeMillis() - 5000

        val message =
            SavedFixMessage(
                name = "Timestamp Test",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
                createdAt = createdAt,
                lastUsedAt = lastUsedAt,
            )

        val result = service.saveMessage(profileId, message)

        assertEquals(1, result.size)
        assertEquals(createdAt, result[0].createdAt, "createdAt should be preserved")
        assertEquals(lastUsedAt, result[0].lastUsedAt, "lastUsedAt should be preserved")
    }

    @Test
    fun testDeleteFromProfileWithMultipleMessages() {
        val profileId = "test-profile-delete-multi"

        val message1 =
            SavedFixMessage(
                id = "keep-1",
                name = "Keep Message 1",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )

        val message2 =
            SavedFixMessage(
                id = "delete-2",
                name = "Delete Message 2",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "8")),
            )

        val message3 =
            SavedFixMessage(
                id = "keep-3",
                name = "Keep Message 3",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "G")),
            )

        service.saveMessage(profileId, message1)
        service.saveMessage(profileId, message2)
        service.saveMessage(profileId, message3)

        val result = service.deleteMessage(profileId, "delete-2")

        assertEquals(2, result.size, "Should have 2 messages remaining")
        assertTrue(result.none { it.id == "delete-2" }, "Deleted message should not be in results")
        assertTrue(result.any { it.name == "Keep Message 1" })
        assertTrue(result.any { it.name == "Keep Message 3" })
    }
}
