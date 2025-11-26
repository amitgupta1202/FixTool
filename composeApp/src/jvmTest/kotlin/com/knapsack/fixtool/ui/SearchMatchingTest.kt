package com.knapsack.fixtool.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchMatchingTest {

    // Copy of the function from SavedMessagesBrowserPopup for testing
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
    fun `extractInitials from QuoteRequest Templat`() {
        val name = "QuoteRequest Templat"
        val parts = name.split(Regex("[-_\\s]|(?=[A-Z])")).filter { it.isNotEmpty() }
        println("'$name' splits to: $parts")
        val initials = extractInitials(name)
        println("'$name' -> '$initials'")
        assertEquals("QRT", initials)
    }

    @Test
    fun `debug all test cases`() {
        val testCases = listOf(
            "QuoteRequestTemplate",
            "Quote_Request_Template",
            "Quote Request Template",
            "quote-request-template",
            "QuoteRequest Template",
            "QuoteRequest Templat"
        )
        val results = testCases.map { name ->
            val parts = name.split(Regex("[-_\\s]|(?=[A-Z])")).filter { it.isNotEmpty() }
            "'$name' splits to: $parts -> initials: '${extractInitials(name)}'"
        }
        // All should produce QRT
        testCases.forEach { name ->
            assertEquals("QRT", extractInitials(name), "Failed for: $name")
        }
    }

    @Test
    fun `QRT matches QuoteRequestTemplate`() {
        assertTrue(matchesSearch("QuoteRequestTemplate", "QRT"))
    }

    @Test
    fun `QRT matches Quote Request Template`() {
        assertTrue(matchesSearch("Quote Request Template", "QRT"))
    }

    @Test
    fun `QRT matches QuoteRequest Template`() {
        assertTrue(matchesSearch("QuoteRequest Template", "QRT"))
    }

    @Test
    fun `QR matches QuoteRequestTemplate`() {
        assertTrue(matchesSearch("QuoteRequestTemplate", "QR"))
    }

    @Test
    fun `partial match works`() {
        assertTrue(matchesSearch("QuoteRequestTemplate", "quote"))
        assertTrue(matchesSearch("QuoteRequestTemplate", "Request"))
    }
}
