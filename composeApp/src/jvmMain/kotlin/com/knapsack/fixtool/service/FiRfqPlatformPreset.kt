package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.PartyRole
import com.knapsack.fixtool.model.QuoteConstraint
import com.knapsack.fixtool.model.QuotesStanding
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.RfqConstraint
import com.knapsack.fixtool.model.SenderRole
import com.knapsack.fixtool.model.WHEN_RFQ_EXPIRES
import com.knapsack.fixtool.model.scenario.Matcher

/**
 * **A fixed-income RFQ platform**: buy sides ask, dealers quote, and the platform carries the negotiation between
 * them — US Treasuries by CUSIP, levels in 32nds and yield, settling T+1.
 *
 * The shape a Treasury RFQ actually has. A platform does not price anything: it opens a request to the dealers,
 * shows the buy side each dealer's quote under an id of its own, and when the buy side lifts one it confirms the
 * trade to both sides — an ExecutionReport and a TradeCaptureReport each — and tells the dealers who did not win
 * that they were the cover or that the trade was done away. Everything that cannot be carried is refused by name.
 * See `docs/rfq-relay-proposal.md`.
 *
 * ### Two presets, two sides
 *
 * [preset] is the platform's rules: relay and entitlement, no prices. [dealerPreset] is a dealer's: quote every
 * request for an issue on the run, one way to a disclosed side and two-way otherwise. The example's **Dealer Load
 * Client** carries the dealer rules, which is how a load run has five dealers answering without a person. Dealer 1
 * and Dealer 2 carry none — they are there to be played by hand, which is the point of a two-party demo.
 *
 * ### What the platform assumes about its market
 *
 * **Disclosed**: each dealer sees who is asking (`Parties`, role 13) and the buy side sees who quoted (role 35).
 * **Firm**: a quote is `QuoteType(537)=1`, and a lift books at the level the platform showed, read back from its own
 * quote book rather than from the price the buy side sent. **Every dealer online is asked.** A counter is declined.
 * Those are the defaults `docs/rfq-relay-impl-plan.md` records as open questions.
 *
 * ### The identifiers are illustrative
 *
 * The CUSIPs are well formed and shaped like Treasury notes. They are **not** real securities, and nothing here
 * should be pointed at a real one: a demo platform quoting a live ISIN is one somebody will eventually mistake
 * for a price.
 */
@Suppress("TooManyFunctions") // a venue's catalogue of message shapes, each named for the reader of its card
object FiRfqPlatformPreset {
    /** The id [AcceptorPresets.byId] answers to, and what the fixed-income example workspace carries. */
    const val ID = "fi-rfq-venue"

    /** The dealer's rules: what the Dealer Load Client quotes with. */
    const val DEALER_ID = "fi-rfq-dealer"

    /** How long a dealer stands behind a level. */
    const val VALIDITY_SECONDS = 30

    // ------------------------------------------------------------------ the parties

    const val BUY_SIDE_1 = "FIBUY1"
    const val BUY_SIDE_2 = "FIBUY2"
    const val DEALER_1 = "FIDLR1"
    const val DEALER_2 = "FIDLR2"

    /** The load clients' CompIDs before their lane number: `FIBUYLG1` to `FIBUYLG5`. */
    const val BUY_SIDE_LOAD = "FIBUYLG"
    const val DEALER_LOAD = "FIDLRLG"

    /** Who the platform lets ask and who it asks. A load client is a family, so every lane is covered. */
    val COUNTERPARTIES =
        listOf(
            Counterparty(BUY_SIDE_1, PartyRole.REQUESTER.word),
            Counterparty(BUY_SIDE_2, PartyRole.REQUESTER.word),
            Counterparty("$BUY_SIDE_LOAD*", PartyRole.REQUESTER.word),
            Counterparty(DEALER_1, PartyRole.RESPONDER.word),
            Counterparty(DEALER_2, PartyRole.RESPONDER.word),
            Counterparty("$DEALER_LOAD*", PartyRole.RESPONDER.word),
        )

    // ------------------------------------------------------------------ the issues

