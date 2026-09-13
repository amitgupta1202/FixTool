package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.LegOutcome
import com.knapsack.fixtool.model.LegQuote
import com.knapsack.fixtool.model.PartyRole
import com.knapsack.fixtool.model.RelayRef
import com.knapsack.fixtool.model.RfqBookView
import com.knapsack.fixtool.model.RfqConstraint
import com.knapsack.fixtool.model.RfqEntry
import com.knapsack.fixtool.model.RfqLeg
import com.knapsack.fixtool.model.RfqLife
import com.knapsack.fixtool.model.RfqReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import quickfix.Message
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * **What a venue that relays between two parties is holding: every RFQ, across every session.**
 *
 * The first thing in a venue that spans sessions. The order book and the quote book are kept per
 * counterparty, because their ids are unique per counterparty; an RFQ is by nature two counterparties at once,
 * so this book is one per venue and keys everything by **(counterparty, id)** instead. See
 * `docs/rfq-relay-proposal.md`, decision 5, and the implementation plan's step 2.
 *
 * ### Where it learns from
 *
 * The wire, like the other books, with one exception. A requester's `35=R` arriving opens an entry; a
 * responder's `35=S` adds a quote to its leg; every message the venue **relays** — a sent message whose reason
 * carries a [RelayRef] — links the ids on it, for the counterparty it went to, back to the RFQ its trigger
 * belonged to. That link is what lets a dealer's later Quote find the buy side it answers, although the two
 * share no id once the venue has re-keyed them.
 *
 * The exception is [decideTrade]. A trade is recorded when the rule decides it, on the callback thread, rather
 * than when its fill reaches the wire, because the fills go out on the dispatch thread and a second lift is
 * already queued behind the first. Recording on the wire would let one RFQ trade twice (decision R3).
 *
 * ### What it answers
 *
 * [reading] is what a trigger asks, taken before the message is recorded. [entryFor], [link] and [toValue] are
 * what a relay reads after it has been, and are what step 3's addresses and `${to.…}` resolve through.
 */
