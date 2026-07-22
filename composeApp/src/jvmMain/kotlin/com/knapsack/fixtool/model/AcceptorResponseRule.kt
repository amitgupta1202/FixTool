package com.knapsack.fixtool.model

import com.knapsack.fixtool.service.MatcherCodec
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.validationError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One condition a trigger places on a tag: the tag, and how its value must compare.
 *
 * **The matcher is carried as the JSON the scenario format already uses**, read back through
 * [MatcherCodec], rather than as a typed `Matcher` field with a kotlinx serializer. That is
 * deliberate and it is about failure, not convenience: a typed field throws during *profile*
 * deserialization, so a single malformed matcher — one half-typed character class — would cost the
 * user every unrelated connection setting in that profile, and the profile would vanish from the
 * list. It is the mistake `MatcherCodec`'s own documentation was written to describe. Carried
 * verbatim here, an unusable matcher is a bad *rule*, named by [AcceptorResponseRule.validationError],
 * and nothing else is lost.
 */
@Serializable
data class FieldCondition(
    val tag: Int,
    val matcher: JsonObject,
) {
    /** The typed matcher, or null if this JSON is not one. [reason] says which. */
    fun parsed(): Matcher? =
        try {
            MatcherCodec.parseMatcher(matcher)
        } catch (e: IllegalArgumentException) {
            null
        }

    /** What is wrong with this condition, in the author's words, or null if it is usable. */
    fun reason(): String? {
        val parsed =
            try {
                MatcherCodec.parseMatcher(matcher)
            } catch (e: IllegalArgumentException) {
                return "the condition on tag $tag is not a usable matcher: ${e.message}"
            }
        // A reference resolves against a scenario run's scope. A trigger has no run and no scope, so
        // there is nothing for `${...}` to mean here — and an unresolvable reference matches nothing,
        // which would silently stop the rule ever firing. Refused by name instead.
        if (parsed is Matcher.Reference) {
            return "the condition on tag $tag is a reference, and a trigger has no scenario scope to resolve it against"
        }
        return parsed.validationError()
    }
}

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
    val conditions: List<FieldCondition> = emptyList(),
    val responseTemplate: String = "",
    val steps: List<ResponseStep> = emptyList(),
    /**
     * A rule switched off is **kept and skipped**, not deleted.
     *
     * Narrowing down a venue's behaviour means asking "what happens without this one" a dozen times,
     * and the answer must not cost the rule. Defaults true so every rule written before this existed
     * stays on, and so a rule is never silently inert for a reason that is not on its own card.
     */
    val enabled: Boolean = true,
) {
    /**
     * Every condition the incoming message must satisfy, from both spellings, **ANDed**.
     *
     * [whenFields] is the exact-value-only form that predates the matcher vocabulary and is what
     * existing profiles carry; each entry reads as an `exact` condition. Unlike the two spellings of
     * a *reply*, these are added rather than chosen between — and that asymmetry is on purpose. For a
     * reply, picking one spelling sends one message or the other. For a trigger, ignoring a spelling
     * drops a constraint, and a rule that fires on messages it was never meant to is the dangerous
     * direction to be wrong in. So nothing here is ever dropped.
     */
    fun trigger(): List<FieldCondition> =
        whenFields.mapNotNull { (tag, value) ->
            tag.toIntOrNull()?.let { FieldCondition(it, MatcherCodec.matcherToJson(Matcher.Exact(value))) }
        } + conditions

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
            // A key that is not a tag number can never be read off a message, so the rule silently
            // never fires — which looks exactly like a rule whose trigger simply has not come up yet.
            whenFields.keys.any { it.toIntOrNull() == null } ->
                "'${whenFields.keys.first { it.toIntOrNull() == null }}' is not a tag number, so this rule can never match"
            conditions.any { it.reason() != null } -> conditions.firstNotNullOf { it.reason() }
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
