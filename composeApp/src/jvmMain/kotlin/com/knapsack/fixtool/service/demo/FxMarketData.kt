package com.knapsack.fixtool.service.demo

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Represents a price quote for a currency pair
 */
data class FxQuote(
    val symbol: String,
    val bidPx: Double,      // Best bid price
    val askPx: Double,      // Best ask (offer) price
    val bidSize: Double,    // Size at bid
    val askSize: Double,    // Size at ask
    val timestamp: Long = System.currentTimeMillis()
) {
    val midPx: Double get() = (bidPx + askPx) / 2
    val spread: Double get() = askPx - bidPx
}

/**
 * Manages live FX market data with simulated price movements.
 * Prices tick randomly within realistic bounds.
 */
class FxMarketData {
    private val logger = LoggerFactory.getLogger(FxMarketData::class.java)

    // Current prices for each symbol
    private val quotes = ConcurrentHashMap<String, FxQuote>()

    // Listeners for price updates (for filling resting orders)
    private val priceListeners = mutableListOf<(FxQuote) -> Unit>()

    init {
        // Initialize prices for all supported pairs
        FxCurrencyPair.SUPPORTED_PAIRS.forEach { pair ->
            quotes[pair.symbol] = generateInitialQuote(pair)
        }
        logger.info("Initialized FX market data for {} pairs", quotes.size)
    }

    /**
     * Registers a listener to be notified of price changes
     */
    fun addPriceListener(listener: (FxQuote) -> Unit) {
        synchronized(priceListeners) {
            priceListeners.add(listener)
        }
    }

    /**
     * Removes a price listener
     */
    fun removePriceListener(listener: (FxQuote) -> Unit) {
        synchronized(priceListeners) {
            priceListeners.remove(listener)
        }
    }

    /**
     * Gets the current quote for a symbol
     */
    fun getQuote(symbol: String): FxQuote? {
        val normalized = FxCurrencyPair.normalizeSymbol(symbol)
        return quotes[normalized]
    }

    /**
     * Gets all current quotes
     */
    fun getAllQuotes(): Map<String, FxQuote> = quotes.toMap()

    /**
     * Simulates a price tick for all pairs.
     * Call this periodically (e.g., every 1 second) to simulate market movement.
     */
    fun tick() {
        FxCurrencyPair.SUPPORTED_PAIRS.forEach { pair ->
            val currentQuote = quotes[pair.symbol] ?: return@forEach
            val newQuote = simulatePriceMove(pair, currentQuote)
            quotes[pair.symbol] = newQuote

            // Notify listeners
            notifyListeners(newQuote)
        }
    }

    /**
     * Simulates a price tick for a specific symbol
     */
    fun tickSymbol(symbol: String) {
        val pair = FxCurrencyPair.findBySymbol(symbol) ?: return
        val currentQuote = quotes[pair.symbol] ?: return
        val newQuote = simulatePriceMove(pair, currentQuote)
        quotes[pair.symbol] = newQuote
        notifyListeners(newQuote)
    }

    /**
     * Clears all market data and listeners
     */
    fun clear() {
        quotes.clear()
        synchronized(priceListeners) {
            priceListeners.clear()
        }
    }

    private fun notifyListeners(quote: FxQuote) {
        val listeners = synchronized(priceListeners) { priceListeners.toList() }
        listeners.forEach { listener ->
            try {
                listener(quote)
            } catch (e: Exception) {
                logger.error("Error notifying price listener: {}", e.message)
            }
        }
    }

    private fun generateInitialQuote(pair: FxCurrencyPair): FxQuote {
        // Add some initial randomness to the base price (+/- 0.5%)
        val variation = pair.basePrice * Random.nextDouble(-0.005, 0.005)
        val midPrice = pair.basePrice + variation

        // Calculate spread based on pair's default
        val halfSpread = pair.pipsToPrice(pair.defaultSpreadPips) / 2

        return FxQuote(
            symbol = pair.symbol,
            bidPx = roundPrice(midPrice - halfSpread, pair),
            askPx = roundPrice(midPrice + halfSpread, pair),
            bidSize = Random.nextDouble(100000.0, 1000000.0),
            askSize = Random.nextDouble(100000.0, 1000000.0)
        )
    }

    private fun simulatePriceMove(pair: FxCurrencyPair, current: FxQuote): FxQuote {
        // Random price movement: 0-3 pips in either direction
        val pipMove = Random.nextInt(-3, 4) // -3 to +3 pips
        val priceMove = pair.pipsToPrice(pipMove)

        // Calculate new mid price
        val newMid = current.midPx + priceMove

        // Random spread variation: 2-5 pips
        val spreadPips = Random.nextInt(2, 6)
        val halfSpread = pair.pipsToPrice(spreadPips) / 2

        // Random size changes
        val bidSize = current.bidSize * Random.nextDouble(0.9, 1.1)
        val askSize = current.askSize * Random.nextDouble(0.9, 1.1)

        return FxQuote(
            symbol = pair.symbol,
            bidPx = roundPrice(newMid - halfSpread, pair),
            askPx = roundPrice(newMid + halfSpread, pair),
            bidSize = bidSize.coerceIn(50000.0, 2000000.0),
            askSize = askSize.coerceIn(50000.0, 2000000.0)
        )
    }

    /**
     * Rounds a price to appropriate decimal places for the pair
     */
    private fun roundPrice(price: Double, pair: FxCurrencyPair): Double {
        val decimals = if (pair.pipSize == 0.01) 3 else 5
        val multiplier = Math.pow(10.0, decimals.toDouble())
        return Math.round(price * multiplier) / multiplier
    }

    companion object {
        /**
         * Formats a price for display with appropriate precision
         */
        fun formatPrice(price: Double, symbol: String): String {
            val pair = FxCurrencyPair.findBySymbol(symbol)
            val decimals = if (pair?.pipSize == 0.01) 3 else 5
            return "%.${decimals}f".format(price)
        }
    }
}
