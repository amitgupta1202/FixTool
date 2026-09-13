package com.knapsack.fixtool.control

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.LegQuote
import com.knapsack.fixtool.model.RfqBookView
import com.knapsack.fixtool.model.RfqEntry
import com.knapsack.fixtool.model.RfqLeg
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.Recipient
import com.knapsack.fixtool.service.RelayPlan
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The control surface's JSON for a venue that relays: its RFQ book, and a relay rule's reply as a dry run plans it.
 * Kept out of ControlServer because nothing here reads a request or a session — each is a value in, a document out.
 */

/**
 * **A relaying venue's RFQ book**: every negotiation it is carrying, with each id the way each side knows it.
 *
 * Legs are per responder and say how that part ended. The venue quote id beside each dealer quote id is the pair
 * `${to.117}` reads, so a reader checking why a dealer was told the wrong quote can see the link itself.
 */
internal fun rfqBookJson(profile: String, view: RfqBookView, now: Long): JsonObject =
    buildJsonObject {
        put("profile", profile)
        put("evicted", view.evicted)
        put("rfqs", buildJsonArray { view.rfqs.forEach { add(rfqJson(it, now)) } })
    }

private fun rfqJson(rfq: RfqEntry, now: Long): JsonObject =
    buildJsonObject {
        put("rfqId", rfq.rfqId)
        put("state", rfq.lifeAt(now).word)
        put(
            "requester",
            buildJsonObject {
                put("compId", rfq.requesterCompId)
                put("quoteReqId", rfq.requesterQuoteReqId)
            },
        )
        rfq.symbol?.let { put("symbol", it) }
        rfq.securityId?.let { put("securityId", it) }
        rfq.side?.let { put("side", it) }
        rfq.qty?.let { put("qty", it) }
        val rfqLive = rfq.lifeAt(now).live
        put("legs", buildJsonArray { rfq.legs.forEach { add(legJson(it, now, rfqLive)) } })
    }

private fun legJson(leg: RfqLeg, now: Long, rfqLive: Boolean): JsonObject =
    buildJsonObject {
        put("compId", leg.compId)
        leg.venueQuoteReqId?.let { put("quoteReqId", it) }
        leg.outcome?.let { put("outcome", it.word) }
        put("quotes", buildJsonArray { leg.quotes.forEach { add(quoteJson(it, now, rfqLive)) } })
    }

/** A quote is live only while its RFQ is: one still within its own validity on an RFQ that is over cannot be dealt. */
private fun quoteJson(quote: LegQuote, now: Long, rfqLive: Boolean): JsonObject =
    buildJsonObject {
        put("quoteId", quote.dealerQuoteId)
        quote.venueQuoteId?.let { put("shownAs", it) }
        quote.bid?.let { put("bid", it) }
        quote.offer?.let { put("offer", it) }
        put("live", rfqLive && quote.liveAt(now))
    }

/**
 * A relay rule's reply as a dry run shows it: every send with the step it belongs to and who it goes to, then the
 * recipients a step was owed to and could not reach, and the steps that reached nobody at all.
 */
internal fun relayReplyJson(rule: AcceptorResponseRule, plan: RelayPlan): JsonArray =
    buildJsonArray {
        val steps = rule.sequence()
        plan.sends.forEach { planned ->
            add(
                buildJsonObject {
                    put("offsetMillis", planned.offsetMillis)
                    put("step", planned.authoredStep + 1)
                    planned.to?.let { put("to", recipientJson(it)) }
                    rendered(planned.render).fold(
                        onSuccess = { put("message", it.replace(FixMessageHelper.SOH, '|')) },
                        onFailure = { failure ->
                            put("unrendered", failure.message ?: "this step could not be built")
                            steps.getOrNull(planned.authoredStep)?.let { put("template", it.template) }
                        },
                    )
                },
            )
        }
        plan.notDelivered.forEach { (step, recipient) ->
            add(
                buildJsonObject {
                    put("step", step + 1)
                    put("to", recipientJson(recipient))
                    put("notDelivered", "${recipient.compId} is not logged on, so this step would not be sent")
                },
            )
        }
        plan.nobody.forEach { step ->
            add(
                buildJsonObject {
                    put("step", step + 1)
                    steps.getOrNull(step)?.to?.let { put("to", buildJsonObject { put("address", it) }) }
                    put("nobody", "this address reaches nobody on this RFQ, so the step sends nothing")
                },
            )
        }
    }

private fun recipientJson(recipient: Recipient): JsonObject =
    buildJsonObject {
        put("address", recipient.address.word)
        put("compId", recipient.compId)
    }

/** A dry run reports why a step could not be built as its answer, whatever the builder threw. */
@Suppress("TooGenericExceptionCaught")
private fun rendered(render: () -> String): Result<String> =
    try {
        Result.success(render())
    } catch (e: Exception) {
        Result.failure(e)
    }
