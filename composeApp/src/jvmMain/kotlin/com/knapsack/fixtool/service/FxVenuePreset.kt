package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher

/**
 * **An FX venue, as rules you can read** — the bundle the demo installs, and the answer to "what does
 * a venue actually do?" for anyone who opens the preset menu.
 *
 * It lives beside [AcceptorPresets] rather than inside it for one reason and one only: that object is
 * already past detekt's `LargeClass` threshold, and this adds twenty-one rules. The catalogue is still
 * single — [AcceptorPresets.all] includes [preset] — and everything reused from the starter venue is
 * referenced by name rather than re-typed, so the two venues cannot drift apart.
 *
 * ### Why the prices are expressions
 *
 * A venue that answers every quote request with the same number is a venue nobody believes. Every
 * price here is a Kotlin expression evaluated as the reply is sent, so two quotes a second apart
 * differ — with no new engine work, because templates have always been able to carry them.
 *
 * Three things about those expressions were **measured**, not assumed, and each one is a way this
 * would have shipped broken:
 *
 * - **`(0..9).random()`, never `Random.nextInt(…)`.** `kotlin.random` is not one of Kotlin's default
 *   imports and the script preamble does not add it, and a template expression that fails to compile
 *   is returned **as its own source text** — `132=${Random.nextInt(0, 5)}` goes on the wire verbatim,
 *   as the value of the field. It is not an empty field, so the guard that catches those cannot see
 *   it; `FxVenuePresetTest` has one that can.
 * - **`"%.5f".format(…)`, never bare arithmetic.** `1.08990 + 2 * 1.0E-5` renders through
 *   `Double.toString()` as `1.0901` or `1.09010000000000001` depending on the draw. A price whose
 *   number of decimals varies per message is not a price.
 * - **`java.util.Locale.US`, explicitly.** Without it a JVM whose default locale uses a comma decimal
 *   separator writes `1,08995`, and every test run on a US-locale machine still passes.
 *
 * ### Why the bid and the ask come from one draw
 *
 * Two independent draws would let the spread vary, and let it **invert** — a quote whose bid is above
 * its ask is the one mistake an FX audience catches instantly. [FixMessageTemplate.evaluate] shares a
 * variable map across every `${…}` of one template and injects what it stores into the script by bare
 * name, so the bid is drawn and named once and the ask is derived from it. The same trick keeps a
 * fill's LastPx and AvgPx equal, which for a single fill they must be.
 *
 * That makes template **order** load-bearing — the substitution runs left to right, so the naming
 * expression has to precede the reading one. A test pins it, because nothing else here would tell a
 * reader that moving a field could break the message.
 */
object FxVenuePreset {
    /** The id [AcceptorPresets.byId] answers to, and what the demo workspace asks for. */
    const val ID = "fx-venue"

    // ------------------------------------------------------------------ the pairs

    /**
     * One priced pair.
     *
     * Every number is a **string literal**, not arithmetic, so what reaches the template is exactly
     * what is written here — building `1.08990` by subtracting half a spread from a mid would embed
     * `1.0898999999999999` into the expression and no reader could tell why.
     *
     * [tick] is deliberately the *pipette* (a tenth of a pip) rather than the pip. Jittering whole pips
     * and printing five decimals yields prices that always end in `0`, which reads as a placeholder —
     * the opposite of what the jitter is for.
     */
    private data class FxPair(
        val symbol: String,
        val quoteCurrency: String,
        /** Decimals the pair is quoted to — 5 for the dollar majors, 3 for JPY. The realism flex. */
        val decimals: Int,
        /** The bid before jitter: the mid less half the spread. */
        val bidFloor: String,
        val spread: String,
        /** The market-fill price before jitter, set half a jitter-range below the mid so it straddles it. */
        val fillFloor: String,
        val tick: String,
    )

    private val PAIRS =
        listOf(
            FxPair(
                "EUR/USD",
                "USD",
                decimals = 5,
                bidFloor = "1.08990",
                spread = "2.0E-4",
                fillFloor = "1.08995",
                tick = "1.0E-5",
            ),
            FxPair(
                "GBP/USD",
                "USD",
                decimals = 5,
                bidFloor = "1.26985",
                spread = "3.0E-4",
                fillFloor = "1.26995",
                tick = "1.0E-5",
            ),
            FxPair(
                "USD/JPY",
                "JPY",
                decimals = 3,
                bidFloor = "149.490",
                spread = "2.0E-2",
                fillFloor = "149.495",
                tick = "1.0E-3",
            ),
        )

