package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.QuoteConstraint
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher

/**
 * **A fixed-income request-for-quote desk**: US Treasuries, quoted in price *and* yield, identified by
 * CUSIP, settling T+1.
 *
 * The [RfqVenuePreset] beside it plays the same negotiation — QuoteRequest, Quote, QuoteResponse — in
 * **FX**, and that is the difference worth having two of them for. The mechanics of an RFQ are the same
 * everywhere; the *vocabulary* is not, and a desk that trades bonds will not recognise itself in a venue
 * that quotes EUR/USD to five decimal places. What changes here:
 *
 * - **The instrument** is a **CUSIP** in `SecurityID(48)` with `SecurityIDSource(22)=1`, plus
 *   `SecurityType(167)=TNOTE`, `CouponRate(223)` and `MaturityDate(541)` — where the FX desk has a
 *   currency pair in `Symbol(55)` and nothing else.
 * - **The level** is **percent of par** (`PriceType(423)=1`) on the 32nd grid, *and* a yield in
 *   `BidYield(632)` / `OfferYield(634)`.
 * - **The size** is **nominal**, in round millions.
 * - **Settlement** is said out loud: `SettlType(63)=0` regular, with the T+1 `SettlDate(64)`.
 *
 * ### Three things the FX desk gets wrong, and this one does not
 *
 * **A disclosed side is quoted one way.** A dealer quotes two-way when the client withholds direction —
 * that is what two-way is *for*. Disclose a side and you are quoted the side you can trade on, which is
 * why [Quote] carries a `Side(54)` of its own. The FX desk answers a request naming `54=1` with both a
 * bid and an offer, which is a dealer showing its hand for no reason.
 *
 * **The quote says whether it is tradeable.** `QuoteType(537)=1` — a venue that books at the price it
 * quoted is quoting firm, and indicative-versus-tradeable is the first thing an RFQ client needs to know.
 *
 * **Cover and Done Away are answers, not errors.** In a multi-dealer RFQ the client tells you that they
 * traded elsewhere (`694=5`) or that you were the cover (`694=4`). Those are the ordinary end of a
 * negotiation you did not win, and a venue that answers them "QuoteRespType not accepted" — as the FX
 * desk does — is telling a client its perfectly conformant message is malformed.
 *
 * ### Why the prices do not move
 *
 * Every other venue here draws its prices. This one states them, and the reason is the 32nd grid: a
 * Treasury trades in thirty-seconds of a point, so `98-16+` is `98.515625` and the prices *between* grid
 * points do not exist. The native `${random:…}` generator quantises to a number of decimal places, and no
 * power of ten is a thirty-second, so there is no way to draw a price that a Treasury desk would
 * recognise. Drawing one anyway would put `98.513` on the wire, which is not a price — and inventing one
 * is exactly what this example is here not to do.
 *
 * So the desk quotes its **axe**: a stated level, firm for thirty seconds, with the size drawn instead.
 * What that costs is nothing the example was teaching — the quote book still expires a stale quote,
 * still spends one that has been hit, and still disowns one it never sent — and what it buys is a price
 * a scenario can name, as [EquityVenuePreset]'s print is.
 *
 * ### The identifiers are illustrative
 *
 * The CUSIPs below are well formed and shaped like Treasury notes. They are **not** real securities, and
 * nothing here should be pointed at a real one: a demo venue that quotes a live ISIN is a demo venue
 * somebody will eventually mistake for a price.
 */
object FiRfqVenuePreset {
    /** The id [AcceptorPresets.byId] answers to, and what the fixed-income example workspace carries. */
    const val ID = "fi-rfq-venue"

    /** How long the desk stands behind a level. The same thirty seconds the FX desk holds. */
    const val VALIDITY_SECONDS = 30

    // ------------------------------------------------------------------ the issues

