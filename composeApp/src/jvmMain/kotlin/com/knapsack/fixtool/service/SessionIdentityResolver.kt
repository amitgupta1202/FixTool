package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionConfig

/**
 * Resolves the per-session identity of a multi-session profile (sessionCount > 1).
 *
 * The identity fields (SenderCompID, TargetCompID, Username, Password) accept:
 * - a numbering pattern: "LOADGEN{n}" -> LOADGEN1, LOADGEN2, ... ("{nn}" zero-pads: LOADGEN01, ...)
 * - a comma-separated list: "SESS_A, SESS_B, SESS_C" -> one value per session slot
 * - a single plain value: shared by every session in the group
 *
 * When the resolved SenderCompID/TargetCompID pair is unique within the group, the configured
 * SessionQualifier is used as-is. When two slots share the same pair (single plain CompIDs),
 * a unique qualifier is derived per slot so QuickFIX/J session IDs and store files stay separate.
 */
object SessionIdentityResolver {
    private val NUMBER_PATTERN = Regex("""\{(n+)}""")

    /**
     * Resolves the connection config for one session slot (1-based) of a [sessionCount]-sized group.
     */
    fun resolve(config: FixConnectionConfig, slot: Int, sessionCount: Int): FixConnectionConfig {
        if (sessionCount <= 1) return config

        val sender = resolveValue(config.senderCompID, slot)
        val target = resolveValue(config.targetCompID, slot)

        // Derive a unique qualifier only if another slot resolves to the same sender/target pair
        val pairIsDuplicated =
            (1..sessionCount).any { other ->
                other != slot &&
                    resolveValue(config.senderCompID, other) == sender &&
                    resolveValue(config.targetCompID, other) == target
            }
        val qualifier =
            if (pairIsDuplicated) deriveQualifier(config.sessionQualifier, slot) else config.sessionQualifier

        return config.copy(
            senderCompID = sender,
            targetCompID = target,
            username = resolveValue(config.username, slot),
            password = resolveValue(config.password, slot),
            sessionQualifier = qualifier,
        )
    }

    /**
     * Validates the identity fields of a multi-session config.
     * @return error messages, empty when valid
     */
    fun validate(config: FixConnectionConfig, sessionCount: Int): List<String> {
        if (sessionCount <= 1) return emptyList()

        val errors = mutableListOf<String>()
        listOf(
            "SenderCompID" to config.senderCompID,
            "TargetCompID" to config.targetCompID,
            "Username" to config.username,
            "Password" to config.password,
        ).forEach { (label, value) ->
            val listSize = splitList(value).size
            if (listSize > 1 && listSize != sessionCount) {
                errors.add("$label: expected 1 or $sessionCount comma-separated values, got $listSize")
            }
        }
        return errors
    }

    /** A value is per-session when it is a list or contains a {n} numbering pattern. */
    fun isPerSession(value: String): Boolean = splitList(value).size > 1 || NUMBER_PATTERN.containsMatchIn(value)

    fun deriveQualifier(base: String, slot: Int): String = if (base.isBlank()) "S$slot" else "$base-$slot"

    private fun resolveValue(value: String, slot: Int): String {
        val values = splitList(value)
        val picked =
            when {
                values.size <= 1 -> value.trim()
                else -> values.getOrElse(slot - 1) { values.last() }
            }
        return NUMBER_PATTERN.replace(picked) { match ->
            slot.toString().padStart(match.groupValues[1].length, '0')
        }
    }

    private fun splitList(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
