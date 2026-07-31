@file:Suppress("TooGenericExceptionCaught", "SwallowedException") // field reads return null on any parse error

package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher
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
 * [render] is deliberately a thunk rather than a finished string: `${uuid}` and `${now}` resolve when
 * it runs, which is at the moment of sending, so each step of a sequence carries its own ExecID and
 * its own TransactTime. Everything that reads the *request* has already been resolved.
 *
 * The raw text is the thunk and [build] is derived from it, rather than the other way around, so that
 * anything wanting to *show* a planned reply — the dry run behind `/acceptor/test` — shows the very
 * string the wire would get. Rendering separately would be a second implementation of the same
 * substitution, free to drift, and a dry run that lies is worse than no dry run.
 */
data class PlannedSend(
    val offsetMillis: Long,
    val render: () -> String,
) {
    fun build(): Message = AcceptorResponder.buildMessage(render())
}

/** A rule whose trigger has been parsed once, ready to be asked of every inbound message. */
data class CompiledRule(
    val rule: AcceptorResponseRule,
    val conditions: List<Pair<Int, Matcher>>,
)

/** What one condition of a trigger said about one message, and the value it read to say it. */
data class ConditionOutcome(
    val tag: Int,
    val matcher: Matcher,
    /** The value the trigger actually read off the message; null means the tag is not present. */
    val actual: String?,
    val satisfied: Boolean,
)

/**
 * How one rule answered one message — the whole of why it did or did not fire.
 *
 * [matched] is about this rule's own trigger; [selected] is about the list, since under
 * first-match-wins a rule can match and still not be the one that replies. Both are reported because
 * "my rule matches but nothing happens" and "my rule does not match" are different problems with
 * different fixes, and a reader who is only told "no" cannot tell which one they have.
 *
 * [skipped] is why the trigger was never asked at all: the rule is disabled, or its trigger does not
 * parse. Such a rule is silently absent from the live ruleset ([AcceptorResponder.compile] drops it),
 * which is exactly the state that looks from outside like a rule that simply has not come up yet.
 */
data class RuleOutcome(
    /** Position in the profile's rule list, so a reader can address the rule to edit it. */
    val index: Int,
    val rule: AcceptorResponseRule,
    val matched: Boolean,
    val selected: Boolean,
    val skipped: String?,
    val conditions: List<ConditionOutcome>,
)

/**
 * Matches incoming application messages against acceptor [AcceptorResponseRule]s (first match wins)
 * and builds the templated FIX responses. Stateless and side-effect free — [AcceptorDispatch] decides
 * when each one goes out and puts it there.
 */
object AcceptorResponder {
    private const val MSG_TYPE_TAG = 35

    // Matches a whole ${req.<tag>} expression, e.g. ${req.11}.
    private val REQ_REF = Regex("\\\$\\{req\\.(\\d+)}")

    // Any ${...} expression, so a req reference *inside* one can be filled in before Kotlin sees it.
    private val ANY_EXPR = Regex("\\\$\\{([^}]*)}")

    // A req reference sitting inside a larger expression, e.g. the `req.38` of ${req.38 / 2}.
    private val REQ_IN_EXPR = Regex("\\breq\\.(\\d+)\\b")
    private val NOW_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")

    /**
     * Parses each rule's trigger once, ahead of any traffic.
     *
     * The matchers live on disk as JSON, and re-reading them per inbound message would put a parse on
     * the path of every message an acceptor under load receives, to reach an answer that cannot have
     * changed. A condition that does not parse is dropped **and the rule with it** — a trigger missing
     * one of its constraints is looser than the author wrote, and would fire on messages they excluded.
     * [AcceptorResponseRule.validationError] is what tells them why, on the rule's own card.
     */
    fun compile(rules: List<AcceptorResponseRule>): List<CompiledRule> =
        rules.mapNotNull { rule ->
            // A rule switched off never reaches the matcher, so the rule after it gets the message —
            // which is what "disabled" has to mean under first-match-wins, and is exactly what an
            // author toggling one off is asking to see.
            if (!rule.enabled) return@mapNotNull null
            val trigger = rule.trigger()
            if (trigger.any { it.reason() != null }) return@mapNotNull null
            CompiledRule(rule, trigger.map { it.tag to (it.parsed() ?: return@mapNotNull null) })
        }

