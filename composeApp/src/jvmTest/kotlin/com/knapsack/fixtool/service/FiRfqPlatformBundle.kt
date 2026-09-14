package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionConfig.ConnectionType
import com.knapsack.fixtool.model.FixConnectionConfig.MessageLogKind
import com.knapsack.fixtool.model.FixConnectionConfig.MessageStoreKind
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPhaseSpec
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.model.scenario.BindScope
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.TagValue
import com.knapsack.fixtool.model.scenario.TrafficMode

/**
 * **The Fixed Income RFQ Platform example, as the data it is written from.**
 *
 * [ExampleBundleGenerator] writes these through the app's own stores, and `ExampleWorkspacesTest` reads the bundle
 * back and compares it with them, so the JSON in the build is never edited by hand and never drifts from this.
 *
 * The parties are the ones [FiRfqPlatformPreset] is written against: two buy sides and two dealers to be played by
 * hand, and a load client of each. Only the Dealer Load Client carries rules, which is what lets a load run have
 * five dealers answering without a person.
 *
 * ### Why the scenarios quote the prices they do
 *
 * The Dealer Load Client quotes every request at the ten-year's table level, 98-16+, and a user demoing this will
 * often have it connected. So the two-dealer scenario prices both hand-played dealers inside that level: Dealer 1
 * at 98-15+ and Dealer 2 at 98-16. The better offer is Dealer 1's and the cover is Dealer 2's whoever else is online,
 * which is the claim the scenario makes.
 */
internal object FiRfqPlatformBundle {
    const val VENUE_COMP_ID = "FIRFQ_VENUE"
    const val PORT = "19880"

    const val VENUE = "fi-rfq-profile-venue"
    const val BUY_SIDE_1 = "fi-rfq-profile-BUY1"
    const val BUY_SIDE_2 = "fi-rfq-profile-BUY2"
    const val DEALER_1 = "fi-rfq-profile-DLR1"
    const val DEALER_2 = "fi-rfq-profile-DLR2"
    const val BUY_SIDE_LOAD = "fi-rfq-profile-BUY-LOAD"
    const val DEALER_LOAD = "fi-rfq-profile-DLR-LOAD"

    const val VENUE_NAME = "FI RFQ Platform"
    const val BUY_SIDE_1_NAME = "Buy Side 1"
    const val BUY_SIDE_2_NAME = "Buy Side 2"
    const val DEALER_1_NAME = "Dealer 1"
    const val DEALER_2_NAME = "Dealer 2"
    const val BUY_SIDE_LOAD_NAME = "Buy Side Load Client"
    const val DEALER_LOAD_NAME = "Dealer Load Client"

    const val LANES = 5

    /**
     * How long an RFQ stays open on the platform when its request names no ExpireTime. Long enough to play both sides by
     * hand, and short enough that an RFQ left alone is seen to end. A dealer's quote stands for thirty seconds of it.
     */
    const val RFQ_EXPIRY_SECONDS = 60

    /** RFQs a load set asks for. Five dealers answer each one, so a phase of these is five times as many quotes. */
    const val LOAD_COUNT = 1_000

    /** SecurityType as well as the CUSIP: it is what tells the RFQ book to show a level in 32nds. */
    private const val TEN_YEAR = "55=T 4.25 08/15/36|48=91282CMF5|22=1|167=TNOTE"
    private const val TEN_MM = "10000000"
    private const val CREATED_AT = 1_789_000_000_000L

    // ------------------------------------------------------------------ profiles

    private fun initiator(compId: String) =
        FixConnectionConfig(
            senderCompID = compId,
            targetCompID = VENUE_COMP_ID,
            port = PORT,
            reconnectInterval = "5",
            resetOnLogon = true,
        )

    private fun profile(id: String, name: String, config: FixConnectionConfig) =
        FixConnectionProfile(id = id, name = name, config = config, createdAt = 0, lastUsedAt = 0)

