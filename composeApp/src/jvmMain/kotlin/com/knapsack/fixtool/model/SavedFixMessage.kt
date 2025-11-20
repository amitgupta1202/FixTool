package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class SavedFixMessage(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    @Deprecated("Use userTags instead for multi-user support")
    val profileId: String = "",
    val userTags: Set<String> = if (profileId.isNotBlank()) setOf(profileId) else emptySet(),
    val fields: List<SavedFixField>,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val version: Int = 1,
) {
    /**
     * Returns all user tags (including migrated profileId if needed for backwards compatibility)
     */
    fun getAllUserTags(): Set<String> {
        val tags = userTags.toMutableSet()
        // For backwards compatibility: if profileId exists but not in tags, include it
        if (profileId.isNotBlank() && !tags.contains(profileId)) {
            tags.add(profileId)
        }
        return tags
    }
}

@Serializable
data class SavedFixField(
    val tag: String,
    val value: String,
    val excluded: Boolean = false,
)
