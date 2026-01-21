package com.knapsack.fixtool.service.demo

import org.slf4j.LoggerFactory
import quickfix.Message
import quickfix.SessionID
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Result of order processing
 */
sealed class OrderResult {
    data class Accepted(
        val order: FxOrder,
    ) : OrderResult()

    data class Filled(
        val order: FxOrder,
        val execType: ExecType,
    ) : OrderResult()

    data class Rejected(
        val clOrdId: String,
        val reason: String,
        val reasonCode: Int,
    ) : OrderResult()

    data class Canceled(
        val order: FxOrder,
    ) : OrderResult()

    data class CancelRejected(
        val clOrdId: String,
        val origClOrdId: String,
        val reason: String,
    ) : OrderResult()
}

/**
 * Processes FX orders: validation, execution, and matching.
 * Coordinates between market data and order book.
 */
class FxOrderProcessor(
    private val marketData: FxMarketData,
    private val orderBook: FxOrderBook,
) {
    private val logger = LoggerFactory.getLogger(FxOrderProcessor::class.java)

    // Callback for sending execution reports
    var onExecutionReport: ((FxOrder, ExecType, SessionID) -> Unit)? = null

    // Counter for generating order IDs
    private var orderIdCounter = System.currentTimeMillis()

    init {
        // Register for price updates to fill resting orders
        marketData.addPriceListener { quote ->
            processMarketableOrders(quote)
        }
    }

    /**
     * Processes a new order request from a FIX message
     * @param request The NewOrderSingle (35=D) message
     * @param sessionId The session this order came from
     * @return OrderResult indicating the outcome
     */
    fun processNewOrder(request: Message, sessionId: SessionID): OrderResult {
        // Extract order fields
        val clOrdId = request.getString(11) // ClOrdID
        val symbol = extractSymbol(request)
        val sideStr = request.getString(54) // Side
        val ordTypeStr = request.getString(40) // OrdType
        val orderQty = request.getDouble(38) // OrderQty
        val price = if (request.isSetField(44)) request.getDouble(44) else null // Price
        val account = if (request.isSetField(1)) request.getString(1) else null
        val currency = if (request.isSetField(15)) request.getString(15) else "USD"
        val tifStr = if (request.isSetField(59)) request.getString(59) else "0"

        // Parse enums
        val side = Side.fromFixValue(sideStr)
        val ordType = OrdType.fromFixValue(ordTypeStr)
        val timeInForce = TimeInForce.fromFixValue(tifStr) ?: TimeInForce.DAY

        // Validate order
        val validationError = validateOrder(symbol, side, ordType, orderQty, price)
        if (validationError != null) {
            logger.info("Order {} rejected: {}", clOrdId, validationError.second)
            return OrderResult.Rejected(clOrdId, validationError.second, validationError.first)
        }

        // Create order
        val order =
            FxOrder(
                clOrdId = clOrdId,
                orderId = generateOrderId(),
                symbol = FxCurrencyPair.normalizeSymbol(symbol),
                side = side!!,
                ordType = ordType!!,
                orderQty = orderQty,
                price = price,
                timeInForce = timeInForce,
                currency = currency,
                account = account,
                sessionId = sessionId.toString(),
            )

        // Get current market prices
        val quote = marketData.getQuote(order.symbol)
        if (quote == null) {
            order.reject()
            return OrderResult.Rejected(clOrdId, "No market data available for ${order.symbol}", 1)
        }

        // Process based on order type
        return when (ordType) {
            OrdType.MARKET -> processMarketOrder(order, quote)
            OrdType.LIMIT -> processLimitOrder(order, quote)
            else -> {
                order.reject()
                OrderResult.Rejected(clOrdId, "Unsupported order type: $ordTypeStr", 11)
            }
        }
    }

    /**
     * Processes a cancel request
     * @param request The OrderCancelRequest (35=F) message
     * @param sessionId The session this request came from
     * @return OrderResult indicating the outcome
     */
    fun processCancelRequest(request: Message, sessionId: SessionID): OrderResult {
        val clOrdId = request.getString(11) // ClOrdID (of the cancel request)
        val origClOrdId = request.getString(41) // OrigClOrdID (order to cancel)

        // Find the original order
        val order = orderBook.getOrderByClOrdId(origClOrdId)
        if (order == null) {
            logger.info("Cancel rejected: Order {} not found", origClOrdId)
            return OrderResult.CancelRejected(clOrdId, origClOrdId, "Unknown order")
        }

        // Check if order can be canceled
        if (!order.isActive()) {
            logger.info("Cancel rejected: Order {} is not active (status: {})", origClOrdId, order.ordStatus)
            return OrderResult.CancelRejected(clOrdId, origClOrdId, "Order is not active")
        }

        // Cancel the order
        order.cancel()
        orderBook.removeFromWorkingOrders(order)
        logger.info("Order {} canceled", origClOrdId)

        return OrderResult.Canceled(order)
    }

    /**
     * Processes a quote request
     * @param request The QuoteRequest (35=R) message
     * @param sessionId The session this request came from
     * @return The quote to send back, or null if rejected
     */
    fun processQuoteRequest(request: Message, sessionId: SessionID): FxQuote? {
        val symbol = extractSymbol(request)
        val normalizedSymbol = FxCurrencyPair.normalizeSymbol(symbol)

        // Check if symbol is supported
        if (!FxCurrencyPair.isSupported(normalizedSymbol)) {
            logger.info("Quote request rejected: Unknown symbol {}", symbol)
            return null
        }

        // Return current market quote
        return marketData.getQuote(normalizedSymbol)
    }

    /**
     * Validates an order before processing
     * @return Pair of (reject reason code, reason text) or null if valid
     */
    private fun validateOrder(
        symbol: String,
        side: Side?,
        ordType: OrdType?,
        orderQty: Double,
        price: Double?,
    ): Pair<Int, String>? {
        // Check symbol
        val normalizedSymbol = FxCurrencyPair.normalizeSymbol(symbol)
        if (!FxCurrencyPair.isSupported(normalizedSymbol)) {
            return Pair(1, "Unknown symbol: $symbol") // OrdRejReason=1 (Unknown symbol)
        }

        // Check side
        if (side == null) {
            return Pair(11, "Invalid side") // OrdRejReason=11 (Unsupported)
        }

        // Check order type
        if (ordType == null) {
            return Pair(11, "Invalid order type")
        }

        // Check quantity
        if (orderQty <= 0) {
            return Pair(13, "Invalid quantity: $orderQty") // OrdRejReason=13 (Incorrect quantity)
        }

        // Check price for limit orders
        if (ordType == OrdType.LIMIT && (price == null || price <= 0)) {
            return Pair(5, "Limit order requires valid price") // OrdRejReason=5 (Unknown order)
        }

        return null // Valid
    }

    /**
     * Processes a market order - immediate fill at current price
     */
    private fun processMarketOrder(order: FxOrder, quote: FxQuote): OrderResult {
        order.accept()
        orderBook.addOrder(order)

        // Immediate fill at market price
        val fillPrice = order.getFillPrice(quote.bidPx, quote.askPx)
        val execType = order.applyFill(order.orderQty, fillPrice)

        logger.info("Market order {} filled at {}", order.clOrdId, fillPrice)
        return OrderResult.Filled(order, execType)
    }

    /**
     * Processes a limit order - may fill immediately if marketable, otherwise rests in book
     */
    private fun processLimitOrder(order: FxOrder, quote: FxQuote): OrderResult {
        order.accept()
        orderBook.addOrder(order)

        // Check if immediately marketable
        if (order.isMarketable(quote.bidPx, quote.askPx)) {
            val fillPrice = order.getFillPrice(quote.bidPx, quote.askPx)
            val execType = order.applyFill(order.orderQty, fillPrice)
            orderBook.removeFromWorkingOrders(order)
            logger.info("Limit order {} immediately filled at {}", order.clOrdId, fillPrice)
            return OrderResult.Filled(order, execType)
        }

        // Order rests in book
        logger.info("Limit order {} accepted, resting at price {}", order.clOrdId, order.price)
        return OrderResult.Accepted(order)
    }

    /**
     * Called when prices change to fill any resting orders that are now marketable
     */
    private fun processMarketableOrders(quote: FxQuote) {
        val marketableOrders = orderBook.getMarketableOrders(quote.symbol, quote.bidPx, quote.askPx)

        for (order in marketableOrders) {
            val fillPrice = order.getFillPrice(quote.bidPx, quote.askPx)
            val execType = order.applyFill(order.leavesQty, fillPrice)
            orderBook.removeFromWorkingOrders(order)

            logger.info("Resting order {} filled at {} on price tick", order.clOrdId, fillPrice)

            // Notify via callback
            try {
                val sessionId = SessionID(order.sessionId)
                onExecutionReport?.invoke(order, execType, sessionId)
            } catch (e: Exception) {
                logger.error("Error sending execution report for order {}: {}", order.clOrdId, e.message)
            }
        }
    }

    /**
     * Extracts symbol from a FIX message, handling various formats
     */
    private fun extractSymbol(message: Message): String {
        // Try tag 55 (Symbol) first
        if (message.isSetField(55)) {
            return message.getString(55)
        }

        // Try extracting from repeating group (NoRelatedSym)
        if (message.isSetField(146)) {
            val group = message.getGroup(1, 146)
            if (group.isSetField(55)) {
                return group.getString(55)
            }
        }

        return "UNKNOWN"
    }

    /**
     * Generates a unique order ID
     */
    private fun generateOrderId(): String = "FX${++orderIdCounter}"

    /**
     * Clears all orders and resets state
     */
    fun clear() {
        orderBook.clear()
    }

    companion object {
        private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")

        /**
         * Formats a timestamp for FIX messages
         */
        fun formatTimestamp(dateTime: LocalDateTime = LocalDateTime.now()): String = dateTime.format(timestampFormatter)
    }
}
