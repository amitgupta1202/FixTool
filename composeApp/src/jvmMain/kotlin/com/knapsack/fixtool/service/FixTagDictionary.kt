package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter

/**
 * Provides tag name to tag number resolution for shorthand template syntax.
 * Uses the loaded data dictionary for lookups, with fallback to common FIX tags.
 */
object FixTagDictionary {

    /**
     * Common FIX tags as fallback when no dictionary is loaded.
     * These are the most frequently used tags in FIX 4.2/4.4/5.0.
     */
    private val COMMON_TAGS: Map<String, Int> = mapOf(
        // Session level
        "BeginString" to 8,
        "BodyLength" to 9,
        "MsgType" to 35,
        "SenderCompID" to 49,
        "TargetCompID" to 56,
        "MsgSeqNum" to 34,
        "SendingTime" to 52,
        "CheckSum" to 10,

        // Order fields
        "ClOrdID" to 11,
        "OrderID" to 37,
        "OrigClOrdID" to 41,
        "ExecID" to 17,
        "ExecType" to 150,
        "OrdStatus" to 39,
        "Symbol" to 55,
        "Side" to 54,
        "OrderQty" to 38,
        "OrdType" to 40,
        "Price" to 44,
        "StopPx" to 99,
        "TimeInForce" to 59,
        "TransactTime" to 60,
        "Account" to 1,
        "HandlInst" to 21,
        "SecurityID" to 48,
        "SecurityIDSource" to 22,
        "SecurityType" to 167,
        "Currency" to 15,
        "LeavesQty" to 151,
        "CumQty" to 14,
        "AvgPx" to 6,
        "LastQty" to 32,
        "LastPx" to 31,
        "Text" to 58,

        // Quote fields
        "QuoteReqID" to 131,
        "QuoteID" to 117,
        "BidPx" to 132,
        "OfferPx" to 133,
        "BidSize" to 134,
        "OfferSize" to 135,
        "QuoteType" to 537,
        "ValidUntilTime" to 62,
        "QuoteStatus" to 297,
        "QuoteResponseLevel" to 301,

        // Market data
        "MDReqID" to 262,
        "SubscriptionRequestType" to 263,
        "MarketDepth" to 264,
        "MDUpdateType" to 265,
        "MDEntryType" to 269,
        "MDEntryPx" to 270,
        "MDEntrySize" to 271,
        "NoMDEntries" to 268,

        // Party fields
        "NoPartyIDs" to 453,
        "PartyID" to 448,
        "PartyIDSource" to 447,
        "PartyRole" to 452,

        // Allocation
        "AllocID" to 70,
        "AllocTransType" to 71,
        "NoAllocs" to 78,
        "AllocAccount" to 79,
        "AllocQty" to 80,

        // Settlement
        "SettlDate" to 64,
        "SettlType" to 63,
        "FutSettDate" to 64,

        // Legs (multi-leg)
        "NoLegs" to 555,
        "LegSymbol" to 600,
        "LegSide" to 624,
        "LegQty" to 687,
        "LegPrice" to 566,

        // Common rejection/status fields
        "RefSeqNum" to 45,
        "RefMsgType" to 372,
        "SessionRejectReason" to 373,
        "BusinessRejectReason" to 380,
        "CxlRejReason" to 102,
        "OrdRejReason" to 103,
    )

    // Cache for dictionary-based name -> tag lookup (built lazily)
    @Volatile
    private var cachedNameToTag: Map<String, Int>? = null

    @Volatile
    private var lastDictionary: FixDictionaryAdapter? = null

    /**
     * Resolves a tag name to its tag number.
     *
     * @param name The tag name (e.g., "ClOrdID", "Symbol")
     * @param dictionary Optional data dictionary for extended tag names
     * @return The tag number, or null if not found
     */
    fun resolveTagName(name: String, dictionary: FixDictionaryAdapter?): Int? {
        // If dictionary changed, rebuild cache
        if (dictionary !== lastDictionary) {
            rebuildCache(dictionary)
        }

        // Try dictionary cache first (if available)
        cachedNameToTag?.get(name)?.let { return it }

        // Fall back to common tags
        return COMMON_TAGS[name]
    }

    /**
     * Checks if a string is a valid tag reference (either a number or a known name).
     */
    fun isValidTagReference(tagOrName: String, dictionary: FixDictionaryAdapter?): Boolean {
        return tagOrName.toIntOrNull() != null || resolveTagName(tagOrName, dictionary) != null
    }

    /**
     * Resolves a tag reference to a tag number.
     * Accepts either a numeric string or a tag name.
     *
     * @param tagOrName Either a tag number as string (e.g., "11") or tag name (e.g., "ClOrdID")
     * @param dictionary Optional data dictionary
     * @return The tag number, or null if not resolvable
     */
    fun resolveTagOrName(tagOrName: String, dictionary: FixDictionaryAdapter?): Int? {
        // Try parsing as number first (most common case, fastest)
        tagOrName.toIntOrNull()?.let { return it }

        // Otherwise resolve as name
        return resolveTagName(tagOrName, dictionary)
    }

    /**
     * Rebuilds the name-to-tag cache from the dictionary.
     */
    @Synchronized
    private fun rebuildCache(dictionary: FixDictionaryAdapter?) {
        lastDictionary = dictionary
        cachedNameToTag = dictionary?.getAllFields()
            ?.associate { (tag, name) -> name to tag }
    }

    /**
     * Clears the cache. Useful for testing or when dictionary is reloaded.
     */
    fun clearCache() {
        cachedNameToTag = null
        lastDictionary = null
    }
}
