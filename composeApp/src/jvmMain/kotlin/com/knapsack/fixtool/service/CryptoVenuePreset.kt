package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher

/**
 * **A 24/7 crypto spot venue**, and the fourth bundled example — built to answer the question the other
 * three raise: does this tool only know the instruments a bank trades?
 *
 * It would have been easy, and worthless, to ship the equity venue with `BTC-USD` where `AAPL` is. A
 * crypto venue sends `35=D` and gets `35=8` back like everything else, so the only thing that makes this
 * a different example is the set of rules a crypto venue actually has and an equity venue actually does
 * not. There are four, and each is why one of the rules below exists:
 *
 * | What only a crypto venue does | The rule |
 * |---|---|
 * | Never closes | A Day order is **refused**, because there is no day to be good for |
 * | Sizes in satoshis | A quantity finer than the product's **base increment** is refused |
 * | Has a dust floor | A quantity below the product's **minimum size** is refused |
 * | Sells maker-only access | A **post-only** order that would take liquidity is refused, not filled |
 *
 * Post-only is the one worth opening the cards for. `18=6` *ParticipateDontInitiate* is FIX's name for
 * "I want the maker fee, so do not let me cross" — a client asking for it is asking the venue to refuse
 * the order rather than execute it at a worse economic outcome, which is the opposite of what every other
 * venue in this build does with a marketable order. It is also a rule you cannot write without knowing
 * both the order's price and the venue's own touch, and that pairing is what the price bands are for.
 *
 * ### The names, the prices and the sizes are the real ones
 *
 * Products are written `BTC-USD`, the hyphenated form Coinbase and its imitators use, not the
 * `BTCUSDT` of the futures venues or the `BTC/USD` an FX desk would write. Quantities are fractional and
 * carry eight decimals, because that is what a base increment of `0.00000001` means, and the minimums are
 * a real dust floor rather than a round number: nobody's book takes an order for 0.000001 BTC.
 *
 * ### Everything renders natively
 *
 * Same discipline as [EquityVenuePreset] and for the same measured reason: `${random:…}` is rendered by
 * the template renderer in microseconds, while a Kotlin expression is compiled one at a time for the
 * whole process at tens of milliseconds each. A venue meant to be hit at volume cannot afford one.
 */
object CryptoVenuePreset {
    /** The id [AcceptorPresets.byId] answers to, and what the crypto example workspace carries. */
    const val ID = "crypto-venue"

    // ------------------------------------------------------------------ the products

    /**
     * One listed product.
     *
     * Every number is a **string literal**, as both venues before it are, so what reaches the template is
     * what is written here rather than the nearest double to it. [minSize] is the venue's dust floor and
     * [bid]/[offer] are what it prints — the touch a post-only order is judged against.
     */
    internal data class Product(
        val symbol: String,
        val base: String,
        val bid: String,
        val offer: String,
        val bidLow: String,
        val bidHigh: String,
        val offerLow: String,
        val offerHigh: String,
        /** The dust floor, in the base currency. Below this the venue will not take an order at all. */
        val minSize: String,
    )

    internal val PRODUCTS =
        listOf(
            Product(
                symbol = "BTC-USD", base = "BTC",
                bid = "61240.50", offer = "61240.60",
                bidLow = "61239.80", bidHigh = "61240.50",
                offerLow = "61240.60", offerHigh = "61241.30",
                minSize = "0.0001",
            ),
            Product(
                symbol = "ETH-USD", base = "ETH",
                bid = "2415.30", offer = "2415.35",
                bidLow = "2415.10", bidHigh = "2415.30",
                offerLow = "2415.35", offerHigh = "2415.55",
                minSize = "0.001",
            ),
            Product(
                symbol = "SOL-USD", base = "SOL",
                bid = "142.18", offer = "142.20",
                bidLow = "142.13", bidHigh = "142.18",
                offerLow = "142.20", offerHigh = "142.25",
                minSize = "0.01",
            ),
        )

    internal val SYMBOLS = PRODUCTS.map { it.symbol }

    // ------------------------------------------------------------------ templates

    private fun draw(low: String, high: String) = "\${random:$low:$high:2}"

    /** A fractional size, to the eighth decimal, which is what a base increment of 0.00000001 means. */
    private fun size(low: String, high: String) = "\${random:$low:$high:8}"

    private fun fill(price: String): String =
        AcceptorPresets.executionReport(
            "150=F", "39=2", AcceptorPresets.ORDER_ECHO,
            "14=\${req.38}", "151=0", "31=$price", "32=\${req.38}", "6=$price",
        )

    private fun refusal(reason: String, text: String): String =
        AcceptorPresets.executionReport(
            "150=8", "39=8", AcceptorPresets.ORDER_ECHO,
            "14=0", "151=0", "6=0", "103=$reason", "58=$text",
        )

