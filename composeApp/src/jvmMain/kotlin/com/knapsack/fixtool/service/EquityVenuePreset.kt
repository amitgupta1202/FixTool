package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher

/**
 * **A cash equities venue with a book that holds your orders**, and the first bundled example where an
 * order is still there a minute after you sent it.
 *
 * The two venues that came before it answer everything at once. The FX venue fills or refuses every
 * order inside a second; the RFQ venue quotes, books and moves on. So `whenOrder working` was reachable
 * only in the 250&nbsp;ms between an ack and its fill, a cancel was a race nobody could win on purpose,
 * and a replace was a rule with nothing to replace. **A Day limit order here is acknowledged and then
 * nothing else happens to it.** It rests. That one difference is what makes the cancel, the replace and
 * the status request into behaviours a person can drive by hand and a scenario can assert on.
 *
 * ### What TimeInForce decides
 *
 * The order matrix is the venue, and all three outcomes are deterministic:
 *
 * | Order | What the venue does |
 * |---|---|
 * | Limit, Day (`59=0`, or absent) | New, and it rests at `39=0` until you cancel or replace it |
 * | Limit, IOC (`59=3`) | Marketable against the touch: New, then filled. Not marketable: New, then Canceled `39=4` |
 * | Market (`40=1`) | New, then filled at the touch: a buy pays the offer, a sell hits the bid |
 *
 * FIX's default TimeInForce is Day, so the resting rule carries **no `59` condition at all** and catches
 * both the order that says `59=0` and the one that says nothing. The IOC rule sits above it and names
 * `59=3`. Writing it the other way round — a `59=0` condition on the resting rule — would have let an
 * order with no TimeInForce fall past every order rule to the unknown-symbol refusal, and told its
 * sender the venue had never heard of AAPL.
 *
 * ### Why the fills are fixed and the market data is drawn
 *
 * Every price the venue *prints* is a literal: [Listing.bid] and [Listing.offer]. Every price it
 * *publishes* is a `${random:…}` draw across a band. That split is deliberate and it is the opposite of
 * the FX venue's, for two reasons.
 *
 * **A printed price a scenario can assert.** The FX venue draws its fill price, so its bundled scenario
 * can asserts a fill happened but never at what. Here `31` and `6` are the same literal the market data
 * band is centred on, so `EQTY buy AAPL at the touch` can expect `31=227.42` and mean it.
 *
 * **Nothing here reaches the script engine.** `${random:…}` is a native generator, rendered in
 * microseconds by the same renderer a load run's compiled template uses; a Kotlin expression is compiled
 * one at a time for the whole process at tens of milliseconds each (see `FixMessageTemplate.warmUp`).
 * The FX venue's fill draws its price with `"%.2f".format(…)` and therefore costs the venue's dispatch
 * thread ~58&nbsp;ms per order, which is why its own load set has to keep the order phase small. This
 * venue can be asked for orders at volume. Two draws would also have let `31` and `6` disagree on a
 * single fill, which they must never do, and the only native way to make two fields share one draw is
 * to make them the same literal.
 *
 * ### The three refusals, and why each is the one a US venue actually sends
 *
 * **Sub-penny.** SEC Rule 612 forbids quoting a sub-penny price on a security priced at or above
 * $1.00, so a limit at `227.421` is refused. The condition matches the *fault* rather than the good
 * case — a price whose third decimal or beyond is non-zero — because [Matcher.Regex] matches
 * positively and there is no negated form. Written the obvious way, `^\d+(\.\d{1,2})?$`, it would
 * also have refused `227.4200`, which is a penny price with trailing zeros and perfectly good FIX.
 *
 * **Reg SHO.** A sell short (`54=5`) with no LocateReqd (`114`) is refused, because a broker has to
 * have located the stock before it can be sold short. It carries no `40` condition: the rule applies
 * to a market order exactly as it does to a limit one.
 *
 * **The price band.** A limit more than 5% from the printed mid is refused as a fat-finger. It is
 * **two rules per listing, not one**, because [Matcher.Range] describes a bound and "outside a band"
 * is two of them. Both carry `40=2` as well as the price bound: a market order has no `44` to compare,
 * and a rule that reads a tag its own trigger does not guarantee is the discipline every preset here
 * keeps.
 *
 * Every reason code is one FIX 4.4 actually defines. `103=18` *Invalid price increment* would have
 * been the exact name for the sub-penny case and it does not exist before FIX 5.0 — the 4.4 enum stops
 * at 15 and 99 — so this venue says `103=11` *Unsupported order characteristic* and puts the specifics
 * in `58`. A venue that sends an undefined enum value teaches its reader something untrue.
 *
 */
