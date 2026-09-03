package com.knapsack.fixtool.service.demo

import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage

/**
 * Provides sample FX message templates for the demo server.
 * Templates use expression syntax for dynamic values.
 */
object DemoTemplatesProvider {
    /** Prefix for demo template IDs to enable cleanup */
    const val DEMO_TEMPLATE_PREFIX = "demo-"

    /**
     * Creates the list of demo FX templates.
     * @param profileIds The demo profile IDs to associate templates with
     * @return List of SavedFixMessage templates
     */
    fun createDemoTemplates(profileIds: Set<String>): List<SavedFixMessage> =
        listOf(
            createMarketBuyTemplate(profileIds),
            createMarketSellTemplate(profileIds),
            createLimitBuyTemplate(profileIds),
            createLimitSellTemplate(profileIds),
            createOrderCancelTemplate(profileIds),
            createOrderReplaceTemplate(profileIds),
            createOrderStatusTemplate(profileIds),
            createSessionProbeTemplate(profileIds),
        ) + PRICED_PAIRS.map { pair -> createQuoteRequestTemplate(profileIds, pair) }

    /**
     * The pairs the demo venue prices — one quote-request template each.
     *
     * Deliberately a plain list rather than a read of `FxVenuePreset`'s own constants: these are the
     * *client's* messages, and a client knows a venue's symbols because someone told it, not because it
     * can see inside. A template that drifted from the venue would produce the unknown-symbol reject,
     * which is a demo of something real either way.
     */
    private val PRICED_PAIRS = listOf("EUR/USD", "GBP/USD", "USD/JPY")

    /**
     * FX Market Buy EUR/USD (35=D, Side=1, OrdType=1)
     */
    private fun createMarketBuyTemplate(profileIds: Set<String>): SavedFixMessage =
        SavedFixMessage(
            id = "${DEMO_TEMPLATE_PREFIX}fx-market-buy-eurusd",
            name = "FX Market Buy EUR/USD",
            userTags = profileIds,
            isFavorite = true,
            fields =
                listOf(
                    SavedFixField(tag = "35", value = "D"), // MsgType = NewOrderSingle
                    SavedFixField(tag = "11", value = "\${uuid}"), // ClOrdID = unique
                    SavedFixField(tag = "55", value = "EUR/USD"), // Symbol
                    SavedFixField(tag = "54", value = "1"), // Side = Buy
                    SavedFixField(tag = "40", value = "1"), // OrdType = Market
                    SavedFixField(tag = "38", value = "100000"), // OrderQty = 100K
                    SavedFixField(tag = "60", value = "\${now}"), // TransactTime
                    SavedFixField(tag = "15", value = "USD"), // Currency
                    SavedFixField(tag = "59", value = "0"), // TimeInForce = Day
                ),
        )

    /**
     * Session probe (35=1): FIX's own ping. The venue's session engine must answer with a Heartbeat
     * echoing the TestReqID, before any rule or book is involved and with nothing placed — so with
     * latency tracking on, each send is one session-layer round trip in the Latency panel, under
     * TestReqID. Fresh id per send so a panel row is never ambiguous about which probe it answers.
     */
    private fun createSessionProbeTemplate(profileIds: Set<String>): SavedFixMessage =
        SavedFixMessage(
            id = "${DEMO_TEMPLATE_PREFIX}session-probe",
            name = "Session Probe (TestRequest)",
            userTags = profileIds,
            isFavorite = false,
            fields =
                listOf(
                    SavedFixField(tag = "35", value = "1"), // MsgType = TestRequest
                    SavedFixField(tag = "112", value = "PROBE-\${uuid}"), // TestReqID = unique, echoed by the Heartbeat
                ),
        )

    /**
     * FX Market Sell EUR/USD (35=D, Side=2, OrdType=1)
     */
    private fun createMarketSellTemplate(profileIds: Set<String>): SavedFixMessage =
        SavedFixMessage(
            id = "${DEMO_TEMPLATE_PREFIX}fx-market-sell-eurusd",
            name = "FX Market Sell EUR/USD",
            userTags = profileIds,
            isFavorite = true,
            fields =
                listOf(
                    SavedFixField(tag = "35", value = "D"), // MsgType = NewOrderSingle
                    SavedFixField(tag = "11", value = "\${uuid}"), // ClOrdID = unique
                    SavedFixField(tag = "55", value = "EUR/USD"), // Symbol
                    SavedFixField(tag = "54", value = "2"), // Side = Sell
                    SavedFixField(tag = "40", value = "1"), // OrdType = Market
                    SavedFixField(tag = "38", value = "100000"), // OrderQty = 100K
                    SavedFixField(tag = "60", value = "\${now}"), // TransactTime
                    SavedFixField(tag = "15", value = "USD"), // Currency
                    SavedFixField(tag = "59", value = "0"), // TimeInForce = Day
                ),
        )

