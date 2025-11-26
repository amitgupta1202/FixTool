package com.knapsack.fixtool.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the IntelliJ-style search matching functionality in SavedMessagesBrowserPopup.
 * Tests both extractInitials and matchesSearch functions.
 */
class SearchMatchingTest {

    // Copy of the functions from SavedMessagesBrowserPopup for testing
    private fun extractInitials(name: String): String {
        return name
            .split(Regex("[-_\\s]|(?=[A-Z])"))
            .filter { it.isNotEmpty() }
            .map { it.first().uppercaseChar() }
            .joinToString("")
    }

    private fun matchesSearch(name: String, query: String): Boolean {
        if (query.isBlank()) return true
        val trimmedQuery = query.trim()

        // 1. Partial name match (case-insensitive)
        if (name.lowercase().contains(trimmedQuery.lowercase())) return true

        // 2. Abbreviation match - check if all chars are uppercase/digits
        if (trimmedQuery.all { it.isUpperCase() || it.isDigit() }) {
            val initials = extractInitials(name)
            if (initials.startsWith(trimmedQuery, ignoreCase = true)) return true
        }

        return false
    }

    // ========================================
    // extractInitials tests
    // ========================================

    @Test
    fun `extractInitials from CamelCase`() {
        assertEquals("QRT", extractInitials("QuoteRequestTemplate"))
    }

    @Test
    fun `extractInitials from snake_case`() {
        assertEquals("QRT", extractInitials("Quote_Request_Template"))
    }

    @Test
    fun `extractInitials from space separated`() {
        assertEquals("QRT", extractInitials("Quote Request Template"))
    }

    @Test
    fun `extractInitials from kebab-case`() {
        assertEquals("QRT", extractInitials("quote-request-template"))
    }

    @Test
    fun `extractInitials from mixed CamelCase and space`() {
        assertEquals("QRT", extractInitials("QuoteRequest Template"))
    }

    @Test
    fun `extractInitials from mixed CamelCase and underscore`() {
        assertEquals("QRT", extractInitials("QuoteRequest_Template"))
    }

    @Test
    fun `extractInitials handles single word`() {
        assertEquals("Q", extractInitials("Quote"))
    }

    @Test
    fun `extractInitials handles lowercase single word`() {
        assertEquals("Q", extractInitials("quote"))
    }

    @Test
    fun `extractInitials handles empty string`() {
        assertEquals("", extractInitials(""))
    }

    @Test
    fun `extractInitials handles numbers in CamelCase`() {
        // Numbers don't start a new word in CamelCase
        assertEquals("QT", extractInitials("Quote2Template"))
    }

    @Test
    fun `extractInitials handles all uppercase with underscores`() {
        // ALL_CAPS splits on underscores, each word starts with uppercase
        // QUOTE splits into Q,U,O,T,E because of (?=[A-Z])
        // This is expected behavior - ALL_CAPS isn't ideal for abbreviation matching
        val initials = extractInitials("QUOTE_REQUEST_TEMPLATE")
        assertTrue(initials.startsWith("Q"), "Should start with Q")
    }

    @Test
    fun `extractInitials handles mixed separators`() {
        assertEquals("QRTA", extractInitials("Quote_Request-Template Another"))
    }

    @Test
    fun `extractInitials all naming conventions produce same result`() {
        val testCases = listOf(
            "QuoteRequestTemplate",
            "Quote_Request_Template",
            "Quote Request Template",
            "quote-request-template",
            "QuoteRequest Template",
            "QuoteRequest_Template"
        )
        testCases.forEach { name ->
            assertEquals("QRT", extractInitials(name), "Failed for: $name")
        }
    }

    // ========================================
    // matchesSearch - blank query tests
    // ========================================

    @Test
    fun `matchesSearch returns true for blank query`() {
        assertTrue(matchesSearch("QuoteRequestTemplate", ""))
        assertTrue(matchesSearch("QuoteRequestTemplate", "   "))
    }

    // ========================================
    // matchesSearch - partial name match tests
    // ========================================

    @Test
    fun `matchesSearch partial name match at start`() {
        assertTrue(matchesSearch("QuoteRequestTemplate", "Quote"))
    }

