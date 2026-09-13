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
    @Suppress("ReturnCount")
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
        // The other half of that pairing. A quote field is exactly what a trigger *can* resolve and a
        // scenario cannot, so `validationError()` — which speaks for the scenario side — must not be
        // asked about it. What is left to check is the one thing that can be wrong here: the name.
        if (parsed is Matcher.QuoteField) {
            return if (parsed.name in QuoteEntry.FIELDS) {
                null
            } else {
                "'${parsed.name}' is not a name the quote book has, and the names are " +
                    QuoteEntry.FIELDS.joinToString(", ")
            }
        }
        // The two relay matchers are resolved against the venue, so the scenario-side validationError() must
        // not be asked about them either. What can be wrong is the tag they sit on and the word they carry.
        if (parsed is Matcher.CounterpartyRole) {
            return when {
                tag != TAG_SENDER_COMP_ID ->
                    "a role belongs to the sender, so it reads tag 49 (SenderCompID), not tag $tag"
                SenderRole.byWord(parsed.role) == null ->
                    "'${parsed.role}' is not a role, and the roles are ${SenderRole.words.joinToString(", ")}"
                else -> null
            }
        }
        if (parsed is Matcher.RfqState) {
            return when {
                tag != TAG_QUOTE_REQ_ID && tag != TAG_QUOTE_ID ->
                    "an RFQ is found by the QuoteReqID (131) or the quote id (117) a message carries, not by tag $tag"
                RfqConstraint.byWord(parsed.state) == null ->
                    "'${parsed.state}' is not a state an RFQ can be in, and the states are " +
                        RfqConstraint.words.joinToString(", ")
                else -> null
            }
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
    /**
     * Who this step goes to, as a [StepAddress] word: `requester`, `quoter`, `responders`, `compId:FIDLR1`
     * and the rest. Null is the sender, which is what every step written before relaying means.
     *
     * A string, never an enum, so an older FixTool reading a newer profile loses the field rather than the
     * file. A rule that uses one must also carry a `role` or `rfq` condition, so that older build drops the
     * rule instead of answering the sender with a message meant for someone else — see
     * [AcceptorResponseRule.validationError].
     */
    val to: String? = null,
) {
    /** The address this step names, or null when [to] is not one. */
    fun address(): StepAddress? = StepAddress.parse(to)
}

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
    /**
     * The MsgType that fires this rule, or [WHEN_RFQ_EXPIRES] for a rule that fires when an RFQ's time runs out rather
     * than on a message: nothing sent it, so every step is addressed and the RFQ it reads is the one that expired.
     */
    val whenMsgType: String,
    val whenFields: Map<String, String> = emptyMap(),
    val conditions: List<FieldCondition> = emptyList(),
    val responseTemplate: String = "",
    val steps: List<ResponseStep> = emptyList(),
    /**
     * What the venue must already be holding for this rule to fire — **one more condition, ANDed**
     * with every tag condition above, and the only one no tag can express.
     *
     * Null is the whole of backward compatibility: a rule written before the book existed asks the
     * book nothing and fires exactly as it did. See `docs/acceptor-order-state-proposal.md`,
     * decisions 1 and 4 — the book answers, the rules still decide, and a venue behaviour it causes
     * is a rule on a card that can be read, reordered and switched off.
     *
     * **The question is what the venue held *before* this message** (decision 4a). Otherwise a rule
     * conditioned `unknown` on `35=D` could never fire — the book records from the wire, so by the
     * time the rule is asked the order it is about has already been booked by its own arrival.
     */
    val whenOrder: OrderConstraint? = null,
    /**
     * What the venue must already have **quoted** for this rule to fire — [whenOrder]'s sibling, ANDed
     * the same way, and the reason an RFQ venue can behave like one.
     *
     * Four words, and each answers a question a real venue is asked every day: `unknown` is a hit for
     * a quote this venue never sent, `expired` is a hit that arrived too late, `done` is a second hit
     * on a quote already answered, and `open` is the one case where a trade is owed. Without it every
     * one of those four is the same message to a rule, so the venue either honours all of them or none.
     *
     * **The question is what the venue held *before* this message** (decision 4a), exactly as it is for
     * [whenOrder]: a hit has to be judged against the quote as it stood when the hit arrived, not
     * against the quote its own reply is about to close.
     */
    val whenQuote: QuoteConstraint? = null,
    /**
     * Whether a responder is logged on to be asked, `none` or `some` — the one question about a venue no tag
     * can carry, and what lets a platform refuse an RFQ at once rather than open one nobody will see.
     *
     * A string, and only valid on a rule that also carries a `role` or `rfq` condition, for the reason
     * [ResponseStep.to] is.
     */
    val whenResponders: String? = null,
    /**
     * Whether any quote stands on the RFQ, `none` or `some` — what tells an RFQ that expired with quotes, whose quotes
     * each have to be ended by name, from one nobody answered. On a message, the RFQ it names; on expiry, the one that
     * expired.
     *
     * A string, and on a message it needs an `rfq` condition to say which RFQ, for the reason [whenResponders] does.
     */
    val whenQuotes: String? = null,
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

    /** True when any step of the reply reads the book — see [validationError] for what that implies. */
    fun readsTheBook(): Boolean = sequence().any { ORDER_REF in it.template }

    /** True when any step of the reply reads the quote book, which [validationError] requires a quote for. */
    fun readsTheQuote(): Boolean = sequence().any { QUOTE_REF in it.template }

    /** True when any condition of the trigger compares a tag against the quote's own value. */
    fun comparesTheQuote(): Boolean = trigger().any { it.parsed() is Matcher.QuoteField }

    /**
     * True when the venue is **guaranteed** to have the quote by the time this rule's reply is built.
     *
     * One way rather than [willHaveAnOrder]'s two, and the missing one is the difference between the
     * two books. An order is born by a message the *client* sends, so a `35=D` rule can read the order
     * its own trigger created. A quote is born by a message the *venue* sends, and a rule never
     * answers its own send, so nothing a rule can trigger on will ever mint the quote it reads. The
     * trigger has to require one.
     */
    fun willHaveAQuote(): Boolean = whenQuote != null && whenQuote != QuoteConstraint.UNKNOWN

    /**
     * True when nothing narrows this rule: it answers **every** message of its type.
     *
     * The question two different callers ask — where a preset has to be inserted for it to be
     * reachable, and whether an earlier rule makes a later one unreachable — and both were asking it
     * of [trigger] alone. [whenOrder] is a constraint no tag can express, so a rule carrying one is
     * conditioned even with an empty trigger, and a caller that missed that would place it as though
     * it answered everything.
     */
    fun isUnconditional(): Boolean =
        trigger().isEmpty() && whenOrder == null && whenQuote == null && whenResponders == null && whenQuotes == null

    /** True when the trigger asks the venue something: the sender's role, or the state of an RFQ. */
    fun asksTheVenue(): Boolean =
        trigger().any { it.parsed() is Matcher.CounterpartyRole || it.parsed() is Matcher.RfqState }

    /** The `rfq` conditions this trigger carries, as the tag each reads. */
    private fun rfqTags(): Set<Int> = trigger().filter { it.parsed() is Matcher.RfqState }.map { it.tag }.toSet()

    /** True when the trigger requires the sender to be a requester. */
    private fun requiresARequester(): Boolean =
        trigger().any { (it.parsed() as? Matcher.CounterpartyRole)?.role == SenderRole.REQUESTER.word }

    /** True when any step of the reply leaves the conversation the trigger arrived on. */
    fun relays(): Boolean = sequence().any { it.address()?.relays == true }

    /**
     * True when this rule **books a trade**: one of its steps sends the quoter an ExecutionReport.
     *
     * Derived from the reply rather than declared, the way [willHaveAnOrder] is derived from the trigger — the
     * rule already says it, and a flag beside it could disagree. A venue records the trade the moment such a rule
     * fires, before any step goes out, so a second lift already queued behind it reads `done`.
     */
    fun booksATrade(): Boolean =
        sequence().any { step ->
            step.address() == StepAddress.Quoter && MSG_TYPE_OF.find(step.template)?.groupValues?.get(1) == "8"
        }

    /** True when any step of the reply reads `${to.…}`. */
    fun readsTheRecipient(): Boolean = sequence().any { TO_REF in it.template }

    /** True when any step of the reply reads `${rfq.…}`. */
    fun readsTheRfq(): Boolean = sequence().any { RFQ_REF in it.template }

    /**
     * True when the venue is **guaranteed** to hold an order by the time this rule's reply is built —
     * which is what a reply reading `${order.…}` requires, and the whole of why the requirement is
     * structural rather than a check at send time.
     *
     * Two ways to be sure, and the second is the one slice C had to add:
     *
     * - **[whenOrder] required one.** `pending`, `working` or `done` all mean the venue was already
     *   holding it when the message arrived. `unknown` is the exact opposite and does not count.
     * - **The triggering message is what creates it.** A `35=D` opens the entry itself — the book is
     *   fed from the wire before the rules are asked — so an order exists by the time any step of the
     *   reply is rendered, even though `whenOrder` read `unknown` a moment earlier (decision 4a).
     *   Without this an accumulating fill sequence would be unwritable as a rule: it answers the
     *   message that brings the order, so no constraint it could carry would ever hold.
     */
    fun willHaveAnOrder(): Boolean =
        (whenOrder != null && whenOrder != OrderConstraint.UNKNOWN) || whenMsgType in BookSpec.ORDERS.bornBy

    /**
     * What is wrong with this rule, in the author's words, or null if it is usable.
     *
     * Judged where it can be acted on — the control surface reports it beside the rule — and
     * **never** by the file format. Same stance as `Matcher.validationError()`: a rule that cannot
     * reply is a bad rule, not a corrupt profile, and refusing to load it would take every other
     * rule, and every unrelated connection setting, down with it.
     */
    fun validationError(): String? = validationError(counterparties = null)

    /**
     * The same judgement, told what the venue declares.
     *
     * [counterparties] null is a caller that has no venue in hand — a preset, a rule on its own — and every
     * refusal that needs one is skipped rather than made against an empty list, which would flag every relay
     * rule ever written. Non-null, even empty, is a venue that has said who it expects.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun validationError(
        counterparties: List<Counterparty>?,
        /** True for a rule on an initiator, which answers only the one counterparty it is connected to. */
        initiator: Boolean = false,
    ): String? =
        when {
            whenMsgType.isBlank() -> "the rule has no trigger MsgType, so nothing can match it"
            // A key that is not a tag number can never be read off a message, so the rule silently
            // never fires — which looks exactly like a rule whose trigger simply has not come up yet.
            whenFields.keys.any { it.toIntOrNull() == null } ->
                "'${whenFields.keys.first { it.toIntOrNull() == null }}' is not a tag number, so this rule can never match"
            conditions.any { it.reason() != null } -> conditions.firstNotNullOf { it.reason() }
            // A reply that reads the book cannot be sent for an order the venue will not have: every
            // `${order.…}` would substitute empty and the venue would put `37=` on the wire as a real
            // field with no value — the malformed message the preset discipline exists to prevent.
            // Refused *structurally*, the way the fill presets are conditioned on `40 = 2` rather than
            // testing for a price at send time: the rule simply does not match, and the next rule for
            // that MsgType answers instead. (Settled open question 1.)
            readsTheBook() && !willHaveAnOrder() ->
                "the reply reads \${order.…}, so the trigger has to require an order to read — " +
                    "set 'when the order is' to pending, working or done"
            // The same refusal for the quote book, and structural for the same reason: every
            // `${quote.…}` would substitute empty and the venue would put `31=` on the wire as a real
            // field with no value. Unlike an order, no trigger can mint the quote it reads, so the
            // constraint is the only way to be sure — see [willHaveAQuote].
            readsTheQuote() && !willHaveAQuote() ->
                "the reply reads \${quote.…}, so the trigger has to require a quote to read — " +
                    "set 'when the quote is' to open, expired or done"
            // A rule that compares a tag against a quote the venue has never sent can never fire: the
            // comparison has nothing to resolve against, so it is false on every message. That is a
            // rule with no purpose rather than a rule with a bug, and it is easy to reach by setting
            // the constraint and then the condition.
            comparesTheQuote() && whenQuote == QuoteConstraint.UNKNOWN ->
                "the trigger wants a quote this venue has never sent and also compares a tag against " +
                    "that quote's own value, so it can never match — drop one of the two"
            steps.isNotEmpty() && responseTemplate.isNotBlank() ->
                "the rule carries both 'steps' and the older 'responseTemplate'; the sequence is played and " +
                    "the single template is ignored — remove it to say so"
            sequence().isEmpty() -> "the rule has nothing to reply with"
            steps.any { it.template.isBlank() } ->
                "step ${steps.indexOfFirst { it.template.isBlank() } + 1} has no message to send"
            steps.any { it.delayMillis < 0 } ->
                "step ${steps.indexOfFirst { it.delayMillis < 0 } + 1} has a negative delay"
            else -> expiryError() ?: relayError(counterparties, initiator)
        }

    /** What is wrong with how this rule addresses other counterparties, or null. See [validationError]. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun relayError(counterparties: List<Counterparty>?, initiator: Boolean): String? {
        val played = sequence()
        val expiry = whenMsgType == WHEN_RFQ_EXPIRES
        val speaksOfParties = relays() || asksTheVenue() || whenResponders != null || whenQuotes != null || expiry
        // Before anything about how it is addressed: an initiator is one session to one counterparty, so there is
        // nobody else to reach and no venue to ask who played what.
        if (initiator && speaksOfParties) {
            return "this rule is on an initiator, which answers only the counterparty it is connected to — " +
                "addresses, roles, RFQ states and responders online are for a venue that relays"
        }
        played.forEachIndexed { index, step ->
            if (step.address() == null) {
                return "step ${index + 1} is addressed to '${step.to}', and the addresses are " +
                    StepAddress.words.joinToString(", ")
            }
        }
        if (whenResponders != null && RespondersOnline.byWord(whenResponders) == null) {
            return "'$whenResponders' is not an answer to whether responders are online; the answers are " +
                RespondersOnline.words.joinToString(", ")
        }
        if (whenQuotes != null && QuotesStanding.byWord(whenQuotes) == null) {
            return "'$whenQuotes' is not an answer to whether quotes stand; the answers are " +
                QuotesStanding.words.joinToString(", ")
        }
        // Protects the older builds, not this one: a FixTool that predates relaying drops an unknown field
        // and keeps the rule, so without a matcher it cannot parse it would answer the sender with a message
        // meant for somebody else. A role or rfq condition makes it drop the rule. A rule on expiry needs none: no
        // message carries its trigger word, so an older build never fires it at all.
        val asksAParty = relays() || whenResponders != null || whenQuotes != null
        if (asksAParty && !asksTheVenue() && !expiry) {
            val what =
                if (relays()) "a step addressed to someone other than the sender" else "a responders-online check"
            return "the rule has $what, so it needs a 'the sender is' or 'the RFQ is' condition as well — " +
                "an older FixTool reading this profile would otherwise run it as a reply to the sender"
        }
        val rfqTags = rfqTags()
        if (whenQuotes != null && rfqTags.isEmpty() && !expiry) {
            return "'and quotes standing' asks about the RFQ the message names, so the trigger has to name one — " +
                "add an 'RFQ is' condition"
        }
        val opensAnRfq = whenMsgType == MSG_QUOTE_REQUEST && requiresARequester()
        // On expiry the RFQ is the trigger itself, so every reference to it is backed as though a condition named it.
        val rfqNamed = opensAnRfq || expiry
        played.forEachIndexed { index, step ->
            stepRelayError(step, index + 1, rfqTags, rfqNamed, expiry)?.let { return it }
        }
        if (counterparties?.isEmpty() == true && speaksOfParties) {
            return "this venue declares no counterparties, so nobody is a requester or a responder — " +
                "add them to the venue's Counterparties"
        }
        return null
    }

    /** What is wrong with one step's address or what it reads, given what the trigger requires. See [relayError]. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun stepRelayError(
        step: ResponseStep,
        number: Int,
        rfqTags: Set<Int>,
        /** The trigger itself names an RFQ: a requester's QuoteRequest opens one, and an expiry is one. */
        rfqNamed: Boolean,
        expiry: Boolean,
    ): String? {
        val address = step.address() ?: return null
        // Something names the RFQ this address is a part of: a condition, the expiry that is the trigger, or — for its
        // requester alone — the QuoteRequest that opens it.
        val rfqBacked = rfqTags.isNotEmpty() || expiry || (address == StepAddress.Requester && rfqNamed)
        when {
            address.needsAQuote && TAG_QUOTE_ID !in rfqTags ->
                return "step $number goes to the ${address.word}, which is found through the quote the trigger " +
                    "names — add an 'RFQ is' condition on tag 117"
            address.needsAnRfq && !rfqBacked ->
                return "step $number goes to ${address.word}, which only an RFQ the venue holds can name — " +
                    "add an 'RFQ is' condition on tag 131 or 117"
            TO_REF in step.template && (address == StepAddress.Responders || address is StepAddress.CompId) ->
                return "step $number reads \${to.…} and goes to ${address.word}, and a counterparty being asked " +
                    "has not seen this RFQ, so it has nothing to read — draw an id instead: \${req.uuid} for " +
                    "every recipient, or \${uuid:10} for each"
            TO_QUOTE_ID in step.template &&
                address != StepAddress.Requester &&
                !address.needsAQuote &&
                address != StepAddress.Quoted &&
                address != StepAddress.Quotes ->
                return "step $number reads \${to.117} and goes to ${address.word}, and not every one of them " +
                    "holds a quote — address quotes, quoted, quoter, cover or others"
            TO_QUOTE_ID in step.template && address == StepAddress.Requester && TAG_QUOTE_ID !in rfqTags ->
                return "step $number reads \${to.117} for the requester, which is the quote the trigger names — " +
                    "add an 'RFQ is' condition on tag 117"
            (TO_REF in step.template || RFQ_REF in step.template) && rfqTags.isEmpty() && !rfqNamed ->
                return "step $number reads the RFQ, and the trigger does not require one — " +
                    "add an 'RFQ is' condition"
        }
        val names = RFQ_NAME.findAll(step.template).map { it.groupValues[1] }
        names.firstOrNull { it !in RfqEntryNames.FIELDS }?.let { name ->
            return "\${rfq.$name} is not a name the RFQ book has, and the names are " +
                RfqEntryNames.FIELDS.joinToString(", ")
        }
        return null
    }
}