    val profiles: List<FixConnectionProfile> =
        listOf(
            profile(
                VENUE,
                VENUE_NAME,
                FixConnectionConfig(
                    senderCompID = VENUE_COMP_ID,
                    targetCompID = "*",
                    port = PORT,
                    socketAcceptPort = PORT,
                    connectionType = ConnectionType.ACCEPTOR,
                    acceptorResponseRules = AcceptorPresets.insert(emptyList(), FiRfqPlatformPreset.preset).rules,
                    counterparties = FiRfqPlatformPreset.preset.counterparties,
                    rfqExpirySeconds = RFQ_EXPIRY_SECONDS,
                ),
            ),
            profile(BUY_SIDE_1, BUY_SIDE_1_NAME, initiator(FiRfqPlatformPreset.BUY_SIDE_1)),
            profile(BUY_SIDE_2, BUY_SIDE_2_NAME, initiator(FiRfqPlatformPreset.BUY_SIDE_2)),
            profile(DEALER_1, DEALER_1_NAME, initiator(FiRfqPlatformPreset.DEALER_1)),
            profile(DEALER_2, DEALER_2_NAME, initiator(FiRfqPlatformPreset.DEALER_2)),
            profile(
                BUY_SIDE_LOAD,
                BUY_SIDE_LOAD_NAME,
                initiator("${FiRfqPlatformPreset.BUY_SIDE_LOAD}{n}").forLoad(),
            ),
            profile(
                DEALER_LOAD,
                DEALER_LOAD_NAME,
                initiator("${FiRfqPlatformPreset.DEALER_LOAD}{n}")
                    .forLoad()
                    .copy(acceptorResponseRules = AcceptorPresets.insert(emptyList(), FiRfqPlatformPreset.dealerPreset).rules),
            ),
        )

    private fun FixConnectionConfig.forLoad() =
        copy(sessionCount = LANES, messageStore = MessageStoreKind.MEMORY, messageLog = MessageLogKind.NONE)

    // ------------------------------------------------------------------ templates

    private fun template(id: String, name: String, owners: Set<String>, raw: String) =
        SavedFixMessage(
            id = id,
            name = name,
            userTags = owners,
            fields = raw.split("|").map { SavedFixField(it.substringBefore("="), it.substringAfter("=")) },
            createdAt = 0,
            lastUsedAt = 0,
            modifiedAt = 0,
            isFavorite = true,
        )

    private val buySides = setOf(BUY_SIDE_1, BUY_SIDE_2)
    private val dealers = setOf(DEALER_1, DEALER_2)

    /** A buy side's request for the ten-year: two-way, or one way when it names a side. */
    private fun request(side: String?, size: String = TEN_MM) =
        listOfNotNull("35=R", "131=\${uuid}", "146=1", TEN_YEAR, side?.let { "54=$it" }, "38=$size").joinToString("|")

    /** A dealer's quote for the ten-year at the table's level, answering the request in front of it. */
    private fun dealerQuote(side: String?): String {
        val bid = side != "1"
        val offer = side != "2"
        return listOfNotNull(
            "35=S",
            "131=\${in.R.131}",
            "117=\${uuid}",
            TEN_YEAR,
            side?.let { "54=$it" },
            "537=1|423=1|15=USD",
            "132=98.500000".takeIf { bid },
            "133=98.515625".takeIf { offer },
            "134=\${in.R.38}".takeIf { bid },
            "135=\${in.R.38}".takeIf { offer },
            "632=4.438".takeIf { bid },
            "634=4.436".takeIf { offer },
            "63=0|64=\${utcnow+1d:yyyyMMdd}",
            "62=\${utcnow+${FiRfqPlatformPreset.VALIDITY_SECONDS}s}",
            "58=" + listOfNotNull("98-16".takeIf { bid }, "98-16+".takeIf { offer }).joinToString(" / "),
            "60=\${utcnow}",
        ).joinToString("|")
    }

