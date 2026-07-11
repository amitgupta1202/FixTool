package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dictionary-mismatch lint: tags a message carries that the loaded dictionary does not define
 * for its message type are named locally — instead of surfacing minutes later as a cryptic
 * counterparty reject (the live BrokerTec-vs-standard-FIX44 QuoteRequest confusion).
 */
class DictionaryLintTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    @Test
    fun `a well-formed grouped message has no unknown tags`() {
        val fields = listOf(
            35 to "R",
            131 to "QR-1",
            146 to "1",
            55 to "EUR/USD", // group-internal: known via the group's dictionary
            54 to "1",
            38 to "250000",
        )
        assertEquals(emptyList(), DictionaryLint.unknownTags(fields, dictionary))
    }

    @Test
    fun `a tag the dictionary does not define for the message type is named`() {
        // ExecType(150) belongs to ExecutionReport, not QuoteRequest.
        val fields = listOf(35 to "R", 131 to "QR-1", 150 to "2")
        val unknown = DictionaryLint.unknownTags(fields, dictionary)
        assertEquals(listOf(150), unknown)
        val text = DictionaryLint.describe(unknown, fields, dictionary)
        assertTrue(text.contains("150"), text)
        assertTrue(text.contains("in the loaded dictionary"), text)
    }

    @Test
    fun `header and trailer tags are never flagged`() {
        val fields = listOf(8 to "FIX.4.4", 9 to "100", 35 to "R", 34 to "2", 49 to "A", 56 to "B", 131 to "QR-1", 10 to "000")
        assertEquals(emptyList(), DictionaryLint.unknownTags(fields, dictionary))
    }

    @Test
    fun `no dictionary or unknown message type stays silent`() {
        val fields = listOf(35 to "ZZ", 9999 to "x")
        assertEquals(emptyList(), DictionaryLint.unknownTags(fields, null))
        assertEquals(emptyList(), DictionaryLint.unknownTags(fields, dictionary))
    }
}
