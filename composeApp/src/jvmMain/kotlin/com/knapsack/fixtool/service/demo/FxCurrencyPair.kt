package com.knapsack.fixtool.service.demo

/**
 * Represents an FX currency pair with its trading characteristics.
 * Standard FX naming convention: BASE/QUOTE (e.g., EUR/USD means 1 EUR costs X USD)
 */
data class FxCurrencyPair(
    val symbol: String,           // Trading symbol (e.g., "EUR/USD")
    val baseCurrency: String,     // Base currency (e.g., "EUR")
    val quoteCurrency: String,    // Quote currency (e.g., "USD")
    val pipSize: Double,          // Size of one pip (e.g., 0.0001 for most pairs, 0.01 for JPY pairs)
    val defaultSpreadPips: Int,   // Default spread in pips (e.g., 2-5)
    val basePrice: Double,        // Reference price for simulation
) {
    companion object {
        /**
         * Standard FX currency pairs for the demo exchange
         */
        val SUPPORTED_PAIRS = listOf(
            FxCurrencyPair(
                symbol = "EUR/USD",
                baseCurrency = "EUR",
                quoteCurrency = "USD",
                pipSize = 0.0001,
                defaultSpreadPips = 2,
                basePrice = 1.0900
            ),
            FxCurrencyPair(
                symbol = "GBP/USD",
                baseCurrency = "GBP",
                quoteCurrency = "USD",
                pipSize = 0.0001,
                defaultSpreadPips = 3,
                basePrice = 1.2700
            ),
            FxCurrencyPair(
                symbol = "USD/JPY",
                baseCurrency = "USD",
                quoteCurrency = "JPY",
                pipSize = 0.01,
                defaultSpreadPips = 2,
                basePrice = 149.50
            ),
            FxCurrencyPair(
                symbol = "USD/CHF",
                baseCurrency = "USD",
                quoteCurrency = "CHF",
                pipSize = 0.0001,
                defaultSpreadPips = 3,
                basePrice = 0.8800
            ),
            FxCurrencyPair(
                symbol = "AUD/USD",
                baseCurrency = "AUD",
                quoteCurrency = "USD",
                pipSize = 0.0001,
                defaultSpreadPips = 3,
                basePrice = 0.6550
            ),
            FxCurrencyPair(
                symbol = "USD/CAD",
                baseCurrency = "USD",
                quoteCurrency = "CAD",
                pipSize = 0.0001,
                defaultSpreadPips = 3,
                basePrice = 1.3600
            ),
        )

        /**
         * Maps symbol strings to FxCurrencyPair objects for quick lookup
         */
        private val symbolMap: Map<String, FxCurrencyPair> by lazy {
            SUPPORTED_PAIRS.associateBy { it.symbol }
        }

        /**
         * Finds a currency pair by symbol.
         * Supports multiple formats: "EUR/USD", "EURUSD", "EUR.USD"
         */
        fun findBySymbol(symbol: String): FxCurrencyPair? {
            // Direct lookup
            symbolMap[symbol]?.let { return it }

            // Try normalized formats
            val normalized = normalizeSymbol(symbol)
            return symbolMap[normalized]
        }

        /**
         * Normalizes a symbol to standard format (e.g., "EURUSD" -> "EUR/USD")
         */
        fun normalizeSymbol(symbol: String): String {
            val clean = symbol.uppercase().replace(".", "").replace("/", "").replace("-", "")
            return if (clean.length == 6) {
                "${clean.substring(0, 3)}/${clean.substring(3, 6)}"
            } else {
                symbol.uppercase()
            }
        }

        /**
         * Checks if a symbol is a supported FX pair
         */
        fun isSupported(symbol: String): Boolean = findBySymbol(symbol) != null

        /**
         * Returns all supported symbol strings
         */
        fun allSymbols(): List<String> = SUPPORTED_PAIRS.map { it.symbol }
    }

    /**
     * Calculates price movement in pips
     */
    fun pipsToPrice(pips: Int): Double = pips * pipSize

    /**
     * Calculates pips from price difference
     */
    fun priceToPips(priceDiff: Double): Int = (priceDiff / pipSize).toInt()
}