    const val LOAD_REQUEST = "FI RFQ Load QuoteRequest"
    const val LOAD_LIFT = "FI RFQ Load Lift"

    val templates: List<SavedFixMessage> =
        listOf(
            template("fi-rfq-request-10y", "FI RFQ Request 10y, two-way", buySides, request(null)),
            template("fi-rfq-request-10y-buy", "FI RFQ Request 10y, buying", buySides, request("1")),
            template("fi-rfq-request-10y-sell", "FI RFQ Request 10y, selling", buySides, request("2")),
            template(
                "fi-rfq-request-10y-short",
                "FI RFQ Request 10y, buying, open for 20s",
                buySides,
                request("1") + "|126=\${utcnow+20s}",
            ),
            template(
                "fi-rfq-request-5y",
                "FI RFQ Request 5y, two-way",
                buySides,
                "35=R|131=\${uuid}|146=1|55=T 4 09/30/31|48=91282CME8|22=1|167=TNOTE|38=25000000",
            ),
            template(
                "fi-rfq-lift",
                "FI RFQ Lift the offer",
                buySides,
                "35=AJ|693=\${uuid}|694=1|117=\${in.S.117}|11=\${uuid}|55=\${in.S.55}|54=1|38=\${in.S.135}|44=\${in.S.133}|60=\${utcnow}",
            ),
            template(
                "fi-rfq-hit",
                "FI RFQ Hit the bid",
                buySides,
                "35=AJ|693=\${uuid}|694=1|117=\${in.S.117}|11=\${uuid}|55=\${in.S.55}|54=2|38=\${in.S.134}|44=\${in.S.132}|60=\${utcnow}",
            ),
            template(
                "fi-rfq-pass",
                "FI RFQ Pass",
                buySides,
                "35=AJ|693=\${uuid}|694=6|117=\${in.S.117}|55=\${in.S.55}|60=\${utcnow}",
            ),
            template(
                "fi-rfq-counter",
                "FI RFQ Counter the level",
                buySides,
                "35=AJ|693=\${uuid}|694=2|117=\${in.S.117}|55=\${in.S.55}|54=1|38=\${in.S.135}|44=98.500000|60=\${utcnow}",
            ),
            template(
                "fi-rfq-not-listed",
                "FI RFQ Request an issue the platform does not list",
                buySides,
                "35=R|131=\${uuid}|146=1|55=$NOT_LISTED|48=$NOT_LISTED_CUSIP|22=1|167=TBOND|38=$TEN_MM",
            ),
            template("fi-rfq-no-size", "FI RFQ Request with no size", buySides, "35=R|131=\${uuid}|146=1|$TEN_YEAR"),
            template("fi-rfq-quote-10y", "FI RFQ Quote 10y, two-way", dealers, dealerQuote(null)),
            template("fi-rfq-quote-10y-offer", "FI RFQ Quote 10y, offer only", dealers, dealerQuote("1")),
            template("fi-rfq-quote-10y-bid", "FI RFQ Quote 10y, bid only", dealers, dealerQuote("2")),
            template(
                "fi-rfq-dealer-pass",
                "FI RFQ Pass on the request",
                dealers,
                "35=AG|131=\${in.R.131}|658=10|146=1|55=\${in.R.55}|58=Pass",
            ),
            template(
                "fi-rfq-load-request",
                LOAD_REQUEST,
                setOf(BUY_SIDE_LOAD),
                "35=R|131=FIQ-\${run}-\${messageIndex}|146=1|$TEN_YEAR|54=1|38=$TEN_MM",
            ),
            template(
                "fi-rfq-load-lift",
                LOAD_LIFT,
                setOf(BUY_SIDE_LOAD),
                "35=AJ|693=FIL-\${run}-\${messageIndex}|694=1|117=\${quoteId}|11=FIT-\${run}-\${messageIndex}|" +
                    "55=T 4.25 08/15/36|54=1|38=$TEN_MM|44=\${offer}",
            ),
        )

