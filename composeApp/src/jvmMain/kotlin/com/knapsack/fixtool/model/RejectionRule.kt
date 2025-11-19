package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable
import quickfix.Message

/**
 * Defines a rule for detecting rejection messages.
 * Can match on message type (tag 35) alone or with one additional tag.
 */
@Serializable
data class RejectionRule(
    val messageType: String, // Tag 35 value (e.g., "3", "j", "9", "AZ")
    val additionalTag: Int? = null, // Optional additional tag to match (e.g., 905)
    val additionalValue: String? = null, // Required value for additional tag (e.g., "3")
) {
    /**
     * Check if a FIX message matches this rejection rule
     */
    fun matches(message: Message, messageType: String): Boolean {
        // First check if message type matches
        if (messageType != this.messageType) {
            return false
        }

        // If no additional tag requirement, just messageType match is sufficient
        if (additionalTag == null || additionalValue == null) {
            return true
        }

        // Check if additional tag matches
        return try {
            val actualValue =
                if (message.header.isSetField(additionalTag)) {
                    message.header.getString(additionalTag)
                } else if (message.isSetField(additionalTag)) {
                    message.getString(additionalTag)
                } else if (message.trailer.isSetField(additionalTag)) {
                    message.trailer.getString(additionalTag)
                } else {
                    null
                }
            actualValue == additionalValue
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Human-readable description of this rule
     */
    fun description(): String =
        if (additionalTag != null && additionalValue != null) {
            "35=$messageType and $additionalTag=$additionalValue"
        } else {
            "35=$messageType"
        }

    companion object {
        /**
         * Default rejection rules covering common FIX rejection message types
         */
        fun defaultRules(): List<RejectionRule> =
            listOf(
                // Session-level reject
                RejectionRule(messageType = "3"),
                // Business Message Reject (Application-level reject)
                RejectionRule(messageType = "j"),
                // Order Cancel Reject
                RejectionRule(messageType = "9"),
                // Custom rejection: 35=AZ and 905=3
                RejectionRule(
                    messageType = "AZ",
                    additionalTag = 905,
                    additionalValue = "3",
                ),
            )
    }
}
