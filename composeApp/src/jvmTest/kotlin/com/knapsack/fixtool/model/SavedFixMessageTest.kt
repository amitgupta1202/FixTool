package com.knapsack.fixtool.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for SavedFixMessage model, including favorites functionality
 * and message type extraction.
 */
class SavedFixMessageTest {

    // ========================================
    // isFavorite field tests
    // ========================================

    @Test
    fun `isFavorite defaults to false`() {
        val message = SavedFixMessage(
            name = "TestMessage",
            fields = emptyList()
        )
        assertFalse(message.isFavorite)
    }

    @Test
    fun `isFavorite can be set to true`() {
        val message = SavedFixMessage(
            name = "TestMessage",
            fields = emptyList(),
            isFavorite = true
        )
        assertTrue(message.isFavorite)
    }

    @Test
    fun `isFavorite can be toggled via copy`() {
        val message = SavedFixMessage(
            name = "TestMessage",
            fields = emptyList(),
            isFavorite = false
        )

        val toggled = message.copy(isFavorite = !message.isFavorite)
        assertTrue(toggled.isFavorite)

        val toggledBack = toggled.copy(isFavorite = !toggled.isFavorite)
        assertFalse(toggledBack.isFavorite)
    }

    // ========================================
    // getMessageType tests
    // ========================================

    @Test
    fun `getMessageType returns null when no tag 35`() {
        val message = SavedFixMessage(
            name = "TestMessage",
            fields = listOf(
                SavedFixField(tag = "49", value = "SENDER"),
                SavedFixField(tag = "56", value = "TARGET")
            )
        )
        assertNull(message.getMessageType())
    }

    @Test
    fun `getMessageType returns value of tag 35`() {
        val message = SavedFixMessage(
            name = "QuoteRequest",
            fields = listOf(
                SavedFixField(tag = "35", value = "R"),
                SavedFixField(tag = "49", value = "SENDER")
            )
        )
        assertEquals("R", message.getMessageType())
    }

    @Test
    fun `getMessageType returns first tag 35 if multiple exist`() {
        val message = SavedFixMessage(
            name = "TestMessage",
            fields = listOf(
                SavedFixField(tag = "35", value = "D"),
                SavedFixField(tag = "35", value = "8")
            )
        )
        assertEquals("D", message.getMessageType())
    }

    @Test
    fun `getMessageType returns empty string for empty tag 35`() {
        val message = SavedFixMessage(
            name = "TestMessage",
            fields = listOf(
                SavedFixField(tag = "35", value = "")
            )
        )
        assertEquals("", message.getMessageType())
    }

    @Test
    fun `getMessageType returns null for empty fields list`() {
        val message = SavedFixMessage(
            name = "TestMessage",
            fields = emptyList()
        )
        assertNull(message.getMessageType())
    }

    // ========================================
    // getAllUserTags tests
    // ========================================

    @Test
    fun `getAllUserTags returns empty set when no tags`() {
        val message = SavedFixMessage(
            name = "TestMessage",
            fields = emptyList(),
            userTags = emptySet()
        )
        assertTrue(message.getAllUserTags().isEmpty())
    }

    @Test
    fun `getAllUserTags returns userTags`() {
        val message = SavedFixMessage(
            name = "TestMessage",
            fields = emptyList(),
            userTags = setOf("profile1", "profile2")
        )
        assertEquals(setOf("profile1", "profile2"), message.getAllUserTags())
    }

    // ========================================
    // SavedFixField excluded tests
    // ========================================

    @Test
    fun `SavedFixField excluded defaults to false`() {
        val field = SavedFixField(tag = "35", value = "D")
        assertFalse(field.excluded)
    }

    @Test
    fun `SavedFixField excluded can be set to true`() {
        val field = SavedFixField(tag = "35", value = "D", excluded = true)
        assertTrue(field.excluded)
    }

    // ========================================
    // Data class equality tests
    // ========================================

    @Test
    fun `messages with same id are not equal if other fields differ`() {
        val id = "test-id"
        val message1 = SavedFixMessage(
            id = id,
            name = "Message1",
            fields = emptyList(),
            isFavorite = false
        )
        val message2 = SavedFixMessage(
            id = id,
            name = "Message1",
            fields = emptyList(),
            isFavorite = true
        )
        // Data class equality checks all fields
        assertFalse(message1 == message2)
    }

    @Test
    fun `message copy preserves id`() {
        val message = SavedFixMessage(
            name = "TestMessage",
            fields = emptyList()
        )
        val copied = message.copy(isFavorite = true)
        assertEquals(message.id, copied.id)
    }
}
