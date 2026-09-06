package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher
import java.math.BigDecimal

/**
 * **An RFQ desk, as rules you can read**: the second shipped example, and the first real use case for a
 * load run. A client asks for a quote, is quoted, hits or lifts it, and is booked. Everything that cannot
 * be booked is answered, never left in silence.
 *
 * It lives beside [FxVenuePreset] and composes what it shares by name: the pair table, so the two venues
 * cannot disagree about what is priced, the unknown-symbol refusal, and [AcceptorPresets.executionReport]
 * for the trade report. The catalogue is still single, [AcceptorPresets.all] includes [preset].
 *
 * ### The dictionary decided the shapes
 *
 * FIX 4.4's QuoteResponse carries no QuoteReqID, its ExecutionReport carries neither QuoteID nor
 * QuoteReqID, and its QuoteStatusReport has no QuoteRejectReason. So the trade report ties back by
 * QuoteRespID (693) and ClOrdID (11), which both do carry, and a refusal says why in QuoteStatus (297)
 * and Text (58). QuoteStatusReport requires a QuoteID, and nothing on a QuoteResponse can supply one
 * but the QuoteResponse's own optional 117, so every refusal is conditioned on 117 being present and
 * the one QuoteResponse that lacks it gets a BusinessMessageReject. That is the preset discipline: a
 * rule never reads a tag its trigger does not guarantee.
 *
 * ### Why the QuoteID follows from the QuoteReqID
 *
 * `117=Q-${req.131}`, not `${uuid}`. A load run's second phase has to address the quotes the first
 * phase created without having seen them, and a client can only do that if the QuoteID follows from
 * the QuoteReqID it chose. The FX venue mints a fresh id per quote, and for a venue nobody drives at
 * load that is the more realistic choice. This venue exists to be driven at load.
 *
 * ### Why the prices do not move
 *
 * The FX venue draws a price per quote. This venue quotes the FX pair table's bid floor and its offer
 * with no draw, for two reasons that are the same reason: a second load phase has to know the price to
 * hit without having seen the quote, and "a hit at a price other than the quoted one is refused" can
 * only be a rule if the rule knows the quoted price. The numbers are computed once here with
 * `BigDecimal` from the pair's own string literals, so `1.08990 + 2.0E-4` is exactly `1.09010` and the
 * quote's `133` and the booking rule's condition on `44` are the same string.
 */
object RfqVenuePreset {
    /** The id [AcceptorPresets.byId] answers to, and what the RFQ example workspace carries. */
    const val ID = "rfq-venue"

    // ------------------------------------------------------------------ the prices, fixed

    /** One pair's firm two-way price. Both sides are the strings the wire and the conditions share. */
    internal data class FirmPrice(
        val pair: FxVenuePreset.FxPair,
        val bid: String,
        val offer: String,
    ) {
        val symbol: String get() = pair.symbol
    }

    internal val PRICES: List<FirmPrice> =
        FxVenuePreset.PAIRS.map { pair ->
            val bid = BigDecimal(pair.bidFloor).setScale(pair.decimals)
            val offer = (BigDecimal(pair.bidFloor) + BigDecimal(pair.spread)).setScale(pair.decimals)
            FirmPrice(pair, bid.toPlainString(), offer.toPlainString())
        }

    private val SYMBOLS = PRICES.map { it.symbol }

    // ------------------------------------------------------------------ templates

    /**
     * A firm two-way quote. `131` echoes the request, `117` is derived from it, the sizes echo the
     * request's `38`, and `62` is a minute out because the shorthand's finest unit is the minute.
     */
    private fun quote(price: FirmPrice): String =
        listOf(
            "35=S",
            "131=\${req.131}",
            "117=Q-\${req.131}",
            "55=${price.symbol}",
            "132=${price.bid}",
            "133=${price.offer}",
            "134=\${req.38}",
            "135=\${req.38}",
            "15=${price.pair.quoteCurrency}",
            "62=\${utcnow+1min}",
            "60=\${now}",
        ).joinToString("|")

    /**
     * The refusal a request without a size earns. An RFQ names an amount, and quoting a size the client
     * never asked for would be the venue answering a question nobody asked. NoRelatedSym is required
     * on a QuoteRequestReject, hence the group, as the FX venue's own reject builds it.
     */
    private const val QUOTE_REQUEST_NO_SIZE =
        "35=AG|131=\${req.131}|658=99|146=1|55=\${req.55}|58=QuoteRequest without OrderQty: this venue quotes a size"

    /**
     * A QuoteStatusReport. `117` and `55` are required on it and `693` is required on the trigger, so
     * the only read that needs a condition is `117`, and every rule using this carries one.
     */
    private fun quoteStatus(status: String, text: String? = null): String =
        listOfNotNull(
            "35=AI",
            "117=\${req.117}",
            "693=\${req.693}",
            "55=\${req.55}",
            "297=$status",
            text?.let { "58=$it" },
            "60=\${now}",
        ).joinToString("|")