/**
 * **What is wrong with a rule that fires when an RFQ expires**, or null — and null for every other rule.
 *
 * Nothing sent it. So there is no sender to answer, no message to name an order or a quote, and the RFQ it reads is
 * always the one that expired: every step has to be addressed, and the parts of a trigger that read a message or a
 * quote can never hold.
 */
private fun AcceptorResponseRule.expiryError(): String? {
    if (whenMsgType != WHEN_RFQ_EXPIRES) return null
    val steps = sequence()
    val toSender = steps.indexOfFirst { it.address() == StepAddress.Sender }
    val throughAQuote = steps.indexOfFirst { it.address()?.needsAQuote == true }
    val oneQuote = steps.indexOfFirst { it.address() == StepAddress.Requester && TO_QUOTE_ID in it.template }
    val notExpired =
        trigger()
            .mapNotNull { it.parsed() as? Matcher.RfqState }
            .firstOrNull { RfqConstraint.byWord(it.state) != RfqConstraint.EXPIRED }
    return when {
        whenOrder != null || whenQuote != null ->
            "nothing sends this rule an order or a quote, so 'when the order is' and 'when the quote is' can never " +
                "be answered — clear them"
        comparesTheQuote() ->
            "nothing names a quote when an RFQ expires, so a condition against the quote's own value can never hold"
        notExpired != null ->
            "the RFQ is always expired when this rule fires, so 'the RFQ is ${notExpired.state}' can never hold"
        toSender >= 0 ->
            "step ${toSender + 1} goes to the sender, and nobody sent anything when an RFQ expires — address it to " +
                "requester, quotes, quoted or asked"
        throughAQuote >= 0 ->
            "step ${throughAQuote + 1} goes to the ${steps[throughAQuote].address()?.word}, found through the quote " +
                "a message names, and nothing names one when an RFQ expires — address quotes or quoted"
        oneQuote >= 0 ->
            "step ${oneQuote + 1} reads \${to.117} for the requester, and an RFQ that expires names no one quote — " +
                "address it to quotes, which tells the requester once for each"
        else -> null
    }
}

