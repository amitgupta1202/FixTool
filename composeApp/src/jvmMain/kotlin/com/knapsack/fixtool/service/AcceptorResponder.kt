@file:Suppress("TooGenericExceptionCaught", "SwallowedException") // field reads return null on any parse error

package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import quickfix.Message
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * One message the acceptor is going to send, and how long after the trigger to send it.
 *
 * [offsetMillis] is cumulative from the trigger, where a step's own delay is measured from the step
 * before it — the accumulation happens once, here, so no caller repeats it.
 *
 * [build] is deliberately a thunk rather than a finished [Message]: `${uuid}` and `${now}` resolve
 * when it runs, which is at the moment of sending, so each step of a sequence carries its own ExecID
 * and its own TransactTime. Everything that reads the *request* has already been resolved.
 */
data class PlannedSend(
    val offsetMillis: Long,
    val build: () -> Message,
)

/**
 * Matches incoming application messages against acceptor [AcceptorResponseRule]s (first match wins)
 * and builds the templated FIX responses. Stateless and side-effect free — [AcceptorDispatch] decides
 * when each one goes out and puts it there.
 */
object AcceptorResponder {
    private const val MSG_TYPE_TAG = 35

    // Matches ${req.<tag>} in a response template, e.g. ${req.11}.
    private val REQ_REF = Regex("\\\$\\{req\\.(\\d+)}")
    private val NOW_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")

    fun firstMatch(rules: List<AcceptorResponseRule>, incoming: Message): AcceptorResponseRule? =
        rules.firstOrNull { rule ->
            valueOf(incoming, MSG_TYPE_TAG) == rule.whenMsgType &&
                rule.whenFields.all { (tag, value) -> tag.toIntOrNull()?.let { valueOf(incoming, it) } == value }
        }

    /**
     * The whole of [rule]'s reply to [incoming], as sends waiting for their moment.
     *
     * `${req.<tag>}` is substituted here, against the message that triggered the rule and while that
     * message is unambiguously the current one. `${uuid}` and `${now}` are left for [PlannedSend.build].
     */
    fun plan(rule: AcceptorResponseRule, incoming: Message): List<PlannedSend> {
        var offset = 0L
        return rule.sequence().map { step ->
            offset += step.delayMillis.coerceAtLeast(0)
            val againstRequest = resolveRequestRefs(step.template, incoming)
            PlannedSend(offset) { buildMessage(resolveAtSendTime(againstRequest)) }
        }
    }

    /** Substitutes `${req.<tag>}`, `${uuid}` and `${now}` in [template] against the incoming message. */
    fun resolve(template: String, incoming: Message): String =
        resolveAtSendTime(resolveRequestRefs(template, incoming))

    /** The half of [resolve] that reads the request: `${req.<tag>}`. Fixed when the trigger arrives. */
    fun resolveRequestRefs(template: String, incoming: Message): String =
        REQ_REF.replace(template) { m -> valueOf(incoming, m.groupValues[1].toInt()) ?: "" }

    /** The half of [resolve] that reads the clock and the id generator. Re-run for every step sent. */
    fun resolveAtSendTime(template: String): String =
        template
            .replace("\${uuid}", UUID.randomUUID().toString())
            .replace("\${now}", LocalDateTime.now(ZoneOffset.UTC).format(NOW_FORMAT))

    /** Builds a QuickFIX message from a resolved raw template (MsgType -> header, the rest -> body). */
    fun buildMessage(resolvedRaw: String): Message {
        val msg = Message()
        FixMessageHelper.parseFixMessage(resolvedRaw).forEach { (tag, value) ->
            if (tag == MSG_TYPE_TAG) msg.header.setString(tag, value) else msg.setString(tag, value)
        }
        return msg
    }

    private fun valueOf(msg: Message, tag: Int): String? =
        try {
            when {
                msg.header.isSetField(tag) -> msg.header.getString(tag)
                msg.isSetField(tag) -> msg.getString(tag)
                msg.trailer.isSetField(tag) -> msg.trailer.getString(tag)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
}
