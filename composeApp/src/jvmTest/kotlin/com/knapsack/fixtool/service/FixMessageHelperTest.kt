package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.service.FixMessageHelper.normalizeFixMessage
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixMessageHelperTest {
    /**
     * The loaded dictionary's own header section decides what is a header field — not the static
     * per-version list alone. Venue dialects add custom header fields (a routing tag, a desk id);
     * classified by the static list they landed in the body, the wire changed, and the venue answered
     * "tag specified out of required order" while Validate — which reads the same dictionary — saw
     * nothing wrong. Send and Validate must read the same message.
     */
    @Test
    fun `a custom header field from the venue dictionary is serialized in the header`() {
        val dictFile = java.io.File.createTempFile("venue_dialect", ".xml")
        try {
            dictFile.writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
<fix major="4" minor="4">
    <header>
        <field name="BeginString" number="8" type="STRING" required="Y"/>
        <field name="BodyLength" number="9" type="LENGTH" required="Y"/>
        <field name="MsgType" number="35" type="STRING" required="Y"/>
        <field name="SenderCompID" number="49" type="STRING" required="Y"/>
        <field name="TargetCompID" number="56" type="STRING" required="Y"/>
        <field name="MsgSeqNum" number="34" type="SEQNUM" required="Y"/>
        <field name="SendingTime" number="52" type="UTCTIMESTAMP" required="Y"/>
        <field name="RouteID" number="5001" type="STRING" required="N"/>
    </header>
    <trailer>
        <field name="CheckSum" number="10" type="STRING" required="Y"/>
    </trailer>
    <messages>
        <message name="NewOrderSingle" msgtype="D" msgcat="app">
            <field name="ClOrdID" number="11" required="Y"/>
            <field name="Symbol" number="55" required="Y"/>
            <field name="Side" number="54" required="Y"/>
            <field name="OrderQty" number="38" required="Y"/>
            <field name="OrdType" number="40" required="Y"/>
        </message>
    </messages>
    <fields>
        <field name="BeginString" number="8" type="STRING"/>
        <field name="BodyLength" number="9" type="LENGTH"/>
        <field name="CheckSum" number="10" type="STRING"/>
        <field name="ClOrdID" number="11" type="STRING"/>
        <field name="MsgSeqNum" number="34" type="SEQNUM"/>
        <field name="MsgType" number="35" type="STRING"/>
        <field name="OrderQty" number="38" type="QTY"/>
        <field name="OrdType" number="40" type="CHAR"/>
        <field name="SenderCompID" number="49" type="STRING"/>
        <field name="SendingTime" number="52" type="UTCTIMESTAMP"/>
        <field name="Side" number="54" type="CHAR"/>
        <field name="Symbol" number="55" type="STRING"/>
        <field name="TargetCompID" number="56" type="STRING"/>
        <field name="RouteID" number="5001" type="STRING"/>
    </fields>
</fix>""",
            )
            val dictionary = FixDictionaryAdapter.fromFile(dictFile)
            assertTrue(dictionary.isLoaded(), "the venue dialect must load")

            val message = "35=D|11=ORD-1|55=EUR/USD|54=1|38=100|40=1|5001=DESK-7|".toQuickFixMessageManual(dictionary)

            assertTrue(
                message.header.isSetField(5001),
                "the dictionary's header section says 5001 is a header field — the static FIX44 list must not overrule it",
            )
            assertFalse(message.isSetField(5001), "and it must not also ride in the body")
            assertEquals("DESK-7", message.header.getString(5001))
        } finally {
            dictFile.delete()
        }
    }

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
        val input =
            """
            35 D
            49 SENDER
            56 TARGET
            """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|56=TARGET|", result, "Line-based format should convert to traditional")
    }

    @Test
    fun testNormalizeLineBasedFormatWithInlineComments() {
        val input =
            """
            35 D
            49 SENDER #this is a comment
            56 TARGET
            """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|56=TARGET|", result, "Inline comments should be stripped")
    }

    @Test
    fun testNormalizeLineBasedFormatWithFullLineComments() {
        val input =
            """
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
        val input =
            """
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
        val input =
            """
            35   D
               49    SENDER
            56 TARGET
            """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|56=TARGET|", result, "Extra whitespace should be trimmed")
    }

    @Test
    fun testNormalizeLineBasedFormatWithBlankLines() {
        val input =
            """
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
        val input =
            """
            35 D
            58 This is a multi word value
            56 TARGET
            """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=D|58=This is a multi word value|56=TARGET|", result, "Multi-word values should be preserved")
    }

    @Test
    fun testNormalizeLineBasedFormatWithNumericValues() {
        val input =
            """
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
        val input =
            """
            # Comment 1
            # Comment 2
            # Comment 3
            """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("", result, "Only comments should result in empty string")
    }

    @Test
    fun testNormalizeLineBasedFormatWithTagsOnly() {
        val input =
            """
            35
            49
            """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=|49=|", result, "Tags without values should result in empty values")
    }

    @Test
    fun testNormalizeLineBasedFormatWithNonNumericTags() {
        val input =
            """
            35 D
            ABC INVALID
            49 SENDER
            """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|", result, "Non-numeric tags should be skipped")
    }

    @Test
    fun testNormalizeMixedFormatDefaultsToTraditional() {
        val input =
            """
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
        val input =
            """
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
            "Complex message with comments should parse correctly",
        )
    }

    @Test
    fun testNormalizeLineBasedFormatWithSpecialCharacters() {
        val input =
            """
            35 D
            58 Value with = sign
            55 EUR/USD
            100 EX-CHANGE
            """.trimIndent()

        val result = input.normalizeFixMessage()

        assertEquals(
            "35=D|58=Value with = sign|55=EUR/USD|100=EX-CHANGE|",
            result,
            "Special characters in values should be preserved",
        )
    }

    @Test
    fun testNormalizeLineBasedFormatWithTabsAndSpaces() {
        val input = "35\tD\n49    SENDER\n56\t  TARGET"

        val result = input.normalizeFixMessage()

        assertEquals("35=D|49=SENDER|56=TARGET|", result, "Tabs and multiple spaces should be handled as delimiters")
    }
}