    /**
     * `103=11` is Unsupported order characteristic, and a TimeInForce this venue does not keep is exactly
     * that. The text names the two it does, because a client that sent Day has to be told what to send
     * instead.
     */
    private val DAY_REFUSED =
        refusal("11", "This venue never closes: send GTC (59=1) or IOC (59=3), not Day")

    /** `103=13` is Incorrect quantity, which a size finer than the venue can hold is. */
    private val SUB_SATOSHI =
        refusal("13", "Quantity is finer than the 0.00000001 base increment")

    private fun dust(product: Product) =
        refusal("13", "Below the ${product.symbol} minimum of ${product.minSize} ${product.base}")

    /**
     * The refusal a post-only order earns for being marketable.
     *
     * `103=11` again, because "I would have taken liquidity" is a characteristic of the order the venue
     * will not support on this client's instruction — and the instruction was the client's own.
     */
    private fun wouldTake(product: Product) =
        refusal(
            "11",
            "Post-only (18=6) would have taken liquidity against the ${product.symbol} touch",
        )

    private val UNKNOWN_PRODUCT =
        refusal("1", "This venue lists ${SYMBOLS.joinToString(", ")}")

    /** Top of book, both sides, fractional sizes. Same shape the equity venue publishes. */
    private fun snapshot(product: Product): String =
        listOf(
            "35=W",
            "262=\${req.262}",
            "55=${product.symbol}",
            "268=2",
            "269=0", "270=${draw(product.bidLow, product.bidHigh)}", "271=${size("0.05", "9.5")}",
            "269=1", "270=${draw(product.offerLow, product.offerHigh)}", "271=${size("0.05", "9.5")}",
        ).joinToString("|")

    private val MD_REJECT =
        "35=Y|262=\${req.262}|281=0|58=This venue publishes ${SYMBOLS.joinToString(", ")}"

    /** An IOC that found no contra inside its limit. */
    private val IOC_CANCELED =
        AcceptorPresets.executionReport(
            "150=4", "39=4", AcceptorPresets.ORDER_ECHO,
            "14=0", "151=0", "6=0",
            "58=Immediate-or-cancel: no contra inside your limit",
        )

    // ------------------------------------------------------------------ conditions, named

    private val listed = AcceptorPresets.condition(55, Matcher.OneOf(SYMBOLS))
    private val limitOrder = AcceptorPresets.condition(40, Matcher.Exact("2"))
    private val marketOrder = AcceptorPresets.condition(40, Matcher.Exact("1"))
    private val immediateOrCancel = AcceptorPresets.condition(59, Matcher.Exact("3"))
    private val dayOrder = AcceptorPresets.condition(59, Matcher.Exact("0"))
    private val postOnly = AcceptorPresets.condition(18, Matcher.Exact("6"))

    /**
     * A ninth decimal or beyond that is not zero.
     *
     * Written as the fault rather than the good case, for the reason [EquityVenuePreset]'s sub-penny
     * condition is: `0.10000000` is eight decimals written long and a perfectly good size, and a pattern
     * over the acceptable forms would have refused it.
     */
    private val subSatoshi =
        AcceptorPresets.condition(38, Matcher.Regex("""^\d+\.\d{8}[0-9]*[1-9][0-9]*$"""))

    private fun symbolIs(product: Product) = AcceptorPresets.condition(55, Matcher.Exact(product.symbol))

    private fun sideIs(side: String) = AcceptorPresets.condition(54, Matcher.Exact(side))

    // ------------------------------------------------------------------ 35=D

