package com.knapsack.fixtool.service.demo

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages working limit orders for the FX exchange.
 * Orders are stored by symbol and can be matched when prices tick.
 */
class FxOrderBook {
    private val logger = LoggerFactory.getLogger(FxOrderBook::class.java)

    // Orders indexed by clOrdId for quick lookup
    private val ordersByClOrdId = ConcurrentHashMap<String, FxOrder>()

    // Orders indexed by orderId for quick lookup
    private val ordersByOrderId = ConcurrentHashMap<String, FxOrder>()

    // Active (working) orders grouped by symbol for efficient matching
    private val workingOrdersBySymbol = ConcurrentHashMap<String, MutableList<FxOrder>>()

    /**
     * Adds an order to the book
     */
    fun addOrder(order: FxOrder) {
        ordersByClOrdId[order.clOrdId] = order
        ordersByOrderId[order.orderId] = order

        // Only add to working orders if it's active
        if (order.isActive()) {
            val symbol = FxCurrencyPair.normalizeSymbol(order.symbol)
            val orders = workingOrdersBySymbol.getOrPut(symbol) { mutableListOf() }
            synchronized(orders) {
                orders.add(order)
            }
            logger.debug("Added order {} to book for {}", order.clOrdId, symbol)
        }
    }

    /**
     * Gets an order by client order ID
     */
    fun getOrderByClOrdId(clOrdId: String): FxOrder? = ordersByClOrdId[clOrdId]

    /**
     * Gets an order by exchange order ID
     */
    fun getOrderByOrderId(orderId: String): FxOrder? = ordersByOrderId[orderId]

    /**
     * Removes an order from the working book (but keeps in history)
     */
    fun removeFromWorkingOrders(order: FxOrder) {
        val symbol = FxCurrencyPair.normalizeSymbol(order.symbol)
        workingOrdersBySymbol[symbol]?.let { orders ->
            synchronized(orders) {
                orders.removeIf { it.clOrdId == order.clOrdId }
            }
        }
        logger.debug("Removed order {} from working book", order.clOrdId)
    }

    /**
     * Gets all working orders for a symbol that could be filled at the given price
     * @param symbol The currency pair symbol
     * @param bidPx Current bid price
     * @param askPx Current ask price
     * @return List of orders that are now marketable
     */
    fun getMarketableOrders(symbol: String, bidPx: Double, askPx: Double): List<FxOrder> {
        val normalized = FxCurrencyPair.normalizeSymbol(symbol)
        val orders = workingOrdersBySymbol[normalized] ?: return emptyList()

        synchronized(orders) {
            return orders.filter { order ->
                order.isActive() && order.isMarketable(bidPx, askPx)
            }
        }
    }

    /**
     * Gets all working orders for a symbol
     */
    fun getWorkingOrders(symbol: String): List<FxOrder> {
        val normalized = FxCurrencyPair.normalizeSymbol(symbol)
        val orders = workingOrdersBySymbol[normalized] ?: return emptyList()

        synchronized(orders) {
            return orders.filter { it.isActive() }.toList()
        }
    }

    /**
     * Gets all working orders across all symbols
     */
    fun getAllWorkingOrders(): List<FxOrder> {
        return workingOrdersBySymbol.values.flatMap { orders ->
            synchronized(orders) {
                orders.filter { it.isActive() }
            }
        }
    }

    /**
     * Gets all orders (including filled/canceled) for a session
     */
    fun getOrdersForSession(sessionId: String): List<FxOrder> {
        return ordersByClOrdId.values.filter { it.sessionId == sessionId }
    }

    /**
     * Clears all orders from the book
     */
    fun clear() {
        ordersByClOrdId.clear()
        ordersByOrderId.clear()
        workingOrdersBySymbol.clear()
        logger.info("Order book cleared")
    }

    /**
     * Gets statistics about the order book
     */
    fun getStats(): OrderBookStats {
        val allOrders = ordersByClOrdId.values.toList()
        val workingOrders = getAllWorkingOrders()

        return OrderBookStats(
            totalOrders = allOrders.size,
            workingOrders = workingOrders.size,
            filledOrders = allOrders.count { it.ordStatus == OrdStatus.FILLED },
            canceledOrders = allOrders.count { it.ordStatus == OrdStatus.CANCELED },
            rejectedOrders = allOrders.count { it.ordStatus == OrdStatus.REJECTED },
            ordersBySymbol = workingOrdersBySymbol.mapValues { (_, orders) ->
                synchronized(orders) { orders.count { it.isActive() } }
            }
        )
    }

    /**
     * Statistics about the order book
     */
    data class OrderBookStats(
        val totalOrders: Int,
        val workingOrders: Int,
        val filledOrders: Int,
        val canceledOrders: Int,
        val rejectedOrders: Int,
        val ordersBySymbol: Map<String, Int>
    )
}