object EquityVenuePreset {
    /** The id [AcceptorPresets.byId] answers to, and what the equity example workspace carries. */
    const val ID = "equity-venue"

    /** Shares. An equities bound where the FX venue's ten million is a currency one. */
    private const val MAX_SHARES = 1_000_000.0

    // ------------------------------------------------------------------ the listings

    /**
     * One listed name.
     *
     * Every number is a **string literal**, for the reason [FxVenuePreset.FxPair] gives: a band built by
     * arithmetic embeds `227.39999999999998` into the template and no reader could say why. The touch is
     * a penny wide because these are penny-quoted US names, and the band is the printed mid ±5%, to the
     * cent. `EquityVenuePresetTest` re-derives every band from the touch rather than trusting the
     * literals here, which is the same bargain `RfqVenuePresetTest` strikes with its own.
     */
    internal data class Listing(
        val symbol: String,
        val name: String,
        /** What the venue prints for a sell, and the centre of the published bid band. */
        val bid: String,
        /** What the venue prints for a buy. */
        val offer: String,
        val bidLow: String,
        val bidHigh: String,
        val offerLow: String,
        val offerHigh: String,
        /** 5% below the printed mid, to the cent. `EquityVenuePresetTest` re-derives it. */
        val bandLow: String,
        /** 5% above the printed mid. */
        val bandHigh: String,
    )

    internal val LISTINGS =
        listOf(
            Listing(
                symbol = "AAPL", name = "Apple Inc",
                bid = "227.40", offer = "227.42",
                bidLow = "227.35", bidHigh = "227.40",
                offerLow = "227.42", offerHigh = "227.47",
                bandLow = "216.04", bandHigh = "238.78",
            ),
            Listing(
                symbol = "MSFT", name = "Microsoft Corp",
                bid = "418.10", offer = "418.14",
                bidLow = "418.05", bidHigh = "418.10",
                offerLow = "418.14", offerHigh = "418.19",
                bandLow = "397.21", bandHigh = "439.03",
            ),
            Listing(
                symbol = "TSLA", name = "Tesla Inc",
                bid = "246.55", offer = "246.60",
                bidLow = "246.50", bidHigh = "246.55",
                offerLow = "246.60", offerHigh = "246.65",
                bandLow = "234.25", bandHigh = "258.90",
            ),
        )

    internal val SYMBOLS = LISTINGS.map { it.symbol }

    // ------------------------------------------------------------------ templates

    /** A price drawn across a band, quantised to the penny. Native: no script engine, no lock. */
    private fun draw(low: String, high: String) = "\${random:$low:$high:2}"

    /**
     * A round lot: one to nine hundred shares.
     *
     * Written as a one-digit draw with `00` glued on rather than `${random:100:900:0}`, which would
     * publish 137 shares at the top of a US book. Round lots are what a lit book shows.
     */
    private fun lots() = "\${random:1:9:0}00"

    /**
     * Top of book, both sides, as a MarketDataSnapshotFullRefresh.
     *
     * `268=2` and then two `269` entries: the manual builder reads the dictionary and starts a new
     * NoMDEntries instance at each repeat of the group's first field, which is what lets a reply carry a
     * two-sided book at all. Before that builder existed a reply's repeated tags collapsed onto one
     * FieldMap and the count claimed an entry that was not there.
     */
    private fun snapshot(listing: Listing): String =
        listOf(
            "35=W",
            "262=\${req.262}",
            "55=${listing.symbol}",
            "268=2",
            "269=0", "270=${draw(listing.bidLow, listing.bidHigh)}", "271=${lots()}",
            "269=1", "270=${draw(listing.offerLow, listing.offerHigh)}", "271=${lots()}",
        ).joinToString("|")

    /**
     * One side of the book moved, as a MarketDataIncrementalRefresh.
     *
     * `279=1` is Change, which is what a top-of-book update is; the Instrument sits **inside** the entry
     * on an X where it sits at the top level on a W, and the dictionary is what says so.
     */
    private fun tick(listing: Listing, entryType: String, low: String, high: String): String =
        listOf(
            "35=X",
            "262=\${req.262}",
            "268=1",
            "279=1",
            "269=$entryType",
            "55=${listing.symbol}",
            "270=${draw(low, high)}",
            "271=${lots()}",
        ).joinToString("|")

