package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Service for persisting and loading saved FIX messages organized by profile
 */
class SavedMessagesService(
    private val onError: ((String) -> Unit)? = null,
    customPath: String = "",
) {
    private val logger = NotifyingLogger(SavedMessagesService::class.java, onError)

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val savedMessagesFile =
        if (customPath.isNotBlank()) {
            File(customPath)
        } else {
            File(System.getProperty("user.home"), ".fixtool/saved_messages.json")
        }

    init {
        // Ensure directory exists
        savedMessagesFile.parentFile?.mkdirs()
    }

    @Serializable
    private data class SavedMessagesContainer(
        // Legacy: Map of profileId to messages (for backwards compatibility)
        val messagesByProfile: Map<String, List<SavedFixMessage>> = emptyMap(),
        // New: Flat list of all messages with multi-tag support
        val messages: List<SavedFixMessage> = emptyList(),
    )

    /**
     * Loads all saved messages from disk and migrates legacy data if needed
     */
    private fun loadAll(): SavedMessagesContainer =
        try {
            if (!savedMessagesFile.exists()) {
                SavedMessagesContainer()
            } else {
                val content = savedMessagesFile.readText()
                val container = json.decodeFromString<SavedMessagesContainer>(content)

                // Migrate legacy data: if we have messagesByProfile but no messages, migrate them
                if (container.messages.isEmpty() && container.messagesByProfile.isNotEmpty()) {
                    val migratedMessages = container.messagesByProfile.flatMap { (profileId, messages) ->
                        messages.map { message ->
                            // Ensure old messages have the profileId in their userTags
                            if (message.userTags.isEmpty()) {
                                message.copy(userTags = setOf(profileId))
                            } else {
                                message
                            }
                        }
                    }.distinctBy { it.id } // Remove duplicates by ID

                    SavedMessagesContainer(
                        messagesByProfile = emptyMap(), // Clear legacy data after migration
                        messages = migratedMessages,
                    )
                } else {
                    container
                }
            }
        } catch (e: Exception) {
            val errorMsg = "Failed to load saved messages: ${e.message}"
            logger.error(errorMsg, e, notifyUser = true)
            SavedMessagesContainer()
        }

    /**
     * Saves all messages to disk
     * @return true if save succeeded, false if failed
     */
    private fun saveAll(container: SavedMessagesContainer): Boolean =
        try {
            val content = json.encodeToString(container)
            savedMessagesFile.writeText(content)
            true
        } catch (e: Exception) {
            val errorMsg = "Failed to save messages: ${e.message}"
            logger.error(errorMsg, e, notifyUser = true)
            false
        }

    /**
     * Loads saved messages for a specific profile (filters by userTags)
     */
    fun loadMessagesForProfile(profileId: String): List<SavedFixMessage> {
        val container = loadAll()
        // Filter messages that have this profileId in their userTags
        return container.messages.filter { message ->
            message.getAllUserTags().contains(profileId)
        }
    }

    /**
     * Saves a message (supports multi-user tags)
     * @return Result with updated message list for the profile on success, or failure with exception
     */
    fun saveMessage(profileId: String, message: SavedFixMessage): Result<List<SavedFixMessage>> {
        val container = loadAll()
        val allMessages = container.messages.toMutableList()

        // Check if message with same ID exists, update it, otherwise add new
        val existingIndex = allMessages.indexOfFirst { it.id == message.id }
        if (existingIndex >= 0) {
            allMessages[existingIndex] = message
        } else {
            allMessages.add(message)
        }

        val updatedContainer =
            SavedMessagesContainer(
                messagesByProfile = emptyMap(), // Legacy field no longer used
                messages = allMessages,
            )

        return if (saveAll(updatedContainer)) {
            // Return messages that are relevant to this profile
            val profileMessages = allMessages.filter { it.getAllUserTags().contains(profileId) }
            Result.success(profileMessages)
        } else {
            Result.failure(java.io.IOException("Failed to save message"))
        }
    }

    /**
     * Deletes a saved message (removes from all users)
     * @return Result with updated message list for the profile on success, or failure with exception
     */
    fun deleteMessage(profileId: String, messageId: String): Result<List<SavedFixMessage>> {
        val container = loadAll()
        val allMessages = container.messages.filterNot { it.id == messageId }

        val updatedContainer =
            SavedMessagesContainer(
                messagesByProfile = emptyMap(), // Legacy field no longer used
                messages = allMessages,
            )

        return if (saveAll(updatedContainer)) {
            // Return messages that are relevant to this profile
            val profileMessages = allMessages.filter { it.getAllUserTags().contains(profileId) }
            Result.success(profileMessages)
        } else {
            Result.failure(java.io.IOException("Failed to delete message"))
        }
    }

    /**
     * Updates the lastUsedAt timestamp for a message
     * @return Result with updated message list for the profile on success, or failure with exception
     */
    fun markMessageAsUsed(profileId: String, messageId: String): Result<List<SavedFixMessage>> {
        val container = loadAll()
        val allMessages = container.messages.toMutableList()

        val messageIndex = allMessages.indexOfFirst { it.id == messageId }
        if (messageIndex >= 0) {
            val message = allMessages[messageIndex]
            allMessages[messageIndex] = message.copy(lastUsedAt = System.currentTimeMillis())

            val updatedContainer =
                SavedMessagesContainer(
                    messagesByProfile = emptyMap(), // Legacy field no longer used
                    messages = allMessages,
                )

            return if (saveAll(updatedContainer)) {
                // Return messages that are relevant to this profile
                val profileMessages = allMessages.filter { it.getAllUserTags().contains(profileId) }
                Result.success(profileMessages)
            } else {
                Result.failure(java.io.IOException("Failed to mark message as used"))
            }
        }

        // Message not found, return current profile messages
        val profileMessages = allMessages.filter { it.getAllUserTags().contains(profileId) }
        return Result.success(profileMessages)
    }
}