    /**
     * One Treasury on the desk's run.
     *
     * [bidPx] and [offerPx] are exact thirty-seconds written as decimals, and [thirtySeconds] is the same
     * number the way a trader says it — carried in `Text(58)` on the quote, because FIX has no field for
     * a price's display convention and a Treasury quote that cannot be read aloud is half a quote.
     *
     * The yields move the other way from the prices, as they must: the bid is the lower price and so the
     * higher yield. `FiRfqVenuePresetTest` asserts that rather than trusting the table.
     */
    internal data class Issue(
        /** As a trader writes it: coupon and maturity. Carried in `Symbol(55)`, which the quote book keys on. */
        val symbol: String,
        val cusip: String,
        val coupon: String,
        /** `YYYYMMDD`, for `MaturityDate(541)`. */
        val maturity: String,
        val description: String,
        val bidPx: String,
        val offerPx: String,
        val bidYield: String,
        val offerYield: String,
        /** "98-16 / 98-16+", for the quote's own Text. */
        val thirtySeconds: String,
    )

    internal val ISSUES =
        listOf(
            Issue(
                symbol = "T 4.125 09/30/28",
                cusip = "91282CMD2",
                coupon = "4.125",
                maturity = "20280930",
                description = "US TREASURY N/B 4.125% 30-SEP-2028",
                bidPx = "99.750000", offerPx = "99.765625",
                bidYield = "4.158", offerYield = "4.150",
                thirtySeconds = "99-24 / 99-24+",
            ),
            Issue(
                symbol = "T 4 09/30/31",
                cusip = "91282CME0",
                coupon = "4.000",
                maturity = "20310930",
                description = "US TREASURY N/B 4.000% 30-SEP-2031",
                bidPx = "99.250000", offerPx = "99.265625",
                bidYield = "4.058", offerYield = "4.050",
                thirtySeconds = "99-08 / 99-08+",
            ),
            Issue(
                symbol = "T 4.25 11/15/36",
                cusip = "91282CMF7",
                coupon = "4.250",
                maturity = "20361115",
                description = "US TREASURY N/B 4.250% 15-NOV-2036",
                bidPx = "98.500000", offerPx = "98.515625",
                bidYield = "4.258", offerYield = "4.250",
                thirtySeconds = "98-16 / 98-16+",
            ),
        )

    internal val SYMBOLS = ISSUES.map { it.symbol }

    // ------------------------------------------------------------------ templates

    /** Nominal, in round millions — which is how a Treasury RFQ is sized. */
    private fun nominal() = "\${random:5:25:0}000000"

    /**
     * What every message about an issue says about it.
     *
     * `55` as well as `48`, and not instead of it: the CUSIP is the identifier a venue matches on, and
     * the coupon-and-maturity string is what a human reads. Real fixed-income messages carry both, and
     * the quote book keys on `55`.
     */
    private fun instrument(issue: Issue): String =
        listOf(
            "55=${issue.symbol}",
            "48=${issue.cusip}",
            "22=1",
            "167=TNOTE",
            "223=${issue.coupon}",
            "541=${issue.maturity}",
            "106=US TREASURY",
            "107=${issue.description}",
        ).joinToString("|")

    /** Regular settlement, spelled out. Treasuries settle T+1, and a date beats a code a reader must look up. */
    private const val SETTLEMENT = "63=0|64=\${utcnow+1d:yyyyMMdd}"

    /** `537=1` is Tradeable: this desk books at the level it showed. `423=1` is percent of par. */
    private const val TERMS = "537=1|423=1|15=USD|235=MATURITY"

    /**
     * A two-way quote, for a request that did not disclose a side.
     *
     * `62` is when the level dies, and it is the whole of what makes the quote book's refusals mean
     * something: after it, a hit is *stale* rather than merely wrong.
     */
    private fun twoWay(issue: Issue): String =
        listOf(
            "35=S",
            "131=\${req.131}",
            "117=\${uuid}",
            instrument(issue),
            TERMS,
            "132=${issue.bidPx}",
            "133=${issue.offerPx}",
            "632=${issue.bidYield}",
            "634=${issue.offerYield}",
            "134=${nominal()}",
            "135=${nominal()}",
            SETTLEMENT,
            "62=\${utcnow+${VALIDITY_SECONDS}s}",
            "58=${issue.thirtySeconds}",
            "60=\${utcnow}",
        ).joinToString("|")