    /** A bond the platform does not list, with a CUSIP that is well formed and nobody's. */
    const val NOT_LISTED = "T 4.75 02/15/45"
    const val NOT_LISTED_CUSIP = "912810ZZ4"

    // ------------------------------------------------------------------ scenarios

    private fun exact(tag: Int, value: String, bindAs: String? = null) = FieldExpectation(tag, Matcher.Exact(value), bindAs)

    private fun present(tag: Int, bindAs: String) = FieldExpectation(tag, Matcher.Presence, bindAs)

    private fun same(tag: Int, variable: String) = FieldExpectation(tag, Matcher.Reference("\${$variable}"))

    /**
     * An expectation on [session], its rows **in wire order**, because an open expectation is a subsequence.
     *
     * Measured off the messages the platform builds, not read from the dictionary: the body's own fields go out in
     * tag order, then each repeating group, its delimiter first, the rest in tag order and a nested group last. So a
     * quote's parties come after `634`, a request's `38` comes before its `48`, and a TradeCaptureReport's side, with
     * its `37` and parties, comes after `571`.
     */
    private fun expect(session: String, type: String, vararg rows: FieldExpectation, match: List<TagValue> = emptyList()) =
        ScenarioStep.Expect(
            session = session,
            match = MatchPredicate(messageType = type, fields = match),
            expectation = Expectation(fields = rows.toList(), messageType = type),
        )

    private fun send(session: String, vararg fields: String) = ScenarioStep.Send(fields.joinToString("|"), session)

    private fun clear(vararg sessions: String) = sessions.map { ScenarioStep.ClearMessages(it) }

    private fun scenario(id: String, name: String, setup: List<ScenarioStep>, steps: List<ScenarioStep>, strict: Boolean = false) =
        Scenario(
            id = id,
            name = name,
            setup = setup,
            steps = steps,
            traffic = if (strict) TrafficMode.STRICT else TrafficMode.OPEN,
            binding = BindScope.THIS_RUN,
            createdAt = CREATED_AT,
        )

    private val buySideRequest =
        send(BUY_SIDE_1_NAME, "35=R", "131=\${rfqId = uuid}", "146=1", TEN_YEAR, "54=1", "38=$TEN_MM")

    /** Dealer [dealer]'s one-way offer for the ten-year at [price], answering the request it bound as [rfq]. */
    private fun offer(dealer: String, rfq: String, quote: String, price: String, yieldPct: String, text: String) =
        send(
            dealer,
            "35=S",
            "131=\${$rfq}",
            "117=\${$quote = uuid}",
            TEN_YEAR,
            "54=1",
            "537=1|423=1|15=USD",
            "133=$price",
            "135=$TEN_MM",
            "634=$yieldPct",
            "63=0|64=\${utcnow+1d:yyyyMMdd}",
            "62=\${utcnow+${FiRfqPlatformPreset.VALIDITY_SECONDS}s}",
            "58=$text",
            "60=\${utcnow}",
        )

    /** The relayed request as a dealer sees it: the platform's own QuoteReqID, and who is asking. */
    private fun relayedRequest(dealer: String, bindAs: String) =
        expect(
            dealer,
            "R",
            present(131, bindAs),
            exact(38, TEN_MM),
            exact(48, "91282CMF5"),
            exact(54, "1"),
            exact(448, FiRfqPlatformPreset.BUY_SIDE_1),
            exact(452, "13"),
        )

    private fun lift(quote: String, price: String) =
        send(
            BUY_SIDE_1_NAME,
            "35=AJ",
            "693=\${uuid}",
            "694=1",
            "117=\${$quote}",
            "11=\${clOrdId = uuid}",
            "55=T 4.25 08/15/36",
            "54=1",
            "38=$TEN_MM",
            "44=$price",
            "60=\${utcnow}",
        )

