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

    /**
     * **The venue's bytes as fields**, parsed once — or null when we do not have those bytes.
     *
     * A message is immutable, so its fields are a constant, and this used to be recomputed from the raw
     * string on every ask. That was affordable when the asks were occasional and stopped being
     * affordable when the grid started asking three times per message per 100ms rebuild: measured over a
     * full 1,000-message buffer, a grouped rebuild allocated 12.4MB and took 3.9ms, ten times a second,
     * per pane. See `GroupingBenchmarkTest`.
     *
     * Declared in the class body rather than the constructor, exactly like [uid], so it stays out of the
     * generated `equals`/`hashCode`/`copy` — two messages with identical content must still compare
     * equal, and a cache is not part of what a message *is*.
     *
     * [LazyThreadSafetyMode.PUBLICATION] rather than the default lock: this is read from the UI thread,
     * the scenario runner's thread and the control server's, and the computation is pure, so the worst a
     * race can do is compute the same list twice and publish one of them. That is cheaper than putting a
     * monitor on a read that happens thousands of times a second.
     */
    val wireFields: List<Pair<Int, String>>? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        wireRaw?.let { FixFields.parse(it, FixFields.SOH) }
    }

    /**
     * Best-effort fields **for rendering**: [wireFields] when we have them, the lossy display string when
     * we do not. Never for assertions — see `FixMessageHelper.wireFields` for why the difference is not a
     * detail.
     */
    val displayFields: List<Pair<Int, String>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        wireFields ?: FixFields.parse(rawMessage)
    }

    /**
     * The grid row's text, built once.
     *
     * Runs per visible row per frame, and once per message in every filter and search pass — the session
     * filter calls it for every message in the buffer whenever a regex filter is set, and that whole pass
     * re-runs on each 100ms message update. The formatter below was hoisted for this reason; the string
     * it formats was still being rebuilt every time.
     */
    private val displayString: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val time = timestamp.format(DISPLAY_TIME_FORMAT)
        val dir = if (direction == Direction.INCOMING) "<<" else ">>"
        "$time $dir [$messageType] $rawMessage"
    }

    override fun toDisplayString(): String = displayString

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

        /**
         * Hoisted for the same reason as [DISPLAY_TIME_FORMAT] beside it, which was hoisted and this was
         * not: `String.toRegex()` calls `Pattern.compile`, and this ran inside the function.
         *
         * [parseMessageType] is the default value of the `messageType` constructor parameter, so it runs
         * **once per FixMessage constructed** — every message ingested, plus every one rebuilt during
         * capture and replay. Compiling the same three-token pattern each time is pure waste on the
         * ingest path.
         */
        private val MSG_TYPE_PATTERN = "35=([^\\x01|]+)".toRegex()

        /**
         * The value of tag 35, or `"UNKNOWN"`.
         *
         * The scan is hand-rolled because a regex is a heavy instrument for "find `35=` at a field
         * boundary and read to the next delimiter", and this is on the per-message path. It looks only at
         * positions where `35=` actually begins a field — start of string, or just after a delimiter —
         * which the old pattern did not check and which matters: `10035=X` and `58=tag 35=foo` both
         * contain the literal `35=`, and the regex matched the first of those. Falls back to the pattern
         * for anything it cannot place, so a string with no field boundary at all still gets the old
         * answer rather than a new kind of wrong.
         */
        fun parseMessageType(raw: String): String {
            var at = raw.indexOf(MSG_TYPE_TAG)
            while (at >= 0) {
                val startsField = at == 0 || raw[at - 1] == FixFields.SOH || raw[at - 1] == '|'
                if (startsField) {
                    val from = at + MSG_TYPE_TAG.length
                    var to = from
                    while (to < raw.length && raw[to] != FixFields.SOH && raw[to] != '|') to++
                    if (to > from) return raw.substring(from, to)
                }
                at = raw.indexOf(MSG_TYPE_TAG, at + 1)
            }
            return MSG_TYPE_PATTERN.find(raw)?.groupValues?.get(1) ?: "UNKNOWN"
        }

        private const val MSG_TYPE_TAG = "35="
    }
}
