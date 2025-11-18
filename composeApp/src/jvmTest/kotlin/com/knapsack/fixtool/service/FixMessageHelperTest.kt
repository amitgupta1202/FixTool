package com.knapsack.fixtool.service

import com.knapsack.fixtool.service.FixMessageHelper.normalizeFixMessage
import org.junit.Test
import kotlin.test.assertEquals

class FixMessageHelperTest {
    @Test
    fun testNormalizeTraditionalFormat() {
        val input = "35=D|49=SENDER|56=TARGET|"
        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|56=TARGET|", result, "Traditional format should pass through unchanged")
    }

    @Test
    fun testNormalizeTraditionalFormatWithoutTrailingPipe() {
        val input = "35=D|49=SENDER|56=TARGET"
        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|56=TARGET", result, "Traditional format without trailing pipe should pass through unchanged")
    }

    @Test
    fun testNormalizeLineBasedFormat() {
        val input = """
            35 D
            49 SENDER
            56 TARGET
        """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|56=TARGET|", result, "Line-based format should convert to traditional")
    }

    @Test
    fun testNormalizeLineBasedFormatWithInlineComments() {
        val input = """
            35 D
            49 SENDER #this is a comment
            56 TARGET
        """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|56=TARGET|", result, "Inline comments should be stripped")
    }

    @Test
    fun testNormalizeLineBasedFormatWithFullLineComments() {
        val input = """
            35 D
            # This is a full line comment
            49 SENDER
            #Another comment
            56 TARGET
        """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|56=TARGET|", result, "Full-line comments should be ignored")
    }

    @Test
    fun testNormalizeLineBasedFormatWithMixedComments() {
        val input = """
            35 R
            131 ORD1 #this is comment
            #49 WRONG_COMMENTED
            45 ABC XYY
            60 123
        """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=R|131=ORD1|45=ABC XYY|60=123|", result, "Should handle both inline and full-line comments")
    }

    @Test
    fun testNormalizeLineBasedFormatWithExtraWhitespace() {
        val input = """
            35   D
               49    SENDER
            56 TARGET
        """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|56=TARGET|", result, "Extra whitespace should be trimmed")
    }

    @Test
    fun testNormalizeLineBasedFormatWithBlankLines() {
        val input = """
            35 D

            49 SENDER


            56 TARGET
        """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|56=TARGET|", result, "Blank lines should be ignored")
    }

    @Test
    fun testNormalizeLineBasedFormatWithWindowsLineEndings() {
        val input = "35 D\r\n49 SENDER\r\n56 TARGET"

        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|56=TARGET|", result, "Windows line endings should be handled correctly")
    }

    @Test
    fun testNormalizeLineBasedFormatWithUnixLineEndings() {
        val input = "35 D\n49 SENDER\n56 TARGET"

        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|56=TARGET|", result, "Unix line endings should be handled correctly")
    }

    @Test
    fun testNormalizeLineBasedFormatWithMultiWordValue() {
        val input = """
            35 D
            58 This is a multi word value
            56 TARGET
        """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=D|58=This is a multi word value|56=TARGET|", result, "Multi-word values should be preserved")
    }

    @Test
    fun testNormalizeLineBasedFormatWithNumericValues() {
        val input = """
            35 D
            38 1000
            44 99.50
            54 1
        """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=D|38=1000|44=99.50|54=1|", result, "Numeric values should be preserved")
    }

    @Test
    fun testNormalizeEmptyString() {
        val input = ""
        val result = input.normalizeFixMessage()

        assertEquals("", result, "Empty string should return empty string")
    }

    @Test
    fun testNormalizeBlankString() {
        val input = "   \n\n   "
        val result = input.normalizeFixMessage()

        assertEquals("", result, "Blank string should return empty string")
    }

    @Test
    fun testNormalizeLineBasedFormatWithOnlyComments() {
        val input = """
            # Comment 1
            # Comment 2
            # Comment 3
        """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("", result, "Only comments should result in empty string")
    }

    @Test
    fun testNormalizeLineBasedFormatWithTagsOnly() {
        val input = """
            35
            49
        """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=|49=|", result, "Tags without values should result in empty values")
    }

    @Test
    fun testNormalizeLineBasedFormatWithNonNumericTags() {
        val input = """
            35 D
            ABC INVALID
            49 SENDER
        """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|", result, "Non-numeric tags should be skipped")
    }

    @Test
    fun testNormalizeMixedFormatDefaultsToTraditional() {
        val input = """
            35=D
            49 SENDER
            56=TARGET
        """.trimIndent()

        val result = input.normalizeFixMessage()

        // When format is mixed (has newlines, has =, but no |), it treats as line-based
        // Only valid line-based entries are parsed (49 SENDER)
        // Lines with = are not valid line-based format and are skipped
        assertEquals("49=SENDER|", result, "Mixed format parses only valid line-based entries")
    }

    @Test
    fun testNormalizeLineBasedFormatComplexExample() {
        val input = """
            # Order message
            35 D
            49 SENDER_COMP
            56 TARGET_COMP
            # Order details
            11 ORDER-123
            21 1
            55 EUR/USD
            54 1
            38 1000000
            40 2
            44 1.0950
            # End of order
        """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals(
            "35=D|49=SENDER_COMP|56=TARGET_COMP|11=ORDER-123|21=1|55=EUR/USD|54=1|38=1000000|40=2|44=1.0950|",
            result,
            "Complex message with comments should parse correctly"
        )
    }

    @Test
    fun testNormalizeLineBasedFormatWithSpecialCharacters() {
        val input = """
            35 D
            58 Value with = sign
            55 EUR/USD
            100 EX-CHANGE
        """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals(
            "35=D|58=Value with = sign|55=EUR/USD|100=EX-CHANGE|",
            result,
            "Special characters in values should be preserved"
        )
    }

    @Test
    fun testNormalizeLineBasedFormatWithTabsAndSpaces() {
        val input = "35\tD\n49    SENDER\n56\t  TARGET"

        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|56=TARGET|", result, "Tabs and multiple spaces should be handled as delimiters")
    }
}