    private val liftDealer1 =
        scenario(
            id = "fi-rfq-scenario-lift-dealer-offer",
            name = "Buy side lifts Dealer 1's offer",
            setup = clear(BUY_SIDE_1_NAME, DEALER_1_NAME),
            steps =
                listOf(
                    buySideRequest,
                    relayedRequest(DEALER_1_NAME, "dealerRfqId"),
                    offer(DEALER_1_NAME, "dealerRfqId", "dealerQuoteId", "98.515625", "4.436", "98-16+"),
                    expect(
                        BUY_SIDE_1_NAME,
                        "S",
                        exact(54, "1"),
                        present(117, "quoteId"),
                        same(131, "rfqId"),
                        exact(133, "98.515625"),
                        exact(537, "1"),
                        exact(448, FiRfqPlatformPreset.DEALER_1),
                        exact(452, "35"),
                        match = listOf(TagValue(448, FiRfqPlatformPreset.DEALER_1)),
                    ),
                    lift("quoteId", "98.515625"),
                    expect(
                        BUY_SIDE_1_NAME,
                        "8",
                        same(11, "clOrdId"),
                        exact(31, "98.515625"),
                        exact(32, TEN_MM),
                        present(37, "orderId"),
                        exact(39, "2"),
                        exact(54, "1"),
                        exact(150, "F"),
                        exact(448, FiRfqPlatformPreset.DEALER_1),
                        exact(452, "17"),
                    ),
                    expect(
                        BUY_SIDE_1_NAME,
                        "AE",
                        exact(31, "98.515625"),
                        exact(32, TEN_MM),
                        exact(570, "N"),
                        present(571, "tradeReportId"),
                        exact(54, "1"),
                        same(37, "orderId"),
                        exact(448, FiRfqPlatformPreset.DEALER_1),
                    ),
                    expect(DEALER_1_NAME, "AJ", exact(44, "98.515625"), same(117, "dealerQuoteId"), exact(694, "1")),
                    expect(
                        DEALER_1_NAME,
                        "8",
                        exact(31, "98.515625"),
                        same(37, "orderId"),
                        exact(39, "2"),
                        exact(54, "2"),
                        exact(150, "F"),
                        exact(448, FiRfqPlatformPreset.BUY_SIDE_1),
                        exact(452, "17"),
                    ),
                    expect(
                        DEALER_1_NAME,
                        "AE",
                        exact(31, "98.515625"),
                        same(571, "tradeReportId"),
                        exact(54, "2"),
                        same(37, "orderId"),
                        exact(448, FiRfqPlatformPreset.BUY_SIDE_1),
                    ),
                ),
        )

    private val betterOfferLifted =
        scenario(
            id = "fi-rfq-scenario-better-offer-lifted",
            name = "Two dealers quote, the better offer is lifted",
            setup = clear(BUY_SIDE_1_NAME, DEALER_1_NAME, DEALER_2_NAME),
            steps =
                listOf(
                    buySideRequest,
                    relayedRequest(DEALER_1_NAME, "dealer1RfqId"),
                    relayedRequest(DEALER_2_NAME, "dealer2RfqId"),
                    offer(DEALER_1_NAME, "dealer1RfqId", "dealer1QuoteId", "98.484375", "4.440", "98-15+"),
                    offer(DEALER_2_NAME, "dealer2RfqId", "dealer2QuoteId", "98.500000", "4.438", "98-16"),
                    expect(
                        BUY_SIDE_1_NAME,
                        "S",
                        same(131, "rfqId"),
                        exact(133, "98.500000"),
                        exact(448, FiRfqPlatformPreset.DEALER_2),
                        match = listOf(TagValue(448, FiRfqPlatformPreset.DEALER_2)),
                    ),
                    // Picked by its price, not by arriving last: the better offer came first.
                    expect(
                        BUY_SIDE_1_NAME,
                        "S",
                        present(117, "bestQuoteId"),
                        same(131, "rfqId"),
                        exact(133, "98.484375"),
                        exact(448, FiRfqPlatformPreset.DEALER_1),
                        match = listOf(TagValue(133, "98.484375")),
                    ),
                    lift("bestQuoteId", "98.484375"),
                    expect(
                        BUY_SIDE_1_NAME,
                        "8",
                        exact(31, "98.484375"),
                        present(37, "orderId"),
                        exact(39, "2"),
                        exact(448, FiRfqPlatformPreset.DEALER_1),
                    ),
                    expect(DEALER_1_NAME, "AJ", same(117, "dealer1QuoteId"), exact(694, "1")),
                    expect(
                        DEALER_1_NAME,
                        "8",
                        exact(31, "98.484375"),
                        same(37, "orderId"),
                        exact(54, "2"),
                        exact(448, FiRfqPlatformPreset.BUY_SIDE_1),
                    ),
                    expect(DEALER_2_NAME, "AJ", same(117, "dealer2QuoteId"), exact(694, "4")),
                ),
        )

