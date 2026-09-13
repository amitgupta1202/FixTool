package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.BookReading
import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.LegQuote
import com.knapsack.fixtool.model.PartyRole
import com.knapsack.fixtool.model.QuoteReading
import com.knapsack.fixtool.model.RelayRef
import com.knapsack.fixtool.model.RfqEntry
import com.knapsack.fixtool.model.RfqLeg
import com.knapsack.fixtool.model.SendReason
import com.knapsack.fixtool.model.StepAddress
import com.knapsack.fixtool.model.roleOf
import quickfix.Message
import quickfix.Session
import quickfix.SessionID
import java.util.concurrent.ConcurrentHashMap

/**
 * **A running venue, answering the relay engine**: who an address reaches among the sessions this engine has
 * created, and what an id is called on the far side, from the RFQ book.
 *
 * [QuickFixService] owns the sessions and the book and hands both in; this is the part of it that knows about
 * more than one counterparty at a time. Every session the engine creates is registered through [created], and
 * that registry — not the panes — is how a declared counterparty is found: a client with no pane is still a
 * client a dealer can be relayed to. See `docs/rfq-relay-impl-plan.md`, fact 3.
 */
internal class LiveRelayVenue(
    private val book: RfqBookService,
    private val counterparties: () -> List<Counterparty>,
    private val isLoggedOn: (SessionID) -> Boolean = { Session.lookupSession(it)?.isLoggedOn == true },
    private val clock: () -> Long = System::currentTimeMillis,
) : RelayVenue {
    /** Every counterparty session this engine has created, by CompID. */
    private val sessions = ConcurrentHashMap<String, SessionID>()

    /** Registers a session the engine has just created. */
    fun created(sessionId: SessionID) {
        sessions[sessionId.targetCompID] = sessionId
    }

    /**
     * Records one application message the venue received or sent on [sessionId] in the RFQ book. True when the book
     * claims it — a relayed fill, which no order book saw an order for. See [RfqBookService.record].
     */
    fun record(sessionId: SessionID, fixMessage: FixMessage, message: Message, sent: Boolean): Boolean =
        book.record(
            sessionKey = sessionId.toString(),
            compId = sessionId.targetCompID,
            role = roleOf(sessionId),
            sent = sent,
            fields = RfqBookService.fieldsOf(message),
            relay = fixMessage.sendReason?.relay,
            messageUid = fixMessage.uid,
        )

    /**
     * **Plans a relay rule's reply, and records in the book what the plan decides**: the trade a rule books the
     * moment it is chosen, and the legs it could not reach.
     *
     * The order inside is the design. Addresses are resolved first, against the RFQ as it stands after the trigger
     * was recorded, because the opening QuoteRequest must find the RFQ it just opened. The trade is recorded second,
     * on the caller's thread, because a second lift is already queued behind this one on the engine's single thread
     * and must read `done`. The caller schedules the sends only after this returns. See
     * `docs/rfq-relay-impl-plan.md`, decisions R2 and R3.
     */
    @Suppress("LongParameterList") // the planner's own inputs, passed through
    fun plan(
        rule: AcceptorResponseRule,
        incoming: Message,
        request: FixMessage,
        dictionary: FixDictionary?,
        sessionId: SessionID,
        quote: () -> QuoteReading?,
        order: () -> Map<String, String>?,
    ): Pair<RelayTrigger, RelayPlan> {
        val trigger = trigger(sessionId, incoming)
        val plan = AcceptorResponder.planRelay(rule, incoming, request, dictionary, this, trigger, quote, order)
        val rfqId = trigger.rfqId ?: return trigger to plan
        val quoter = plan.sends.firstOrNull { it.to?.address == StepAddress.Quoter }?.to
        if (quoter != null && rule.booksATrade()) book.decideTrade(rfqId, quoter.sessionKey, trigger.fields[TAG_SIDE])
        plan.notDelivered.forEach { (_, recipient) -> book.notDelivered(rfqId, recipient.sessionKey, recipient.compId) }
        if (plan.nobody.isNotEmpty()) {
            val steps = plan.nobody.map { it + 1 }
            logger.info("Rule for 35={}: step(s) {} reached nobody on this RFQ", rule.whenMsgType, steps)
        }
        return trigger to plan
    }

    /** The part [sessionId]'s counterparty plays on this venue, or null when the venue does not declare it. */
    fun roleOf(sessionId: SessionID): PartyRole? = roleOf(counterparties(), sessionId.targetCompID)

    /** What a relay trigger is judged against, taken **before** the message is recorded (decision 4a). */
    fun reading(sessionId: SessionID, message: Message): VenueReading {
        val fields = RfqBookService.fieldsOf(message)
        val key = sessionId.toString()
        return VenueReading(
            senderRole = roleOf(sessionId),
            rfqBy131 = fields[TAG_QUOTE_REQ_ID]?.let { book.reading(key, TAG_QUOTE_REQ_ID, fields) },
            rfqBy117 = fields[TAG_QUOTE_ID]?.let { book.reading(key, TAG_QUOTE_ID, fields) },
            respondersOnline = sessions.values.any { id -> roleOf(id) == PartyRole.RESPONDER && isLoggedOn(id) },
        )
    }

    /** The message a relay rule answers, read **after** it was recorded, so an opening request has its RFQ (R2). */
    private fun trigger(sessionId: SessionID, message: Message): RelayTrigger {
        val fields = RfqBookService.fieldsOf(message)
        val key = sessionId.toString()
        return RelayTrigger(
            sessionId = sessionId,
            sessionKey = key,
            compId = sessionId.targetCompID,
            msgType = fields[TAG_MSG_TYPE],
            fields = fields,
            rfqId = book.entryFor(key, fields)?.rfqId,
            quote = fields[TAG_QUOTE_ID]?.let { book.reading(key, TAG_QUOTE_ID, fields) }?.takeIf { it.quote != null },
        )
    }

    override fun resolve(address: StepAddress, trigger: RelayTrigger): Resolution {
        val now = clock()
        val entry = trigger.rfqId?.let { book.entry(it) }
        val legs = entry?.legs.orEmpty()
        val live = legs.mapNotNull { leg -> leg.currentQuote(now)?.let { leg to it } }
        val sender = Recipient(trigger.sessionId, trigger.sessionKey, trigger.compId, StepAddress.Sender)
        return when (address) {
            StepAddress.Sender -> Resolution(listOf(sender))
            StepAddress.Requester -> reach(listOfNotNull(entry?.requesterCompId), address)
            StepAddress.Responders -> responders(address)
            is StepAddress.CompId -> reach(listOf(address.compId), address)
            StepAddress.Asked -> reach(legs.filter { it.venueQuoteReqId != null }.map { it.compId }, address)
            StepAddress.Quoted -> reachLegs(live, address)
            StepAddress.Quoter -> reachLegs(listOfNotNull(quoted(trigger)), address)
            StepAddress.Cover -> reachLegs(listOfNotNull(cover(entry, trigger, now)), address)
            StepAddress.Others -> {
                val cover = cover(entry, trigger, now)?.first?.responderKey
                val excluded = setOfNotNull(trigger.quote?.leg?.responderKey, cover)
                reachLegs(live.filter { (leg, _) -> leg.responderKey !in excluded }, address)
            }
        }
    }

    override fun toValue(recipient: Recipient, trigger: RelayTrigger, tag: Int): String? {
        val rfqId = trigger.rfqId ?: return null
        // A quote id is the one fixed when the address was resolved — the quoter's, the cover's — or, to the
        // requester, the quote the trigger named, in the requester's own words. Anything else is what was last
        // exchanged with that recipient on this RFQ.
        val quoteId =
            when {
                tag != TAG_QUOTE_ID -> null
                recipient.quoteId != null -> recipient.quoteId
                recipient.sessionKey == trigger.sessionKey -> trigger.fields[TAG_QUOTE_ID]
                else -> null
            }
        return quoteId ?: book.toValue(rfqId, recipient.sessionKey, tag)
    }

    override fun rfqField(trigger: RelayTrigger, name: String): String? {
        val entry = trigger.rfqId?.let { book.entry(it) } ?: return null
        val now = clock()
        return when (name) {
            "requester" -> entry.requesterCompId
            "quoter" -> trigger.quote?.leg?.compId
            "asked" -> entry.legs.count { it.venueQuoteReqId != null }.toString()
            "quoted" -> entry.legs.count { it.currentQuote(now) != null }.toString()
            "state" -> entry.lifeAt(now).word
            else -> null
        }
    }

    /** The leg and quote the trigger named, when it named one the venue relayed. */
    private fun quoted(trigger: RelayTrigger): Pair<RfqLeg, LegQuote>? {
        val reading = trigger.quote ?: return null
        val leg = reading.leg ?: return null
        return reading.quote?.let { leg to it }
    }

    /** The best other live quote on the side that traded: the lowest offer to a buyer, the highest bid to a seller. */
    private fun cover(entry: RfqEntry?, trigger: RelayTrigger, now: Long): Pair<RfqLeg, LegQuote>? {
        val quoterKey = trigger.quote?.leg?.responderKey ?: return null
        val side = trigger.fields[TAG_SIDE] ?: entry?.side ?: return null
        val others =
            entry
                ?.legs
                .orEmpty()
                .filter { it.responderKey != quoterKey }
                .mapNotNull { leg -> leg.currentQuote(now)?.let { leg to it } }
        val buy = side == SIDE_BUY
        val priced =
            others.mapNotNull { (leg, quote) ->
                (if (buy) quote.offer else quote.bid)?.toBigDecimalOrNull()?.let { Triple(leg, quote, it) }
            }
        val best = if (buy) priced.minByOrNull { it.third } else priced.maxByOrNull { it.third }
        return best?.let { it.first to it.second }
    }

    /** Declared responders: exact CompIDs whether or not they are on, and any live session a family covers. */
    private fun responders(address: StepAddress): Resolution {
        val declared = counterparties().filter { PartyRole.byWord(it.role) == PartyRole.RESPONDER }
        val exact = declared.filterNot { it.isPrefix }.map { it.compId }
        val family = sessions.keys.filter { id -> id !in exact && declared.any { it.isPrefix && it.covers(id) } }
        val online = family.filter { compId -> sessions[compId]?.let(isLoggedOn) == true }
        return reach(exact + online, address)
    }

    private fun reach(compIds: List<String>, address: StepAddress): Resolution =
        sort(
            compIds.distinct().map { compId ->
                Recipient(sessions[compId], sessions[compId]?.toString() ?: compId, compId, address)
            },
        )

    private fun reachLegs(legs: List<Pair<RfqLeg, LegQuote>>, address: StepAddress): Resolution =
        sort(
            legs.map { (leg, quote) ->
                Recipient(sessions[leg.compId], leg.responderKey, leg.compId, address, quoteId = quote.dealerQuoteId)
            },
        )

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(LiveRelayVenue::class.java)

        /**
         * Why a relayed step went out, recorded as it is scheduled: the rule and its decision, like every rule's
         * reason (decision 6a), and — for a step that left the sender's conversation — where it came from, which is
         * what Trace joins two panes that share no id through.
         */
        @Suppress("LongParameterList") // one reason's facts, each from a different place
        fun reasonFor(
            rule: AcceptorResponseRule,
            ruleNumber: Int?,
            request: FixMessage,
            send: PlannedSend,
            heldBefore: BookReading,
            trigger: RelayTrigger,
        ): SendReason =
            SendReason(
                source = SendReason.Source.RULE,
                at = request.timestamp,
                ruleIndex = ruleNumber,
                whenMsgType = rule.whenMsgType,
                step = send.authoredStep + 1,
                steps = rule.sequence().size,
                constraint = rule.whenOrder,
                reading = heldBefore,
                relay = send.to?.takeIf { it.address.relays }?.let { relayRef(request.uid, trigger, it) },
            )

        private fun relayRef(triggerUid: Long, trigger: RelayTrigger, recipient: Recipient) =
            RelayRef(
                triggerUid = triggerUid,
                triggerSession = trigger.sessionKey,
                triggerCompId = trigger.compId,
                triggerMsgType = trigger.msgType,
                address = recipient.address.word,
                recipientCompId = recipient.compId,
                rfqId = trigger.rfqId,
            )
    }

    /** Who can be sent to now, and who is owed a message and counted as not delivered instead. */
    private fun sort(candidates: List<Recipient>): Resolution {
        val (here, away) = candidates.partition { recipient -> recipient.sessionId?.let(isLoggedOn) == true }
        return Resolution(here, away)
    }
}

private const val TAG_MSG_TYPE = 35
private const val TAG_SIDE = 54
private const val TAG_QUOTE_ID = 117
private const val TAG_QUOTE_REQ_ID = 131
private const val SIDE_BUY = "1"
