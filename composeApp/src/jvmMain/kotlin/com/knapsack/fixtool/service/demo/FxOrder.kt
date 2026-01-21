package com.knapsack.fixtool.service.demo

import java.time.LocalDateTime

/**
 * FIX Order Status (Tag 39) - OrdStatus
 */
enum class OrdStatus(val fixValue: String, val description: String) {
    NEW("0", "New"),
    PARTIALLY_FILLED("1", "Partially Filled"),
    FILLED("2", "Filled"),
    DONE_FOR_DAY("3", "Done for Day"),
    CANCELED("4", "Canceled"),
    REPLACED("5", "Replaced"),
    PENDING_CANCEL("6", "Pending Cancel"),
    STOPPED("7", "Stopped"),
    REJECTED("8", "Rejected"),
    SUSPENDED("9", "Suspended"),
    PENDING_NEW("A", "Pending New"),
    CALCULATED("B", "Calculated"),
    EXPIRED("C", "Expired"),
    PENDING_REPLACE("E", "Pending Replace");

    companion object {
        fun fromFixValue(value: String): OrdStatus? = entries.find { it.fixValue == value }
    }
}

/**
 * FIX Execution Type (Tag 150) - ExecType
 */
enum class ExecType(val fixValue: String, val description: String) {
    NEW("0", "New"),
    PARTIAL_FILL("1", "Partial Fill"),
    FILL("2", "Fill"),
    DONE_FOR_DAY("3", "Done for Day"),
    CANCELED("4", "Canceled"),
    REPLACED("5", "Replaced"),
    PENDING_CANCEL("6", "Pending Cancel"),
    STOPPED("7", "Stopped"),
    REJECTED("8", "Rejected"),
    SUSPENDED("9", "Suspended"),
    PENDING_NEW("A", "Pending New"),
    CALCULATED("B", "Calculated"),
    EXPIRED("C", "Expired"),
    RESTATED("D", "Restated"),
    PENDING_REPLACE("E", "Pending Replace"),
    TRADE("F", "Trade"),
    TRADE_CORRECT("G", "Trade Correct"),
    TRADE_CANCEL("H", "Trade Cancel"),
    ORDER_STATUS("I", "Order Status");

    companion object {
        fun fromFixValue(value: String): ExecType? = entries.find { it.fixValue == value }
    }
}

/**
 * FIX Side (Tag 54) - Side
 */
enum class Side(val fixValue: String, val description: String) {
    BUY("1", "Buy"),
    SELL("2", "Sell");

    companion object {
        fun fromFixValue(value: String): Side? = entries.find { it.fixValue == value }
    }
}

/**
 * FIX Order Type (Tag 40) - OrdType
 */
enum class OrdType(val fixValue: String, val description: String) {
    MARKET("1", "Market"),
    LIMIT("2", "Limit"),
    STOP("3", "Stop"),
    STOP_LIMIT("4", "Stop Limit"),
    PREVIOUSLY_QUOTED("P", "Previously Quoted");

    companion object {
        fun fromFixValue(value: String): OrdType? = entries.find { it.fixValue == value }
    }
}

/**
 * FIX Time In Force (Tag 59) - TimeInForce
 */
enum class TimeInForce(val fixValue: String, val description: String) {
    DAY("0", "Day"),
    GTC("1", "Good Till Cancel"),
    AT_THE_OPENING("2", "At the Opening"),
    IOC("3", "Immediate or Cancel"),
    FOK("4", "Fill or Kill"),
    GTX("5", "Good Till Crossing"),
    GTD("6", "Good Till Date");

    companion object {
        fun fromFixValue(value: String): TimeInForce? = entries.find { it.fixValue == value }
    }
}

/**
 * Represents an FX order in the demo exchange
 */