@Suppress("TooManyFunctions") // one lock over one book: every reader and recorder has to be a member to share it
class RfqBookService(
    /** Epoch millis. Injected so a test can step past an expiry without sleeping. */
    private val clock: () -> Long = System::currentTimeMillis,
    initialCap: Int = DEFAULT_CAP,
    /** How long an RFQ lives when its request names no ExpireTime(126). Null: until something ends it. */
    private val defaultExpiryMillis: () -> Long? = { null },
) {
    private val logger = LoggerFactory.getLogger(RfqBookService::class.java)

    @Volatile
    private var cap: Int = initialCap.coerceAtLeast(1)

    /**
     * How one counterparty's id reaches an RFQ: which RFQ, and — when the id belongs to a responder's part in it —
     * which leg.
     */
    data class Link(
        val rfqId: String,
        /** The responder session this id belongs to, or null for an id of the requester's. */
        val legKey: String? = null,
        /** For a quote id the venue showed the requester: the responder's own quote id it stands for. */
        val dealerQuoteId: String? = null,
    )

    private data class LinkKey(
        val sessionKey: String,
        val tag: Int,
        val value: String,
    )

    private val rfqs = LinkedHashMap<String, RfqEntry>()
    private val links = HashMap<LinkKey, Link>()

    /** Every link and value recorded for an RFQ, so evicting it takes them with it. */
    private val linksByRfq = HashMap<String, MutableSet<LinkKey>>()

    /** The last value of each tag exchanged with one counterparty on one RFQ: `${to.<tag>}`. */
    private val toValues = HashMap<Triple<String, String, Int>, String>()
    private var nextId = 1L
    private var evicted = 0L

    private val view = MutableStateFlow(RfqBookView())

    // ------------------------------------------------------------------ readings

    /**
     * **What the book says about the RFQ [fields] names through [tag]**, for [sessionKey], as it stands now.
     *
     * On 131 the RFQ is found by the QuoteReqID the sender used or was given. On 117 it is found by the quote id
     * the sender was given, and then `open` also requires that quote to be its leg's current one: a lift of a
     * level its dealer has since replaced reads `done`, because that price no longer stands.
     */
    @Synchronized
    fun reading(sessionKey: String, tag: Int, fields: Map<Int, String>): RfqReading {
        val link = fields[tag]?.takeIf { it.isNotBlank() }?.let { links[LinkKey(sessionKey, tag, it)] }
        val entry = link?.let { rfqs[it.rfqId] } ?: return RfqReading.unknown()
        val now = clock()
        val leg = link.legKey?.let { entry.leg(it) }
        val quote = link.dealerQuoteId?.let { id -> leg?.quotes?.lastOrNull { it.dealerQuoteId == id } }
        // On 117, a quote that no longer stands — replaced, lapsed, or never found — is not a quote that can still be
        // traded, whatever the RFQ around it is doing.
        val quoteGone = tag == TAG_QUOTE_ID && link.dealerQuoteId != null && !stillStands(leg, quote, now)
        val word =
            when (entry.lifeAt(now)) {
                RfqLife.EXPIRED -> RfqConstraint.EXPIRED
                RfqLife.REFUSED, RfqLife.PASSED, RfqLife.DONE -> RfqConstraint.DONE
                RfqLife.REQUESTED, RfqLife.OPEN -> if (quoteGone) RfqConstraint.DONE else RfqConstraint.OPEN
            }
        return RfqReading(entry.rfqId, entry, word.word, leg, quote)
    }

    /** True when [quote] was found and is still its leg's current quote. */
    private fun stillStands(leg: RfqLeg?, quote: LegQuote?, now: Long): Boolean =
        quote != null && leg?.currentQuote(now) == quote

    /** The RFQ [fields] belong to for [sessionKey], found through 131 then 117, as it stands now. */
    @Synchronized
    fun entryFor(sessionKey: String, fields: Map<Int, String>): RfqEntry? =
        sequenceOf(TAG_QUOTE_REQ_ID, TAG_QUOTE_ID, TAG_QUOTE_RESP_ID, TAG_CL_ORD_ID)
            .mapNotNull { tag -> fields[tag]?.let { links[LinkKey(sessionKey, tag, it)] } }
            .firstNotNullOfOrNull { rfqs[it.rfqId] }

    /** How [value] in [tag], seen on [sessionKey], reaches an RFQ — or null. */
    @Synchronized
    fun link(sessionKey: String, tag: Int, value: String): Link? = links[LinkKey(sessionKey, tag, value)]

    @Synchronized
    fun entry(rfqId: String): RfqEntry? = rfqs[rfqId]

    /** The last [tag] exchanged with [sessionKey] on [rfqId], sent or received. */
    @Synchronized
    fun toValue(rfqId: String, sessionKey: String, tag: Int): String? = toValues[Triple(rfqId, sessionKey, tag)]

    fun views(): StateFlow<RfqBookView> = view.asStateFlow()

    @Synchronized
    fun size(): Int = rfqs.size

    @Synchronized
    fun evicted(): Long = evicted

    // ------------------------------------------------------------------ recording

    /**
     * Records one application message the venue received or sent on [sessionKey], whose counterparty is
     * [compId] playing [role] (null: not declared).
     *
     * Returns **true when the book claims the message**: a relayed `35=8`. It carries a ClOrdID the recipient's
     * order book never saw on an order, so offered there too it could only be filed as unattributed.
     */
    @Synchronized
    @Suppress("LongParameterList") // the facts of one message, named at the one call site that has them all
    fun record(
        sessionKey: String,
        compId: String,
        role: PartyRole?,
        sent: Boolean,
        fields: Map<Int, String>,
        relay: RelayRef? = null,
        messageUid: Long? = null,
    ): Boolean {
        val msgType = fields[TAG_MSG_TYPE] ?: return false
        val claimed =
            if (sent) {
                recordSent(sessionKey, msgType, fields, relay)
            } else {
                recordReceived(sessionKey, compId, role, fields, messageUid)
                false
            }
        publish()
        return claimed
    }

    private fun recordReceived(
        sessionKey: String,
        compId: String,
        role: PartyRole?,
        fields: Map<Int, String>,
        messageUid: Long?,
    ) {
        val msgType = fields[TAG_MSG_TYPE]
        when {
            msgType == MSG_QUOTE_REQUEST && role == PartyRole.REQUESTER -> open(sessionKey, compId, fields, messageUid)
            msgType == MSG_QUOTE && role == PartyRole.RESPONDER -> quoted(sessionKey, fields)
            msgType == MSG_QUOTE_REQUEST_REJECT && role == PartyRole.RESPONDER -> passedByResponder(sessionKey, fields)
            msgType == MSG_QUOTE_RESPONSE && role == PartyRole.REQUESTER -> answeredByRequester(sessionKey, fields)
        }
        // Whatever it was, a message on an RFQ this counterparty is part of refreshes what `${to.…}` reads.
        entryFor(sessionKey, fields)?.let { entry -> remember(entry.rfqId, sessionKey, fields) }
    }

    /** A requester's QuoteRequest: a new RFQ, requested and relayed to nobody yet. */
    private fun open(sessionKey: String, compId: String, fields: Map<Int, String>, messageUid: Long?) {
        val reqId = fields[TAG_QUOTE_REQ_ID]?.takeIf { it.isNotBlank() } ?: return
        val now = clock()
        val rfqId = "RFQ-${nextId++}"
        val expireAt = fields[TAG_EXPIRE_TIME]?.let(::utcMillisOf) ?: defaultExpiryMillis()?.let { now + it }
        rfqs[rfqId] =
            RfqEntry(
                rfqId = rfqId,
                requesterKey = sessionKey,
                requesterCompId = compId,
                requesterQuoteReqId = reqId,
                openingUid = messageUid,
                opening = fields,
                openedAt = now,
                expireAt = expireAt,
                life = RfqLife.REQUESTED,
            )
        linkId(sessionKey, TAG_QUOTE_REQ_ID, reqId, Link(rfqId))
        remember(rfqId, sessionKey, fields)
        evictIfNeeded()
    }

    /** A responder's Quote, found through the QuoteReqID the venue sent it. A new quote supersedes its last one. */
    private fun quoted(sessionKey: String, fields: Map<Int, String>) {
        val link = fields[TAG_QUOTE_REQ_ID]?.let { links[LinkKey(sessionKey, TAG_QUOTE_REQ_ID, it)] } ?: return
        val entry = rfqs[link.rfqId] ?: return
        val dealerQuoteId = fields[TAG_QUOTE_ID]?.takeIf { it.isNotBlank() } ?: return
        val quote =
            LegQuote(
                dealerQuoteId = dealerQuoteId,
                bid = fields[TAG_BID_PX],
                offer = fields[TAG_OFFER_PX],
                bidSize = fields[TAG_BID_SIZE],
                offerSize = fields[TAG_OFFER_SIZE],
                validUntil = fields[TAG_VALID_UNTIL]?.let(::utcMillisOf),
            )
        updateLeg(entry, sessionKey) { leg -> leg.copy(quotes = leg.quotes.map { it.copy(superseded = true) } + quote) }
        linkId(sessionKey, TAG_QUOTE_ID, dealerQuoteId, Link(entry.rfqId, sessionKey, dealerQuoteId))
    }

    private fun passedByResponder(sessionKey: String, fields: Map<Int, String>) {
        val link = fields[TAG_QUOTE_REQ_ID]?.let { links[LinkKey(sessionKey, TAG_QUOTE_REQ_ID, it)] } ?: return
        val entry = rfqs[link.rfqId] ?: return
        updateLeg(entry, sessionKey) { it.copy(outcome = LegOutcome.PASSED) }
    }

    /** A requester's QuoteResponse. Only a pass moves the RFQ here: a lift is a trade only once a rule decides it. */
    private fun answeredByRequester(sessionKey: String, fields: Map<Int, String>) {
        if (fields[TAG_QUOTE_RESP_TYPE] != RESP_TYPE_PASS) return
        val link = fields[TAG_QUOTE_ID]?.let { links[LinkKey(sessionKey, TAG_QUOTE_ID, it)] } ?: return
        val entry = rfqs[link.rfqId] ?: return
        if (entry.life.live) rfqs[entry.rfqId] = entry.copy(life = RfqLife.PASSED)
    }

    private fun recordSent(
        sessionKey: String,
        msgType: String,
        fields: Map<Int, String>,
        relay: RelayRef?,
    ): Boolean {
        if (relay == null) {
            if (msgType == MSG_QUOTE_REQUEST_REJECT) refusedByVenue(sessionKey, fields)
            return false
        }
        val entry = relay.rfqId?.let { rfqs[it] } ?: return false
        val toRequester = sessionKey == entry.requesterKey
        when {
            msgType == MSG_QUOTE_REQUEST && !toRequester -> asked(entry, sessionKey, fields, relay)
            msgType == MSG_QUOTE && toRequester -> shownToRequester(entry, sessionKey, fields, relay)
            msgType == MSG_QUOTE_RESPONSE -> toldResponder(entry, sessionKey, fields)
        }
        // Every correlation id the venue put on a relayed message, for the counterparty it went to — except the
        // ones the cases above linked more precisely.
        LINKED_TAGS.forEach { tag ->
            val value = fields[tag]?.takeIf { it.isNotBlank() } ?: return@forEach
            if (links[LinkKey(sessionKey, tag, value)] == null) {
                linkId(sessionKey, tag, value, Link(entry.rfqId, legKey = sessionKey.takeUnless { toRequester }))
            }
        }
        remember(entry.rfqId, sessionKey, fields)
        return msgType == MSG_EXECUTION_REPORT
    }

    /**
     * A refusal to the requester ends an RFQ the venue never relayed. The only unrelayed send that moves anything:
     * every other reply to a sender is about a message, not about the negotiation.
     */
    private fun refusedByVenue(sessionKey: String, fields: Map<Int, String>) {
        val link = fields[TAG_QUOTE_REQ_ID]?.let { links[LinkKey(sessionKey, TAG_QUOTE_REQ_ID, it)] }
        val entry = link?.let { rfqs[it.rfqId] }
        if (entry != null && entry.requesterKey == sessionKey && entry.life.live) {
            rfqs[entry.rfqId] = entry.copy(life = RfqLife.REFUSED)
        }
    }

    /** The request relayed to a responder: its leg, and the RFQ open once anybody has been asked. */
    private fun asked(entry: RfqEntry, sessionKey: String, fields: Map<Int, String>, relay: RelayRef) {
        val leg = RfqLeg(sessionKey, relay.recipientCompId, venueQuoteReqId = fields[TAG_QUOTE_REQ_ID])
        val legs = entry.legs.filterNot { it.responderKey == sessionKey } + leg
        val life = if (entry.life == RfqLife.REQUESTED) RfqLife.OPEN else entry.life
        rfqs[entry.rfqId] = entry.copy(legs = legs, life = life)
    }

    /**
     * A dealer's quote shown to the requester under the venue's own id: the link from that id back to the dealer's
     * quote is what a lift of it is resolved through.
     */
    private fun shownToRequester(entry: RfqEntry, sessionKey: String, fields: Map<Int, String>, relay: RelayRef) {
        val venueQuoteId = fields[TAG_QUOTE_ID] ?: return
        val dealerKey = relay.triggerSession
        val dealerQuote = entry.leg(dealerKey)?.quotes?.lastOrNull() ?: return
        val shown = dealerQuote.copy(venueQuoteId = venueQuoteId)
        updateLeg(entry, dealerKey) { leg -> leg.copy(quotes = leg.quotes.dropLast(1) + shown) }
        linkId(sessionKey, TAG_QUOTE_ID, venueQuoteId, Link(entry.rfqId, dealerKey, dealerQuote.dealerQuoteId))
    }

    /** A responder told how its quote ended, when it did not trade: cover, or done away. */
    private fun toldResponder(entry: RfqEntry, sessionKey: String, fields: Map<Int, String>) {
        val outcome =
            when (fields[TAG_QUOTE_RESP_TYPE]) {
                RESP_TYPE_COVER -> LegOutcome.COVER
                RESP_TYPE_DONE_AWAY -> LegOutcome.DONE_AWAY
                else -> return
            }
        updateLeg(entry, sessionKey) { it.copy(outcome = outcome) }
    }

    /**
     * **Records a trade the moment a rule decides it** — see the class doc, and decision R3.
     *
     * The RFQ is done, [quoterKey]'s leg is lifted, and every other leg's quotes stop standing, so a second lift
     * already queued behind this one reads `done` and is refused.
     */
    @Synchronized
    fun decideTrade(rfqId: String, quoterKey: String) {
        val entry = rfqs[rfqId] ?: return
        val legs =
            entry.legs.map { leg ->
                if (leg.responderKey == quoterKey) {
                    leg.copy(outcome = LegOutcome.LIFTED)
                } else {
                    leg.copy(quotes = leg.quotes.map { it.copy(superseded = true) })
                }
            }
        rfqs[rfqId] = entry.copy(life = RfqLife.DONE, legs = legs)
        publish()
    }

    /** Marks [responderKey]'s leg on [rfqId] as not delivered, for a relay that could not reach it. */
    @Synchronized
    fun notDelivered(rfqId: String, responderKey: String, compId: String) {
        val entry = rfqs[rfqId] ?: return
        val leg = entry.leg(responderKey) ?: RfqLeg(responderKey = responderKey, compId = compId)
        val owed = leg.copy(outcome = LegOutcome.NOT_DELIVERED)
        val legs = entry.legs.filterNot { it.responderKey == responderKey } + owed
        rfqs[rfqId] = entry.copy(legs = legs)
        publish()
    }

    // ------------------------------------------------------------------ housekeeping

    @Synchronized
    fun setCap(newCap: Int) {
        cap = newCap.coerceAtLeast(1)
        evictIfNeeded()
        publish()
    }

    @Synchronized
    fun clear() {
        rfqs.clear()
        links.clear()
        linksByRfq.clear()
        toValues.clear()
        evicted = 0
        publish()
    }

    private fun linkId(sessionKey: String, tag: Int, value: String, link: Link) {
        val key = LinkKey(sessionKey, tag, value)
        links[key] = link
        linksByRfq.getOrPut(link.rfqId) { mutableSetOf() } += key
    }

    private fun remember(rfqId: String, sessionKey: String, fields: Map<Int, String>) {
        REMEMBERED_TAGS.forEach { tag ->
            fields[tag]?.takeIf { it.isNotBlank() }?.let { toValues[Triple(rfqId, sessionKey, tag)] = it }
        }
    }

    private fun updateLeg(entry: RfqEntry, legKey: String, change: (RfqLeg) -> RfqLeg) {
        val current = rfqs[entry.rfqId] ?: entry
        val leg = current.leg(legKey) ?: return
        rfqs[entry.rfqId] = current.copy(legs = current.legs.map { if (it.responderKey == legKey) change(leg) else it })
    }

    /** Drops ended RFQs first, then the oldest, taking each one's links and remembered values with it. */
    private fun evictIfNeeded() {
        while (rfqs.size > cap) {
            val now = clock()
            val victim = rfqs.values.firstOrNull { !it.lifeAt(now).live }?.rfqId ?: rfqs.keys.first()
            rfqs.remove(victim)
            linksByRfq.remove(victim)?.forEach { links.remove(it) }
            toValues.keys.removeIf { it.first == victim }
            evicted++
        }
    }

    @Volatile
    private var lastPublishedAt = 0L

    @Volatile
    private var flushScheduled = false

    /**
     * Publishes the view **at most ten times a second, with a trailing flush**, for the reason the order book
     * does: copying every RFQ per message would make the cost of one message proportional to how much the book
     * holds, on the callback thread, for a panel that cannot draw faster than the screen. Call inside the lock.
     */
    private fun publish() {
        val now = System.nanoTime()
        if (now - lastPublishedAt >= PUBLISH_INTERVAL_NANOS) {
            lastPublishedAt = now
            view.value = RfqBookView(rfqs.values.toList(), evicted)
            return
        }
        if (!flushScheduled) {
            flushScheduled = true
            FLUSHER.schedule(
                {
                    synchronized(this) {
                        flushScheduled = false
                        lastPublishedAt = System.nanoTime()
                        view.value = RfqBookView(rfqs.values.toList(), evicted)
                    }
                },
                PUBLISH_INTERVAL_NANOS / 1_000_000,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }
    }

    /** The view as it stands this instant, flushing any change still inside the pacing window. */
    @Synchronized
    fun view(): RfqBookView = RfqBookView(rfqs.values.toList(), evicted).also { view.value = it }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun utcMillisOf(value: String): Long? {
        for (pattern in TIME_PATTERNS) {
            try {
                return LocalDateTime.parse(value.trim(), pattern).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (e: Exception) {
                continue
            }
        }
        logger.debug("'{}' is not a UTCTimestamp, so it sets no time", value)
        return null
    }

    companion object {
        const val DEFAULT_CAP = 5_000

        private const val PUBLISH_INTERVAL_NANOS = 100_000_000L

        /** One daemon thread for every RFQ book in the process, running only the trailing publish. */
        private val FLUSHER: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "rfq-book-publish").apply { isDaemon = true }
            }

        /** The tags an RFQ is followed by: the ids a relayed message is linked through. */
        private val LINKED_TAGS = listOf(TAG_QUOTE_REQ_ID, TAG_QUOTE_ID, TAG_QUOTE_RESP_ID, TAG_CL_ORD_ID, TAG_ORDER_ID)

        /** The tags `${to.<tag>}` can read. */
        private val REMEMBERED_TAGS = LINKED_TAGS + listOf(TAG_TRADE_REPORT_ID)

        /** Everything the book reads off a message. */
        private val READ_TAGS =
            setOf(
                TAG_MSG_TYPE,
                TAG_QUOTE_REQ_ID,
                TAG_QUOTE_ID,
                TAG_QUOTE_RESP_ID,
                TAG_CL_ORD_ID,
                TAG_ORDER_ID,
                TAG_TRADE_REPORT_ID,
                TAG_QUOTE_RESP_TYPE,
                TAG_BID_PX,
                TAG_OFFER_PX,
                TAG_BID_SIZE,
                TAG_OFFER_SIZE,
                TAG_VALID_UNTIL,
                TAG_EXPIRE_TIME,
                TAG_SYMBOL,
                TAG_SECURITY_ID,
                TAG_SECURITY_ID_SOURCE,
                TAG_SECURITY_TYPE,
                TAG_SIDE,
                TAG_ORDER_QTY,
            )

        private val TIME_PATTERNS =
            listOf(
                DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS"),
                DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss"),
            )

        /**
         * [message] reduced to the tags this book reads, **reading through repeating groups**.
         *
         * A conformant FIX 4.4 QuoteRequest carries its instrument, side, size and ExpireTime inside
         * `NoRelatedSym(146)`, where a flat read finds nothing. So this reads the way a trigger does — flat first,
         * then the first instance of each group — through [AcceptorResponder.valueOf].
         */
        fun fieldsOf(message: Message): Map<Int, String> =
            READ_TAGS.mapNotNull { tag -> AcceptorResponder.valueOf(message, tag)?.let { tag to it } }.toMap()
    }
}

