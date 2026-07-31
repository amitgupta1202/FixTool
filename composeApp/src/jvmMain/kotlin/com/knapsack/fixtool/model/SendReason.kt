package com.knapsack.fixtool.model

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * **Why the venue sent this reply, written when the decision was made.**
 *
 * This exists because the acceptor stopped being pure. Until the book, "why did the venue answer that
 * cancel that way?" could be reconstructed at any time from the rule list and the message — which is
 * exactly what `/acceptor/test` does, and why nothing anywhere recorded which rule answered which
 * message. It did not need to: the answer was a function of two things that were both still on screen.
 *
 * A stateful venue breaks that. A cancel rejected at 09:14:22 because the order was unknown
 * re-derives as *accepted* at 09:14:25, because by then the order exists. Neither answer is wrong;
 * they were asked at different times. But a tool that re-derives after the fact states the wrong one
 * **confidently**, and a QA tool that misreports why it did something is worse than one that declines
 * to say. See `docs/acceptor-order-state-proposal.md`, decision 6a.
 *
 * So the reason is a **record, never a derivation**: taken at the moment of the decision, carried to
 * the wire, and attached to the outgoing [FixMessage] it explains.
 *
 * Hand-sent replies carry one too. The client cannot tell which of them sent it and neither should
 * the record — a log where the improvised replies are the silent ones is hardest to read exactly when
 * a person was improvising.
 */
data class SendReason(
    val source: Source,
    val at: LocalDateTime,
    /** Position of the rule that fired, in the profile's list. Null for a hand-sent reply. */
    val ruleIndex: Int? = null,
    /** The MsgType that rule triggers on, so the line says what matched and not only that something did. */
    val whenMsgType: String? = null,
    /** Which step of the reply this is, and how many there are. Null for a single-message reply. */
    val step: Int? = null,
    val steps: Int? = null,
    /** The "Reply With…" shape a person picked. Null for a rule. */
    val shapeName: String? = null,
    /** What the rule required of the book, if it required anything. */
    val constraint: OrderConstraint? = null,
    /** What the book said at that moment — the half that cannot be re-derived. */
    val reading: BookReading? = null,
) {
    enum class Source {
        /** A rule in the profile's list matched and this is its reply. */
        RULE,

        /** A person picked a reply shape and sent it. */
        HAND,
    }

    /**
     * The reason in one line, as it reads on the message.
     *
     * Three shapes, and the difference between the first two is deliberate: a rule that *asked* the
     * book gets "and the book said", because that clause is part of why it fired. A rule that did not
     * ask gets the book's answer as trailing context — reported, since it is the state that could not
     * be recovered later, but not dressed up as a cause it was not.
     */
    fun line(): String {
        val time = at.format(TIME)
        return when (source) {
            Source.RULE -> ruleLine(time)
            Source.HAND -> handLine(time)
        }
    }

    private fun ruleLine(time: String): String {
        val rule = "rule ${(ruleIndex ?: 0) + 1}" + stepSuffix()
        val matched = whenMsgType?.let { "35=$it matched" } ?: "the rule matched"
        val book = reading
        return when {
            constraint != null && book != null ->
                "sent by $rule — $matched, and the book said ${book.key ?: "the order"} was ${book.word} at $time"
            constraint != null -> "sent by $rule — $matched, and the order was ${constraint.word} at $time"
            book?.state != null -> "sent by $rule — $matched at $time, with ${book.key} ${book.word} in the book"
            else -> "sent by $rule — $matched at $time"
        }
    }

    private fun handLine(time: String): String {
        val what = shapeName?.let { "\"$it\"" } ?: "a reply typed by hand"
        val book = reading
        val about =
            when {
                book?.state != null ->
                    " against ${book.key}, ${book.word}" + (book.leavesQty?.let { ", $it leaves" } ?: "")
                book?.key != null -> " against ${book.key}, which the book did not hold"
                else -> ""
            }
        return "sent by hand — $what$about at $time"
    }

    private fun stepSuffix(): String {
        val index = step ?: return ""
        val total = steps ?: return ""
        return if (total > 1) " (step $index of $total)" else ""
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}

/**
 * The reason for the send **currently on this thread**, handed from whoever decided to whoever
 * records the outgoing message.
 *
 * QuickFIX calls `Application.toApp` synchronously inside `Session.sendToTarget`, on the caller's own
 * thread, and that callback is the one place every outgoing application message is turned into a
 * [FixMessage]. A thread-local is what bridges those two without threading a nullable parameter
 * through the send path of every caller that has no reason to carry one — and, unlike a field on the
 * service, it is correct when a venue with four clients is sending on four threads at once.
 *
 * Always cleared, on every path, so a send that throws before `toApp` cannot leave its reason to be
 * picked up by whatever this thread sends next. A wrong reason is worse than none.
 */
object PendingSendReason {
    private val current = ThreadLocal<SendReason?>()

    /** Runs [block] with [reason] available to whatever `toApp` this thread reaches. */
    fun <T> during(reason: SendReason?, block: () -> T): T {
        val previous = current.get()
        current.set(reason)
        return try {
            block()
        } finally {
            current.set(previous)
        }
    }

    /** The reason for the send in flight on this thread, consumed so one reason explains one message. */
    fun take(): SendReason? {
        val reason = current.get() ?: return null
        current.set(null)
        return reason
    }
}
