package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class SavedFixMessage(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val userTags: Set<String> = emptySet(),
    val fields: List<SavedFixField>,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val version: Int = 1,
    val isFavorite: Boolean = false,
) {
    /**
     * Returns all user tags
     */
    fun getAllUserTags(): Set<String> = userTags

    /**
     * Extracts the FIX message type (tag 35) from saved fields.
     * Returns null if no message type field is present.
     */
    fun getMessageType(): String? {
        return fields.find { it.tag == "35" }?.value
    }
}

@Serializable
data class SavedFixField(
    val tag: String,
    val value: String,
    val excluded: Boolean = false,
)