private const val TAG_MSG_TYPE = 35
private const val TAG_CL_ORD_ID = 11
private const val TAG_ORDER_ID = 37
private const val TAG_ORDER_QTY = 38
private const val TAG_SECURITY_ID = 48
private const val TAG_SIDE = 54
private const val TAG_SYMBOL = 55
private const val TAG_SECURITY_ID_SOURCE = 22
private const val TAG_VALID_UNTIL = 62
private const val TAG_QUOTE_ID = 117
private const val TAG_EXPIRE_TIME = 126
private const val TAG_QUOTE_REQ_ID = 131
private const val TAG_BID_PX = 132
private const val TAG_OFFER_PX = 133
private const val TAG_BID_SIZE = 134
private const val TAG_OFFER_SIZE = 135
private const val TAG_SECURITY_TYPE = 167
private const val TAG_TRADE_REPORT_ID = 571
private const val TAG_QUOTE_RESP_ID = 693
private const val TAG_QUOTE_RESP_TYPE = 694

private const val MSG_QUOTE_REQUEST = "R"
private const val MSG_QUOTE = "S"
private const val MSG_QUOTE_REQUEST_REJECT = "AG"
private const val MSG_QUOTE_RESPONSE = "AJ"
private const val MSG_EXECUTION_REPORT = "8"

private const val RESP_TYPE_COVER = "4"
private const val RESP_TYPE_DONE_AWAY = "5"
private const val RESP_TYPE_PASS = "6"