    /**
     * One side, for a request that disclosed one.
     *
     * A client that says `54=1` is buying, so it is shown the **offer** and nothing else; the bid is the
     * desk's own business. `54` on the quote names the side being shown, which is the field's whole job.
     */
    private fun oneWay(issue: Issue, side: String): String {
        val buying = side == "1"
        return listOf(
            "35=S",
            "131=\${req.131}",
            "117=\${uuid}",
            instrument(issue),
            "54=$side",
            TERMS,
            if (buying) "133=${issue.offerPx}" else "132=${issue.bidPx}",
            if (buying) "634=${issue.offerYield}" else "632=${issue.bidYield}",
            if (buying) "135=${nominal()}" else "134=${nominal()}",
            SETTLEMENT,
            "62=\${utcnow+${VALIDITY_SECONDS}s}",
            "58=${issue.thirtySeconds}",
            "60=\${utcnow}",
        ).joinToString("|")
    }

    private val NO_SIZE =
        "35=AG|131=\${req.131}|658=99|146=1|55=\${req.55}|" +
            "58=QuoteRequest without OrderQty: this desk prices a nominal"

    private val UNKNOWN_ISSUE =
        "35=AG|131=\${req.131}|658=1|146=1|55=\${req.55}|" +
            "58=Not on the run here: this desk makes ${SYMBOLS.joinToString(", ")}"

    /**
     * The booked trade.
     *
     * Priced from the desk's own record of what it quoted (`${quote.…}`) and not from anything the client
     * sent, which is the same discipline [RfqVenuePreset] keeps. It carries the settlement terms because
     * a bond trade without a settlement date is not a trade anyone can book.
     */
    private fun trade(side: String): String =
        AcceptorPresets.executionReport(
            "150=F",
            "39=2",
            "11=\${req.11}",
            "55=\${req.55}",
            "54=\${req.54}",
            "38=\${req.38}",
            "693=\${req.693}",
            "14=\${req.38}",
            "151=0",
            "32=\${req.38}",
            "31=\${quote.$side}",
            "6=\${quote.$side}",
            "423=1",
            SETTLEMENT,
        )

    /**
     * A QuoteStatusReport.
     *
     * [symbol] is a parameter because it has two honest sources, and the difference is not cosmetic: for
     * a quote this desk sent it is `${quote.symbol}`, the desk's own record, and for one it never sent
     * there is no record to read — so the client's own `55` is the only thing that can name the issue,
     * and the rule that uses it has to require `55` to be there. Written with `${quote.symbol}`
     * throughout, the unknown-quote refusal read a quote that by definition does not exist, and the
     * editor refused the rule as unusable before any of it reached a wire.
     */
    private fun quoteStatus(status: String, symbol: String = QUOTE_SYMBOL, text: String? = null): String =
        listOfNotNull(
            "35=AI",
            "117=\${req.117}",
            "693=\${req.693}",
            "55=$symbol",
            "297=$status",
            text?.let { "58=$it" },
            "60=\${utcnow}",
        ).joinToString("|")

    private const val QUOTE_SYMBOL = "\${quote.symbol}"

    private const val CANNOT_ANSWER =
        "35=j|372=AJ|379=\${req.693}|380=5|" +
            "58=A QuoteResponse needs QuoteID (117) to say which quote it answers"

    private const val ACCEPTED = "0"
    private const val REJECTED = "5"
    private const val WITHDRAWN = "6"
    private const val EXPIRED = "7"
    private const val NOT_FOUND = "9"
    private const val PASS = "11"