    /**
     * One Treasury on the run.
     *
     * [bidPx] and [offerPx] are exact thirty-seconds written as decimals, and [thirtySeconds] is the same number the
     * way a trader says it — carried in `Text(58)` on a dealer's quote, because FIX has no field for a price's
     * display convention. The yields move the other way from the prices, as they must: the bid is the lower price
     * and so the higher yield. Every issue here trades below par, so every yield is above its coupon. They are
     * street yields for a settlement of 15 September 2026, so half a 32nd moves the ten-year a fifth of a basis
     * point and the two-year nearly one. `FiRfqPlatformPresetTest` asserts both rather than trusting the table.
     */
    internal data class Issue(
        /** As a trader writes it: coupon and maturity. */
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
                cusip = "91282CMD0",
                coupon = "4.125",
                maturity = "20280930",
                description = "US TREASURY N/B 4.125% 30-SEP-2028",
                bidPx = "99.750000",
                offerPx = "99.765625",
                bidYield = "4.253",
                offerYield = "4.245",
                thirtySeconds = "99-24 / 99-24+",
            ),
            Issue(
                symbol = "T 4 09/30/31",
                cusip = "91282CME8",
                coupon = "4.000",
                maturity = "20310930",
                description = "US TREASURY N/B 4.000% 30-SEP-2031",
                bidPx = "99.250000",
                offerPx = "99.265625",
                bidYield = "4.166",
                offerYield = "4.163",
                thirtySeconds = "99-08 / 99-08+",
            ),
            Issue(
                symbol = "T 4.25 08/15/36",
                cusip = "91282CMF5",
                coupon = "4.250",
                maturity = "20360815",
                description = "US TREASURY N/B 4.250% 15-AUG-2036",
                bidPx = "98.500000",
                offerPx = "98.515625",
                bidYield = "4.438",
                offerYield = "4.436",
                thirtySeconds = "98-16 / 98-16+",
            ),
        )

    internal val SYMBOLS = ISSUES.map { it.symbol }
    internal val CUSIPS = ISSUES.map { it.cusip }

    // ------------------------------------------------------------------ what every message says

    /**
     * What a dealer says about an issue it quotes.
     *
     * `55` as well as `48`, and not instead of it: the CUSIP is the identifier a platform matches on, and the
     * coupon-and-maturity string is what a human reads. Real fixed-income messages carry both.
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

    /** `537=1` is Tradeable: the level is firm. `423=1` is percent of par. */
    private const val TERMS = "537=1|423=1|15=USD"

    private const val BUY = "1"
    private const val SELL = "2"

    // ------------------------------------------------------------------ conditions, named

    private fun condition(tag: Int, matcher: Matcher) = FieldCondition(tag, MatcherCodec.matcherToJson(matcher))

    private fun present(vararg tags: Int) = tags.map { condition(it, Matcher.Presence) }

    private fun exact(tag: Int, value: String) = condition(tag, Matcher.Exact(value))

    private val fromRequester = condition(49, Matcher.CounterpartyRole(SenderRole.REQUESTER.word))
    private val fromResponder = condition(49, Matcher.CounterpartyRole(SenderRole.RESPONDER.word))

    private fun rfq(tag: Int, state: RfqConstraint) = condition(tag, Matcher.RfqState(state.word))

    private val onTheRun = condition(48, Matcher.OneOf(CUSIPS))

    // ================================================================== the platform

    // ------------------------------------------------------------------ 35=R

    /**
     * The request as each dealer receives it: the platform's own QuoteReqID (one for every dealer asked), the
     * instrument and size the buy side asked for, and who is asking — role 13, Order Origination Firm.
     *
     * [withSide] carries the buy side's `54`, and is a separate rule rather than an optional field because a
     * missing `${req.54}` substitutes empty: a two-way request would reach the dealers as `54=`.
     */
    private fun relayedRequest(withSide: Boolean): String =
        listOfNotNull(
            "35=R",
            "131=\${req.uuid}",
            "146=1",
            "55=\${req.55}",
            "48=\${req.48}",
            "22=1",
            "167=TNOTE",
            "54=\${req.54}".takeIf { withSide },
            "38=\${req.38}",
            SETTLEMENT,
            "453=1",
            "448=\${req.49}",
            "447=D",
            "452=13",
            "60=\${utcnow}",
        ).joinToString("|")

    private fun quoteRequestReject(reason: String, text: String) =
        "35=AG|131=\${req.131}|658=$reason|146=1|55=\${req.55}|58=$text"

    /** A dealer asking for a quote is misconfigured, and told so rather than relayed. `658=6` is Not authorized. */
    private val requestFromDealer =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions = listOf(fromResponder) + present(131, 55),
            steps =
                listOf(
                    ResponseStep(
                        quoteRequestReject("6", "Not authorized: a dealer on this platform quotes, it does not ask"),
                    ),
                ),
        )

    private val nobodyToAsk =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions = listOf(fromRequester, onTheRun) + present(131, 55, 38),
            whenResponders = "none",
            steps = listOf(ResponseStep(quoteRequestReject("99", "No dealer is online to quote this"))),
        )

    private fun relayRequest(withSide: Boolean) =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions =
                listOf(fromRequester, onTheRun) + present(131, 55, 38) + if (withSide) present(54) else emptyList(),
            steps = listOf(ResponseStep(relayedRequest(withSide), to = "responders")),
        )

    private val requestNoSize =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions = listOf(fromRequester, onTheRun) + present(131, 55),
            steps =
                listOf(
                    ResponseStep(quoteRequestReject("99", "QuoteRequest without OrderQty: a dealer prices a nominal")),
                ),
        )

    /** Anything a requester asks that got past the on-the-run rules names an issue this platform does not list. */
    private val requestUnknownIssue =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions = listOf(fromRequester) + present(131, 55),
            steps =
                listOf(
                    ResponseStep(
                        quoteRequestReject(
                            "1",
                            "Not listed here: this platform lists " +
                                ISSUES.joinToString(", ") { "${it.cusip} (${it.symbol})" },
                        ),
                    ),
                ),
        )

    /** `380=6` is Not authorized: a request from a CompID the platform does not list, or one it cannot read. */
    private val requestNobodyListed =
        AcceptorResponseRule(
            whenMsgType = "R",
            steps =
                listOf(
                    ResponseStep(
                        "35=j|372=R|380=6|58=Only a buy side this platform lists can ask for a quote, " +
                            "with a QuoteReqID and a Symbol",
                    ),
                ),
        )

    // ------------------------------------------------------------------ 35=S

    /** The three shapes a dealer's quote can have, and the tags each one has to carry for the platform to show it. */
    private enum class Shape(
        val side: String?,
        val tags: List<Int>,
    ) {
        TWO_WAY(null, listOf(132, 133, 134, 135, 632, 634)),
        OFFER(BUY, listOf(133, 135, 634)),
        BID(SELL, listOf(132, 134, 632)),
    }

    /**
     * A dealer's quote as the buy side sees it: the buy side's own QuoteReqID, a QuoteID the platform minted, the
     * dealer's level, size, yield and validity, the platform's own terms, and who quoted — role 35, Liquidity
     * provider.
     */
    private fun relayedQuote(shape: Shape): String =
        (
            listOfNotNull(
                "35=S",
                "131=\${to.131}",
                "117=\${uuid:10}",
                "55=\${req.55}",
                "48=\${req.48}",
                "22=1",
                "167=TNOTE",
                shape.side?.let { "54=$it" },
                TERMS,
            ) +
                shape.tags.map { "$it=\${req.$it}" } +
                listOf(
                    SETTLEMENT,
                    "62=\${req.62}",
                    "453=1",
                    "448=\${req.49}",
                    "447=D",
                    "452=35",
                    "60=\${utcnow}",
                )
        ).joinToString("|")

    private fun relayQuote(shape: Shape) =
        AcceptorResponseRule(
            whenMsgType = "S",
            conditions =
                listOf(fromResponder, rfq(131, RfqConstraint.OPEN)) +
                    present(117, 55, 48, 62) +
                    listOfNotNull(shape.side?.let { exact(54, it) }) +
                    present(*shape.tags.toIntArray()),
            steps = listOf(ResponseStep(relayedQuote(shape), to = "requester")),
        )

    private fun quoteStatus(status: String, text: String) =
        "35=AI|117=\${req.117}|131=\${req.131}|55=\${req.55}|297=$status|58=$text|60=\${utcnow}"

    private val quoteIncomplete =
        AcceptorResponseRule(
            whenMsgType = "S",
            conditions = listOf(fromResponder, rfq(131, RfqConstraint.OPEN)) + present(117, 55),
            steps =
                listOf(
                    ResponseStep(
                        quoteStatus(
                            "5",
                            "Not shown: a quote carries its CUSIP, ValidUntilTime, and a price, size and yield " +
                                "for each side",
                        ),
                    ),
                ),
        )

    /** A quote for an RFQ that has traded, passed, expired, or that this dealer was never asked. */
    private val quoteTooLate =
        AcceptorResponseRule(
            whenMsgType = "S",
            conditions = listOf(fromResponder) + present(117, 55),
            steps = listOf(ResponseStep(quoteStatus("5", "Not shown: this RFQ is no longer open"))),
        )

    private val quoteNobodyListed =
        AcceptorResponseRule(
            whenMsgType = "S",
            steps =
                listOf(
                    ResponseStep(
                        "35=j|372=S|380=6|58=Only a dealer this platform lists can quote, with a QuoteID and a Symbol",
                    ),
                ),
        )

    // ------------------------------------------------------------------ 35=AJ

    /**
     * **The trade, confirmed to both sides and to nobody else**, the moment a buy side lifts or hits a live quote
     * at the level it was shown.
     *
     * One OrderID (`37`) and one TradeReportID (`571`) for both sides, drawn once for the trigger, because they are
     * the ids the two sides reconcile on. Each side gets an ExecutionReport and a TradeCaptureReport naming the
     * other side as contra firm (role 17). The quoter is told first that its quote was accepted; the cover and every
     * other dealer quoting are told they did not win. The price is the platform's own record of the level it showed
     * (`${quote.…}`), never the price the buy side sent.
     *
     * Sending the quoter its fill is what makes this rule a trade: the platform records it as the rule fires, so a
     * second lift already on its way reads the RFQ as done.
     */
    private fun lift(side: String): AcceptorResponseRule {
        val buying = side == BUY
        val level = if (buying) "\${quote.offer}" else "\${quote.bid}"
        val dealerSide = if (buying) SELL else BUY
        return AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions =
                listOf(fromRequester, rfq(117, RfqConstraint.OPEN), exact(694, "1"), exact(54, side)) +
                    present(11, 38, 55) +
                    condition(44, Matcher.QuoteField(if (buying) "offer" else "bid")),
            // The level shown is read back from the platform's own quote book, which is why the quote must be open
            // there as well as its RFQ being open: a level the platform no longer stands behind is not booked.
            whenQuote = QuoteConstraint.OPEN,
            steps =
                listOf(
                    ResponseStep(fill(side, "\${req.11}", level, contra = "\${rfq.quoter}")),
                    ResponseStep(tradeReport(side, "\${req.11}", level, contra = "\${rfq.quoter}")),
                    ResponseStep(
                        "35=AJ|693=\${uuid:10}|694=1|117=\${to.117}|11=\${uuid:10}|55=\${req.55}|54=$side|" +
                            "38=\${req.38}|44=$level|60=\${utcnow}",
                        to = "quoter",
                    ),
                    ResponseStep(fill(dealerSide, "\${to.11}", level, contra = "\${rfq.requester}"), to = "quoter"),
                    ResponseStep(
                        tradeReport(dealerSide, "\${to.11}", level, contra = "\${rfq.requester}"),
                        to = "quoter",
                    ),
                    ResponseStep(ended("4"), to = "cover"),
                    ResponseStep(ended("5"), to = "others"),
                ),
        )
    }

    private fun fill(side: String, clOrdId: String, level: String, contra: String): String =
        listOf(
            "35=8",
            "37=\${req.uuid}",
            "17=\${uuid}",
            "11=$clOrdId",
            "150=F",
            "39=2",
            "55=\${req.55}",
            "54=$side",
            "38=\${req.38}",
            "32=\${req.38}",
            "31=$level",
            "14=\${req.38}",
            "151=0",
            "6=$level",
            "423=1",
            SETTLEMENT,
            "453=1",
            "448=$contra",
            "447=D",
            "452=17",
            "60=\${utcnow}",
        ).joinToString("|")

    /** FIX 4.4's TradeCaptureReport: its required 571, 570, 55, 32, 31, 75 and 60, and one side with 54 and 37. */
    private fun tradeReport(side: String, clOrdId: String, level: String, contra: String): String =
        listOf(
            "35=AE",
            "571=TR-\${req.uuid}",
            "570=N",
            "55=\${req.55}",
            "32=\${req.38}",
            "31=$level",
            "423=1",
            "75=\${utcnow:yyyyMMdd}",
            "60=\${utcnow}",
            SETTLEMENT,
            "552=1",
            "54=$side",
            "37=\${req.uuid}",
            "11=$clOrdId",
            "453=1",
            "448=$contra",
            "447=D",
            "452=17",
        ).joinToString("|")

    /** A dealer told the RFQ ended without it, by its own quote id: `694=4` Cover, `5` Done Away, `6` Pass. */
    private fun ended(respType: String) =
        "35=AJ|693=\${uuid:10}|694=$respType|117=\${to.117}|55=\${req.55}|60=\${utcnow}"

    /** A pass ends the RFQ: every dealer quoting is told, and the buy side's pass is acknowledged. */
    private val pass =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(fromRequester, rfq(117, RfqConstraint.OPEN), exact(694, "6")) + present(55),
            steps =
                listOf(
                    ResponseStep(ended("6"), to = "quoted"),
                    ResponseStep(responseStatus("11", null)),
                ),
        )

    private fun responseStatus(status: String, text: String?) =
        listOfNotNull(
            "35=AI",
            "117=\${req.117}",
            "693=\${req.693}",
            "55=\${req.55}",
            "297=$status",
            text?.let { "58=$it" },
            "60=\${utcnow}",
        ).joinToString("|")

    private fun refuseResponse(state: RfqConstraint, extra: List<FieldCondition>, status: String, text: String) =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(fromRequester, rfq(117, state)) + extra + present(55),
            steps = listOf(ResponseStep(responseStatus(status, text))),
        )

    private val counter =
        refuseResponse(
            RfqConstraint.OPEN,
            listOf(exact(694, "2")),
            "5",
            "Counter declined: the level is firm and stands until it expires",
        )

    private val notAtTheLevel =
        refuseResponse(
            RfqConstraint.OPEN,
            listOf(exact(694, "1")),
            "5",
            "Not the level shown, or not a side this quote showed",
        )

    private val otherResponse =
        refuseResponse(
            RfqConstraint.OPEN,
            emptyList(),
            "5",
            "QuoteRespType not accepted from a buy side: lift, hit, counter or pass",
        )

    private val rfqDone =
        refuseResponse(RfqConstraint.DONE, emptyList(), "5", "This RFQ has already traded or ended")

    private val rfqExpired =
        refuseResponse(RfqConstraint.EXPIRED, emptyList(), "7", "The RFQ expired before this arrived")

    private val rfqUnknown =
        refuseResponse(RfqConstraint.UNKNOWN, emptyList(), "9", "This platform did not show that quote")

    private val cannotAnswer =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            steps =
                listOf(
                    ResponseStep(
                        "35=j|372=AJ|379=\${req.693}|380=5|" +
                            "58=A QuoteResponse needs QuoteID (117) and Symbol (55), " +
                            "from a buy side this platform lists",
                    ),
                ),
        )

    // ------------------------------------------------------------------ when the RFQ expires

    /**
     * **An RFQ nobody traded, ended when its time runs out**, to everyone who is holding part of it.
     *
     * A QuoteStatusReport names one quote, so a quoted RFQ ends each quote by name: to the requester once for each
     * quote it was shown, under the id it was shown, and to each dealer under its own. Every quote ends with the RFQ,
     * including one whose own validity ran out first, since nobody has said so yet.
     */
    private val expiredQuoted =
        AcceptorResponseRule(
            whenMsgType = WHEN_RFQ_EXPIRES,
            whenQuotes = QuotesStanding.SOME.word,
            steps = listOf(ResponseStep(quoteExpired(), to = "quotes"), ResponseStep(quoteExpired(), to = "quoted")),
        )

    private fun quoteExpired() =
        "35=AI|117=\${to.117}|131=\${to.131}|55=\${req.55}|48=\${req.48}|22=1|297=7|" +
            "58=Expired: the RFQ's time ran out before it traded|60=\${utcnow}"

    /** Nobody quoted: the request is refused to the buy side that made it, under its own QuoteReqID. */
    private val expiredUnquoted =
        AcceptorResponseRule(
            whenMsgType = WHEN_RFQ_EXPIRES,
            whenQuotes = QuotesStanding.NONE.word,
            steps =
                listOf(
                    ResponseStep(
                        "35=AG|131=\${to.131}|658=99|146=1|55=\${req.55}|48=\${req.48}|22=1|" +
                            "58=Expired: no dealer quoted before the RFQ's time ran out",
                        to = "requester",
                    ),
                ),
        )

    /**
     * **Declared backwards to read forwards**, as every bundle here is: [AcceptorPresets.insert] places each
     * conditioned rule above the first rule for its MsgType. Four things depend on the order:
     *
     * - **A dealer's request is refused before anything else**, and nobody-online before a relay.
     * - **The one-sided relay reads before the two-way one**, which is the same trigger without a `54`.
     * - **The unknown-issue refusal reads after every on-the-run rule**, because that position is the whole of what
     *   tells it the CUSIP was not on the run.
     * - **The lifts read before the not-at-the-level refusal**, or every lift is refused.
     */
    internal val rules: List<AcceptorResponseRule> =
        // 35=R
        listOf(requestNobodyListed, requestUnknownIssue, requestNoSize, relayRequest(withSide = false)) +
            listOf(relayRequest(withSide = true), nobodyToAsk, requestFromDealer) +
            // 35=S
            listOf(quoteNobodyListed, quoteTooLate, quoteIncomplete) +
            listOf(relayQuote(Shape.BID), relayQuote(Shape.OFFER), relayQuote(Shape.TWO_WAY)) +
            // 35=AJ
            listOf(cannotAnswer, rfqUnknown, rfqExpired, rfqDone, otherResponse, notAtTheLevel, counter, pass) +
            listOf(lift(SELL), lift(BUY)) +
            // When the RFQ expires: the two can never both hold, so their order is not the reader's concern.
            listOf(expiredUnquoted, expiredQuoted)

    val preset: AcceptorPreset =
        AcceptorPreset(
            id = ID,
            name = "Fixed income RFQ platform — buy sides and dealers, relayed",
            group = AcceptorPresets.GROUP_BUNDLES,
            summary =
                "${rules.size} rules · a request to every dealer online · each quote shown under the platform's " +
                    "own id · a lift confirmed to both sides with a trade report each · cover and done away told · " +
                    "an RFQ nobody trades ended to both sides when it expires",
            rules = rules,
            counterparties = COUNTERPARTIES,
        )

    // ================================================================== a dealer

    /** A dealer's quote for [issue]: one way to a request that disclosed [side], two-way otherwise. */
    private fun dealerQuote(issue: Issue, side: String?): String {
        val bid = side != BUY
        val offer = side != SELL
        return listOfNotNull(
            "35=S",
            "131=\${req.131}",
            "117=\${uuid:12}",
            instrument(issue),
            side?.let { "54=$it" },
            TERMS,
            "132=${issue.bidPx}".takeIf { bid },
            "133=${issue.offerPx}".takeIf { offer },
            "632=${issue.bidYield}".takeIf { bid },
            "634=${issue.offerYield}".takeIf { offer },
            "134=\${req.38}".takeIf { bid },
            "135=\${req.38}".takeIf { offer },
            SETTLEMENT,
            "62=\${utcnow+${VALIDITY_SECONDS}s}",
            "58=${issue.thirtySeconds}",
            "60=\${utcnow}",
        ).joinToString("|")
    }

    private fun dealerRule(issue: Issue, side: String?) =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions = listOf(exact(48, issue.cusip)) + present(131, 38) + listOfNotNull(side?.let { exact(54, it) }),
            steps = listOf(ResponseStep(dealerQuote(issue, side))),
        )

    /** A request for an issue this dealer does not make: passed, which is `658=10`. */
    private val dealerPasses =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions = present(131, 55),
            steps = listOf(ResponseStep(quoteRequestReject("10", "Pass: not an issue this dealer makes"))),
        )

    /**
     * **A dealer quotes every request it is asked** for an issue on the run, and answers nothing else.
     *
     * No rule for a QuoteResponse: on a firm platform the platform's own ExecutionReport is what confirms a trade
     * to the dealer, and a dealer that answered a lift would be confirming a trade nobody asked it to.
     */
    internal val dealerRules: List<AcceptorResponseRule> =
        listOf(dealerPasses) +
            ISSUES.reversed().map { dealerRule(it, side = null) } +
            ISSUES.reversed().flatMap { listOf(dealerRule(it, SELL), dealerRule(it, BUY)) }

    val dealerPreset: AcceptorPreset =
        AcceptorPreset(
            id = DEALER_ID,
            name = "Fixed income RFQ dealer — quotes every request on the run",
            group = AcceptorPresets.GROUP_BUNDLES,
            summary =
                "${dealerRules.size} rules · for an initiator · one way to a disclosed side, two-way otherwise · " +
                    "levels in 32nds and yield · passes an issue it does not make",
            rules = dealerRules,
        )
}
