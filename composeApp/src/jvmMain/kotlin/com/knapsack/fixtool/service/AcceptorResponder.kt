@file:Suppress("TooGenericExceptionCaught", "SwallowedException") // field reads return null on any parse error

package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import quickfix.Message
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Matches incoming application messages against acceptor [AcceptorResponseRule]s (first match wins)
 * and builds the templated FIX response. Stateless and side-effect free — the caller sends the
 * returned message via `Session.sendToTarget`.
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

    /** Substitutes `${req.<tag>}`, `${uuid}` and `${now}` in [template] against the incoming message. */
    fun resolve(template: String, incoming: Message): String =
        REQ_REF.replace(template) { m -> valueOf(incoming, m.groupValues[1].toInt()) ?: "" }
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
