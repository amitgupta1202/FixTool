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

/**
 * Unit tests for SavedMessagesService
 * Uses isolated test directory - NEVER touches real .fixtool directory
 */
class SavedMessagesServiceTest {
    private lateinit var service: SavedMessagesService
    private lateinit var testDir: File
    private lateinit var testFile: File

    @Before
    fun setup() {
        // Create a completely isolated temporary directory for test files
        testDir =
            File.createTempFile("fixtool-test", "").apply {
                delete() // Delete the file
                mkdirs() // Create as directory
            }

        // Create test file path in isolated directory
        testFile = File(testDir, "saved_messages.json")

        // Create service with custom path pointing to isolated test directory
        service = SavedMessagesService(customPath = testFile.absolutePath)
    }

    @After
    fun cleanup() {
        // Clean up isolated test directory - never touches real .fixtool
        testDir.deleteRecursively()
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
                userTags = setOf(profileId),
                fields = fields,
            )

        val result = service.saveMessage(profileId, message).getOrThrow()

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
                userTags = setOf(profileId),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )

        val message2 =
            SavedFixMessage(
                name = "Message 2",
                userTags = setOf(profileId),
                fields = listOf(SavedFixField(tag = "35", value = "8")),
            )

        service.saveMessage(profileId, message1).getOrThrow()
        val result = service.saveMessage(profileId, message2).getOrThrow()

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
                userTags = setOf(profileId),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )

        service.saveMessage(profileId, originalMessage).getOrThrow()

        val updatedMessage =
            SavedFixMessage(
                id = messageId,
                name = "Updated Message",
                userTags = setOf(profileId),
                fields =
                    listOf(
                        SavedFixField(tag = "35", value = "D"),
                        SavedFixField(tag = "49", value = "NEWSENDER"),
                    ),
            )

        val result = service.saveMessage(profileId, updatedMessage).getOrThrow()

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
                userTags = setOf(profileId),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )

        service.saveMessage(profileId, message).getOrThrow()
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
                userTags = setOf(profileId),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )

        service.saveMessage(profileId, message).getOrThrow()
        val beforeDelete = service.loadMessagesForProfile(profileId)
        assertEquals(1, beforeDelete.size)

        val result = service.deleteMessage(profileId, "delete-me").getOrThrow()

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
                userTags = setOf(profileId),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )

        service.saveMessage(profileId, message).getOrThrow()
        val result = service.deleteMessage(profileId, "non-existent-id").getOrThrow()

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
                userTags = setOf(profileId),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
                lastUsedAt = 1000L,
            )

        service.saveMessage(profileId, message).getOrThrow()
        Thread.sleep(10) // Small delay to ensure timestamp difference

        val result = service.markMessageAsUsed(profileId, messageId).getOrThrow()

        assertEquals(1, result.size)
        assertTrue(result[0].lastUsedAt > 1000L, "lastUsedAt should be updated to a more recent timestamp")
    }

    @Test
    fun testMarkNonExistentMessageAsUsed() {
        val profileId = "test-profile-mark-nonexistent"
        val result = service.markMessageAsUsed(profileId, "non-existent-id").getOrThrow()

        assertTrue(result.isEmpty(), "Should return empty list when marking non-existent message")
    }

    @Test
    fun testMessagesIsolatedBetweenProfiles() {
        val profile1 = "profile-1"
        val profile2 = "profile-2"

        val message1 =
            SavedFixMessage(
                name = "Profile 1 Message",
                userTags = setOf(profile1),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )

        val message2 =
            SavedFixMessage(
                name = "Profile 2 Message",
                userTags = setOf(profile2),
                fields = listOf(SavedFixField(tag = "35", value = "8")),
            )

        service.saveMessage(profile1, message1).getOrThrow()
        service.saveMessage(profile2, message2).getOrThrow()

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
                userTags = setOf(profileId),
                fields = fields,
            )

        val result = service.saveMessage(profileId, message).getOrThrow()

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
                userTags = setOf(profileId),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
                createdAt = createdAt,
                lastUsedAt = lastUsedAt,
            )

        val result = service.saveMessage(profileId, message).getOrThrow()

        assertEquals(1, result.size)
        assertEquals(createdAt, result[0].createdAt, "createdAt should be preserved")
        assertEquals(lastUsedAt, result[0].lastUsedAt, "lastUsedAt should be preserved")
    }

    @Test
    fun testUntaggedMessagesAppearForAllProfiles() {
        val profile1 = "profile-1"
        val profile2 = "profile-2"

        // Write a file with an untagged message directly (simulates pre-existing data)
        testFile.writeText(
            """
            {
                "messages": [
                    {
                        "id": "global-1",
                        "name": "Global Message",
                        "userTags": [],
                        "fields": [{"tag": "35", "value": "D"}],
                        "createdAt": 1000,
                        "lastUsedAt": 1000,
                        "modifiedAt": 1000,
                        "version": 1,
                        "isFavorite": false
                    },
                    {
                        "id": "p1-msg",
                        "name": "Profile 1 Message",
                        "userTags": ["$profile1"],
                        "fields": [{"tag": "35", "value": "8"}],
                        "createdAt": 1000,
                        "lastUsedAt": 1000,
                        "modifiedAt": 1000,
                        "version": 1,
                        "isFavorite": false
                    }
                ],
                "version": 1
            }
            """.trimIndent(),
        )

        // Global message should appear for both profiles
        val profile1Messages = service.loadMessagesForProfile(profile1)
        val profile2Messages = service.loadMessagesForProfile(profile2)

        assertEquals(2, profile1Messages.size, "Profile 1 should see its own message + global message")
        assertEquals(1, profile2Messages.size, "Profile 2 should see the global message")
        assertTrue(profile1Messages.any { it.name == "Global Message" })
        assertTrue(profile2Messages.any { it.name == "Global Message" })
    }

    @Test
    fun testDeleteFromProfileWithMultipleMessages() {
        val profileId = "test-profile-delete-multi"

        val message1 =
            SavedFixMessage(
                id = "keep-1",
                name = "Keep Message 1",
                userTags = setOf(profileId),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )

        val message2 =
            SavedFixMessage(
                id = "delete-2",
                name = "Delete Message 2",
                userTags = setOf(profileId),
                fields = listOf(SavedFixField(tag = "35", value = "8")),
            )

        val message3 =
            SavedFixMessage(
                id = "keep-3",
                name = "Keep Message 3",
                userTags = setOf(profileId),
                fields = listOf(SavedFixField(tag = "35", value = "G")),
            )

        service.saveMessage(profileId, message1).getOrThrow()
        service.saveMessage(profileId, message2).getOrThrow()
        service.saveMessage(profileId, message3).getOrThrow()

        val result = service.deleteMessage(profileId, "delete-2").getOrThrow()

        assertEquals(2, result.size, "Should have 2 messages remaining")
        assertTrue(result.none { it.id == "delete-2" }, "Deleted message should not be in results")
        assertTrue(result.any { it.name == "Keep Message 1" })
        assertTrue(result.any { it.name == "Keep Message 3" })
    }
}