    /** `281=0` is MDReqRejReason Unknown symbol, and the text names what this venue does publish. */
    private val MD_REJECT =
        "35=Y|262=\${req.262}|281=0|58=This venue publishes ${SYMBOLS.joinToString(", ")}"

    /**
     * The print. `31` and `6` are the same literal because a single fill's LastPx and AvgPx must agree,
     * and because a scenario should be able to name the number it expects.
     */
    private fun fill(price: String): String =
        AcceptorPresets.executionReport(
            "150=F", "39=2", AcceptorPresets.ORDER_ECHO,
            "14=\${req.38}", "151=0", "31=$price", "32=\${req.38}", "6=$price",
        )

    /**
     * An IOC that found nothing to trade with.
     *
     * `14=0` and `151=0`: nothing filled, and nothing is left working either, which is the whole meaning
     * of immediate-or-cancel. The text says which of the venue's rules the client met, because "Canceled"
     * on its own reads like somebody cancelled it.
     */
    private val IOC_CANCELED =
        AcceptorPresets.executionReport(
            "150=4", "39=4", AcceptorPresets.ORDER_ECHO,
            "14=0", "151=0", "6=0",
            "58=Immediate-or-cancel: nothing resting at your limit",
        )

    /** `103=3` is OrdRejReason Order exceeds limit — here a price limit; the text names the bound. */
    private fun bandReject(listing: Listing, bound: String) =
        AcceptorPresets.executionReport(
            "150=8", "39=8", AcceptorPresets.ORDER_ECHO,
            "14=0", "151=0", "6=0", "103=3",
            "58=Limit price is more than 5% from the ${listing.symbol} mid, $bound",
        )

    /** `103=11` is Unsupported order characteristic. See the class comment on why not `103=18`. */
    private val SUB_PENNY =
        AcceptorPresets.executionReport(
            "150=8", "39=8", AcceptorPresets.ORDER_ECHO,
            "14=0", "151=0", "6=0", "103=11",
            "58=Sub-penny limit price: this venue quotes in whole cents (SEC Rule 612)",
        )

    /** `103=0` is Broker/exchange option, which is what a venue policy refusal is in FIX 4.4. */
    private val NO_LOCATE =
        AcceptorPresets.executionReport(
            "150=8", "39=8", AcceptorPresets.ORDER_ECHO,
            "14=0", "151=0", "6=0", "103=0",
            "58=Sell short without LocateReqd (114): Reg SHO requires a locate",
        )

    /**
     * **This venue's own status reply, because the shipped one is missing a required field.**
     *
     * `AcceptorPresets.ORDER_STATUS` carries no AvgPx, and FIX 4.4 requires one on every
     * ExecutionReport — so every venue built on it answers a status request with a message a validating
     * client rejects. Found by this venue's dictionary check (`EquityVenuePresetTest`), and **not fixed
     * in the shared template**, because `resolveOrderRefs` refuses to send a reply that reads an order
     * field the book has not got: adding `6` there would turn a malformed answer into no answer at all
     * for any venue whose book lacks an AvgPx, which is a worse failure and not this change's to make.
     *
     * Safe here because the two rules that use it fire only for an order this venue has already
     * reported on, and every report this venue sends carries a `6` — the ack's is `6=0` — so the book
     * always has one to give back.
     */
    private val ORDER_STATUS =
        AcceptorPresets.executionReport(
            "150=I",
            "37=\${order.orderId}",
            "11=\${req.11}",
            "39=\${order.ordStatus}",
            "14=\${order.cumQty}",
            "151=\${order.leavesQty}",
            "6=\${order.avgPx}",
            "55=\${order.symbol}",
            "54=\${order.side}",
            "38=\${order.orderQty}",
        )

    private val UNKNOWN_SYMBOL =
        AcceptorPresets.executionReport(
            "150=8", "39=8", AcceptorPresets.ORDER_ECHO,
            "14=0", "151=0", "6=0", "103=1",
            "58=This venue lists ${SYMBOLS.joinToString(", ")}",
        )

    // ------------------------------------------------------------------ conditions, named

    private val listed = AcceptorPresets.condition(55, Matcher.OneOf(SYMBOLS))
    private val limitOrder = AcceptorPresets.condition(40, Matcher.Exact("2"))
    private val marketOrder = AcceptorPresets.condition(40, Matcher.Exact("1"))
    private val immediateOrCancel = AcceptorPresets.condition(59, Matcher.Exact("3"))
    private val sellShort = AcceptorPresets.condition(54, Matcher.Exact("5"))
    private val noLocate = AcceptorPresets.condition(114, Matcher.Absent)

