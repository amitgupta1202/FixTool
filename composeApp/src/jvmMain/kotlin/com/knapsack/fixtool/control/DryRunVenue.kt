package com.knapsack.fixtool.control

import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.PartyRole
import com.knapsack.fixtool.model.RfqConstraint
import com.knapsack.fixtool.model.RfqReading
import com.knapsack.fixtool.model.StepAddress
import com.knapsack.fixtool.model.roleOf
import com.knapsack.fixtool.service.AcceptorResponder
import com.knapsack.fixtool.service.Recipient
import com.knapsack.fixtool.service.RelayTrigger
import com.knapsack.fixtool.service.RelayVenue
import com.knapsack.fixtool.service.Resolution
import com.knapsack.fixtool.service.RfqBookService
import com.knapsack.fixtool.service.VenueReading
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * **A venue a dry run assumes**, built from what its caller said: who sent the message, what state its RFQ is in,
 * who holds which quote, and who is logged on.
 *
 * The same seam the live venue answers through ([RelayVenue]), so a dry run and a connected venue plan a relay with
 * the one planner. Every answer is one the caller gave or the profile declares; nothing is guessed. `online`
 * defaults to every counterparty the request names or the profile declares exactly, because a dry run is usually
 * asked about the day everyone is there.
 */
internal class DryRunVenue(
    private val counterparties: List<Counterparty>,
    val from: String?,
    private val rfqWord: String?,
    private val rfq: AssumedRfq,
) : RelayVenue {
    /** Who the caller said holds which part of the RFQ, and what each of them knows it by. */
    data class AssumedRfq(
        val requester: String? = null,
        val quoter: String? = null,
        val cover: String? = null,
        val asked: List<String> = emptyList(),
        /** Responder CompID to the quote id it holds. */
        val quotes: Map<String, String> = emptyMap(),
        /** CompID to the ids it knows, by tag — what `${to.<tag>}` reads. */
        val ids: Map<String, Map<Int, String>> = emptyMap(),
        val online: Set<String> = emptySet(),
        /** A dealer's quote id to the id the requester was shown it under. Absent: shown under its own. */
        val shown: Map<String, String> = emptyMap(),
    )

    fun reading(message: quickfix.Message): VenueReading {
        val word = rfqWord ?: RfqConstraint.UNKNOWN.word
        return VenueReading(
            senderRole = from?.let { roleOf(counterparties, it) },
            rfqBy131 = AcceptorResponder.valueOf(message, TAG_QUOTE_REQ_ID)?.let { RfqReading(null, null, word) },
            rfqBy117 = AcceptorResponder.valueOf(message, TAG_QUOTE_ID)?.let { RfqReading(null, null, word) },
            respondersOnline = rfq.online.any { roleOf(counterparties, it) == PartyRole.RESPONDER },
            quotesStanding = rfqWord?.takeIf { it != RfqConstraint.UNKNOWN.word }?.let { rfq.quotes.isNotEmpty() },
        )
    }

    fun trigger(message: quickfix.Message) =
        RelayTrigger(
            sessionId = null,
            sessionKey = from ?: "sender",
            compId = from ?: "sender",
            msgType = AcceptorResponder.valueOf(message, TAG_MSG_TYPE),
            fields = RfqBookService.fieldsOf(message),
            rfqId = if (rfqWord == null || rfqWord == RfqConstraint.UNKNOWN.word) null else "assumed",
        )

    override fun resolve(address: StepAddress, trigger: RelayTrigger): Resolution =
        when (address) {
            StepAddress.Sender -> reach(listOfNotNull(from), address)
            StepAddress.Requester -> reach(listOfNotNull(rfq.requester), address)
            // Once per quote the caller said stands, each named as the caller said the requester was shown it.
            StepAddress.Quotes -> quotesToRequester()
            StepAddress.Quoter -> reach(listOfNotNull(rfq.quoter), address)
            StepAddress.Cover -> reach(listOfNotNull(rfq.cover), address)
            StepAddress.Quoted -> reach(rfq.quotes.keys.toList(), address)
            StepAddress.Others -> reach(rfq.quotes.keys.filterNot { it == rfq.quoter || it == rfq.cover }, address)
            StepAddress.Asked -> reach(rfq.asked, address)
            StepAddress.Responders -> reach(responders(), address)
            is StepAddress.CompId -> reach(listOf(address.compId), address)
        }

    override fun toValue(recipient: Recipient, trigger: RelayTrigger, tag: Int): String? =
        if (tag == TAG_QUOTE_ID && recipient.quoteId != null) recipient.quoteId else rfq.ids[recipient.compId]?.get(tag)

    override fun rfqField(trigger: RelayTrigger, name: String): String? =
        when (name) {
            "requester" -> rfq.requester
            "quoter" -> rfq.quoter
            "asked" -> rfq.asked.size.toString()
            "quoted" -> rfq.quotes.size.toString()
            "state" -> rfqWord
            else -> null
        }

    private fun quotesToRequester(): Resolution {
        val requester = rfq.requester ?: return Resolution()
        val shown = rfq.quotes.values.map { dealerQuote -> rfq.shown[dealerQuote] ?: dealerQuote }
        val recipients = shown.map { Recipient(null, requester, requester, StepAddress.Quotes, it) }
        return if (requester in rfq.online) Resolution(recipients) else Resolution(notDelivered = recipients)
    }

    /** Declared responders named exactly, and the online members of a declared responder family. */
    private fun responders(): List<String> {
        val declared = counterparties.filter { PartyRole.byWord(it.role) == PartyRole.RESPONDER }
        val exact = declared.filterNot { it.isPrefix }.map { it.compId }
        val family = rfq.online.filter { compId -> declared.any { it.isPrefix && it.covers(compId) } }
        return exact + family
    }

    private fun reach(compIds: List<String>, address: StepAddress): Resolution {
        val (on, off) = compIds.distinct().partition { it in rfq.online }

        fun recipient(compId: String) = Recipient(null, compId, compId, address, rfq.quotes[compId])
        return Resolution(on.map(::recipient), off.map(::recipient))
    }

    companion object {
        /** The venue a dry-run request describes: `from`, `online`, and `rfq` with its parties, quotes and ids. */
        fun of(counterparties: List<Counterparty>, body: JsonObject, rfqWord: String?): DryRunVenue {
            val rfq = body["rfq"] as? JsonObject

            fun str(key: String) = rfq?.get(key)?.jsonPrimitive?.contentOrNull
            val quotes = (rfq?.get("quotes") as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
            val asked = (rfq?.get("asked") as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            val ids =
                (rfq?.get("ids") as? JsonObject).orEmpty().mapValues { (_, tags) ->
                    (tags as? JsonObject)
                        .orEmpty()
                        .mapNotNull { (tag, value) -> tag.toIntOrNull()?.let { it to value.jsonPrimitive.content } }
                        .toMap()
                }
            val from = body["from"]?.jsonPrimitive?.contentOrNull
            val named = listOfNotNull(from, str("requester"), str("quoter"), str("cover")) + asked + quotes.keys
            val online =
                (body["online"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()
                    ?: (counterparties.filterNot { it.isPrefix }.map { it.compId } + named).toSet()
            val shown = (rfq?.get("shown") as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
            val assumed = AssumedRfq(str("requester"), str("quoter"), str("cover"), asked, quotes, ids, online, shown)
            return DryRunVenue(counterparties, from, rfqWord, assumed)
        }
    }
}

private const val TAG_MSG_TYPE = 35
private const val TAG_QUOTE_ID = 117
private const val TAG_QUOTE_REQ_ID = 131