    /**
     * FX Limit Buy EUR/USD (35=D, Side=1, OrdType=2, Price=1.0850)
     */
    private fun createLimitBuyTemplate(profileIds: Set<String>): SavedFixMessage =
        SavedFixMessage(
            id = "${DEMO_TEMPLATE_PREFIX}fx-limit-buy-eurusd",
            name = "FX Limit Buy EUR/USD",
            userTags = profileIds,
            isFavorite = true,
            fields =
                listOf(
                    SavedFixField(tag = "35", value = "D"), // MsgType = NewOrderSingle
                    SavedFixField(tag = "11", value = "\${uuid}"), // ClOrdID = unique
                    SavedFixField(tag = "55", value = "EUR/USD"), // Symbol
                    SavedFixField(tag = "54", value = "1"), // Side = Buy
                    SavedFixField(tag = "40", value = "2"), // OrdType = Limit
                    SavedFixField(tag = "44", value = "1.0850"), // Price = Below market
                    SavedFixField(tag = "38", value = "100000"), // OrderQty = 100K
                    SavedFixField(tag = "60", value = "\${now}"), // TransactTime
                    SavedFixField(tag = "15", value = "USD"), // Currency
                    SavedFixField(tag = "59", value = "1"), // TimeInForce = GTC
                    SavedFixField(tag = "64", value = "\${now+2d:yyyyMMdd}"), // SettlDate = T+2
                ),
        )

    /**
     * FX Limit Sell EUR/USD (35=D, Side=2, OrdType=2, Price=1.0950)
     */
    private fun createLimitSellTemplate(profileIds: Set<String>): SavedFixMessage =
        SavedFixMessage(
            id = "${DEMO_TEMPLATE_PREFIX}fx-limit-sell-eurusd",
            name = "FX Limit Sell EUR/USD",
            userTags = profileIds,
            isFavorite = true,
            fields =
                listOf(
                    SavedFixField(tag = "35", value = "D"), // MsgType = NewOrderSingle
                    SavedFixField(tag = "11", value = "\${uuid}"), // ClOrdID = unique
                    SavedFixField(tag = "55", value = "EUR/USD"), // Symbol
                    SavedFixField(tag = "54", value = "2"), // Side = Sell
                    SavedFixField(tag = "40", value = "2"), // OrdType = Limit
                    SavedFixField(tag = "44", value = "1.0950"), // Price = Above market
                    SavedFixField(tag = "38", value = "100000"), // OrderQty = 100K
                    SavedFixField(tag = "60", value = "\${now}"), // TransactTime
                    SavedFixField(tag = "15", value = "USD"), // Currency
                    SavedFixField(tag = "59", value = "1"), // TimeInForce = GTC
                    SavedFixField(tag = "64", value = "\${now+2d:yyyyMMdd}"), // SettlDate = T+2
                ),
        )

    /**
     * FX Quote Request (35=R), **conformant**: the symbol lives inside `NoRelatedSym(146)`.
     *
     * The old demo template carried a flat `55`, which is not a FIX 4.4 QuoteRequest — NoRelatedSym is
     * `required="Y"` and Symbol is required within it. It worked only because the old server read the
     * flat tag first. The venue now reads the group (see `AcceptorResponder.valueOf`), so the demo can
     * send the message a real client sends, which is the only version worth putting on camera.
     *
     * No `54`/`38`: the venue does not read them, and a template carrying fields nothing looks at
     * teaches a reader that they matter.
     */
    private fun createQuoteRequestTemplate(profileIds: Set<String>, symbol: String): SavedFixMessage =
        SavedFixMessage(
            id = "$DEMO_TEMPLATE_PREFIX${quoteRequestSlug(symbol)}",
            name = "FX Quote Request $symbol",
            userTags = profileIds,
            isFavorite = true,
            fields =
                listOf(
                    SavedFixField(tag = "35", value = "R"), // MsgType = QuoteRequest
                    SavedFixField(tag = "131", value = "\${uuid}"), // QuoteReqID = unique
                    SavedFixField(tag = "146", value = "1"), // NoRelatedSym = one instrument
                    SavedFixField(tag = "55", value = symbol), // Symbol, inside the group
                ),
        )