    /** The symbols the venue prices — what the limit rule's `oneOf` is built from. */
    private val SYMBOLS = PAIRS.map { it.symbol }

    // ------------------------------------------------------------------ prices, as expressions

    /** `"%.Nf".format(Locale.US, <value>)` wrapped as a template expression. See the class KDoc. */
    private fun formatted(pair: FxPair, value: String): String =
        "\${\"%.${pair.decimals}f\".format(java.util.Locale.US, $value)}"

    /** The same, but **naming** its result so a later field can read it back: `${name = …}`. */
    private fun formattedAs(name: String, pair: FxPair, value: String): String =
        "\${$name = \"%.${pair.decimals}f\".format(java.util.Locale.US, $value)}"

    /** 0–9 ticks of movement, drawn as the step is sent. `(0..9).random()`, for the reason in the KDoc. */
    private fun jitter(pair: FxPair): String = "(0..9).random() * ${pair.tick}"

    // ------------------------------------------------------------------ templates

    /**
     * A two-sided quote. `117` is the QuoteID this venue mints; `131` echoes the request's QuoteReqID.
     *
     * `131` is read without a presence condition because the dictionary guarantees it — QuoteReqID is
     * `required="Y"` on a QuoteRequest — which is the standing the starter venue's `CANCEL_ECHO`
     * already reads `41` on. No `134`/`135`: a QuoteRequest's OrderQty is optional *and* lives inside
     * NoRelatedSym, so there is no size the trigger guarantees, and inventing one would be the venue
     * answering a question nobody asked.
     */
    private fun quote(pair: FxPair): String =
        listOf(
            "35=S",
            "117=\${uuid}",
            "131=\${req.131}",
            "55=${pair.symbol}",
            // Named here, read on the next line — the order is the mechanism, not a style choice.
            "132=" + formattedAs("bid", pair, "${pair.bidFloor} + ${jitter(pair)}"),
            "133=" + formatted(pair, "bid.toDouble() + ${pair.spread}"),
            "15=${pair.quoteCurrency}",
            "60=\${now}",
        ).joinToString("|")

    /**
     * The refusal a venue owes a symbol it does not price.
     *
     * `146=1|55=${req.55}` is a **repeating group**, and both halves of it were impossible until this
     * bundle needed them: the acceptor could not read a tag inside a group (so `${req.55}` saw
     * nothing on a conformant request), and could not build one (the reply's fields sorted by tag
     * number, so `55` arrived before the `146` meant to contain it). Both are fixed in
     * [AcceptorResponder]; `AcceptorReplyBuilderTest` is where that is proved.
     *
     * NoRelatedSym is `required="Y"` on a QuoteRequestReject, so a flat reply would have been a
     * malformed message — and one whose consequences land on the client.
     */
    private val QUOTE_REQUEST_REJECT =
        "35=AG|131=\${req.131}|658=1|146=1|55=\${req.55}|58=Symbol not supported"

    /**
     * A market order's fill, priced from the **rule's own content**.
     *
     * This is why the market rules are per-pair and conditioned on the symbol: a market order carries
     * no `44`, so a template pricing at `${req.44}` would put `31=` on the wire. The price has to come
     * from somewhere the trigger guarantees, and the only such place is the rule itself.
     *
     * `6` reads back the `31` this template named, rather than drawing again — one fill has one price,
     * and an AvgPx that disagreed with its own LastPx would be a venue contradicting itself.
     */
    private fun marketFill(pair: FxPair): String =
        AcceptorPresets.executionReport(
            "150=F",
            "39=2",
            AcceptorPresets.ORDER_ECHO,
            "14=\${req.38}",
            "151=0",
            "32=\${req.38}",
            "31=" + formattedAs("px", pair, "${pair.fillFloor} + ${jitter(pair)}"),
            "6=\${px}",
        )

    /** `103=1` is OrdRejReason *Unknown symbol*, which is the whole of what this rule found out. */
    private val UNKNOWN_SYMBOL_REJECT =
        AcceptorPresets.executionReport(
            "150=8",
            "39=8",
            AcceptorPresets.ORDER_ECHO,
            "14=0",
            "151=0",
            "6=0",
            "103=1",
            "58=Symbol not supported by this venue",
        )

    // ------------------------------------------------------------------ the rules

