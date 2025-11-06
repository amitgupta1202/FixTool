package com.knapsack.fixtool.model

import java.util.*

/**
 * Represents a notification message to be displayed to the user
 */
data class Notification(
    val id: String = UUID.randomUUID().toString(),
    val message: String,
    val type: NotificationType = NotificationType.ERROR,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Type of notification
 */
enum class NotificationType {
    ERROR,
    WARNING,
    INFO,
    SUCCESS,
}