data class FxOrder(
    val clOrdId: String,                      // Client Order ID (Tag 11)
    val orderId: String,                      // Exchange Order ID (Tag 37)
    val symbol: String,                       // Symbol (Tag 55)
    val side: Side,                           // Buy/Sell (Tag 54)
    val ordType: OrdType,                     // Order type (Tag 40)
    val orderQty: Double,                     // Original quantity (Tag 38)
    val price: Double? = null,                // Limit price (Tag 44), null for market orders
    val timeInForce: TimeInForce = TimeInForce.DAY, // Time in force (Tag 59)
    val currency: String = "USD",             // Currency (Tag 15)
    val account: String? = null,              // Account (Tag 1)
    val sessionId: String,                    // QuickFIX session this order belongs to

    // Execution state
    var ordStatus: OrdStatus = OrdStatus.PENDING_NEW,
    var cumQty: Double = 0.0,                 // Cumulative filled quantity (Tag 14)
    var avgPx: Double = 0.0,                  // Average fill price (Tag 6)
    var leavesQty: Double = orderQty,         // Remaining quantity (Tag 151)
    var lastQty: Double = 0.0,                // Last fill quantity (Tag 32)
    var lastPx: Double = 0.0,                 // Last fill price (Tag 31)

    // Timestamps
    val createdAt: LocalDateTime = LocalDateTime.now(),
    var lastUpdatedAt: LocalDateTime = LocalDateTime.now(),
) {
    /**
     * Checks if this order can be filled (is still active)
     */
    fun isActive(): Boolean = ordStatus in listOf(
        OrdStatus.NEW,
        OrdStatus.PARTIALLY_FILLED,
        OrdStatus.PENDING_NEW
    )

    /**
     * Checks if this order is fully filled
     */
    fun isFilled(): Boolean = ordStatus == OrdStatus.FILLED

    /**
     * Checks if this order is terminal (no more changes expected)
     */
    fun isTerminal(): Boolean = ordStatus in listOf(
        OrdStatus.FILLED,
        OrdStatus.CANCELED,
        OrdStatus.REJECTED,
        OrdStatus.EXPIRED,
        OrdStatus.DONE_FOR_DAY
    )

    /**
     * Applies a fill to this order
     * @param fillQty The quantity being filled
     * @param fillPx The price of the fill
     * @return The execution type for this fill
     */
    fun applyFill(fillQty: Double, fillPx: Double): ExecType {
        lastQty = fillQty
        lastPx = fillPx

        // Update cumulative values
        val totalValue = (cumQty * avgPx) + (fillQty * fillPx)
        cumQty += fillQty
        avgPx = if (cumQty > 0) totalValue / cumQty else 0.0
        leavesQty = orderQty - cumQty

        lastUpdatedAt = LocalDateTime.now()

        return if (leavesQty <= 0.0001) { // Small epsilon for floating point
            ordStatus = OrdStatus.FILLED
            leavesQty = 0.0
            ExecType.FILL
        } else {
            ordStatus = OrdStatus.PARTIALLY_FILLED
            ExecType.PARTIAL_FILL
        }
    }

    /**
     * Marks this order as accepted (New)
     */
    fun accept() {
        ordStatus = OrdStatus.NEW
        lastUpdatedAt = LocalDateTime.now()
    }

    /**
     * Marks this order as rejected
     */
    fun reject() {
        ordStatus = OrdStatus.REJECTED
        lastUpdatedAt = LocalDateTime.now()
    }

    /**
     * Marks this order as canceled
     */
    fun cancel() {
        ordStatus = OrdStatus.CANCELED
        lastUpdatedAt = LocalDateTime.now()
    }

    /**
     * Checks if a limit order is marketable at the given prices
     * @param bidPx Current bid price
     * @param askPx Current ask price
     * @return true if order can be filled immediately
     */
    fun isMarketable(bidPx: Double, askPx: Double): Boolean {
        if (ordType == OrdType.MARKET) return true
        if (ordType != OrdType.LIMIT || price == null) return false

        return when (side) {
            Side.BUY -> price >= askPx  // Buy limit at or above ask = marketable
            Side.SELL -> price <= bidPx // Sell limit at or below bid = marketable
        }
    }

    /**
     * Gets the fill price for this order given current market prices
     */
    fun getFillPrice(bidPx: Double, askPx: Double): Double {
        return when (side) {
            Side.BUY -> askPx   // Buyers lift the ask
            Side.SELL -> bidPx  // Sellers hit the bid
        }
    }
}
