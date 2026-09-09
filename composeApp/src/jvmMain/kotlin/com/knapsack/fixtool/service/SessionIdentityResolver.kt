package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionConfig

/**
 * Resolves the per-session identity of a connection profile.
 *
 * The identity fields (SenderCompID, TargetCompID, Username, Password) accept:
 * - a numbering pattern: "LOADGEN{n}" -> LOADGEN1, LOADGEN2, ... ("{nn}" zero-pads: LOADGEN01, ...)
 * - a comma-separated list: "SESS_A, SESS_B, SESS_C" -> one value per session slot
 * - a single plain value: shared by every session in the group
 *
 * **The numbering pattern is resolved at every session count**, one session being slot 1. A venue hands out
 * load-test accounts one session at a time, so "initiator_org_1_user_{n}@load.test" on a one-session profile
 * is an ordinary identity and has to log on as user_1 rather than as the literal text.
 *
 * Lists and derived qualifiers are the multi-lane part, and they stay behind sessionCount > 1. A comma list
 * exists to give several lanes different values, so at one session the value keeps its commas verbatim, which
 * is also what stops a password that legitimately contains a comma from being cut short. In the same way, when
 * the resolved SenderCompID/TargetCompID pair is unique within the group the configured SessionQualifier is
 * used as-is, and only when two slots share the same pair (single plain CompIDs) is a unique qualifier derived
 * per slot so QuickFIX/J session IDs and store files stay separate.
 */
object SessionIdentityResolver {
    private val NUMBER_PATTERN = Regex("""\{(n+)}""")

    /**
     * Resolves the connection config for one session slot (1-based) of a [sessionCount]-sized group.
     * A [slot] of 0 is the convention a single-session profile uses, and it resolves as slot 1.
     */
    fun resolve(config: FixConnectionConfig, slot: Int, sessionCount: Int): FixConnectionConfig {
        val lane = slot.coerceAtLeast(1)
        val sender = resolveValue(config.senderCompID, lane, sessionCount)
        val target = resolveValue(config.targetCompID, lane, sessionCount)

        // Derive a unique qualifier only if another slot resolves to the same sender/target pair.
        // With one slot there is no other slot to collide with, so the configured qualifier stands.
        val pairIsDuplicated =
            sessionCount > 1 &&
                (1..sessionCount).any { other ->
                    other != lane &&
                        resolveValue(config.senderCompID, other, sessionCount) == sender &&
                        resolveValue(config.targetCompID, other, sessionCount) == target
                }
        val qualifier =
            if (pairIsDuplicated) deriveQualifier(config.sessionQualifier, lane) else config.sessionQualifier

        return config.copy(
            senderCompID = sender,
            targetCompID = target,
            username = resolveValue(config.username, lane, sessionCount),
            password = resolveValue(config.password, lane, sessionCount),
            sessionQualifier = qualifier,
        )
    }

    /**
     * Validates the identity fields of a multi-session config.
     *
     * Silent at one session, because there is nothing there to disagree with a lane count: a "{n}" renders as
     * slot 1, and a comma stays in the value instead of being read as a list.
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

    /**
     * **The numbering pattern applies at any count, the list only above one lane.** At one session the value
     * is taken exactly as configured, commas and surrounding whitespace included, so the only thing that can
     * change it is a "{n}" of its own.
     */
    private fun resolveValue(value: String, slot: Int, sessionCount: Int): String {
        val picked =
            if (sessionCount <= 1) {
                value
            } else {
                val values = splitList(value)
                if (values.size <= 1) value.trim() else values.getOrElse(slot - 1) { values.last() }
            }
        return NUMBER_PATTERN.replace(picked) { match ->
            slot.toString().padStart(match.groupValues[1].length, '0')
        }
    }

    private fun splitList(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