    /** The first rule whose MsgType and every condition the incoming message satisfies. */
    fun firstMatch(compiled: List<CompiledRule>, incoming: Message): AcceptorResponseRule? =
        compiled.firstOrNull { (rule, conditions) ->
            valueOf(incoming, MSG_TYPE_TAG) == rule.whenMsgType &&
                conditions.all { (tag, matcher) -> ExpectationEvaluator.satisfies(matcher, valueOf(incoming, tag)) }
        }?.rule

    /**
     * The same question [firstMatch] asks, answered for **every** rule and with its working shown:
     * which conditions passed, what value each one read, and which rule wins.
     *
     * This exists so a rule can be tested without a counterparty. Authoring one otherwise means
     * save, connect, get someone to send the trigger, read the log — and when nothing comes back,
     * the tool says only that nothing came back, which is equally the shape of a typo'd tag, a rule
     * shadowed by an earlier one, and a rule that is simply switched off.
     *
     * Takes the rules **as written** rather than a compiled list, so the indices line up with the
     * profile the caller is going to edit, and so a rule [compile] would drop can be reported as
     * dropped instead of vanishing.
     *
     * This is a **second** implementation of the judgement in [firstMatch], and deliberately so:
     * that one runs on the callback thread of a loaded acceptor and short-circuits, this one
     * evaluates every condition because it has to report each. They share the two pieces that decide
     * anything — `valueOf` reads the tag, [ExpectationEvaluator.satisfies] judges it — but the
     * "MsgType matches and every condition holds, first one wins" shape is written twice, so
     * `AcceptorResponderExplainTest` re-asks each case through both and fails if they ever disagree.
     * A dry run that passes where the wire does nothing moves the bug from the rule into the tool.
     */
    fun explain(rules: List<AcceptorResponseRule>, incoming: Message): List<RuleOutcome> {
        val msgType = valueOf(incoming, MSG_TYPE_TAG)
        var alreadyWon = false
        return rules.mapIndexed { index, rule ->
            // Compiled one at a time: compile() drops what it cannot use, and a rule that is *absent*
            // from the result cannot be told apart from a rule that did not match. Alone, the drop is
            // attributable to the rule it happened to.
            val compiled = compile(listOf(rule)).firstOrNull()
            val skipped =
                when {
                    !rule.enabled -> "the rule is disabled, so the message falls through to the rule after it"
                    compiled == null -> rule.validationError() ?: "the rule's trigger is not usable"
                    else -> null
                }
            val conditions =
                compiled?.conditions.orEmpty().map { (tag, matcher) ->
                    val actual = valueOf(incoming, tag)
                    ConditionOutcome(tag, matcher, actual, ExpectationEvaluator.satisfies(matcher, actual))
                }
            val matched = skipped == null && msgType == rule.whenMsgType && conditions.all { it.satisfied }
            val selected = matched && !alreadyWon
            if (selected) alreadyWon = true
            RuleOutcome(index, rule, matched, selected, skipped, conditions)
        }
    }

