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

    /**
     * Check if this message matches any rejection rule
     */
    fun isRejectionOrLogout(rejectionRules: List<RejectionRule> = RejectionRule.defaultRules()): Boolean {
        return rejectionRules.any { rule ->
            rule.matches(quickfixMessage, messageType)
        }
    }

    /**
     * Extract the value of a specific tag from this message.
     * Searches in header, body, and trailer.
     * Returns null if the tag is not found.
     */
    fun valueOfTag(tag: Int): String? {
        return try {
            when {
                quickfixMessage.header.isSetField(tag) -> quickfixMessage.header.getString(tag)
                quickfixMessage.isSetField(tag) -> quickfixMessage.getString(tag)
                quickfixMessage.trailer.isSetField(tag) -> quickfixMessage.trailer.getString(tag)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
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