    private val dayRefused =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = listOf(listed, dayOrder),
            steps = listOf(ResponseStep(DAY_REFUSED)),
        )

    private val subSatoshiRefused =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = listOf(listed, subSatoshi),
            steps = listOf(ResponseStep(SUB_SATOSHI)),
        )

    private fun dustRule(product: Product) =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions =
                listOf(
                    symbolIs(product),
                    AcceptorPresets.condition(
                        38,
                        Matcher.Range(max = product.minSize.toDouble(), maxInclusive = false),
                    ),
                ),
            steps = listOf(ResponseStep(dust(product))),
        )

    /**
     * **The rule this venue is here for.** A post-only buy at or above the offer would have crossed, so
     * it is refused rather than filled — the client asked to be a maker, and taking would have cost it
     * the fee it was protecting.
     */
    private fun postOnlyRule(product: Product, side: String, marketable: Matcher) =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions =
                listOf(
                    symbolIs(product),
                    limitOrder,
                    postOnly,
                    sideIs(side),
                    AcceptorPresets.condition(44, marketable),
                ),
            steps = listOf(ResponseStep(wouldTake(product))),
        )

    private fun postOnlyRules(product: Product) =
        listOf(
            postOnlyRule(product, "2", Matcher.Range(max = product.bid.toDouble())),
            postOnlyRule(product, "1", Matcher.Range(min = product.offer.toDouble())),
        )

    /** A GTC limit rests on a book that never closes. */
    private val restsOnTheBook =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = listOf(listed, limitOrder),
            steps = listOf(ResponseStep(AcceptorPresets.ACK)),
        )

    private fun iocFillRule(product: Product, side: String, marketable: Matcher, price: String) =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions =
                listOf(
                    symbolIs(product),
                    limitOrder,
                    immediateOrCancel,
                    sideIs(side),
                    AcceptorPresets.condition(44, marketable),
                ),
            steps = listOf(ResponseStep(AcceptorPresets.ACK), ResponseStep(fill(price), delayMillis = 150)),
        )

    private fun iocRules(product: Product) =
        listOf(
            iocFillRule(product, "2", Matcher.Range(max = product.bid.toDouble()), product.bid),
            iocFillRule(product, "1", Matcher.Range(min = product.offer.toDouble()), product.offer),
        )

    private val iocCancelled =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = listOf(listed, limitOrder, immediateOrCancel),
            steps = listOf(ResponseStep(AcceptorPresets.ACK), ResponseStep(IOC_CANCELED, delayMillis = 150)),
        )

    private fun marketRule(product: Product, side: String, price: String) =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = listOf(symbolIs(product), marketOrder, sideIs(side)),
            steps = listOf(ResponseStep(AcceptorPresets.ACK), ResponseStep(fill(price), delayMillis = 150)),
        )

    private val orderUnknownProduct =
        AcceptorResponseRule(whenMsgType = "D", steps = listOf(ResponseStep(UNKNOWN_PRODUCT)))

    // ------------------------------------------------------------------ 35=H, 35=V

    private fun snapshotRule(product: Product) =
        AcceptorResponseRule(
            whenMsgType = "V",
            conditions = listOf(symbolIs(product)),
            steps = listOf(ResponseStep(snapshot(product))),
        )

    private val marketDataReject =
        AcceptorResponseRule(whenMsgType = "V", steps = listOf(ResponseStep(MD_REJECT)))

    // ------------------------------------------------------------------ the bundle

    /**
     * **Declared backwards to read forwards**, as every bundle here is. `CryptoVenuePreset`'s order
     * carries three claims that nothing in a single rule shows:
     *
     * - **The four refusals outrank every accept path**, so an order that is both dust and post-only is
     *   refused for being dust, which is the thing that would still be wrong after the client dropped the
     *   instruction.
     * - **Post-only outranks the IOC fills and the resting rule**, or a marketable post-only order is
     *   filled — which is precisely the outcome its sender paid to avoid.
     * - **The Day refusal is first of the four**, because it is the only one that is a fact about the
     *   venue's hours rather than about the order, and a client that has the wrong hours has nothing else
     *   worth being told.
     */
    internal val rules: List<AcceptorResponseRule> =
        PRODUCTS.reversed().flatMap { product ->
            listOf(
                marketRule(product, side = "2", price = product.bid),
                marketRule(product, side = "1", price = product.offer),
            )
        } +
            listOf(restsOnTheBook, iocCancelled) +
            PRODUCTS.reversed().flatMap(::iocRules) +
            PRODUCTS.reversed().flatMap(::postOnlyRules) +
            PRODUCTS.reversed().map(::dustRule) +
            listOf(subSatoshiRefused, dayRefused) +
            starterRules("D", OrderConstraint.PENDING, OrderConstraint.WORKING) +
            orderUnknownProduct +
            starterRules(
                "F",
                OrderConstraint.DONE,
                OrderConstraint.WORKING,
                OrderConstraint.PENDING,
                OrderConstraint.UNKNOWN,
            ) +
            listOf(AcceptorPresets.replaceAccepted, AcceptorPresets.replaceAcceptedSameId) +
            listOf(
                AcceptorPresets.statusRequestDone,
                AcceptorPresets.statusRequestWorking,
                AcceptorPresets.statusRequestUnknown,
            ) +
            PRODUCTS.reversed().map(::snapshotRule) +
            marketDataReject

    val preset: AcceptorPreset =
        AcceptorPreset(
            id = ID,
            name = "Crypto venue — ${SYMBOLS.joinToString(", ")}, 24/7",
            group = AcceptorPresets.GROUP_BUNDLES,
            summary =
                "${rules.size} rules · no Day orders · post-only refused rather than crossed · " +
                    "sub-satoshi and dust refused · GTC rests, IOC fills at the touch or cancels",
            rules = rules,
        )

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
