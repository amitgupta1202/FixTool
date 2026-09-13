package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.QuoteConstraint
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher
import java.math.BigDecimal
import java.math.RoundingMode

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
 * and Text (58). QuoteStatusReport requires a QuoteID and a Symbol, and nothing on a QuoteResponse can
 * supply the QuoteID but its own optional 117, so every refusal is conditioned on 117 being present and
 * the one QuoteResponse that lacks it gets a BusinessMessageReject. That is the preset discipline: a
 * rule never reads a tag its trigger does not guarantee.
 *
 * ### Nothing here is derived any more
 *
 * The first slice of this venue derived its QuoteID from the QuoteReqID and quoted a fixed price, so a
 * load run's second phase could address a quote it had never seen and hit a price it had never been
 * told. Both were the shape of the tooling showing through, and a client written against them would
 * pass here and fail against any real venue: an opaque QuoteID is the one thing every RFQ venue has in
 * common, and a firm price that never moves is the one thing none of them has.
 *
 * So the QuoteID is `${uuid}` and each side is drawn from its own band. What replaced the derivation is
 * the venue's own memory — `QuoteBookService` — and the two things it made expressible: a trigger that
 * compares the client's price against **the price this quote actually carried**
 * ([Matcher.QuoteField]), and a reply that books at it (`${quote.offer}`). A load run addresses the
 * quotes by capturing 117 and 133 off each reply, which is what a client does.
 *
 * ### The bands, and why they cannot overlap
 *
 * Each side is drawn from `[floor, floor + 9 ticks]` on the pair's own tick — the *pipette*, so a
 * five-decimal price does not always end in `0`. The bid band starts at the pair's `bidFloor` and the
 * offer band a full spread above it, so **no draw can put a bid at or above an offer**: an inverted
 * quote is not a quote, and a venue that emitted one would be teaching a client to accept one.
 * `RfqVenuePresetTest` asserts the bands are disjoint rather than trusting the arithmetic here.
 *
 * ### What an RFQ client can read off every quote
 *
 * Three things this venue once got wrong, and the fixed-income desk got right first:
 *
 * - **A disclosed side is quoted one way.** Two-way is for a client who withheld direction. A request naming
 *   `54=1` is shown the offer and nothing else, with `Side(54)` on the quote naming the side shown.
 * - **The quote says it is tradeable**: `QuoteType(537)=1`, because indicative-versus-firm is the first thing a
 *   client needs to know about a price, and this venue books at the price it showed.
 * - **Cover (`694=4`) and Done Away (`694=5`) are answered, not refused.** They are the ordinary end of a
 *   multi-dealer negotiation the venue did not win, and the level is withdrawn (`297=6`).
 */
object RfqVenuePreset {
    /** The id [AcceptorPresets.byId] answers to, and what the RFQ example workspace carries. */
    const val ID = "rfq-venue"

    /** How long a quote stands. Thirty seconds: long enough to hit by hand, short enough to test expiry. */
    const val VALIDITY_SECONDS = 30

    /** Ticks of movement in each band. Nine, so the band's own width is visible in the prices. */
    private const val BAND_TICKS = 9

    // ------------------------------------------------------------------ the bands

    /** One side's closed range, as the two strings a `${random:…}` is written from. */
    internal data class Band(
        val low: String,
        val high: String,
    )

    /** One pair, priced as two bands. */
    internal data class Quoted(
        val pair: FxVenuePreset.FxPair,
        val bid: Band,
        val offer: Band,
    ) {
        val symbol: String get() = pair.symbol
    }

    internal val QUOTED: List<Quoted> =
        FxVenuePreset.PAIRS.map { pair ->
            val floor = BigDecimal(pair.bidFloor)
            Quoted(pair, bid = band(pair, floor), offer = band(pair, floor + BigDecimal(pair.spread)))
        }

    /** `[from, from + 9 ticks]`, both ends at the pair's own decimals so the wire never carries `1.0899E+0`. */
    private fun band(pair: FxVenuePreset.FxPair, from: BigDecimal): Band {
        fun at(value: BigDecimal) = value.setScale(pair.decimals, RoundingMode.HALF_UP).toPlainString()
        return Band(at(from), at(from + BigDecimal(pair.tick) * BigDecimal(BAND_TICKS)))
    }

    private val SYMBOLS = QUOTED.map { it.symbol }

    // ------------------------------------------------------------------ templates

