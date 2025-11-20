package com.knapsack.fixtool.model

/**
 * Represents the state of a message in the editor
 * Similar to a file editor's state (new, saved, modified)
 */
sealed class MessageEditorState {
    /**
     * New message that has never been saved
     */
    data object New : MessageEditorState()

    /**
     * Existing message that is unmodified since last load/save
     * @param messageId The ID of the loaded message
     * @param messageName The name of the loaded message
     * @param userTags The tags associated with this message
     */
    data class Clean(
        val messageId: String,
        val messageName: String,
        val userTags: Set<String> = emptySet(),
    ) : MessageEditorState()

    /**
     * Existing message that has been modified since load/save
     * @param messageId The ID of the loaded message
     * @param messageName The name of the loaded message
     * @param userTags The tags associated with this message
     */
    data class Dirty(
        val messageId: String,
        val messageName: String,
        val userTags: Set<String> = emptySet(),
    ) : MessageEditorState()

    /**
     * Returns true if the message has unsaved changes
     */
    fun isDirty(): Boolean = this is Dirty

    /**
     * Returns true if this is a new, unsaved message
     */
    fun isNew(): Boolean = this is New

    /**
     * Returns the message ID if this is a saved message (Clean or Dirty)
     */
    fun messageIdOrNull(): String? =
        when (this) {
            is Clean -> messageId
            is Dirty -> messageId
            is New -> null
        }

    /**
     * Returns the message name if this is a saved message (Clean or Dirty)
     */
    fun messageNameOrNull(): String? =
        when (this) {
            is Clean -> messageName
            is Dirty -> messageName
            is New -> null
        }

    /**
     * Returns the user tags if this is a saved message (Clean or Dirty)
     */
    fun allUserTags(): Set<String> =
        when (this) {
            is Clean -> userTags
            is Dirty -> userTags
            is New -> emptySet()
        }

    /**
     * Returns a display string for the current state
     */
    fun getDisplayText(): String =
        when (this) {
            is New -> "New Message"
            is Clean -> messageName
            is Dirty -> "$messageName (modified)"
        }
}
