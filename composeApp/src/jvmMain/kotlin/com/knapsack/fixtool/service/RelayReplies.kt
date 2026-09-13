package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.BookReading
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.PartyRole
import com.knapsack.fixtool.model.QuoteReading
import com.knapsack.fixtool.model.RfqConstraint
import com.knapsack.fixtool.model.RfqEntry
import com.knapsack.fixtool.model.RfqReading
import com.knapsack.fixtool.model.WHEN_RFQ_EXPIRES
import quickfix.Message
import quickfix.SessionID
import java.time.LocalDateTime

/**
 * **A relaying venue's replies, played**: a rule's reply to a message, sent on each recipient's own session, and the
 * reply to an RFQ whose time ran out, which nothing sent the venue at all.
 *
 * [QuickFixService] owns the sessions, the rules and the one dispatch thread, and hands in what these need of them
 * through [Venue]. The order inside each is the design, and is recorded where each is played. See
 * `docs/rfq-relay-impl-plan.md`, decisions R2 and R3, and `docs/rfq-relay-proposal.md`, decision 10.
 */
internal class RelayReplies(
    private val book: RfqBookService,
    private val relayVenue: LiveRelayVenue,
    private val dispatch: AcceptorDispatch,
    private val venue: Venue,
) {
    /** What the service that runs the rules tells the replies it plays. */
    interface Venue {
        fun rules(): List<CompiledRule>

        fun dictionary(): FixDictionary?

        /** The rule's number on its card, or null when the profile no longer holds it. */
        fun ruleNumberOf(rule: AcceptorResponseRule): Int?

        /** A rule fired for [sessionId]'s RFQ with only a timer having asked it. A message counts itself. */
        fun firedOnExpiry(sessionId: SessionID?, ruleNumber: Int?, rule: AcceptorResponseRule, at: LocalDateTime)

        fun ruleFired(sessionId: SessionID, ruleNumber: Int?, rule: AcceptorResponseRule, at: LocalDateTime)

        fun notDelivered(sessionId: SessionID, ruleNumber: Int?)
    }

    /**
     * **Plays a relay rule's reply**: resolves every step to the counterparties it reaches, records a trade the moment
     * the rule decides one, and schedules each send on its recipient's own session.
     *
     * Addresses are resolved first, against the RFQ as it stands after the trigger was recorded, because the opening
     * QuoteRequest must find the RFQ it just opened. The trade is decided second, on the callback thread, because a
     * second lift is already queued behind this one on the engine's single thread and must read `done`. Only then is
     * anything scheduled.
     */
    @Suppress("LongParameterList") // the trigger's own facts, each taken at the moment decision 4a names
    fun relay(
        rule: AcceptorResponseRule,
        incoming: Message,
        request: FixMessage,
        sessionId: SessionID,
        heldBefore: BookReading,
        latencyMillis: Long,
        quote: () -> QuoteReading?,
        order: () -> Map<String, String>?,
    ) {
        val ruleNumber = venue.ruleNumberOf(rule)
        val (trigger, plan) = relayVenue.plan(rule, incoming, request, venue.dictionary(), sessionId, quote, order)
        venue.ruleFired(sessionId, ruleNumber, rule, request.timestamp)
        plan.notDelivered.forEach { (_, recipient) -> venue.notDelivered(recipient.sessionId ?: sessionId, ruleNumber) }
        plan.sends.forEach { send ->
            val target = send.to?.sessionId ?: return@forEach
            val reason = LiveRelayVenue.reasonFor(rule, ruleNumber, request, send, heldBefore, trigger)
            dispatch.schedule(target, send.offsetMillis + latencyMillis, reason, send::build)
        }
    }

    /** The timer key an RFQ's expiry is armed under on the dispatch thread. */
    fun arm(rfqId: String, expireAt: Long) {
        // A millisecond past the expiry, because an RFQ reads expired only once its time is strictly behind it.
        dispatch.scheduleTimer(timerKey(rfqId), expireAt - System.currentTimeMillis() + 1) { expired(rfqId) }
    }

    fun disarm(rfqId: String) {
        dispatch.cancelTimer(timerKey(rfqId))
    }

    /**
     * **Ends an RFQ whose timer has run, and plays the rule for it**, on the dispatch thread the timer ran on.
     *
     * The book decides first whether there is anything to end: an RFQ that traded, passed or was refused while the
     * timer waited is not expired, and a timer that fires late finds nothing. With no rule for it, the RFQ simply ends.
     * Each send is scheduled like any relayed step, its reason joining it in Trace to the request that opened the RFQ.
     */
    @Suppress("TooGenericExceptionCaught") // the dispatch thread is shared by every venue reply
    fun expired(rfqId: String) {
        try {
            val entry = book.expire(rfqId) ?: return
            val rule =
                AcceptorResponder.firstMatchOnExpiry(venue.rules(), entry.opening, expiryReading(entry)) ?: return
            val ruleNumber = venue.ruleNumberOf(rule)
            val at = LocalDateTime.now()
            val (trigger, plan) = planExpiry(rule, entry)
            venue.firedOnExpiry(trigger.sessionId, ruleNumber, rule, at)
            plan.notDelivered.forEach { (_, recipient) ->
                (recipient.sessionId ?: trigger.sessionId)?.let { venue.notDelivered(it, ruleNumber) }
            }
            plan.sends.forEach { send ->
                val target = send.to?.sessionId ?: return@forEach
                val reason = LiveRelayVenue.reasonOnExpiry(rule, ruleNumber, at, send, trigger, entry.openingUid)
                dispatch.schedule(target, send.offsetMillis, reason, send::build)
            }
        } catch (e: Exception) {
            logger.error("Acceptor could not play the rule for expired {}: {}", rfqId, e.message, e)
        }
    }

    /**
     * [rule]'s reply to [entry] expiring. The requester stands where a sender would, and its opening QuoteRequest is
     * the message `${req.<tag>}` reads, so `${req.55}` is the issue that was asked for.
     */
    private fun planExpiry(rule: AcceptorResponseRule, entry: RfqEntry): Pair<RelayTrigger, RelayPlan> {
        val trigger =
            RelayTrigger(
                sessionId = relayVenue.sessionOf(entry.requesterCompId),
                sessionKey = entry.requesterKey,
                compId = entry.requesterCompId,
                msgType = WHEN_RFQ_EXPIRES,
                fields = entry.opening,
                rfqId = entry.rfqId,
            )
        val opening =
            Message().apply {
                entry.opening.forEach { (tag, value) ->
                    if (tag == TAG_MSG_TYPE) header.setString(tag, value) else setString(tag, value)
                }
            }
        val plan = AcceptorResponder.planRelay(rule, opening, request = null, venue.dictionary(), relayVenue, trigger)
        plan.notDelivered.forEach { (_, owed) -> book.notDelivered(entry.rfqId, owed.sessionKey, owed.compId) }
        return trigger to plan
    }

    /** What a rule on expiry is judged against: the requester as sender, the RFQ expired, and what still stands. */
    fun expiryReading(entry: RfqEntry): VenueReading {
        val expired = RfqReading(entry.rfqId, entry, RfqConstraint.EXPIRED.word)
        return VenueReading(
            senderRole = PartyRole.REQUESTER,
            rfqBy131 = expired,
            rfqBy117 = expired,
            respondersOnline = relayVenue.respondersOnline(),
            quotesStanding = relayVenue.standing(entry).isNotEmpty(),
        )
    }

    private fun timerKey(rfqId: String) = "rfq-expiry:$rfqId"

    private companion object {
        val logger = org.slf4j.LoggerFactory.getLogger(RelayReplies::class.java)
        const val TAG_MSG_TYPE = 35
    }
}
