package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/**
 * Service for persisting and loading saved FIX messages organized by profile
 */
class SavedMessagesService(
    private val onError: ((String) -> Unit)? = null,
    customPath: String = "",
) {
    private val logger = NotifyingLogger(SavedMessagesService::class.java, onError)

    companion object {
        /**
         * Current schema version for saved messages
         * Increment this when making breaking changes to the data format
         */
        private const val CURRENT_VERSION = 1

        /**
         * In-memory lock for thread-safe access within the same JVM
         * File locks only work across processes, not across threads
         */
        private val fileLock = Any()
    }

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
        val messages: List<SavedFixMessage> = emptyList(),
        val version: Int = CURRENT_VERSION,
    )

    /**
     * Loads all saved messages from disk with thread-safe locking
     */
    private fun loadAll(): SavedMessagesContainer =
        synchronized(fileLock) {
            if (!savedMessagesFile.exists()) {
                return SavedMessagesContainer()
            }

            try {
                // Read file content
                val content = savedMessagesFile.readText()
                val container = json.decodeFromString<SavedMessagesContainer>(content)

                // Check if migration is needed
                if (container.version < CURRENT_VERSION) {
                    logger.info("Migrating saved messages from version ${container.version} to $CURRENT_VERSION")
                    val migratedContainer = migrate(container)
                    // Save migrated data (saveAll is also synchronized)
                    saveAll(migratedContainer)
                    return migratedContainer
                }

                return container
            } catch (e: Exception) {
                val errorMsg = "Failed to load saved messages: ${e.message}"
                logger.error(errorMsg, e, notifyUser = true)
                return SavedMessagesContainer()
            }
        }

    /**
     * Migrates saved messages from an older version to the current version
     * Add migration logic here when schema changes are needed
     */
    private fun migrate(container: SavedMessagesContainer): SavedMessagesContainer {
        var migrated = container

        // Example migration pattern for future use:
        // if (migrated.version < 2) {
        //     migrated = migrateV1ToV2(migrated)
        // }
        // if (migrated.version < 3) {
        //     migrated = migrateV2ToV3(migrated)
        // }

        // Return with updated version
        return migrated.copy(version = CURRENT_VERSION)
    }

    /**
     * Saves all messages to disk with thread-safe locking
     * @return true if save succeeded, false if failed
     */
    private fun saveAll(container: SavedMessagesContainer): Boolean =
        synchronized(fileLock) {
            try {
                // Ensure parent directory exists
                savedMessagesFile.parentFile?.mkdirs()

                // Serialize to JSON
                val content = json.encodeToString(container)

                // Write to file atomically
                savedMessagesFile.writeText(content)

                return true
            } catch (e: Exception) {
                val errorMsg = "Failed to save messages: ${e.message}"
                logger.error(errorMsg, e, notifyUser = true)
                return false
            }
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

        // Ensure the message has the profileId in its userTags if no tags are set
        val messageToSave =
            if (message.userTags.isEmpty()) {
                message.copy(userTags = setOf(profileId))
            } else {
                message
            }

        // Check if message with same ID exists, update it, otherwise add new
        val existingIndex = allMessages.indexOfFirst { it.id == messageToSave.id }
        if (existingIndex >= 0) {
            allMessages[existingIndex] = messageToSave
        } else {
            allMessages.add(messageToSave)
        }

        val updatedContainer = SavedMessagesContainer(messages = allMessages)

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

        val updatedContainer = SavedMessagesContainer(messages = allMessages)

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

            val updatedContainer = SavedMessagesContainer(messages = allMessages)

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
