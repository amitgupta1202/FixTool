@file:Suppress("TooGenericExceptionCaught", "SwallowedException") // field reads return null on any parse error

package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.BookReading
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.OrderBook
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.load.CompiledTemplate
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
    /**
     * The dictionary the reply is **built** with, not merely validated against — see
     * [AcceptorResponder.buildMessage]. Null is a venue with none loaded, which still sends.
     */
    val dictionary: FixDictionary? = null,
    val render: () -> String,
) {
    fun build(): Message = AcceptorResponder.buildMessage(render(), dictionary)
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
 * What a rule's `whenOrder` asked of the book, and what the book said — the same shape as
 * [ConditionOutcome] and for the same reason.
 *
 * A reader who is told only "did not match" cannot tell a rule that wanted `working` from a book that
 * had never heard of the order. [actual] is the value this constraint read, exactly as
 * [ConditionOutcome.actual] is the value a tag condition read, and [key] says *which* order was asked
 * after — a cancel names two ids and only one of them is the order.
 *
 * [actual] is null only when there was no book to ask, which is a caller that supplied none rather
 * than a book with nothing in it. Those are different answers and neither may be reported as the other.
 */
data class OrderOutcome(
    val constraint: OrderConstraint,
    val key: String?,
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
    /** What the rule asked of the book, and what it heard. Null when the rule asked nothing. */
    val order: OrderOutcome? = null,
)

/**
 * Matches incoming application messages against acceptor [AcceptorResponseRule]s (first match wins)
 * and builds the templated FIX responses. Stateless and side-effect free — [AcceptorDispatch] decides
 * when each one goes out and puts it there.
 */
object AcceptorResponder {
    private val logger = org.slf4j.LoggerFactory.getLogger(AcceptorResponder::class.java)

    private const val MSG_TYPE_TAG = 35

    // Matches a whole ${req.<tag>} expression, e.g. ${req.11}.
    private val REQ_REF = Regex("\\\$\\{req\\.(\\d+)}")

    // Any ${...} expression, so a req reference *inside* one can be filled in before Kotlin sees it.
    private val ANY_EXPR = Regex("\\\$\\{([^}]*)}")

    // A req reference sitting inside a larger expression, e.g. the `req.38` of ${req.38 / 2}.
    private val REQ_IN_EXPR = Regex("\\breq\\.(\\d+)\\b")

    // The same two spellings for the book: ${order.leavesQty} standing alone, and the `order.leavesQty`
    // of ${order.leavesQty / 2}. Names rather than digits — see OrderBook.fields.
    private val ORDER_REF = Regex("\\\$\\{order\\.([A-Za-z][A-Za-z0-9]*)}")
    private val ORDER_IN_EXPR = Regex("\\border\\.([A-Za-z][A-Za-z0-9]*)\\b")
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

    /**
     * The first rule whose MsgType, every tag condition **and** book constraint the incoming message
     * satisfies.
     *
     * [book] is what the venue held before this message arrived (decision 4a) — one reading, taken
     * once by the caller, because every rule in the list is asking about the same order and the answer
     * must not be able to change halfway down. Null means no book was available to ask, which makes a
     * rule that asks one unjudgeable; such a rule does not fire. That is the safe direction, and the
     * same one [compile] takes with a trigger it cannot parse: a rule firing on messages its author
     * excluded is the dangerous way to be wrong.
     */
    fun firstMatch(compiled: List<CompiledRule>, incoming: Message, book: BookReading? = null): AcceptorResponseRule? =
        compiled.firstOrNull { (rule, conditions) ->
            valueOf(incoming, MSG_TYPE_TAG) == rule.whenMsgType &&
                conditions.all { (tag, matcher) -> ExpectationEvaluator.satisfies(matcher, valueOf(incoming, tag)) } &&
                satisfiesBook(rule, book)
        }?.rule

    /** Whether [rule]'s book constraint holds, given what [book] said. See [firstMatch] for the null case. */
    private fun satisfiesBook(rule: AcceptorResponseRule, book: BookReading?): Boolean {
        val constraint = rule.whenOrder ?: return true
        return book != null && book.satisfies(constraint)
    }

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
    fun explain(rules: List<AcceptorResponseRule>, incoming: Message, book: BookReading? = null): List<RuleOutcome> {
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
            // Reported for a *skipped* rule too, since "disabled" and "the book said no" are both
            // reasons a rule did nothing and an author toggling one back on wants to know the other
            // one is waiting for them.
            val order =
                rule.whenOrder?.let { constraint ->
                    OrderOutcome(
                        constraint = constraint,
                        key = book?.key,
                        actual = book?.word,
                        satisfied = book != null && book.satisfies(constraint),
                    )
                }
            val matched =
                skipped == null &&
                    msgType == rule.whenMsgType &&
                    conditions.all { it.satisfied } &&
                    (order?.satisfied ?: true)
            val selected = matched && !alreadyWon
            if (selected) alreadyWon = true
            RuleOutcome(index, rule, matched, selected, skipped, conditions, order)
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
            earlier.enabled && earlier.whenMsgType == rule.whenMsgType && earlier.isUnconditional()
        }.takeIf { it >= 0 }
    }

    /**
     * The whole of [rule]'s reply to [incoming], as sends waiting for their moment.
     *
     * `${req.<tag>}` is substituted here, against the message that triggered the rule and while that
     * message is unambiguously the current one. `${uuid}`, `${now}` and **`${order.…}`** are left for
     * [PlannedSend.build].
     *
     * [order] is a **thunk, read once per step as that step is sent**, and that is the difference
     * between this and everything else here. `${req.…}` is a fact about the triggering message and
     * cannot change; the book can, and within one reply it *does* — step one's `toApp` runs inside its
     * own `send()`, which returns before step two is built, so step two's `${order.leavesQty}` sees
     * what step one filled. A reading captured once at trigger time would make every step of a
     * three-step fill report the same quantity, which is precisely the defect the book was built to
     * fix (see the proposal's opening, and decision 2).
     *
     * Null means no book — an initiator, or a dry run with nothing supplied. A step that reads the
     * book then refuses to render rather than substituting empty; see [resolveOrderRefs].
     */
    fun plan(
        rule: AcceptorResponseRule,
        incoming: Message,
        request: FixMessage? = null,
        dictionary: FixDictionary? = null,
        order: () -> Map<String, String>? = { null },
    ): List<PlannedSend> {
        var offset = 0L
        // One id for the whole reply, drawn here because here is where the trigger is. An OrderID that
        // changed between the ack and the fill would be two unrelated orders to a client tracking it.
        val requestId = UUID.randomUUID().toString()
        return rule.sequence().map { step ->
            offset += step.delayMillis.coerceAtLeast(0)
            val againstRequest = resolveRequestRefs(step.template, incoming, requestId)
            PlannedSend(offset, dictionary) {
                // Order refs before the expression pass, exactly as request refs are, so
                // `${order.leavesQty / 2}` is arithmetic and not a literal.
                resolveExpressions(resolveOrderRefs(resolveAtSendTime(againstRequest), order()), request, dictionary)
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

    /** Every name [template] reads off the book, both spellings, in the order they first appear. */
    fun orderNames(template: String): List<String> =
        (
            ORDER_REF.findAll(template).map { it.groupValues[1] } +
                ANY_EXPR.findAll(template).flatMap { expr ->
                    ORDER_IN_EXPR.findAll(expr.groupValues[1]).map { it.groupValues[1] }
                }
        ).distinct().toList()

    /**
     * Why [template] cannot be built from [order], or null when it can.
     *
     * Three answers rather than one, because they send an author to three different places: a name
     * that is not in the vocabulary at all is a typo in the template; a book with no such order is a
     * trigger that should have been conditioned; and a name this order has simply not got is a venue
     * that has not said that yet — an unacknowledged order has no LeavesQty because nobody has
     * claimed one.
     */
    fun orderRefusal(template: String, order: Map<String, String>?): String? {
        val wanted = orderNames(template)
        if (wanted.isEmpty()) return null
        val unknown = wanted.filterNot { it in OrderBook.names }
        if (unknown.isNotEmpty()) {
            return "${unknown.joinToString(", ") { "\${order.$it}" }} is not a name the book has; " +
                "the names are ${OrderBook.names.joinToString(", ")}"
        }
        if (order == null) return "this reply reads the book, and there is no order here to read"
        val absent = wanted.filterNot { order.containsKey(it) }
        if (absent.isEmpty()) return null
        return if (absent.size == 1) {
            "the venue has not said this order's ${absent.single()} yet, and the reply reads it"
        } else {
            "the venue has not said this order's ${absent.joinToString(", ")} yet, and the reply reads them"
        }
    }

    /**
     * The reply shapes on offer for [incoming], each with the reason it cannot be sent, if any.
     *
     * Filtered by MsgType alone. A *rule* has to be conservative about tags it might not get, because
     * it is written before the message arrives — which is why the fill presets are conditioned on
     * `40 = 2`. Here the message is in hand, so the question is answerable rather than guessable: the
     * shape is offered, and if it reads a tag this message does not carry, it says so instead of
     * quietly building `31=` and letting the client take the blame.
     */
    fun offersFor(
        incoming: Message,
        dictionary: FixDictionary? = null,
        /** What the venue holds for this message's order, or null if it holds nothing. */
        order: Map<String, String>? = null,
    ): List<ReplyOffer> {
        val msgType = valueOf(incoming, MSG_TYPE_TAG) ?: return emptyList()
        return AcceptorPresets.replyShapes
            .filter { it.answers == msgType }
            .map { shape ->
                val missing = requestTags(shape.template).filter { valueOf(incoming, it) == null }
                ReplyOffer(
                    shape,
                    // The message is checked first because a shape that reads a tag this message does
                    // not carry is broken whatever the book holds. Both refusals are the same kind of
                    // answer — "this shape cannot be built from what is here" — and the author gets
                    // whichever is true rather than a list of everything wrong at once.
                    refusal =
                        missing.takeIf { it.isNotEmpty() }?.let { refusal(it, dictionary) }
                            ?: orderRefusal(shape.template, order),
                )
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
    fun replyTo(
        shape: ReplyShape,
        incoming: Message,
        request: FixMessage? = null,
        dictionary: FixDictionary? = null,
        order: Map<String, String>? = null,
    ): String {
        val rule = AcceptorResponseRule(whenMsgType = shape.answers, steps = listOf(ResponseStep(shape.template)))
        // Read now rather than as a thunk: a hand-picked reply is one message composed at one moment,
        // and the moment is this one. The sequencing that makes `plan` read the book per step is a
        // property of a *rule's* reply, which this is not.
        return plan(rule, incoming, request, dictionary) { order }
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

    /**
     * The half that reads **the book**: `${order.<name>}`, in both spellings, as the step is sent.
     *
     * The mechanics are [resolveRequestRefs]'s exactly — standing alone the braces are consumed, and
     * inside a larger expression the name is replaced in place and the braces left for the Kotlin
     * engine, so `${order.leavesQty}` is the value and `${order.leavesQty / 2}` is arithmetic. Textual
     * and before the expression pass on purpose: a *binding* in the script engine would be the
     * `KotlinJsr223` hazard this codebase has been bitten by, where an identifier frozen at compile
     * time silently returns a stale value. There is no such thing to freeze here.
     *
     * **Refuses rather than substituting empty.** A missing name would go on the wire as `37=` — a
     * real field with no value, the malformed message the preset discipline exists to prevent, and
     * the client would be blamed for it. The structural answer is a trigger that cannot match an
     * order the venue has not got ([AcceptorResponseRule.validationError]); this is the backstop for
     * everything that defeats it — a book cleared mid-sequence, an eviction, an order with no
     * ClOrdID. The throw is reported exactly like a send that throws: the step is lost and said so,
     * and the rest of the sequence is not.
     */
    fun resolveOrderRefs(template: String, order: Map<String, String>?): String {
        orderRefusal(template, order)?.let { throw IllegalStateException(it) }
        val fields = order ?: return template
        val whole = ORDER_REF.replace(template) { m -> fields.getValue(m.groupValues[1]) }
        return ANY_EXPR.replace(whole) { m ->
            "\${" + ORDER_IN_EXPR.replace(m.groupValues[1]) { r -> fields.getValue(r.groupValues[1]) } + "}"
        }
    }

    /**
     * The half of [resolve] that reads the clock and the id generator. Re-run for every step sent.
     *
     * `${uuid}` and `${now}` are the two spellings every rule written before shorthands existed uses,
     * and they keep their exact meaning: a UUID with dashes, and the UTC clock in FIX's timestamp
     * pattern. Every other **pure shorthand generator**, `${utcnow+1min}`, `${now-2d:yyyyMMdd}`,
     * `${uuid:8}`, is rendered here too, natively, through the renderer the load run's compiled
     * template already uses, so it never reaches the script engine.
     *
     * **Measured, not assumed.** The expander turns a shorthand into a Kotlin expression, which the
     * script engine then compiles, and compiling costs about 60 ms per reply on the one dispatch
     * thread every acceptor reply shares. The RFQ venue's quote carried a single `${utcnow+1min}` and
     * answered fourteen requests a second because of it: a burst of 500 left 77 unanswered inside a
     * 30 s settle window. Rendered here, the same field costs microseconds and the output is the same
     * string the compiled expression would have produced. Anything that is not a pure generator, a
     * price expression, an assignment, `${order.…}`, is left exactly as it was for the passes after
     * this one.
     */
    fun resolveAtSendTime(template: String): String {
        val literal =
            template
                .replace("\${uuid}", UUID.randomUUID().toString())
                .replace("\${now}", LocalDateTime.now(ZoneOffset.UTC).format(NOW_FORMAT))
        if (!literal.contains("\${")) return literal
        return ANY_EXPR.replace(literal) { m ->
            ShorthandTemplateExpander.generatorOf(m.groupValues[1])?.let { CompiledTemplate.generate(it) } ?: m.value
        }
    }

    /**
     * Builds a QuickFIX message from a resolved raw template.
     *
     * **With a dictionary, through the same builder the send path uses** — which is the only way a
     * reply can carry a repeating group. Without one, every tag is set flat on a bare [Message], and a
     * `FieldMap` orders its body by tag number: a template spelling out `146=1|55=EUR/USD` comes back
     * as `55` *before* `146`, so the count claims an entry the group has not got and the counterparty
     * reads a malformed message. Measured, not assumed.
     *
     * The flat path is kept as the fallback rather than deleted, because a venue with no dictionary
     * loaded must still answer, and because a template the manual builder chokes on should degrade to
     * the behaviour it had before this existed rather than stop replying.
     *
     * It also fixes something older and quieter: the flat path routes **only** tag 35 to the header, so
     * a rule whose template carried `115` (OnBehalfOfCompID) put a header field in the body and the
     * counterparty answered "tag specified out of required order". The manual builder reads the
     * dictionary's own header and trailer sections, venue dialects included.
     */
    fun buildMessage(resolvedRaw: String, dictionary: FixDictionary? = null): Message {
        if (dictionary?.isLoaded() == true) {
            runCatching { with(FixMessageHelper) { resolvedRaw.toQuickFixMessageManual(dictionary) } }
                .onSuccess { return it }
                .onFailure { logger.warn("Building an acceptor reply through the dictionary failed; sent flat", it) }
        }
        val msg = Message()
        FixMessageHelper.parseFixMessage(resolvedRaw).forEach { (tag, value) ->
            if (tag == MSG_TYPE_TAG) msg.header.setString(tag, value) else msg.setString(tag, value)
        }
        return msg
    }

    /**
     * The value of [tag] on [msg], read where a trigger and a `${'$'}{req.…}` both look.
     *
     * Flat first — header, body, trailer — and **then, only if none of them has it, inside the first
     * instance of each repeating group**. That last step is what lets a rule condition on a field a
     * conformant message puts in a group: FIX 4.4's QuoteRequest carries Symbol inside
     * `NoRelatedSym(146)`, and with a dictionary loaded QuickFIX/J parses it there, so `isSetField(55)`
     * is false and a rule conditioned on the symbol could never fire. Measured, not assumed.
     *
     * **The first instance, and that is an approximation** — a two-symbol RFQ is answered about its
     * first symbol. The alternative is refusing to read groups at all, which is what happened before
     * and is worse: the rule silently never matched, which looks exactly like a trigger that has not
     * come up yet.
     *
     * Flat wins on purpose. A message carrying the tag both flat and in a group reads the flat one, so
     * nothing written before this existed changes what it matches.
     */
    private fun valueOf(msg: Message, tag: Int): String? =
        try {
            when {
                msg.header.isSetField(tag) -> msg.header.getString(tag)
                msg.isSetField(tag) -> msg.getString(tag)
                msg.trailer.isSetField(tag) -> msg.trailer.getString(tag)
                else -> inFirstGroupInstance(msg, tag)
            }
        } catch (e: Exception) {
            null
        }

    /** [tag] from the first entry of the first group that has it, or null. See [valueOf]. */
    private fun inFirstGroupInstance(msg: Message, tag: Int): String? =
        msg.groupKeyIterator().asSequence().firstNotNullOfOrNull { groupTag ->
            try {
                msg.getGroup(1, groupTag).takeIf { it.isSetField(tag) }?.getString(tag)
            } catch (e: Exception) {
                null
            }
        }
}