    /**
     * The earlier rule that makes the rule at [index] unreachable, or null when nothing proves one does.
     *
     * The dry run already reports this per message ([RuleOutcome.selected]), but it needs a message to
     * do it, and by then the author has had to guess that shadowing is what they are looking at. This
     * is the part that can be settled by looking: an **enabled** earlier rule for the same MsgType with
     * **no conditions** answers every message of that type, so nothing after it for that type can ever
     * be reached.
     *
     * That is the whole claim, and deliberately the whole claim. Whether two *conditioned* rules
     * overlap — `38 > 1000` against `38 > 500`, a regex against an oneOf — is not decidable in general,
     * and a warning that is sometimes wrong is one authors learn to scroll past, taking the true ones
     * with it. A blank MsgType shadows nothing either: it matches no message at all.
     */
    fun shadowingRule(rules: List<AcceptorResponseRule>, index: Int): Int? {
        val rule = rules.getOrNull(index) ?: return null
        if (rule.whenMsgType.isBlank()) return null
        return rules.take(index).indexOfFirst { earlier ->
            earlier.enabled && earlier.whenMsgType == rule.whenMsgType && earlier.trigger().isEmpty()
        }.takeIf { it >= 0 }
    }

    /**
     * The whole of [rule]'s reply to [incoming], as sends waiting for their moment.
     *
     * `${req.<tag>}` is substituted here, against the message that triggered the rule and while that
     * message is unambiguously the current one. `${uuid}` and `${now}` are left for [PlannedSend.build].
     */
    fun plan(
        rule: AcceptorResponseRule,
        incoming: Message,
        request: FixMessage? = null,
        dictionary: FixDictionary? = null,
    ): List<PlannedSend> {
        var offset = 0L
        // One id for the whole reply, drawn here because here is where the trigger is. An OrderID that
        // changed between the ack and the fill would be two unrelated orders to a client tracking it.
        val requestId = UUID.randomUUID().toString()
        return rule.sequence().map { step ->
            offset += step.delayMillis.coerceAtLeast(0)
            val againstRequest = resolveRequestRefs(step.template, incoming, requestId)
            PlannedSend(offset) {
                resolveExpressions(resolveAtSendTime(againstRequest), request, dictionary)
            }
        }
    }

    /**
     * Every tag [template] reads off the request, both spellings, in the order they first appear.
     *
     * The two spellings are the two [resolveRequestRefs] fills in — `${req.44}` standing alone, and
     * `req.38` inside a larger expression — and a caller asking "what does this reply need?" has to
     * see both or it will pronounce a partial fill safe on a message with no OrderQty.
     */
    fun requestTags(template: String): List<Int> =
        (
            REQ_REF.findAll(template).map { it.groupValues[1] } +
                ANY_EXPR.findAll(template).flatMap { expr ->
                    REQ_IN_EXPR.findAll(expr.groupValues[1]).map { it.groupValues[1] }
                }
        ).mapNotNull { it.toIntOrNull() }.distinct().toList()

    /**
     * The reply shapes on offer for [incoming], each with the reason it cannot be sent, if any.
     *
     * Filtered by MsgType alone. A *rule* has to be conservative about tags it might not get, because
     * it is written before the message arrives — which is why the fill presets are conditioned on
     * `40 = 2`. Here the message is in hand, so the question is answerable rather than guessable: the
     * shape is offered, and if it reads a tag this message does not carry, it says so instead of
     * quietly building `31=` and letting the client take the blame.
     */
    fun offersFor(incoming: Message, dictionary: FixDictionary? = null): List<ReplyOffer> {
        val msgType = valueOf(incoming, MSG_TYPE_TAG) ?: return emptyList()
        return AcceptorPresets.replyShapes
            .filter { it.answers == msgType }
            .map { shape ->
                val missing = requestTags(shape.template).filter { valueOf(incoming, it) == null }
                ReplyOffer(shape, refusal = missing.takeIf { it.isNotEmpty() }?.let { refusal(it, dictionary) })
            }
    }

    private fun refusal(missing: List<Int>, dictionary: FixDictionary?): String {
        val named =
            missing.joinToString(", ") { tag ->
                dictionary?.getFieldName(tag)?.let { "$tag ($it)" } ?: "$tag"
            }
        return if (missing.size == 1) {
            "this message carries no $named, and the reply reads it"
        } else {
            "this message carries none of $named, and the reply reads them"
        }
    }