    /** Strict: the one message the dealer gets is its refusal, and neither buy side hears of the request. */
    private val dealerCannotAsk =
        scenario(
            id = "fi-rfq-scenario-dealer-cannot-ask",
            name = "A dealer cannot ask for a quote",
            setup = clear(DEALER_1_NAME, BUY_SIDE_1_NAME, BUY_SIDE_2_NAME),
            steps =
                listOf(
                    send(DEALER_1_NAME, "35=R", "131=\${dealerAsks = uuid}", "146=1", TEN_YEAR, "54=1", "38=$TEN_MM"),
                    expect(
                        DEALER_1_NAME,
                        "AG",
                        FieldExpectation(58, Matcher.Regex("Not authorized.*")),
                        same(131, "dealerAsks"),
                        exact(658, "6"),
                    ),
                ),
            strict = true,
        )

    /** Strict: the buy side's refusal is the only message, and neither dealer is asked. */
    private val notListed =
        scenario(
            id = "fi-rfq-scenario-not-listed",
            name = "An issue the platform does not list is refused",
            setup = clear(BUY_SIDE_1_NAME, DEALER_1_NAME, DEALER_2_NAME),
            steps =
                listOf(
                    send(
                        BUY_SIDE_1_NAME,
                        "35=R",
                        "131=\${offTheRun = uuid}",
                        "146=1",
                        "55=$NOT_LISTED",
                        "48=$NOT_LISTED_CUSIP",
                        "22=1",
                        "167=TBOND",
                        "54=1",
                        "38=$TEN_MM",
                    ),
                    expect(
                        BUY_SIDE_1_NAME,
                        "AG",
                        FieldExpectation(58, Matcher.Regex("Not listed here.*")),
                        same(131, "offTheRun"),
                        exact(658, "1"),
                    ),
                ),
            strict = true,
        )

    /**
     * **Nobody trades, and both sides are told the RFQ expired.** The request names its own ExpireTime a few seconds
     * ahead, so the run waits on that rather than the platform's minute: the buy side is told of Dealer 1's quote under
     * the id it was shown, and Dealer 1 of its own quote under its own.
     */
    private val expired =
        scenario(
            id = "fi-rfq-scenario-expired",
            name = "Nobody trades, and both sides are told the RFQ expired",
            setup = clear(BUY_SIDE_1_NAME, DEALER_1_NAME),
            steps =
                listOf(
                    send(BUY_SIDE_1_NAME, "35=R", "131=\${rfqId = uuid}", "146=1", TEN_YEAR, "54=1", "38=$TEN_MM", "126=\${utcnow+4s}"),
                    relayedRequest(DEALER_1_NAME, "dealerRfqId"),
                    offer(DEALER_1_NAME, "dealerRfqId", "dealerQuoteId", "98.515625", "4.436", "98-16+"),
                    expect(
                        BUY_SIDE_1_NAME,
                        "S",
                        present(117, "quoteId"),
                        exact(448, FiRfqPlatformPreset.DEALER_1),
                        match = listOf(TagValue(448, FiRfqPlatformPreset.DEALER_1)),
                    ),
                    expect(
                        BUY_SIDE_1_NAME,
                        "AI",
                        same(117, "quoteId"),
                        same(131, "rfqId"),
                        exact(297, "7"),
                        match = listOf(TagValue(117, "\${quoteId}")),
                    ),
                    expect(DEALER_1_NAME, "AI", same(117, "dealerQuoteId"), same(131, "dealerRfqId"), exact(297, "7")),
                ),
        )