    private fun quoteRule(pair: FxPair) =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions = listOf(AcceptorPresets.condition(55, Matcher.Exact(pair.symbol))),
            steps = listOf(ResponseStep(quote(pair))),
        )

    /**
     * Unconditional, and that is how "not one of the three" is written without a negated matcher:
     * first-match-wins does it, provided this sits below the three that name a pair. [AcceptorPresets.insert]
     * puts it there — see the ordering note on [preset].
     */
    private val quoteUnknownSymbol =
        AcceptorResponseRule(whenMsgType = "R", steps = listOf(ResponseStep(QUOTE_REQUEST_REJECT)))

    private fun marketRule(pair: FxPair) =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions =
                listOf(
                    AcceptorPresets.condition(55, Matcher.Exact(pair.symbol)),
                    AcceptorPresets.condition(40, Matcher.Exact("1")),
                ),
            steps = listOf(ResponseStep(AcceptorPresets.ACK), ResponseStep(marketFill(pair), delayMillis = 250)),
        )

    /**
     * The limit flow: the shipped ack→partial→remainder sequence, narrowed to the symbols this venue
     * prices. Every price in it is `${req.44}` — the order's own — so it needs no expression and no
     * per-pair copy, and the scenario that runs it is deterministic enough to assert a price on.
     */
    private val limitFlow =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions =
                listOf(
                    AcceptorPresets.condition(55, Matcher.OneOf(SYMBOLS)),
                    AcceptorPresets.condition(40, Matcher.Exact("2")),
                ),
            steps =
                listOf(
                    ResponseStep(AcceptorPresets.ACK),
                    ResponseStep(AcceptorPresets.PARTIAL_FILL, delayMillis = 250),
                    ResponseStep(AcceptorPresets.FILL_REMAINDER, delayMillis = 250),
                ),
        )

    /** 10M is an FX-plausible bound where the shipped preset's 1M is an equities one. */
    private val overSize =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = listOf(AcceptorPresets.condition(38, Matcher.Range(min = 10_000_000.0, minInclusive = false))),
            steps = listOf(ResponseStep(AcceptorPresets.ORDER_REJECT)),
        )

    private val orderUnknownSymbol =
        AcceptorResponseRule(whenMsgType = "D", steps = listOf(ResponseStep(UNKNOWN_SYMBOL_REJECT)))

    // ------------------------------------------------------------------ the bundle

    /**
     * **The declaration order below is not the order these read on screen**, and the difference is the
     * whole of why it is written down.
     *
     * [AcceptorPresets.insert] places a *conditioned* rule above the first enabled rule for its MsgType
     * and *appends* an unconditioned one, so each conditioned block is declared backwards to come out
     * forwards — the same trick the starter venue's cancel rules use. What the cards actually read is
     * asserted by `FxVenuePresetTest`, because three things depend on it and none is visible here:
     *
     * - **The over-size reject must outrank the fills.** A 20M EUR/USD limit order matches both, and
     *   first-match-wins. This is the defect that was live-caught when presets first shipped.
     * - **The duplicate rules must outrank everything for `35=D`**, or a ClOrdID sent twice is filled
     *   twice instead of rejected — which is exactly what a demo scenario run twice does.
     * - **Each unconditioned rule must land at the foot of its own block.** `insert` appends it to the
     *   end of the *whole* list at the moment it is inserted, so it has to be declared before the next
     *   MsgType's rules or it sinks beneath the cancels and the card list reads as noise.
     */
    val preset: AcceptorPreset =
        AcceptorPreset(
            id = ID,
            name = "FX venue — EUR/USD, GBP/USD, USD/JPY",
            group = AcceptorPresets.GROUP_BUNDLES,
            summary = "21 rules · quotes per pair · orders, cancels, replaces, status",
            rules =
                // 35=R — the three priced pairs, then the refusal everything else falls to.
                PAIRS.reversed().map(::quoteRule) +
                    quoteUnknownSymbol +
                    // 35=D — fills, then the guards that must outrank them, then the catch-all.
                    PAIRS.reversed().map(::marketRule) +
                    limitFlow +
                    overSize +
                    starterRules("D", OrderConstraint.PENDING, OrderConstraint.WORKING) +
                    orderUnknownSymbol +
                    // 35=F, 35=G, 35=H — verbatim, by name.
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
                    ),
        )

    /**
     * The shipped rules for [msgType], in the order named — reused rather than re-typed, so a fix to
     * the starter venue's cancel handling reaches this venue too.
     */
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