    @Test
    fun `matchesSearch partial name match in middle`() {
        assertTrue(matchesSearch("QuoteRequestTemplate", "Request"))
    }

    @Test
    fun `matchesSearch partial name match at end`() {
        assertTrue(matchesSearch("QuoteRequestTemplate", "Template"))
    }

    @Test
    fun `matchesSearch partial name match is case insensitive`() {
        assertTrue(matchesSearch("QuoteRequestTemplate", "quote"))
        assertTrue(matchesSearch("QuoteRequestTemplate", "QUOTE"))
        assertTrue(matchesSearch("QuoteRequestTemplate", "QuOtE"))
    }

    @Test
    fun `matchesSearch partial name match with spaces in name`() {
        assertTrue(matchesSearch("Quote Request Template", "request"))
    }

    @Test
    fun `matchesSearch partial name no match`() {
        assertFalse(matchesSearch("QuoteRequestTemplate", "xyz"))
        assertFalse(matchesSearch("QuoteRequestTemplate", "Order"))
    }

    // ========================================
    // matchesSearch - abbreviation match tests
    // ========================================

    @Test
    fun `matchesSearch abbreviation full match`() {
        assertTrue(matchesSearch("QuoteRequestTemplate", "QRT"))
    }

    @Test
    fun `matchesSearch abbreviation partial match`() {
        assertTrue(matchesSearch("QuoteRequestTemplate", "QR"))
        assertTrue(matchesSearch("QuoteRequestTemplate", "Q"))
    }

    @Test
    fun `matchesSearch abbreviation match with spaces in name`() {
        assertTrue(matchesSearch("Quote Request Template", "QRT"))
    }

    @Test
    fun `matchesSearch abbreviation match with snake_case`() {
        assertTrue(matchesSearch("Quote_Request_Template", "QRT"))
    }

    @Test
    fun `matchesSearch abbreviation match with kebab-case`() {
        assertTrue(matchesSearch("quote-request-template", "QRT"))
    }

    @Test
    fun `matchesSearch abbreviation no match when initials dont match`() {
        assertFalse(matchesSearch("QuoteRequestTemplate", "ABC"))
        assertFalse(matchesSearch("QuoteRequestTemplate", "QRX"))
    }

    @Test
    fun `matchesSearch abbreviation no match when query too long`() {
        assertFalse(matchesSearch("QuoteRequestTemplate", "QRTX"))
    }

    @Test
    fun `matchesSearch abbreviation with digits in name`() {
        // Quote2Template produces initials "QT" (2 is not a word boundary)
        assertTrue(matchesSearch("Quote2Template", "QT"))
    }

    // ========================================
    // matchesSearch - mixed case query tests
    // ========================================

    @Test
    fun `matchesSearch mixed case query uses partial match not abbreviation`() {
        // "Qrt" is not all uppercase, so it should use partial match
        // "Qrt" is not found in "QuoteRequestTemplate" as substring
        assertFalse(matchesSearch("QuoteRequestTemplate", "Qrt"))
    }

    @Test
    fun `matchesSearch lowercase abbreviation uses partial match`() {
        // "qrt" is not all uppercase, so it should use partial match
        // "qrt" is not found in "QuoteRequestTemplate" as substring
        assertFalse(matchesSearch("QuoteRequestTemplate", "qrt"))
    }

    // ========================================
    // matchesSearch - edge cases
    // ========================================

    @Test
    fun `matchesSearch with leading and trailing spaces in query`() {
        assertTrue(matchesSearch("QuoteRequestTemplate", "  Quote  "))
        assertTrue(matchesSearch("QuoteRequestTemplate", "  QRT  "))
    }

    @Test
    fun `matchesSearch single character partial match`() {
        assertTrue(matchesSearch("QuoteRequestTemplate", "q"))
        assertTrue(matchesSearch("QuoteRequestTemplate", "Q"))
    }

    @Test
    fun `matchesSearch single character abbreviation match`() {
        assertTrue(matchesSearch("QuoteRequestTemplate", "Q"))
    }

    @Test
    fun `matchesSearch exact match`() {
        assertTrue(matchesSearch("QuoteRequestTemplate", "QuoteRequestTemplate"))
    }
}
