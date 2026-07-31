package com.knapsack.fixtool.model

import quickfix.Message
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

sealed class AppMessage(
    open val timestamp: LocalDateTime,
) {
    /**
     * Identity for UI keying — unique per instance, stable for the instance's whole life, and
     * **independent of the message's position in any list**.
     *
     * The grid previously keyed rows on `"$timestamp-$index"`. The session list is a ring buffer
     * (`FixMessageSession.addMessage` drops the head at `bufferSize`), so the moment the buffer is
     * full every surviving message's index shifts by one and *every* key changes. That is not only a
     * LazyColumn thrash — `expandedMessages` and `selectedMessageIds` are keyed by the same string,
     * so expansion and selection silently reattach to whichever row inherited the old index.
     * Timestamp alone is no better: two messages inside the same millisecond collide.
     *
     * Deliberately declared in the class body, not the constructor, so it stays out of the data
     * subclasses' generated `equals`/`hashCode`/`copy`. Two messages with identical content must
     * still compare equal — `message == selectedMessage` in the grid depends on it.
     */
    val uid: Long = nextUid.getAndIncrement()

    abstract fun toDisplayString(): String

    private companion object {
        val nextUid = AtomicLong(0L)
    }
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
     * The message as it actually was on the wire — **SOH-delimited, in the order the bytes arrived**.
     * Null means *we do not know that order*, and nothing may guess it.
     *
     * Two properties, and the second is newer and easier to lose.
     *
     * **It is unsubstituted.** [rawMessage] is a *display* string: it replaces SOH with `|` so a human
     * can read it. That substitution is lossy, because `|` is an ordinary character inside a FIX value —
     * a venue is entitled to reject an order with `58=Rejected|insufficient margin`, and splitting that
     * on `|` yields a truncated Text field and a phantom one.
     *
     * **It is the venue's order, never a re-serialisation.** The assertion engine pairs the k-th row for
     * a tag with the k-th occurrence of it, and OPEN requires the expectation to be a *subsequence* of
     * the reply — so the field order here is load-bearing, not incidental. `quickfix.Message.toString()`
     * is **not** a legal source for an incoming message: with no `fieldOrder` set it re-emits the body in
     * ascending tag order and relocates every repeating group to the end (`FieldMap.calculateString`,
     * QFJ 2.3.2 — pinned by `WireOrderFallbackTest`). Filling this field with it would hand the engine a
     * message no venue sent, and the resulting red would look exactly like a venue regression.
     *
     * So the incoming path reads `quickfix.Message.toRawString()`, which is the bytes QFJ parsed, and
     * this field is null when even that is unavailable. Null is not a licence to fall back to the display
     * string — it is a fact the caller must report. See `FixMessageHelper.wireFields`.
     */
    val wireRaw: String? = null,
    /**
     * Why the venue sent this — the rule that chose it and what the book said at that moment, or the
     * shape a person picked. Null for everything else, which is every message on an initiator, every
     * received message, and any reply nobody claimed.
     *
     * Recorded rather than re-derived: see [SendReason] and decision 6a. This is the field that makes
     * an outgoing message able to answer "why" after the state that produced it has moved on.
     */
    val sendReason: SendReason? = null,
) : AppMessage(timestamp) {
    enum class Direction {
        INCOMING,
        OUTGOING,
    }

    override fun toDisplayString(): String {
        val time = timestamp.format(DISPLAY_TIME_FORMAT)
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
        /**
         * Hoisted: `toDisplayString` runs per visible row per frame, and once per message in every
         * filter and search pass. `DateTimeFormatter.ofPattern` re-parses the pattern on each call.
         */
        private val DISPLAY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun parseMessageType(raw: String): String {
            // Extract message type from tag 35
            // Match everything that is NOT a SOH character (\x01) or pipe (|)
            val regex = "35=([^\\x01|]+)".toRegex()
            return regex.find(raw)?.groupValues?.get(1) ?: "UNKNOWN"
        }
    }
}
