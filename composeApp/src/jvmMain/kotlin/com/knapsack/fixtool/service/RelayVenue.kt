package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.PartyRole
import com.knapsack.fixtool.model.RfqReading
import com.knapsack.fixtool.model.StepAddress
import quickfix.SessionID

/**
 * **What a relaying venue knows, as the rule engine may ask it.**
 *
 * The one seam between [AcceptorResponder], which stays pure, and a venue's sessions and RFQ book. The engine
 * asks it who an address reaches and what an id is called on the far side; [QuickFixService] answers from
 * the wire; a test answers from a table. The dry run answers from what its caller assumed.
 */
interface RelayVenue {
    /** Who [address] reaches for [trigger], resolved now — after the trigger has been recorded (decision R2). */
    fun resolve(address: StepAddress, trigger: RelayTrigger): Resolution

    /** `${to.<tag>}`: [tag] as [recipient] knows it on the trigger's RFQ, or null when it knows none. */
    fun toValue(recipient: Recipient, trigger: RelayTrigger, tag: Int): String?

    /** `${rfq.<name>}` for the trigger's RFQ, or null. */
    fun rfqField(trigger: RelayTrigger, name: String): String?
}

/**
 * The message a relay rule is answering, reduced to what addressing it needs.
 *
 * [rfqId] is the RFQ it belongs to **after** it was recorded, so the QuoteRequest that opens an RFQ has one.
 * [quote] is set when the trigger names a relayed quote (117): the reading that says whose it is.
 */
data class RelayTrigger(
    val sessionId: SessionID?,
    val sessionKey: String,
    val compId: String,
    val msgType: String?,
    val fields: Map<Int, String>,
    val rfqId: String?,
    val quote: RfqReading? = null,
)

/**
 * One counterparty a step reaches.
 *
 * [sessionId] is null only for a declared counterparty that has never connected, which is always
 * [Resolution.notDelivered]. [quoteId] is fixed when the address is resolved, for the responders reached
 * through a quote — the quoter, the cover, the others — so a step rendered later still names the quote that
 * was live when the rule decided, not whatever that responder has quoted since.
 */
data class Recipient(
    val sessionId: SessionID?,
    val sessionKey: String,
    val compId: String,
    val address: StepAddress,
    val quoteId: String? = null,
)

/** Who an address reaches: the counterparties a step will go to, and the ones it cannot. */
data class Resolution(
    val recipients: List<Recipient> = emptyList(),
    val notDelivered: List<Recipient> = emptyList(),
)

/** The judgement a trigger's venue conditions are made against, taken before the message is recorded. */
data class VenueReading(
    val senderRole: PartyRole?,
    /** The RFQ the message names through 131, or null when it carries none. */
    val rfqBy131: RfqReading?,
    /** The RFQ behind the quote id the message names through 117, or null when it carries none. */
    val rfqBy117: RfqReading?,
    val respondersOnline: Boolean,
)