    /** `fx-quote-request-eurusd` — the id and the cleanup list are minted from one place. */
    private fun quoteRequestSlug(symbol: String) = "fx-quote-request-" + symbol.replace("/", "").lowercase()

    /**
     * FX Order Cancel Request (35=F)
     * Uses template expressions to reference the last sent order
     */
    private fun createOrderCancelTemplate(profileIds: Set<String>): SavedFixMessage =
        SavedFixMessage(
            id = "${DEMO_TEMPLATE_PREFIX}fx-order-cancel",
            name = "FX Order Cancel Request",
            userTags = profileIds,
            isFavorite = true,
            fields =
                listOf(
                    SavedFixField(tag = "35", value = "F"), // MsgType = OrderCancelRequest
                    SavedFixField(tag = "11", value = "\${uuid}"), // ClOrdID = new unique for cancel
                    SavedFixField(tag = "41", value = "\${outgoing[\"D\"].valueOfTag(11)}"), // OrigClOrdID = last order's ClOrdID
                    SavedFixField(tag = "55", value = "\${outgoing[\"D\"].valueOfTag(55)}"), // Symbol from last order
                    SavedFixField(tag = "54", value = "\${outgoing[\"D\"].valueOfTag(54)}"), // Side from last order
                    SavedFixField(tag = "60", value = "\${now}"), // TransactTime
                ),
        )

    /**
     * FX Order Cancel/Replace Request (35=G) — the message the old demo server always rejected.
     *
     * Carries `38`, because the venue's replace rules read it and are conditioned on its presence: a
     * replace with no OrderQty matches nothing and the venue would answer with silence.
     */
    private fun createOrderReplaceTemplate(profileIds: Set<String>): SavedFixMessage =
        SavedFixMessage(
            id = "${DEMO_TEMPLATE_PREFIX}fx-order-replace",
            name = "FX Order Cancel/Replace Request",
            userTags = profileIds,
            isFavorite = true,
            fields =
                listOf(
                    SavedFixField(tag = "35", value = "G"), // MsgType = OrderCancelReplaceRequest
                    SavedFixField(tag = "11", value = "\${uuid}"), // ClOrdID = new unique
                    SavedFixField(tag = "41", value = "\${outgoing[\"D\"].valueOfTag(11)}"), // OrigClOrdID
                    SavedFixField(tag = "55", value = "\${outgoing[\"D\"].valueOfTag(55)}"), // Symbol
                    SavedFixField(tag = "54", value = "\${outgoing[\"D\"].valueOfTag(54)}"), // Side
                    SavedFixField(tag = "40", value = "2"), // OrdType = Limit
                    SavedFixField(tag = "44", value = "1.08900"), // Price = revised
                    SavedFixField(tag = "38", value = "500000"), // OrderQty = revised down
                    SavedFixField(tag = "60", value = "\${now}"), // TransactTime
                ),
        )

    /**
     * FX Order Status Request (35=H) — answered from the venue's book rather than "not supported".
     */
    private fun createOrderStatusTemplate(profileIds: Set<String>): SavedFixMessage =
        SavedFixMessage(
            id = "${DEMO_TEMPLATE_PREFIX}fx-order-status",
            name = "FX Order Status Request",
            userTags = profileIds,
            isFavorite = true,
            fields =
                listOf(
                    SavedFixField(tag = "35", value = "H"), // MsgType = OrderStatusRequest
                    SavedFixField(tag = "11", value = "\${outgoing[\"D\"].valueOfTag(11)}"), // ClOrdID asked about
                    SavedFixField(tag = "55", value = "\${outgoing[\"D\"].valueOfTag(55)}"), // Symbol
                    SavedFixField(tag = "54", value = "\${outgoing[\"D\"].valueOfTag(54)}"), // Side
                ),
        )

    /**
     * Returns the IDs of all demo templates for cleanup
     */
    fun getDemoTemplateIds(): List<String> = createDemoTemplates(emptySet()).map { it.id }

    /**
     * Checks if a message ID is a demo template
     */
    fun isDemoTemplate(messageId: String): Boolean = messageId.startsWith(DEMO_TEMPLATE_PREFIX)
}