    /**
     * A quote, priced by the venue and valid for [validitySeconds]: **two-way** for a request that named no side,
     * and **one way** — the offer to a buyer, the bid to a seller, with `54` naming the side shown — for one that
     * named [side]. The other side is the venue's own business.
     *
     * `117` is opaque, so the only way to answer this quote is to have read it. The sizes echo the request's `38`,
     * because an RFQ names an amount and quoting a different one would be the venue answering a question nobody
     * asked.
     */
    private fun quote(quoted: Quoted, validitySeconds: Int, side: String? = null): String {
        val decimals = quoted.pair.decimals
        val bid = side != BUY
        val offer = side != SELL
        return listOfNotNull(
            "35=S",
            "131=\${req.131}",
            "117=\${uuid}",
            "55=${quoted.symbol}",
            side?.let { "54=$it" },
            TRADEABLE,
            "132=${random(quoted.bid, decimals)}".takeIf { bid },
            "133=${random(quoted.offer, decimals)}".takeIf { offer },
            "134=\${req.38}".takeIf { bid },
            "135=\${req.38}".takeIf { offer },
            "15=${quoted.pair.quoteCurrency}",
            "62=\${utcnow+${validitySeconds}s}",
            "60=\${utcnow}",
        ).joinToString("|")
    }

    /** `QuoteType(537)=1`, Tradeable: this venue books at the price it showed. */
    private const val TRADEABLE = "537=1"

    private const val BUY = "1"
    private const val SELL = "2"

    /** `${random:low:high:decimals}` — rendered natively, so a burst of four thousand costs microseconds. */
    private fun random(band: Band, decimals: Int): String = "\${random:${band.low}:${band.high}:$decimals}"

    /**
     * The refusal a request without a size earns. NoRelatedSym is required on a QuoteRequestReject,
     * hence the group, as the FX venue's own reject builds it.
     */
    private const val QUOTE_REQUEST_NO_SIZE =
        "35=AG|131=\${req.131}|658=99|146=1|55=\${req.55}|58=QuoteRequest without OrderQty: this venue quotes a size"

    /**
     * A QuoteStatusReport.
     *
     * [symbol] is a parameter rather than a constant because it has two honest sources. For a quote
     * this venue sent it is `${quote.symbol}`, the venue's own record. For a quote it never sent there
     * is nothing to read, so the client's own `55` is the only thing that can name the instrument, and
     * the rule that uses it requires `55` to be there.
     */
    private fun quoteStatus(status: String, symbol: String, text: String? = null): String =
        listOfNotNull(
            "35=AI",
            "117=\${req.117}",
            "693=\${req.693}",
            "55=$symbol",
            "297=$status",
            text?.let { "58=$it" },
            "60=\${utcnow}",
        ).joinToString("|")

    /**
     * The booked trade, priced from **the quote's own record** rather than from anything the client
     * sent. `693` ties the report to the response that caused it, `11` to the trade the client named,
     * which is how a load run's third phase matches it.
     */
    private fun trade(side: String): String =
        AcceptorPresets.executionReport(
            "150=F",
            "39=2",
            AcceptorPresets.ORDER_ECHO,
            "693=\${req.693}",
            "14=\${req.38}",
            "151=0",
            "32=\${req.38}",
            "31=\${quote.$side}",
            "6=\${quote.$side}",
        )

    /**
     * `380=5` is BusinessRejectReason *Conditionally required field missing*, and `379` names the
     * response. It answers two cases at once, and its text says both: a response with no QuoteID, and
     * a response about a quote this venue did not send with no Symbol to name the instrument with.
     * Neither can be answered with a QuoteStatusReport, which requires both.
     */
    private const val CANNOT_ANSWER =
        "35=j|372=AJ|379=\${req.693}|380=5|" +
            "58=A QuoteResponse needs QuoteID (117), and one naming a quote this venue did not send needs Symbol (55)"

    private const val REJECTED = "5"
    private const val WITHDRAWN = "6"
    private const val COVERED = "Noted: cover. The quote is withdrawn"
    private const val DONE_AWAY = "Noted: done away. The quote is withdrawn"
    private const val PASS = "11"
    private const val NOT_FOUND = "9"
    private const val EXPIRED = "7"

    private const val QUOTE_SYMBOL = "\${quote.symbol}"

    // ------------------------------------------------------------------ conditions, named

    private val quoteIdPresent = AcceptorPresets.condition(117, Matcher.Presence)
    private val symbolPresent = AcceptorPresets.condition(55, Matcher.Presence)
    private val clOrdIdPresent = AcceptorPresets.condition(11, Matcher.Presence)
    private val quantityPresent = AcceptorPresets.condition(38, Matcher.Presence)
    private val pricedPair = AcceptorPresets.condition(55, Matcher.OneOf(SYMBOLS))

