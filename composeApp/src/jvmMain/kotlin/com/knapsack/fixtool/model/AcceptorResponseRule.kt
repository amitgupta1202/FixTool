package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable

/**
 * One message of an acceptor's reply, and how long to wait before sending it.
 *
 * [delayMillis] is measured **from the previous step**, not from the trigger — an author writing
 * "acknowledge, then 500ms later a partial fill, then 500ms after that the rest" writes 0, 500, 500
 * and does not do the arithmetic. The dispatcher accumulates.
 *
 * The template is the same raw FIX string a single-response rule uses, with the same substitutions;
 * see [AcceptorResponseRule].
 */
@Serializable
data class ResponseStep(
    val template: String,
    val delayMillis: Long = 0,
)

/**
 * A single acceptor auto-response rule. When FixTool runs as an acceptor and an incoming
 * application message matches [whenMsgType] (and every entry of [whenFields], by exact value),
 * the first matching rule's reply — one message or a sequence of them — is sent back.
 *
 * A response template is a raw FIX message (pipe- or SOH-delimited, app fields only — QuickFIX
 * stamps the session header/trailer) supporting three substitutions:
 *  - `${req.<tag>}` — the value of `<tag>` from the incoming (request) message
 *  - `${uuid}`      — a fresh random id (e.g. for OrderID/ExecID)
 *  - `${now}`       — the current UTC transact time (yyyyMMdd-HH:mm:ss.SSS)
 *
 * `${req.<tag>}` is fixed when the trigger arrives; the other two are resolved **per step, as that
 * step is sent**, so a fill sent a second later carries its own ExecID and its own TransactTime
 * rather than the acknowledgement's. A sequence whose steps all shared one timestamp would report
 * zero latency in exactly the data a latency test reads.
 *
 * Example: `35=8|150=0|39=0|37=${uuid}|11=${req.11}|55=${req.55}|38=${req.38}`.
 *
 * ### One reply, two spellings
 *
 * [steps] is the reply. [responseTemplate] is the one-message spelling that predates it and is still
 * what every existing profile on disk carries, so it is read forever — as a single step with no
 * delay. [sequence] is the one place that decides, and everything asking what a rule replies with
 * asks it rather than reading either field.
 */
@Serializable
data class AcceptorResponseRule(
    val whenMsgType: String,
    val whenFields: Map<String, String> = emptyMap(),
    val responseTemplate: String = "",
    val steps: List<ResponseStep> = emptyList(),
) {
    /**
     * The reply, whichever way it was spelled: [steps] when present, otherwise [responseTemplate] as
     * a single immediate step, otherwise nothing.
     *
     * A rule that carries both is not refused here — see [validationError]. It plays [steps], the
     * newer and more specific spelling, so that adding a sequence to an existing rule does the thing
     * the author was reaching for even if they left the old field behind.
     */
    fun sequence(): List<ResponseStep> =
        when {
            steps.isNotEmpty() -> steps
            responseTemplate.isNotBlank() -> listOf(ResponseStep(template = responseTemplate))
            else -> emptyList()
        }

    /**
     * What is wrong with this rule, in the author's words, or null if it is usable.
     *
     * Judged where it can be acted on — the control surface reports it beside the rule — and
     * **never** by the file format. Same stance as `Matcher.validationError()`: a rule that cannot
     * reply is a bad rule, not a corrupt profile, and refusing to load it would take every other
     * rule, and every unrelated connection setting, down with it.
     */
    fun validationError(): String? =
        when {
            whenMsgType.isBlank() -> "the rule has no trigger MsgType, so nothing can match it"
            steps.isNotEmpty() && responseTemplate.isNotBlank() ->
                "the rule carries both 'steps' and the older 'responseTemplate'; the sequence is played and " +
                    "the single template is ignored — remove it to say so"
            sequence().isEmpty() -> "the rule has nothing to reply with"
            steps.any { it.template.isBlank() } ->
                "step ${steps.indexOfFirst { it.template.isBlank() } + 1} has no message to send"
            steps.any { it.delayMillis < 0 } ->
                "step ${steps.indexOfFirst { it.delayMillis < 0 } + 1} has a negative delay"
            else -> null
        }
}
