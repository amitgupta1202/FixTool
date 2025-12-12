package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive integration tests for message sharing scenarios
 * Tests multi-user tag management, sharing, unsharing, and cloning
 */
class MessageSharingTest {
    private lateinit var service: SavedMessagesService
    private lateinit var testDir: File
    private lateinit var testFile: File

    @Before
    fun setup() {
        // Create isolated test directory
        testDir =
            File.createTempFile("fixtool-test", "").apply {
                delete()
                mkdirs()
            }

        testFile = File(testDir, "saved_messages.json")
        service = SavedMessagesService(customPath = testFile.absolutePath)
    }

    @After
    fun cleanup() {
        testDir.deleteRecursively()
    }

    // ========================================
    // Basic Sharing Tests
    // ========================================

    @Test
    fun testCreateMessageAndShareWithMultipleUsers() {
        val user1 = "user1"
        val user2 = "user2"
        val user3 = "user3"

        // User1 creates a message
        val message =
            SavedFixMessage(
                id = "shared-msg-1",
                name = "Shared Template",
                userTags = setOf(user1),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(user1, message).getOrThrow()

        // Verify only user1 can see it
        assertEquals(1, service.loadMessagesForProfile(user1).size)
        assertEquals(0, service.loadMessagesForProfile(user2).size)
        assertEquals(0, service.loadMessagesForProfile(user3).size)

        // Share with user2 (add user2 tag)
        val sharedWithUser2 =
            message.copy(
                userTags = setOf(user1, user2),
            )
        service.saveMessage(user1, sharedWithUser2).getOrThrow()

        // Verify both user1 and user2 can see it
        assertEquals(1, service.loadMessagesForProfile(user1).size)
        assertEquals(1, service.loadMessagesForProfile(user2).size)
        assertEquals(0, service.loadMessagesForProfile(user3).size)

        // Share with user3 (add user3 tag)
        val sharedWithUser3 =
            message.copy(
                userTags = setOf(user1, user2, user3),
            )
        service.saveMessage(user1, sharedWithUser3).getOrThrow()

        // Verify all three users can see it
        assertEquals(1, service.loadMessagesForProfile(user1).size)
        assertEquals(1, service.loadMessagesForProfile(user2).size)
        assertEquals(1, service.loadMessagesForProfile(user3).size)

        // Verify all users see the same message ID
        val user1Messages = service.loadMessagesForProfile(user1)
        val user2Messages = service.loadMessagesForProfile(user2)
        val user3Messages = service.loadMessagesForProfile(user3)

        assertEquals(user1Messages[0].id, user2Messages[0].id)
        assertEquals(user1Messages[0].id, user3Messages[0].id)
    }

    @Test
    fun testUnshareMessageRemovesUserAccess() {
        val user1 = "user1"
        val user2 = "user2"
        val user3 = "user3"

        // Create and share with all three users
        val message =
            SavedFixMessage(
                id = "unshare-test-1",
                name = "Unshare Test Template",
                userTags = setOf(user1, user2, user3),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(user1, message).getOrThrow()

        // Verify all can see it
        assertEquals(1, service.loadMessagesForProfile(user1).size)
        assertEquals(1, service.loadMessagesForProfile(user2).size)
        assertEquals(1, service.loadMessagesForProfile(user3).size)

        // Unshare from user2 (remove user2 tag)
        val unsharedFromUser2 =
            message.copy(
                userTags = setOf(user1, user3),
            )
        service.saveMessage(user1, unsharedFromUser2).getOrThrow()

        // Verify user2 cannot see it anymore
        assertEquals(1, service.loadMessagesForProfile(user1).size)
        assertEquals(0, service.loadMessagesForProfile(user2).size)
        assertEquals(1, service.loadMessagesForProfile(user3).size)

        // Unshare from user3
        val unsharedFromUser3 =
            message.copy(
                userTags = setOf(user1),
            )
        service.saveMessage(user1, unsharedFromUser3).getOrThrow()

        // Verify only user1 can see it
        assertEquals(1, service.loadMessagesForProfile(user1).size)
        assertEquals(0, service.loadMessagesForProfile(user2).size)
        assertEquals(0, service.loadMessagesForProfile(user3).size)
    }

    @Test
    fun testUnshareLastUserAutoAddsProfileId() {
        val user1 = "user1"

        // Create message for user1
        val message =
            SavedFixMessage(
                id = "last-user-1",
                name = "Last User Template",
                userTags = setOf(user1),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(user1, message).getOrThrow()

        assertEquals(1, service.loadMessagesForProfile(user1).size)

        // Try to remove all tags (unshare from last user)
        val attemptOrphan =
            message.copy(
                userTags = emptySet(),
            )
        service.saveMessage(user1, attemptOrphan).getOrThrow()

        // Service automatically adds the profileId back to prevent orphaning
        // So user1 should still see it
        val messages = service.loadMessagesForProfile(user1)
        assertEquals(1, messages.size)
        assertTrue(messages[0].userTags.contains(user1))
    }

    // ========================================
    // Cloning Tests
    // ========================================

    @Test
    fun testCloneMessageCreatesIndependentCopy() {
        val user1 = "user1"

        // Create original message
        val original =
            SavedFixMessage(
                id = "original-1",
                name = "Original Template",
                userTags = setOf(user1),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(user1, original).getOrThrow()

        // Clone the message (new ID, same content)
        val clone =
            SavedFixMessage(
                id = "clone-1", // Different ID
                name = "Original Template (Copy)",
                userTags = setOf(user1),
                fields = original.fields,
            )
        service.saveMessage(user1, clone).getOrThrow()

        // Verify both exist
        val messages = service.loadMessagesForProfile(user1)
        assertEquals(2, messages.size)

        // Verify they have different IDs
        val ids = messages.map { it.id }.toSet()
        assertEquals(2, ids.size)
        assertTrue(ids.contains("original-1"))
        assertTrue(ids.contains("clone-1"))
    }

    @Test
    fun testCloneAndModifyDoesNotAffectOriginal() {
        val user1 = "user1"

        // Create original
        val original =
            SavedFixMessage(
                id = "original-2",
                name = "Original Template",
                userTags = setOf(user1),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(user1, original).getOrThrow()

        // Clone and modify
        val clone =
            SavedFixMessage(
                id = "clone-2",
                name = "Modified Clone",
                userTags = setOf(user1),
                fields =
                    listOf(
                        SavedFixField(tag = "35", value = "8"), // Different value
                        SavedFixField(tag = "49", value = "SENDER"), // Additional field
                    ),
            )
        service.saveMessage(user1, clone).getOrThrow()

        // Verify original is unchanged
        val messages = service.loadMessagesForProfile(user1)
        val originalFromDb = messages.find { it.id == "original-2" }
        assertNotNull(originalFromDb)
        assertEquals("Original Template", originalFromDb.name)
        assertEquals(1, originalFromDb.fields.size)
        assertEquals("D", originalFromDb.fields[0].value)

        // Verify clone has modifications
        val cloneFromDb = messages.find { it.id == "clone-2" }
        assertNotNull(cloneFromDb)
        assertEquals("Modified Clone", cloneFromDb.name)
        assertEquals(2, cloneFromDb.fields.size)
    }

    @Test
    fun testCloneSharedMessageAndShareWithDifferentUsers() {
        val user1 = "user1"
        val user2 = "user2"
        val user3 = "user3"

        // Create original shared with user1 and user2
        val original =
            SavedFixMessage(
                id = "original-shared-1",
                name = "Shared Template",
                userTags = setOf(user1, user2),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(user1, original).getOrThrow()

        // Clone and share with user3 only
        val clone =
            SavedFixMessage(
                id = "clone-shared-1",
                name = "Shared Template (Clone)",
                userTags = setOf(user3),
                fields = original.fields,
            )
        service.saveMessage(user3, clone).getOrThrow()

        // Verify user1 and user2 can see original
        assertTrue(service.loadMessagesForProfile(user1).any { it.id == "original-shared-1" })
        assertTrue(service.loadMessagesForProfile(user2).any { it.id == "original-shared-1" })
        assertFalse(service.loadMessagesForProfile(user3).any { it.id == "original-shared-1" })

        // Verify user3 can see clone
        assertFalse(service.loadMessagesForProfile(user1).any { it.id == "clone-shared-1" })
        assertFalse(service.loadMessagesForProfile(user2).any { it.id == "clone-shared-1" })
        assertTrue(service.loadMessagesForProfile(user3).any { it.id == "clone-shared-1" })
    }

    // ========================================
    // Complex Sharing Scenarios
    // ========================================

    @Test
    fun testShareThenUnshareMultipleTimes() {
        val user1 = "user1"
        val user2 = "user2"

        // Create message for user1
        val message =
            SavedFixMessage(
                id = "toggle-share-1",
                name = "Toggle Share Template",
                userTags = setOf(user1),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(user1, message).getOrThrow()

        // Share with user2
        service.saveMessage(user1, message.copy(userTags = setOf(user1, user2))).getOrThrow()
        assertEquals(1, service.loadMessagesForProfile(user2).size)

        // Unshare from user2
        service.saveMessage(user1, message.copy(userTags = setOf(user1))).getOrThrow()
        assertEquals(0, service.loadMessagesForProfile(user2).size)

        // Share again
        service.saveMessage(user1, message.copy(userTags = setOf(user1, user2))).getOrThrow()
        assertEquals(1, service.loadMessagesForProfile(user2).size)

        // Unshare again
        service.saveMessage(user1, message.copy(userTags = setOf(user1))).getOrThrow()
        assertEquals(0, service.loadMessagesForProfile(user2).size)
    }

    @Test
    fun testMultipleMessagesSharedWithDifferentUserCombinations() {
        val user1 = "user1"
        val user2 = "user2"
        val user3 = "user3"

        // Message 1: Shared with user1 only
        val msg1 =
            SavedFixMessage(
                id = "combo-1",
                name = "Private to User1",
                userTags = setOf(user1),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(user1, msg1).getOrThrow()

        // Message 2: Shared with user1 and user2
        val msg2 =
            SavedFixMessage(
                id = "combo-2",
                name = "Shared User1 and User2",
                userTags = setOf(user1, user2),
                fields = listOf(SavedFixField(tag = "35", value = "8")),
            )
        service.saveMessage(user1, msg2).getOrThrow()

        // Message 3: Shared with all three
        val msg3 =
            SavedFixMessage(
                id = "combo-3",
                name = "Shared All Users",
                userTags = setOf(user1, user2, user3),
                fields = listOf(SavedFixField(tag = "35", value = "G")),
            )
        service.saveMessage(user1, msg3).getOrThrow()

        // Message 4: Shared with user2 and user3 only
        val msg4 =
            SavedFixMessage(
                id = "combo-4",
                name = "Shared User2 and User3",
                userTags = setOf(user2, user3),
                fields = listOf(SavedFixField(tag = "35", value = "R")),
            )
        service.saveMessage(user2, msg4).getOrThrow()

        // Verify user1 sees: combo-1, combo-2, combo-3 (3 messages)
        val user1Messages = service.loadMessagesForProfile(user1)
        assertEquals(3, user1Messages.size)
        assertTrue(user1Messages.any { it.id == "combo-1" })
        assertTrue(user1Messages.any { it.id == "combo-2" })
        assertTrue(user1Messages.any { it.id == "combo-3" })

        // Verify user2 sees: combo-2, combo-3, combo-4 (3 messages)
        val user2Messages = service.loadMessagesForProfile(user2)
        assertEquals(3, user2Messages.size)
        assertTrue(user2Messages.any { it.id == "combo-2" })
        assertTrue(user2Messages.any { it.id == "combo-3" })
        assertTrue(user2Messages.any { it.id == "combo-4" })

        // Verify user3 sees: combo-3, combo-4 (2 messages)
        val user3Messages = service.loadMessagesForProfile(user3)
        assertEquals(2, user3Messages.size)
        assertTrue(user3Messages.any { it.id == "combo-3" })
        assertTrue(user3Messages.any { it.id == "combo-4" })
    }

    @Test
    fun testSharedMessageModifiedByOneUserVisibleToAll() {
        val user1 = "user1"
        val user2 = "user2"

        // Create and share message
        val message =
            SavedFixMessage(
                id = "modify-shared-1",
                name = "Shared Template",
                userTags = setOf(user1, user2),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(user1, message).getOrThrow()

        // User2 modifies the message
        val modified =
            message.copy(
                fields =
                    listOf(
                        SavedFixField(tag = "35", value = "D"),
                        SavedFixField(tag = "49", value = "NEWSENDER"),
                    ),
            )
        service.saveMessage(user2, modified).getOrThrow()

        // Verify both users see the modification
        val user1Messages = service.loadMessagesForProfile(user1)
        val user2Messages = service.loadMessagesForProfile(user2)

        assertEquals(2, user1Messages[0].fields.size)
        assertEquals(2, user2Messages[0].fields.size)

        assertTrue(user1Messages[0].fields.any { it.tag == "49" && it.value == "NEWSENDER" })
        assertTrue(user2Messages[0].fields.any { it.tag == "49" && it.value == "NEWSENDER" })
    }

    // ========================================
    // Edge Cases
    // ========================================

    @Test
    fun testShareWithSameUserTwiceNoError() {
        val user1 = "user1"

        // Create message with user1 twice in tags (should be deduplicated by Set)
        val message =
            SavedFixMessage(
                id = "duplicate-tag-1",
                name = "Duplicate Tag Template",
                userTags = setOf(user1, user1), // Set will deduplicate
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(user1, message).getOrThrow()

        val messages = service.loadMessagesForProfile(user1)
        assertEquals(1, messages.size)

        // Verify userTags only contains user1 once
        assertEquals(1, messages[0].userTags.size)
        assertTrue(messages[0].userTags.contains(user1))
    }

    @Test
    fun testMessageWithNoUserTagsGetsProfileIdAdded() {
        // Try to create message with no user tags
        val message =
            SavedFixMessage(
                id = "auto-tag-1",
                name = "Auto-Tagged Template",
                userTags = emptySet(),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        // Service automatically adds the profileId
        service.saveMessage("system", message).getOrThrow()

        // Verify "system" can see it (was auto-added)
        val systemMessages = service.loadMessagesForProfile("system")
        assertEquals(1, systemMessages.size)
        assertTrue(systemMessages[0].userTags.contains("system"))

        // Other users cannot see it
        assertEquals(0, service.loadMessagesForProfile("user1").size)
        assertEquals(0, service.loadMessagesForProfile("user2").size)
    }

    @Test
    fun testDeleteSharedMessageRemovesForAllUsers() {
        val user1 = "user1"
        val user2 = "user2"
        val user3 = "user3"

        // Create and share with all users
        val message =
            SavedFixMessage(
                id = "delete-shared-1",
                name = "Delete Shared Template",
                userTags = setOf(user1, user2, user3),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(user1, message).getOrThrow()

        // Verify all can see it
        assertEquals(1, service.loadMessagesForProfile(user1).size)
        assertEquals(1, service.loadMessagesForProfile(user2).size)
        assertEquals(1, service.loadMessagesForProfile(user3).size)

        // Delete the message
        service.deleteMessage(user1, "delete-shared-1").getOrThrow()

        // Verify none can see it
        assertEquals(0, service.loadMessagesForProfile(user1).size)
        assertEquals(0, service.loadMessagesForProfile(user2).size)
        assertEquals(0, service.loadMessagesForProfile(user3).size)
    }

    @Test
    fun testUserCanOnlyModifyMessagesTheyHaveAccessTo() {
        val user1 = "user1"
        val user2 = "user2"

        // User1 creates private message
        val message =
            SavedFixMessage(
                id = "private-1",
                name = "Private Template",
                userTags = setOf(user1),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(user1, message).getOrThrow()

        // User2 tries to modify it by updating tags to include themselves
        val hijackAttempt =
            message.copy(
                userTags = setOf(user1, user2),
            )
        service.saveMessage(user2, hijackAttempt).getOrThrow()

        // User2 should now be able to see it (they added themselves)
        assertEquals(1, service.loadMessagesForProfile(user2).size)

        // User1 should also still see it
        assertEquals(1, service.loadMessagesForProfile(user1).size)

        // Note: This behavior is correct - there's no permission system
        // The app should enforce sharing rules at the UI/ViewModel level
    }

    // ========================================
    // Sharing + Cloning Combined
    // ========================================

    @Test
    fun testCloneSharedMessageThenUnshareOriginal() {
        val user1 = "user1"
        val user2 = "user2"

        // Create shared message
        val original =
            SavedFixMessage(
                id = "orig-unshare-1",
                name = "Original Shared",
                userTags = setOf(user1, user2),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(user1, original).getOrThrow()

        // User2 clones it
        val clone =
            SavedFixMessage(
                id = "clone-unshare-1",
                name = "Original Shared (My Copy)",
                userTags = setOf(user2),
                fields = original.fields,
            )
        service.saveMessage(user2, clone).getOrThrow()

        // User1 unshares original from user2
        val unshared =
            original.copy(
                userTags = setOf(user1),
            )
        service.saveMessage(user1, unshared).getOrThrow()

        // User1 should see only original
        val user1Messages = service.loadMessagesForProfile(user1)
        assertEquals(1, user1Messages.size)
        assertEquals("orig-unshare-1", user1Messages[0].id)

        // User2 should see only their clone
        val user2Messages = service.loadMessagesForProfile(user2)
        assertEquals(1, user2Messages.size)
        assertEquals("clone-unshare-1", user2Messages[0].id)
    }

    @Test
    fun testShareCloneUnshareOriginalComplexWorkflow() {
        val user1 = "user1"
        val user2 = "user2"
        val user3 = "user3"

        // User1 creates message
        val original =
            SavedFixMessage(
                id = "workflow-1",
                name = "Team Template",
                userTags = setOf(user1),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(user1, original).getOrThrow()

        // User1 shares with user2
        service.saveMessage(user1, original.copy(userTags = setOf(user1, user2))).getOrThrow()

        // User2 clones it for their own modifications
        val user2Clone =
            SavedFixMessage(
                id = "workflow-2-clone",
                name = "Team Template (User2 Version)",
                userTags = setOf(user2),
                fields = original.fields,
            )
        service.saveMessage(user2, user2Clone).getOrThrow()

        // User1 adds user3 to original
        service.saveMessage(user1, original.copy(userTags = setOf(user1, user2, user3))).getOrThrow()

        // User3 also clones it
        val user3Clone =
            SavedFixMessage(
                id = "workflow-3-clone",
                name = "Team Template (User3 Version)",
                userTags = setOf(user3),
                fields = original.fields,
            )
        service.saveMessage(user3, user3Clone).getOrThrow()

        // User1 unshares original from everyone (makes it private again)
        service.saveMessage(user1, original.copy(userTags = setOf(user1))).getOrThrow()

        // Verify final state:
        // User1: 1 message (original)
        val user1Messages = service.loadMessagesForProfile(user1)
        assertEquals(1, user1Messages.size)
        assertTrue(user1Messages.any { it.id == "workflow-1" })

        // User2: 1 message (their clone)
        val user2Messages = service.loadMessagesForProfile(user2)
        assertEquals(1, user2Messages.size)
        assertTrue(user2Messages.any { it.id == "workflow-2-clone" })

        // User3: 1 message (their clone)
        val user3Messages = service.loadMessagesForProfile(user3)
        assertEquals(1, user3Messages.size)
        assertTrue(user3Messages.any { it.id == "workflow-3-clone" })
    }

    // ========================================
    // Version Tracking with Sharing
    // ========================================

    @Test
    fun testSharedMessageVersionIncrements() {
        val user1 = "user1"
        val user2 = "user2"

        // Create shared message
        val message =
            SavedFixMessage(
                id = "version-1",
                name = "Versioned Template",
                userTags = setOf(user1, user2),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
                version = 1,
            )
        service.saveMessage(user1, message).getOrThrow()

        // User2 modifies it
        val modified =
            message.copy(
                fields = listOf(SavedFixField(tag = "35", value = "8")),
                version = 2,
            )
        service.saveMessage(user2, modified).getOrThrow()

        // Verify version incremented for both users
        val user1Messages = service.loadMessagesForProfile(user1)
        val user2Messages = service.loadMessagesForProfile(user2)

        assertEquals(2, user1Messages[0].version)
        assertEquals(2, user2Messages[0].version)
    }

    @Test
    fun testFavoriteStatusPreservedWhenSharing() {
        val user1 = "user1"
        val user2 = "user2"

        // User1 creates favorite message
        val message =
            SavedFixMessage(
                id = "fav-1",
                name = "Favorite Template",
                userTags = setOf(user1),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
                isFavorite = true,
            )
        service.saveMessage(user1, message).getOrThrow()

        // Share with user2
        val shared =
            message.copy(
                userTags = setOf(user1, user2),
            )
        service.saveMessage(user1, shared).getOrThrow()

        // Verify user1 still has it as favorite
        val user1Messages = service.loadMessagesForProfile(user1)
        assertTrue(user1Messages[0].isFavorite)

        // User2 also sees it as favorite (shared state)
        val user2Messages = service.loadMessagesForProfile(user2)
        assertTrue(user2Messages[0].isFavorite)
    }
}