    /** The instrument the client named has to be the instrument the venue quoted. */
    private val quotedSymbol = AcceptorPresets.condition(55, Matcher.QuoteField("symbol"))

    private fun respType(value: String) = AcceptorPresets.condition(694, Matcher.Exact(value))

    // ------------------------------------------------------------------ 35=R

    private fun quoteRule(quoted: Quoted, validitySeconds: Int) =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions = listOf(AcceptorPresets.condition(55, Matcher.Exact(quoted.symbol)), quantityPresent),
            steps = listOf(ResponseStep(quote(quoted, validitySeconds))),
        )

    /** A pair's two one-way rules: its two-way rule plus a `54`, sell declared first so the buy reads first. */
    private fun oneWayRules(quoted: Quoted, validitySeconds: Int) =
        listOf(SELL, BUY).map { side ->
            AcceptorResponseRule(
                whenMsgType = "R",
                conditions =
                    listOf(
                        AcceptorPresets.condition(55, Matcher.Exact(quoted.symbol)),
                        quantityPresent,
                        AcceptorPresets.condition(54, Matcher.Exact(side)),
                    ),
                steps = listOf(ResponseStep(quote(quoted, validitySeconds, side))),
            )
        }

    private val quoteNoSize =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions = listOf(pricedPair),
            steps = listOf(ResponseStep(QUOTE_REQUEST_NO_SIZE)),
        )

    // ------------------------------------------------------------------ 35=AJ, judged against the book

    /**
     * **The three states a quote can be in that owe the client a refusal**, and they are first because
     * every rule below them is written for a quote that is live.
     *
     * Each is a real question a venue is asked every day, and before the quote book none of them was
     * expressible: nothing on the incoming message distinguishes a hit on a live quote from a hit that
     * arrived too late, a second hit on one already answered, or a hit on a quote this venue never sent.
     */
    private val unknownQuote =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, symbolPresent),
            whenQuote = QuoteConstraint.UNKNOWN,
            steps = listOf(ResponseStep(quoteStatus(NOT_FOUND, "\${req.55}", "This venue did not send that quote"))),
        )

    private val expiredQuote =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent),
            whenQuote = QuoteConstraint.EXPIRED,
            steps =
                listOf(
                    ResponseStep(quoteStatus(EXPIRED, QUOTE_SYMBOL, "The quote expired before this arrived")),
                ),
        )

    private val doneQuote =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent),
            whenQuote = QuoteConstraint.DONE,
            steps = listOf(ResponseStep(quoteStatus(REJECTED, QUOTE_SYMBOL, "Quote already answered"))),
        )

    /**
     * A hit or lift on the quoted side **at the price the quote actually carried**, with a trade named
     * and sized. Booked, at the venue's own number.
     *
     * One rule per side rather than one per pair and side: the symbol and the price are read from the
     * quote, so the three pairs are one rule. That is the whole of what the quote book bought here.
     */
    private fun bookingRule(side: String, field: String) =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions =
                listOf(
                    quoteIdPresent,
                    clOrdIdPresent,
                    quantityPresent,
                    respType("1"),
                    quotedSymbol,
                    AcceptorPresets.condition(54, Matcher.Exact(side)),
                    AcceptorPresets.condition(44, Matcher.QuoteField(field)),
                ),
            whenQuote = QuoteConstraint.OPEN,
            steps = listOf(ResponseStep(trade(field))),
        )

    /** A buy lifts the offer, a sell hits the bid. */
    private val buyHit = bookingRule(side = "1", field = "offer")
    private val sellHit = bookingRule(side = "2", field = "bid")

    /**
     * A hit on the quoted instrument at some other price. **[quotedSymbol] is the load-bearing condition**,
     * not decoration: without it this rule also caught a hit at the right price on the wrong pair, and
     * answered it "Price is not the quoted price", which is the one thing that was right about it. A client
     * reading that goes and checks its pricing, and finds nothing.
     */
    private val wrongPrice =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, clOrdIdPresent, quantityPresent, respType("1"), quotedSymbol),
            whenQuote = QuoteConstraint.OPEN,
            steps =
                listOf(
                    ResponseStep(
                        quoteStatus(REJECTED, QUOTE_SYMBOL, "Not the quoted price, or not a side this quote showed"),
                    ),
                ),
        )

    /**
     * A bookable hit that got past the two bookings and the price refusal, which leaves one thing it can
     * be: `55` is not the instrument the quote named, or is not there to be read.
     *
     * Said by *position* rather than by a condition, because the matcher has no "not the quote's field"
     * shape and inventing one to express a refusal would be the tail wagging the venue. The three rules
     * above it all carry [quotedSymbol], so anything arriving here failed it.
     */
    private val wrongInstrument =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, clOrdIdPresent, quantityPresent, respType("1")),
            whenQuote = QuoteConstraint.OPEN,
            steps =
                listOf(
                    ResponseStep(quoteStatus(REJECTED, QUOTE_SYMBOL, "Instrument is not the quoted one")),
                ),
        )

    private val cannotBook =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, respType("1")),
            whenQuote = QuoteConstraint.OPEN,
            steps =
                listOf(
                    ResponseStep(
                        quoteStatus(REJECTED, QUOTE_SYMBOL, "A hit needs ClOrdID (11) and OrderQty (38) to book"),
                    ),
                ),
        )

    private val counter =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, respType("2")),
            whenQuote = QuoteConstraint.OPEN,
            steps =
                listOf(
                    ResponseStep(
                        quoteStatus(REJECTED, QUOTE_SYMBOL, "Counter not accepted: this venue quotes firm"),
                    ),
                ),
        )

    /**
     * **Cover and Done Away end a negotiation the venue did not win**, and are answered rather than refused:
     * `297=6` is Removed from market, which is what has become of the level once the client says where the
     * trade went.
     */
    private val cover =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, respType("4")),
            whenQuote = QuoteConstraint.OPEN,
            steps = listOf(ResponseStep(quoteStatus(WITHDRAWN, QUOTE_SYMBOL, COVERED))),
        )

    private val doneAway =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, respType("5")),
            whenQuote = QuoteConstraint.OPEN,
            steps = listOf(ResponseStep(quoteStatus(WITHDRAWN, QUOTE_SYMBOL, DONE_AWAY))),
        )

    private val pass =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, respType("6")),
            whenQuote = QuoteConstraint.OPEN,
            steps = listOf(ResponseStep(quoteStatus(PASS, QUOTE_SYMBOL))),
        )

    private val otherResponse =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent),
            whenQuote = QuoteConstraint.OPEN,
            steps =
                listOf(
                    ResponseStep(
                        quoteStatus(
                            REJECTED,
                            QUOTE_SYMBOL,
                            "QuoteRespType not accepted: hit or lift, counter, pass, cover or done away",
                        ),
                    ),
                ),
        )

    private val cannotAnswer =
        AcceptorResponseRule(whenMsgType = "AJ", steps = listOf(ResponseStep(CANNOT_ANSWER)))

    // ------------------------------------------------------------------ the bundle

    /**
     * Declared backwards to read forwards, as the FX venue is: [AcceptorPresets.insert] places each
     * conditioned rule above the first rule for its MsgType and appends an unconditioned one, so each
     * block below lists its conditioned rules last-first and its catch-all last. What the cards read is
     * asserted by `RfqVenuePresetTest`. Three things depend on it: the three quote-state refusals must
     * outrank everything, because every rule below them is written for a live quote, the bookings must
     * outrank the wrong-price refusal or every hit is refused, and [wrongInstrument] must sit directly
     * under [wrongPrice], because position is the whole of what tells it the instrument was the fault.
     *
     * [validitySeconds] is a parameter for one reason: a test has to be able to watch a quote expire
     * without waiting thirty seconds for it.
     */
    internal fun rules(validitySeconds: Int): List<AcceptorResponseRule> =
        // 35=R: each pair one way for a disclosed side, then two-way, then a request without a size, then the FX
        // refusal by name. The one-way rules are the two-way ones plus a 54, so they must read first.
        listOf(quoteNoSize) +
            QUOTED.reversed().map { quoteRule(it, validitySeconds) } +
            QUOTED.reversed().flatMap { oneWayRules(it, validitySeconds) } +
            FxVenuePreset.quoteUnknownSymbol +
            // 35=AJ: what the book says first, then the bookings, then every other response answered.
            listOf(otherResponse, pass, doneAway, cover, counter, cannotBook, wrongInstrument, wrongPrice) +
            listOf(sellHit, buyHit) +
            listOf(doneQuote, expiredQuote, unknownQuote) +
            cannotAnswer

    val preset: AcceptorPreset =
        AcceptorPreset(
            id = ID,
            name = "RFQ venue: EUR/USD, GBP/USD, USD/JPY quoted live",
            group = AcceptorPresets.GROUP_BUNDLES,
            summary =
                "${rules(VALIDITY_SECONDS).size} rules · a fresh firm price per quote · one-way on a disclosed " +
                    "side · hits booked at the quoted price · cover and done away answered · stale, spent and " +
                    "unknown quotes refused by name",
            rules = rules(VALIDITY_SECONDS),
        )
}