    /**
     * [shape] resolved against [incoming] — the message an author is about to edit and send by hand.
     *
     * Deliberately routed through [plan] rather than through [resolve]: a one-step rule *is* what this
     * is, and going the long way round means the manual reply and the automatic one cannot resolve
     * differently. The expression pass is the difference that would have been missed —
     * `${req.38 / 2}` is half an order to [plan] and a literal to [resolve].
     */
    fun replyTo(shape: ReplyShape, incoming: Message, request: FixMessage? = null, dictionary: FixDictionary? = null): String {
        val rule = AcceptorResponseRule(whenMsgType = shape.answers, steps = listOf(ResponseStep(shape.template)))
        return plan(rule, incoming, request, dictionary)
            .single()
            .render()
    }

    /**
     * Anything still wearing `${...}` after the shorthands, evaluated as a **Kotlin expression** by the
     * engine the message editor and scenarios already use — so `${in.D.38.toInt() / 2}` is half the
     * order quantity, and a partial-fill rule works for any order size instead of the one its author
     * hardcoded.
     *
     * **The map holds the triggering message and nothing else.** That is the whole reason this is safe:
     * `${in.D.38}` normally means "the latest incoming D", which under two orders in flight is a race —
     * step three of the first order's sequence would read the second order's quantity. Scoped to the
     * request that fired this rule, it cannot.
     *
     * Skipped entirely when no `${...}` survives, which is every template written before this existed:
     * the script engine costs real milliseconds, and an acceptor under load should not pay them to
     * discover there was nothing to evaluate.
     */
    fun resolveExpressions(template: String, request: FixMessage?, dictionary: FixDictionary?): String =
        if (request == null || !FixMessageTemplate.hasTemplateExpressions(template)) {
            template
        } else {
            FixMessageTemplate.evaluate(
                template,
                incomingMessages = request.messageType?.let { mapOf(it to request) } ?: emptyMap(),
                dictionary = dictionary,
            )
        }

    /** Substitutes `${req.<tag>}`, `${req.uuid}`, `${uuid}` and `${now}` in [template]. */
    fun resolve(template: String, incoming: Message): String =
        resolveAtSendTime(resolveRequestRefs(template, incoming, UUID.randomUUID().toString()))

    /**
     * The half of [resolve] that reads the request: `${req.<tag>}`. Fixed when the trigger arrives.
     *
     * Two shapes, because an author wants both. `${req.38}` on its own is the value, braces and all
     * consumed. `req.38` *inside* a larger expression — `${req.38 / 2}` — is replaced in place and the
     * braces left for the Kotlin engine, so half the order quantity is written the way anyone would
     * guess rather than as `${incoming["D"].valueOfTag(38)!!.toInt() / 2}`.
     *
     * The value goes in raw, which is what makes the arithmetic work and what limits this to numbers:
     * a string substituted into an expression would need quoting, and quoting would break the sums.
     * A string field is read with the standalone form, which needs none of this.
     *
     * **`${req.uuid}` is an id belonging to the request**, and [requestId] is that id — one draw for
     * the whole reply, so every step of a sequence carries the same OrderID. It sits in the `req.`
     * namespace because that namespace already means "fixed when the trigger arrived", which is
     * exactly what distinguishes it from `${uuid}`: that one resolves per step, as each step is sent,
     * and is right for an ExecID. Substituted first, so the expression pass never sees it.
     */
    fun resolveRequestRefs(template: String, incoming: Message, requestId: String): String {
        val withId = template.replace("\${req.uuid}", requestId)
        val whole = REQ_REF.replace(withId) { m -> valueOf(incoming, m.groupValues[1].toInt()) ?: "" }
        return ANY_EXPR.replace(whole) { m ->
            "\${" + REQ_IN_EXPR.replace(m.groupValues[1]) { r -> valueOf(incoming, r.groupValues[1].toInt()) ?: "" } + "}"
        }
    }

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