    /**
     * The booked trade, priced from the rule's own content: a hit is conditioned on `44` being the
     * quoted side, so `31` and `6` are that side, and one fill has one price. `693` ties the report to
     * the response that caused it, `11` to the trade the client named, which is how a load run's second
     * phase matches it.
     */
    private fun trade(price: String): String =
        AcceptorPresets.executionReport(
            "150=F",
            "39=2",
            AcceptorPresets.ORDER_ECHO,
            "693=\${req.693}",
            "14=\${req.38}",
            "151=0",
            "32=\${req.38}",
            "31=$price",
            "6=$price",
        )

    /** `380=5` is BusinessRejectReason *Conditionally required field missing*, and `379` names the response. */
    private const val NO_QUOTE_ID_REJECT =
        "35=j|372=AJ|379=\${req.693}|380=5|58=QuoteID (117) is required to answer a quote"

    private const val REJECTED = "5"
    private const val PASS = "11"

    // ------------------------------------------------------------------ conditions, named

    private val quoteIdPresent = AcceptorPresets.condition(117, Matcher.Presence)
    private val clOrdIdPresent = AcceptorPresets.condition(11, Matcher.Presence)
    private val quantityPresent = AcceptorPresets.condition(38, Matcher.Presence)
    private val pricedPair = AcceptorPresets.condition(55, Matcher.OneOf(SYMBOLS))

    private fun respType(value: String) = AcceptorPresets.condition(694, Matcher.Exact(value))

    // ------------------------------------------------------------------ 35=R

    private fun quoteRule(price: FirmPrice) =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions = listOf(AcceptorPresets.condition(55, Matcher.Exact(price.symbol)), quantityPresent),
            steps = listOf(ResponseStep(quote(price))),
        )

    private val quoteNoSize =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions = listOf(pricedPair),
            steps = listOf(ResponseStep(QUOTE_REQUEST_NO_SIZE)),
        )

    // ------------------------------------------------------------------ 35=AJ

    /** A hit or lift on the quoted side, at the quoted price, with a trade named and sized. Booked. */
    private fun bookingRule(price: FirmPrice, side: String, at: String) =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions =
                listOf(
                    quoteIdPresent,
                    clOrdIdPresent,
                    quantityPresent,
                    respType("1"),
                    AcceptorPresets.condition(55, Matcher.Exact(price.symbol)),
                    AcceptorPresets.condition(54, Matcher.Exact(side)),
                    AcceptorPresets.condition(44, Matcher.Exact(at)),
                ),
            steps = listOf(ResponseStep(trade(at))),
        )

    /** A buy lifts the offer, a sell hits the bid. */
    private fun bookingRules(price: FirmPrice) =
        listOf(bookingRule(price, side = "1", at = price.offer), bookingRule(price, side = "2", at = price.bid))

    private val wrongPrice =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, clOrdIdPresent, quantityPresent, respType("1"), pricedPair),
            steps = listOf(ResponseStep(quoteStatus(REJECTED, "Price is not the quoted price"))),
        )

    private val cannotBook =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, respType("1"), pricedPair),
            steps = listOf(ResponseStep(quoteStatus(REJECTED, "A hit needs ClOrdID (11) and OrderQty (38) to book"))),
        )

    private val counter =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, respType("2"), pricedPair),
            steps = listOf(ResponseStep(quoteStatus(REJECTED, "Counter not accepted: this venue quotes firm"))),
        )

    private val pass =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, respType("6"), pricedPair),
            steps = listOf(ResponseStep(quoteStatus(PASS))),
        )

    private val otherResponse =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, pricedPair),
            steps =
                listOf(
                    ResponseStep(quoteStatus(REJECTED, "QuoteRespType not accepted: hit or lift, counter, or pass")),
                ),
        )

    private val unknownSymbol =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent),
            steps = listOf(ResponseStep(quoteStatus(REJECTED, "Unknown symbol"))),
        )

    private val noQuoteId =
        AcceptorResponseRule(whenMsgType = "AJ", steps = listOf(ResponseStep(NO_QUOTE_ID_REJECT)))

    // ------------------------------------------------------------------ the bundle

    /**
     * Declared backwards to read forwards, as the FX venue is: [AcceptorPresets.insert] places each
     * conditioned rule above the first rule for its MsgType and appends an unconditioned one, so each
     * block below lists its conditioned rules last-first and its catch-all last. What the cards read is
     * asserted by `RfqVenuePresetTest`. Two things depend on it: the bookings must outrank the
     * wrong-price refusal or every hit is refused, and each catch-all must land at the foot of its own
     * block, which means declaring it before the next MsgType begins.
     */
    val preset: AcceptorPreset =
        AcceptorPreset(
            id = ID,
            name = "RFQ venue: EUR/USD, GBP/USD, USD/JPY quoted firm",
            group = AcceptorPresets.GROUP_BUNDLES,
            summary = "18 rules · firm quotes per pair · hits booked at the quoted price · every response answered",
            rules =
                // 35=R: the three priced pairs, then a request without a size, then the FX refusal by name.
                listOf(quoteNoSize) +
                    PRICES.reversed().map(::quoteRule) +
                    FxVenuePreset.quoteUnknownSymbol +
                    // 35=AJ: bookings, the two refusals a hit can earn, counter, pass, the rest, then the reject.
                    listOf(unknownSymbol, otherResponse, pass, counter, cannotBook, wrongPrice) +
                    PRICES.reversed().flatMap { bookingRules(it).reversed() } +
                    noQuoteId,
        )
}
