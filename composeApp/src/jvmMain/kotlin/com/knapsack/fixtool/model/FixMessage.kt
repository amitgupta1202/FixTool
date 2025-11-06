package com.knapsack.fixtool.model

import quickfix.Message
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

sealed class AppMessage(
    open val timestamp: LocalDateTime,
) {
    abstract fun toDisplayString(): String
}

data class Separator(
    override val timestamp: LocalDateTime,
) : AppMessage(timestamp) {
    override fun toDisplayString(): String = ""
}

data class FixMessage(
    override val timestamp: LocalDateTime,
    val direction: Direction,
    val rawMessage: String,
    val messageType: String = parseMessageType(rawMessage),
    val quickfixMessage: Message,
) : AppMessage(timestamp) {
    enum class Direction {
        INCOMING,
        OUTGOING,
    }

    override fun toDisplayString(): String {
        val time = timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        val dir = if (direction == Direction.INCOMING) "<<" else ">>"
        return "$time $dir [$messageType] $rawMessage"
    }

    fun isRejectionOrLogout(): Boolean {
        // Common FIX rejection message types:
        // 3 = Reject (Session-level reject)
        // j = Business Message Reject (Application-level reject)
        // 9 = Order Cancel Reject
        // 5 = Logout
        return messageType in setOf("3", "j", "9") // TODO: move to settings
    }

    companion object {
        fun parseMessageType(raw: String): String {
            // Extract message type from tag 35
            // Match everything that is NOT a SOH character (\x01) or pipe (|)
            val regex = "35=([^\\x01|]+)".toRegex()
            return regex.find(raw)?.groupValues?.get(1) ?: "UNKNOWN"
        }
    }
}
