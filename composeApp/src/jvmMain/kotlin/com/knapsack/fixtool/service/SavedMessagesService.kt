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

    private val savedMessagesFile = if (customPath.isNotBlank()) {
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
        val messagesByProfile: Map<String, List<SavedFixMessage>> = emptyMap(),
    )

    /**
     * Loads all saved messages from disk
     */
    private fun loadAll(): SavedMessagesContainer =
        try {
            if (!savedMessagesFile.exists()) {
                SavedMessagesContainer()
            } else {
                val content = savedMessagesFile.readText()
                json.decodeFromString<SavedMessagesContainer>(content)
            }
        } catch (e: Exception) {
            val errorMsg = "Failed to load saved messages: ${e.message}"
            logger.error(errorMsg, e, notifyUser = true)
            SavedMessagesContainer()
        }

    /**
     * Saves all messages to disk
     */
    private fun saveAll(container: SavedMessagesContainer) {
        try {
            val content = json.encodeToString(container)
            savedMessagesFile.writeText(content)
        } catch (e: Exception) {
            val errorMsg = "Failed to save messages: ${e.message}"
            logger.error(errorMsg, e, notifyUser = true)
        }
    }

    /**
     * Loads saved messages for a specific profile
     */
    fun loadMessagesForProfile(profileId: String): List<SavedFixMessage> {
        val container = loadAll()
        return container.messagesByProfile[profileId] ?: emptyList()
    }

    /**
     * Saves a message for a specific profile
     */
    fun saveMessage(profileId: String, message: SavedFixMessage): List<SavedFixMessage> {
        val container = loadAll()
        val profileMessages = container.messagesByProfile[profileId]?.toMutableList() ?: mutableListOf()

        // Check if message with same ID exists, update it, otherwise add new
        val existingIndex = profileMessages.indexOfFirst { it.id == message.id }
        if (existingIndex >= 0) {
            profileMessages[existingIndex] = message
        } else {
            profileMessages.add(message)
        }

        val updatedContainer =
            SavedMessagesContainer(
                messagesByProfile = container.messagesByProfile + (profileId to profileMessages),
            )

        saveAll(updatedContainer)
        return profileMessages
    }

    /**
     * Deletes a saved message for a specific profile
     */
    fun deleteMessage(profileId: String, messageId: String): List<SavedFixMessage> {
        val container = loadAll()
        val profileMessages = container.messagesByProfile[profileId]?.filterNot { it.id == messageId } ?: emptyList()

        val updatedContainer =
            SavedMessagesContainer(
                messagesByProfile = container.messagesByProfile + (profileId to profileMessages),
            )

        saveAll(updatedContainer)
        return profileMessages
    }

    /**
     * Updates the lastUsedAt timestamp for a message
     */
    fun markMessageAsUsed(profileId: String, messageId: String): List<SavedFixMessage> {
        val container = loadAll()
        val profileMessages = container.messagesByProfile[profileId]?.toMutableList() ?: return emptyList()

        val messageIndex = profileMessages.indexOfFirst { it.id == messageId }
        if (messageIndex >= 0) {
            val message = profileMessages[messageIndex]
            profileMessages[messageIndex] = message.copy(lastUsedAt = System.currentTimeMillis())

            val updatedContainer =
                SavedMessagesContainer(
                    messagesByProfile = container.messagesByProfile + (profileId to profileMessages),
                )

            saveAll(updatedContainer)
        }

        return profileMessages
    }
}
