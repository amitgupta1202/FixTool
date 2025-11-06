package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class SavedFixMessage(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val profileId: String = "",
    val fields: List<SavedFixField>,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class SavedFixField(
    val tag: String,
    val value: String,
    val excluded: Boolean = false,
)
