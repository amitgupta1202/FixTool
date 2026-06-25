package com.knapsack.fixtool.ui

import androidx.compose.ui.graphics.Color
import com.knapsack.fixtool.model.FixDictionary
import quickfix.field.OrderQty
import quickfix.field.Side
import quickfix.field.Symbol
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the context-preserving search helpers in MessageDetailPanel:
 * [identityFieldTag], [identityInstanceTags] and [highlightMatches].
 *
 * These cover the pure decision logic behind the IDENTITY match-context mode (which fields a
 * matching repeating-group instance reveals) and the search highlight builder.
 *
 * Note: [FixDictionary.createDefault] carries no DataDictionary, so field-name lookups return null;
 * queries here therefore match by tag number / value, which is dictionary-independent.
 */
class MatchContextSearchTest {
    private val dictionary = FixDictionary.createDefault()
    private val highlight = Color(0x66FFD54F)

    /** A NoRelatedSym group instance: Symbol (delimiter) -> Side -> OrderQty. */
    private fun relatedSymGroup(
        symbol: String = "EUR/USD",
        side: Char = Side.BUY,
        qty: Double = 1_000_000.0,
    ): quickfix.fix42.QuoteRequest.NoRelatedSym =
        quickfix.fix42.QuoteRequest.NoRelatedSym().apply {
            setString(Symbol.FIELD, symbol)
            setChar(Side.FIELD, side)
            setDouble(OrderQty.FIELD, qty)
        }

    // ========================================
    // identityFieldTag
    // ========================================

    @Test
    fun `identity field is the first simple field of the instance`() {
        val tag = identityFieldTag(relatedSymGroup(), hideProtocolTags = false, protocolTags = emptySet())
        assertEquals(Symbol.FIELD, tag, "Symbol is the delimiter / first field of NoRelatedSym")
    }

    // ========================================
    // identityInstanceTags
    // ========================================

    @Test
    fun `identity mode keeps the identity plus the matched field`() {
        val tags =
            identityInstanceTags(
                fieldMap = relatedSymGroup(),
                dictionary = dictionary,
                searchQuery = "54", // Side, by tag number
                hideProtocolTags = false,
                protocolTags = emptySet(),
            )
        // Identity (Symbol) is always present so you can tell which entry; Side is the match.
        assertEquals(listOf(Symbol.FIELD, Side.FIELD), tags)
    }

    @Test
    fun `identity mode matches a field by value`() {
        val tags =
            identityInstanceTags(
                fieldMap = relatedSymGroup(qty = 7777.0),
                dictionary = dictionary,
                searchQuery = "7777", // OrderQty value
                hideProtocolTags = false,
                protocolTags = emptySet(),
            )
        assertEquals(listOf(Symbol.FIELD, OrderQty.FIELD), tags)
    }

    @Test
    fun `identity field is not duplicated when it is itself the match`() {
        val tags =
            identityInstanceTags(
                fieldMap = relatedSymGroup(),
                dictionary = dictionary,
                searchQuery = "55", // Symbol == identity
                hideProtocolTags = false,
                protocolTags = emptySet(),
            )
        assertEquals(listOf(Symbol.FIELD), tags)
    }

    @Test
    fun `identity mode keeps only the identity when nothing else matches`() {
        // A query that no simple field in the instance matches still yields the identity field,
        // since this is only ever rendered for an instance already known to contain a match.
        val tags =
            identityInstanceTags(
                fieldMap = relatedSymGroup(),
                dictionary = dictionary,
                searchQuery = "zzz-no-such-field",
                hideProtocolTags = false,
                protocolTags = emptySet(),
            )
        assertEquals(listOf(Symbol.FIELD), tags)
    }

    // ========================================
    // highlightMatches
    // ========================================

    @Test
    fun `highlight is a no-op for blank query`() {
        val result = highlightMatches("PartyRole", "", highlight)
        assertEquals("PartyRole", result.text)
        assertTrue(result.spanStyles.isEmpty(), "No spans should be added for a blank query")
    }

    @Test
    fun `highlight spans the matched substring case-insensitively`() {
        val result = highlightMatches("PartyRole", "role", highlight)
        assertEquals("PartyRole", result.text)
        assertEquals(1, result.spanStyles.size)
        // "Role" begins at index 5 in "PartyRole".
        assertEquals(5, result.spanStyles[0].start)
        assertEquals(9, result.spanStyles[0].end)
    }

    @Test
    fun `highlight spans every occurrence`() {
        val result = highlightMatches("abcabc", "bc", highlight)
        assertEquals("abcabc", result.text)
        assertEquals(2, result.spanStyles.size)
    }

    @Test
    fun `highlight adds no spans when there is no match`() {
        val result = highlightMatches("abc", "xyz", highlight)
        assertEquals("abc", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }
}
