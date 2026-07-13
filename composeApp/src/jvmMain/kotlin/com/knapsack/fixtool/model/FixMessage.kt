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
    /**
     * High-precision capture timestamp in microseconds since epoch.
     * Captured at the QuickFIX/J callback layer for accurate latency measurement.
     * Default of 0 indicates timestamp was not captured (backward compatibility).
     */
    val captureTimeMicros: Long = 0L,
    /**
     * The message as it actually was on the wire — **SOH-delimited**, unsubstituted. Null when the
     * transport could not hand one over.
     *
     * [rawMessage] is a *display* string: it replaces SOH with `|` so a human can read it. That
     * substitution is lossy, because `|` is an ordinary character inside a FIX value — a venue is
     * entitled to reject an order with `58=Rejected|insufficient margin`, and splitting that on `|`
     * yields a truncated Text field and a phantom one. Harmless while only a human was reading it;
     * not harmless now that the assertion engine reads the field list, where it means comparing an
     * assertion against a value the venue never sent.
     */
    val wireRaw: String? = null,
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
    fun isRejectionOrLogout(rejectionRules: List<RejectionRule> = RejectionRule.defaultRules()): Boolean =
        rejectionRules.any { rule ->
            rule.matches(quickfixMessage, messageType)
        }

    /**
     * Extract the value of a specific tag from this message.
     * Searches in header, body, and trailer.
     * Returns null if the tag is not found.
     */
    fun valueOfTag(tag: Int): String? =
        try {
            when {
                quickfixMessage.header.isSetField(tag) -> quickfixMessage.header.getString(tag)
                quickfixMessage.isSetField(tag) -> quickfixMessage.getString(tag)
                quickfixMessage.trailer.isSetField(tag) -> quickfixMessage.trailer.getString(tag)
                else -> null
            }
        } catch (e: Exception) {
            null
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
