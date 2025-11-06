package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class FixConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val config: FixConnectionConfig,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
)