/**
 * [AcceptorResponseRule.validationError] for a rule on [config] — its counterparties, and whether it runs on an
 * initiator — or the rule judged on its own when there is no profile in hand.
 */
fun AcceptorResponseRule.validationError(config: FixConnectionConfig?): String? =
    if (config == null) validationError() else validationError(config.counterparties, initiator = !config.isAcceptor())

/**
 * How a template says it reads the book.
 *
 * A file-private constant rather than a companion: `@Serializable` puts `serializer()` in the
 * companion, and a *private* one takes that with it. Named here rather than in the resolver because
 * the **refusal** ships before the substitution does — slice B has to be unable to write the rule
 * slice C would break on.
 */
private const val ORDER_REF = "\${order."

/** How a template says it reads the quote book. A file-private constant for the reason [ORDER_REF] is. */
private const val QUOTE_REF = "\${quote."

/** How a template says it reads the recipient's own ids: `${to.117}`. */
private const val TO_REF = "\${to."

/** `${to.117}` itself, the one recipient reference that needs the recipient to hold a quote. */
private const val TO_QUOTE_ID = "\${to.117}"

/** How a template says it reads the RFQ book: `${rfq.requester}`. */
private const val RFQ_REF = "\${rfq."

private val RFQ_NAME = Regex("\\$\\{rfq\\.([A-Za-z][A-Za-z0-9]*)")

private const val TAG_SENDER_COMP_ID = 49
private const val TAG_QUOTE_REQ_ID = 131
private const val TAG_QUOTE_ID = 117
private const val MSG_QUOTE_REQUEST = "R"

/** The MsgType a reply template opens with, `35=8|…`. */
private val MSG_TYPE_OF = Regex("(?:^|[|\\u0001])35=([^|\\u0001]+)")