    val scenarios: List<Scenario> = listOf(liftDealer1, betterOfferLifted, dealerCannotAsk, notListed, expired)

    // ------------------------------------------------------------------ load sets

    private fun requestPhase() =
        LoadPhaseSpec(
            label = "Request",
            template = LOAD_REQUEST,
            profile = BUY_SIDE_LOAD_NAME,
            listen = listOf(DEALER_LOAD_NAME),
            match = LoadMatch(requestTag = 131, replyTag = 131, replyType = "S"),
            shape = LoadShape.Burst(LOAD_COUNT),
            settleMs = SETTLE_MS,
            capture = mapOf("quoteId" to 117, "offer" to 133),
        )

    private fun liftPhase(shape: LoadShape) =
        LoadPhaseSpec(
            label = "Lift",
            template = LOAD_LIFT,
            profile = BUY_SIDE_LOAD_NAME,
            listen = listOf(DEALER_LOAD_NAME),
            match = LoadMatch(requestTag = 11, replyTag = 11, replyType = "8"),
            shape = shape,
            settleMs = SETTLE_MS,
            after = 1.takeIf { shape is LoadShape.Triggered },
        )

    private fun loadSet(name: String, label: String, lift: LoadShape) =
        LoadSet(
            name = name,
            label = label,
            seed = mapOf("run" to "\${uuid:4}"),
            storeAndLog = StoreAndLogOverride.FOR_LOAD,
            phases = listOf(requestPhase(), liftPhase(lift)),
        )

    private const val SETTLE_MS = 30_000L

    val loadSets: List<LoadSet> =
        listOf(
            loadSet(
                "fi-rfq-round-trip",
                "FI RFQ round trip — every dealer online quotes each request, then the first offer is lifted",
                LoadShape.Burst(LOAD_COUNT),
            ),
            loadSet("fi-rfq-reactive", "FI RFQ reactive — lift each request's first offer the moment it lands", LoadShape.Triggered()),
        )

    /**
     * The standard FIX 4.4 the scenarios' rows were written in. A machine whose Settings name a venue's own FIX 4.4 lays
     * a QuoteRequest out differently, and the expectations would fail on rows that had only moved.
     */
    val dictionary = WorkspaceDictionary(fixVersion = FixVersion.FIX_4_4)

    // ------------------------------------------------------------------ the manifest

    const val DISPLAY_NAME = "Fixed Income RFQ Platform"

    const val SUMMARY =
        "A US Treasury RFQ platform that carries a negotiation between buy sides and dealers: a buy side asks, the " +
            "platform asks every dealer online under its own id, each dealer's quote reaches the buy side under " +
            "another, and a lift is confirmed to both sides with an ExecutionReport and a TradeCaptureReport each " +
            "while the dealers who lost are told cover or done away. Issues by CUSIP, levels in 32nds and yield, sized " +
            "in nominal, settling T+1. Two buy sides and two dealers to play by hand, a load client of each whose " +
            "dealers quote by themselves, templates for both sides, five scenarios that assert both sides, and two " +
            "load sets. An RFQ nobody trades is ended to both sides after a minute."
}