    /** A third decimal or beyond that is not zero. See the class comment: the fault, not the good case. */
    private val subPenny = AcceptorPresets.condition(44, Matcher.Regex("""^\d+\.\d\d[0-9]*[1-9][0-9]*$"""))

    private fun symbolIs(listing: Listing) = AcceptorPresets.condition(55, Matcher.Exact(listing.symbol))

    private fun sideIs(side: String) = AcceptorPresets.condition(54, Matcher.Exact(side))

    // ------------------------------------------------------------------ 35=D

    /**
     * **The rule this venue exists for.** One step, and the order is still working when it finishes.
     *
     * No `59` condition, deliberately — see the class comment: Day is FIX's default, so this has to be
     * what an order with no TimeInForce falls to.
     */
    private val restsOnTheBook =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = listOf(listed, limitOrder),
            steps = listOf(ResponseStep(AcceptorPresets.ACK)),
        )

    /**
     * An IOC that crossed the touch, filled **at the touch and not at its limit**.
     *
     * A buy that bids 230.00 into a 227.42 offer pays 227.42; the difference is price improvement, and
     * a venue that filled it at 230.00 would be keeping the improvement for itself. That is why the
     * price here is the listing's own and not `${req.44}`.
     */
    private fun iocFillRule(listing: Listing, side: String, marketable: Matcher, price: String) =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions =
                listOf(
                    symbolIs(listing),
                    limitOrder,
                    immediateOrCancel,
                    sideIs(side),
                    AcceptorPresets.condition(44, marketable),
                ),
            steps = listOf(ResponseStep(AcceptorPresets.ACK), ResponseStep(fill(price), delayMillis = 150)),
        )

    /** A buy is marketable at or above the offer; a sell at or below the bid. */
    private fun iocRules(listing: Listing) =
        listOf(
            iocFillRule(listing, "2", Matcher.Range(max = listing.bid.toDouble()), listing.bid),
            iocFillRule(listing, "1", Matcher.Range(min = listing.offer.toDouble()), listing.offer),
        )

    private val iocCancelled =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = listOf(listed, limitOrder, immediateOrCancel),
            steps = listOf(ResponseStep(AcceptorPresets.ACK), ResponseStep(IOC_CANCELED, delayMillis = 150)),
        )

    /** A buy pays the offer and a sell hits the bid, which is why this is two rules per listing. */
    private fun marketRule(listing: Listing, side: String, price: String) =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = listOf(symbolIs(listing), marketOrder, sideIs(side)),
            steps = listOf(ResponseStep(AcceptorPresets.ACK), ResponseStep(fill(price), delayMillis = 150)),
        )

    private fun bandRule(listing: Listing, matcher: Matcher, bound: String) =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = listOf(symbolIs(listing), limitOrder, AcceptorPresets.condition(44, matcher)),
            steps = listOf(ResponseStep(bandReject(listing, bound))),
        )

    private fun bandRules(listing: Listing) =
        listOf(
            bandRule(
                listing,
                Matcher.Range(max = listing.bandLow.toDouble(), maxInclusive = false),
                "which opens at ${listing.bandLow}",
            ),
            bandRule(
                listing,
                Matcher.Range(min = listing.bandHigh.toDouble(), minInclusive = false),
                "which closes at ${listing.bandHigh}",
            ),
        )

    private val subPennyRejected =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = listOf(listed, limitOrder, subPenny),
            steps = listOf(ResponseStep(SUB_PENNY)),
        )

    private val shortWithoutLocate =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = listOf(listed, sellShort, noLocate),
            steps = listOf(ResponseStep(NO_LOCATE)),
        )

    private val overSize =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = listOf(AcceptorPresets.condition(38, Matcher.Range(min = MAX_SHARES, minInclusive = false))),
            steps = listOf(ResponseStep(AcceptorPresets.ORDER_REJECT)),
        )

    private val orderUnknownSymbol =
        AcceptorResponseRule(whenMsgType = "D", steps = listOf(ResponseStep(UNKNOWN_SYMBOL)))

    // ------------------------------------------------------------------ 35=V

    private fun subscribeRule(listing: Listing) =
        AcceptorResponseRule(
            whenMsgType = "V",
            conditions = listOf(symbolIs(listing), AcceptorPresets.condition(263, Matcher.Exact("1"))),
            steps =
                listOf(
                    ResponseStep(snapshot(listing)),
                    ResponseStep(tick(listing, "0", listing.bidLow, listing.bidHigh), delayMillis = 400),
                    ResponseStep(tick(listing, "1", listing.offerLow, listing.offerHigh), delayMillis = 400),
                ),
        )

    private fun snapshotRule(listing: Listing) =
        AcceptorResponseRule(
            whenMsgType = "V",
            conditions = listOf(symbolIs(listing)),
            steps = listOf(ResponseStep(snapshot(listing))),
        )

    /** Working and done are the two states the venue can answer: both mean it has already reported. */
    private fun statusRule(constraint: OrderConstraint) =
        AcceptorResponseRule(
            whenMsgType = "H",
            whenOrder = constraint,
            steps = listOf(ResponseStep(ORDER_STATUS)),
        )

    private val marketDataReject =
        AcceptorResponseRule(whenMsgType = "V", steps = listOf(ResponseStep(MD_REJECT)))

    // ------------------------------------------------------------------ the bundle

    /**
     * **Declared backwards to read forwards**, as both venues before it are: [AcceptorPresets.insert]
     * puts a conditioned rule above the first rule for its MsgType and appends an unconditioned one, so
     * each block lists its conditioned rules last-first and its catch-all last. `EquityVenuePresetTest`
     * asserts what the cards actually read, because four things depend on the order and none is visible
     * from a single rule:
     *
     * - **The duplicates outrank everything for `35=D`**, or a ClOrdID sent twice rests twice and the
     *   second one quietly shadows the first in the book.
     * - **The three refusals outrank every accept path**, so a two-million-share order priced inside the
     *   band is refused for the thing that is actually wrong with it, and a sub-penny sell short with no
     *   locate is refused rather than rested.
     * - **The band rules outrank the resting rule.** They are the reason a limit order is ever refused;
     *   below it, every one of them is dead, because the resting rule catches any listed limit order.
     * - **The IOC rule outranks the resting rule**, for the same reason, and the resting rule is last of
     *   the three because it is the default in both senses: FIX's, and this venue's.
     */
    internal val rules: List<AcceptorResponseRule> =
        // 35=D — the guards, then the flows, then the refusal everything unlisted falls to.
        LISTINGS.reversed().flatMap { listing ->
            listOf(
                marketRule(listing, side = "2", price = listing.bid),
                marketRule(listing, side = "1", price = listing.offer),
            )
        } +
            listOf(restsOnTheBook, iocCancelled) +
            LISTINGS.reversed().flatMap(::iocRules) +
            LISTINGS.reversed().flatMap(::bandRules) +
            listOf(subPennyRejected, shortWithoutLocate, overSize) +
            starterRules("D", OrderConstraint.PENDING, OrderConstraint.WORKING) +
            orderUnknownSymbol +
            // 35=F, 35=G, 35=H — reused by name, and here they finally have a resting order to act on.
            starterRules(
                "F",
                OrderConstraint.DONE,
                OrderConstraint.WORKING,
                OrderConstraint.PENDING,
                OrderConstraint.UNKNOWN,
            ) +
            listOf(AcceptorPresets.replaceAccepted, AcceptorPresets.replaceAcceptedSameId) +
            listOf(
                statusRule(OrderConstraint.DONE),
                statusRule(OrderConstraint.WORKING),
                AcceptorPresets.statusRequestUnknown,
            ) +
            // 35=V — a subscription per listing, a snapshot per listing, then the reject.
            LISTINGS.reversed().map(::snapshotRule) +
            LISTINGS.reversed().map(::subscribeRule) +
            marketDataReject

    val preset: AcceptorPreset =
        AcceptorPreset(
            id = ID,
            name = "Equity venue — ${SYMBOLS.joinToString(", ")} on a resting book",
            group = AcceptorPresets.GROUP_BUNDLES,
            summary =
                "${rules.size} rules · Day limits rest until cancelled · IOC fills at the touch or " +
                    "cancels · sub-penny and Reg SHO refused · market data snapshots and updates",
            rules = rules,
        )

    /** The shipped rules for [msgType], reused rather than re-typed, exactly as [FxVenuePreset] does. */
    private fun starterRules(msgType: String, vararg constraints: OrderConstraint): List<AcceptorResponseRule> {
        val shipped =
            listOf(
                AcceptorPresets.duplicateWorking,
                AcceptorPresets.duplicatePending,
                AcceptorPresets.cancelTooLate,
                AcceptorPresets.cancelAcceptedWorking,
                AcceptorPresets.cancelAcceptedPending,
                AcceptorPresets.cancelRejectedUnknown,
            )
        return constraints.map { constraint ->
            shipped.first { it.whenMsgType == msgType && it.whenOrder == constraint }
        }
    }
}
