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
            createQuoteRequestTemplate(profileIds),
            createOrderCancelTemplate(profileIds),
        )

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
     * FX Quote Request EUR/USD (35=R)
     */
    private fun createQuoteRequestTemplate(profileIds: Set<String>): SavedFixMessage =
        SavedFixMessage(
            id = "${DEMO_TEMPLATE_PREFIX}fx-quote-request-eurusd",
            name = "FX Quote Request EUR/USD",
            userTags = profileIds,
            isFavorite = true,
            fields =
                listOf(
                    SavedFixField(tag = "35", value = "R"), // MsgType = QuoteRequest
                    SavedFixField(tag = "131", value = "\${uuid}"), // QuoteReqID = unique
                    SavedFixField(tag = "55", value = "EUR/USD"), // Symbol
                    SavedFixField(tag = "54", value = "1"), // Side = Buy
                    SavedFixField(tag = "38", value = "100000"), // OrderQty = 100K
                    SavedFixField(tag = "15", value = "USD"), // Currency
                    SavedFixField(tag = "64", value = "\${now+2d:yyyyMMdd}"), // SettlDate = T+2
                ),
        )

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
     * Returns the IDs of all demo templates for cleanup
     */
    fun getDemoTemplateIds(): List<String> =
        listOf(
            "${DEMO_TEMPLATE_PREFIX}fx-market-buy-eurusd",
            "${DEMO_TEMPLATE_PREFIX}fx-market-sell-eurusd",
            "${DEMO_TEMPLATE_PREFIX}fx-limit-buy-eurusd",
            "${DEMO_TEMPLATE_PREFIX}fx-limit-sell-eurusd",
            "${DEMO_TEMPLATE_PREFIX}fx-quote-request-eurusd",
            "${DEMO_TEMPLATE_PREFIX}fx-order-cancel",
        )

    /**
     * Checks if a message ID is a demo template
     */
    fun isDemoTemplate(messageId: String): Boolean = messageId.startsWith(DEMO_TEMPLATE_PREFIX)
}