    // ------------------------------------------------------------------ conditions, named

    private val quoteIdPresent = AcceptorPresets.condition(117, Matcher.Presence)
    private val clOrdIdPresent = AcceptorPresets.condition(11, Matcher.Presence)
    private val quantityPresent = AcceptorPresets.condition(38, Matcher.Presence)
    private val onTheRun = AcceptorPresets.condition(55, Matcher.OneOf(SYMBOLS))
    private val quotedIssue = AcceptorPresets.condition(55, Matcher.QuoteField("symbol"))

    private fun symbolIs(issue: Issue) = AcceptorPresets.condition(55, Matcher.Exact(issue.symbol))

    private fun sideIs(side: String) = AcceptorPresets.condition(54, Matcher.Exact(side))

    private fun respType(value: String) = AcceptorPresets.condition(694, Matcher.Exact(value))

    // ------------------------------------------------------------------ 35=R

    private fun oneWayRule(issue: Issue, side: String) =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions = listOf(symbolIs(issue), quantityPresent, sideIs(side)),
            steps = listOf(ResponseStep(oneWay(issue, side))),
        )

    private fun twoWayRule(issue: Issue) =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions = listOf(symbolIs(issue), quantityPresent),
            steps = listOf(ResponseStep(twoWay(issue))),
        )

    private val quoteNoSize =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions = listOf(onTheRun),
            steps = listOf(ResponseStep(NO_SIZE)),
        )

    private val quoteUnknownIssue =
        AcceptorResponseRule(whenMsgType = "R", steps = listOf(ResponseStep(UNKNOWN_ISSUE)))

    // ------------------------------------------------------------------ 35=AJ

    /** `55` is required here because the reply has to name the issue and the book cannot: see [quoteStatus]. */
    private val unknownQuote =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, AcceptorPresets.condition(55, Matcher.Presence)),
            whenQuote = QuoteConstraint.UNKNOWN,
            steps =
                listOf(
                    ResponseStep(quoteStatus(NOT_FOUND, "\${req.55}", "This desk did not send that quote")),
                ),
        )

    private val expiredQuote =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent),
            whenQuote = QuoteConstraint.EXPIRED,
            steps = listOf(ResponseStep(quoteStatus(EXPIRED, text = "The level expired before this arrived"))),
        )

    private val doneQuote =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent),
            whenQuote = QuoteConstraint.DONE,
            steps = listOf(ResponseStep(quoteStatus(REJECTED, text = "Quote already answered"))),
        )

    private fun bookingRule(side: String, field: String) =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions =
                listOf(
                    quoteIdPresent,
                    clOrdIdPresent,
                    quantityPresent,
                    respType("1"),
                    quotedIssue,
                    sideIs(side),
                    AcceptorPresets.condition(44, Matcher.QuoteField(field)),
                ),
            whenQuote = QuoteConstraint.OPEN,
            steps = listOf(ResponseStep(trade(field))),
        )

    private val buyLift = bookingRule(side = "1", field = "offer")
    private val sellHit = bookingRule(side = "2", field = "bid")

    private val wrongPrice =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, clOrdIdPresent, quantityPresent, respType("1"), quotedIssue),
            whenQuote = QuoteConstraint.OPEN,
            steps =
                listOf(
                    ResponseStep(
                        quoteStatus(REJECTED, text = "Not the level quoted, or not a side this quote showed"),
                    ),
                ),
        )

    private val wrongIssue =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, clOrdIdPresent, quantityPresent, respType("1")),
            whenQuote = QuoteConstraint.OPEN,
            steps = listOf(ResponseStep(quoteStatus(REJECTED, text = "That is not the issue this quote was for"))),
        )

    private val cannotBook =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, respType("1")),
            whenQuote = QuoteConstraint.OPEN,
            steps =
                listOf(
                    ResponseStep(quoteStatus(REJECTED, text = "A lift needs ClOrdID (11) and OrderQty (38) to book")),
                ),
        )

    /** A counter is declined and the level stands — which is what a firm-price desk says to one. */
    private val counter =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, respType("2")),
            whenQuote = QuoteConstraint.OPEN,
            steps =
                listOf(
                    ResponseStep(
                        quoteStatus(
                            REJECTED,
                            text = "Counter declined: this level is firm and stands until it expires",
                        ),
                    ),
                ),
        )

    /**
     * **Cover and Done Away end a negotiation you did not win**, and they are answered rather than
     * refused. `297=6` is Removed from market, which is what has become of the level: the client has told
     * the desk where the trade went, so there is nothing left to stand behind.
     */
    private val cover =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, respType("4")),
            whenQuote = QuoteConstraint.OPEN,
            steps = listOf(ResponseStep(quoteStatus(WITHDRAWN, text = "Noted: cover. The level is withdrawn"))),
        )

    private val doneAway =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, respType("5")),
            whenQuote = QuoteConstraint.OPEN,
            steps = listOf(ResponseStep(quoteStatus(WITHDRAWN, text = "Noted: done away. The level is withdrawn"))),
        )

    private val pass =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, respType("6")),
            whenQuote = QuoteConstraint.OPEN,
            steps = listOf(ResponseStep(quoteStatus(PASS))),
        )

    /** `694=3` is Expired, which a client says when the level died before it could answer. Acknowledged. */
    private val clientExpired =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent, respType("3")),
            whenQuote = QuoteConstraint.OPEN,
            steps = listOf(ResponseStep(quoteStatus(ACCEPTED, text = "Noted: you read the level as expired"))),
        )

    private val otherResponse =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(quoteIdPresent),
            whenQuote = QuoteConstraint.OPEN,
            steps = listOf(ResponseStep(quoteStatus(REJECTED, text = "QuoteRespType not recognised"))),
        )

    private val cannotAnswer =
        AcceptorResponseRule(whenMsgType = "AJ", steps = listOf(ResponseStep(CANNOT_ANSWER)))

    // ------------------------------------------------------------------ the bundle

    /**
     * **Declared backwards to read forwards**, as every bundle here is. Three things depend on the order:
     *
     * - **The one-way rules outrank the two-way ones.** They are the same trigger plus a `54`, so below
     *   them a client that disclosed a side would be shown both, which is the FX desk's mistake.
     * - **The three quote-state refusals outrank everything for `35=AJ`**, because every rule below them
     *   is written for a level that is still live.
     * - **The bookings outrank the wrong-level refusal**, or every lift is refused.
     */
    internal val rules: List<AcceptorResponseRule> =
        // 35=R: the no-size refusal is declared FIRST so it reads LAST — declared where it looks right,
        // it came out above every quote rule and refused a request that plainly carried a size.
        listOf(quoteNoSize) +
            ISSUES.reversed().map(::twoWayRule) +
            ISSUES.reversed().flatMap { listOf(oneWayRule(it, "2"), oneWayRule(it, "1")) } +
            quoteUnknownIssue +
            // 35=AJ: what the book says first, then the lifts, then every other response answered.
            listOf(otherResponse, clientExpired, pass, doneAway, cover, counter, cannotBook, wrongIssue, wrongPrice) +
            listOf(sellHit, buyLift) +
            listOf(doneQuote, expiredQuote, unknownQuote) +
            cannotAnswer

    val preset: AcceptorPreset =
        AcceptorPreset(
            id = ID,
            name = "Fixed income RFQ — on-the-run Treasuries, price and yield",
            group = AcceptorPresets.GROUP_BUNDLES,
            summary =
                "${rules.size} rules · CUSIP, coupon and maturity · quoted in 32nds and yield · " +
                    "one-way on a disclosed side · cover and done-away answered · T+1",
            rules = rules,
        )
}
