package com.knapsack.fixtool.ui

import com.knapsack.fixtool.ui.FixField.Companion.parseCucumberTemplateFormat
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests for parsing Cucumber test template format in MessageEditorPanel.
 */
class CucumberTemplateParserTest {
    @Test
    fun testParseSimpleField() {
        val template = "[ListID]    66 = CREATE_AND_CAPTURE_AS: LIST_ID"
        val fields = parseCucumberTemplateFormat(template)

        assertEquals(1, fields.size)
        assertEquals("66", fields[0].tag)
        assertEquals("\${listId = UUID.randomUUID()}", fields[0].value)
    }

    @Test
    fun testParseFieldWithComment() {
        val template = "[BidType]   394 = 2 [Disclosed style]"
        val fields = parseCucumberTemplateFormat(template)

        assertEquals(1, fields.size)
        assertEquals("394", fields[0].tag)
        assertEquals("2", fields[0].value)
    }

    @Test
    fun testParseCapturedValue() {
        val template = "[ClOrdID]    11 = CAPTURED_VALUE: CL_ORD_ID"
        val fields = parseCucumberTemplateFormat(template)

        assertEquals(1, fields.size)
        assertEquals("11", fields[0].tag)
        assertEquals("\${clOrdId}", fields[0].value)
    }

    @Test
    fun testParseTemplateVariable() {
        val template = "[NoPartyIDs]   453 = <numOfPartyIds>"
        val fields = parseCucumberTemplateFormat(template)

        assertEquals(1, fields.size)
        assertEquals("453", fields[0].tag)
        assertEquals("", fields[0].value)
    }

    @Test
    fun testParseMatchesRegex() {
        val template = "[OrderID]    37 = MATCHES_REGEX: .*"
        val fields = parseCucumberTemplateFormat(template)

        assertEquals(1, fields.size)
        assertEquals("37", fields[0].tag)
        assertEquals("", fields[0].value)
    }

    @Test
    fun testSkipCommentLines() {
        val template =
            """
            ######### FIRST LEG #########
            [ListID]    66 = CREATE_AND_CAPTURE_AS: LIST_ID
            ######### SECOND LEG #########
            [BidType]   394 = 2
            """.trimIndent()

        val fields = parseCucumberTemplateFormat(template)

        assertEquals(2, fields.size)
        assertEquals("66", fields[0].tag)
        assertEquals("394", fields[1].tag)
    }

    @Test
    fun testIncludeFieldsInConditionalBlocks() {
        val template =
            """
            [ListID]    66 = CREATE_AND_CAPTURE_AS: LIST_ID
            @@includeIf:<hasZeroPartyIDs>
            [PartyID]   448 = ADTESTF1
            [PartyRole]   452 = 13
            @@/includeIf
            [BidType]   394 = 2
            """.trimIndent()

        val fields = parseCucumberTemplateFormat(template)

        // Should include all fields, just skip the @@includeIf directives
        assertEquals(4, fields.size)
        assertEquals("66", fields[0].tag)
        assertEquals("448", fields[1].tag)
        assertEquals("452", fields[2].tag)
        assertEquals("394", fields[3].tag)
    }

    @Test
    fun testVariableNameConversion() {
        val testCases =
            listOf(
                "LIST_ID" to "listId",
                "CL_ORD_ID" to "clOrdId",
                "PARTY_ID" to "partyId",
                "NO_PARTY_IDS" to "noPartyIds",
                "SINGLE" to "single",
            )

        for ((snakeCase, expectedCamelCase) in testCases) {
            val template = "[Field]    99 = CREATE_AND_CAPTURE_AS: $snakeCase"
            val fields = parseCucumberTemplateFormat(template)

            assertEquals("\${$expectedCamelCase = UUID.randomUUID()}", fields[0].value)
        }
    }

    @Test
    fun testCompleteRealWorldExample() {
        val template =
            """
            [ListID]    66 = CREATE_AND_CAPTURE_AS: LIST_ID
            [BidType]   394 = 2 [Disclosed style]
            [ListExecInst]    69 = CONTINGENT [Contingent]
            [TotNoOrders]    68 = 2
            [NoOrders]    73 = 2

            ######### FIRST LEG #########
            [ClOrdID]    11 = CREATE_AND_CAPTURE_AS: CL_ORD_ID
            [ListSeqNo]    67 = 1
            [NoPartyIDs]   453 = 4
            ######### PARTY #########
            [PartyID]   448 = ADTESTF1
            [PartyIDSource]   447 = D [Proprietary/Custom]
            [PartyRole]   452 = 13 [Order Origination Firm]

            @@includeIf:!<hasZeroPartyIDs>
            [PartyID]   448 = ADTESTF1
            [PartyIDSource]   447 = D
            @@/includeIf

            [SecurityType]   167 = REPO
            [StartDate]   916 = 20310108
            [Side]    54 = 1 [Buy]

            ######### SECOND LEG #########
            [ClOrdID]    11 = CAPTURED_VALUE: CL_ORD_ID
            [ListSeqNo]    67 = 2
            [NoPartyIDs]   453 = <numOfPartyIds>

            @@includeIf:<hasInitiatorParty>
            [PartyID]   448 = PARTY1
            [PartyRole]   452 = 13
            @@/includeIf

            [Side]    54 = 1
            [OrderQty]    38 = 2000
            [Text]    58 = <errorMessage>
            """.trimIndent()

        val fields = parseCucumberTemplateFormat(template)

        // Verify all non-comment fields are parsed (including those in conditional blocks)
        val expectedTags =
            listOf(
                "66",
                "394",
                "69",
                "68",
                "73",
                "11",
                "67",
                "453",
                "448",
                "447",
                "452",
                "448", // Inside @@includeIf:!<hasZeroPartyIDs>
                "447", // Inside @@includeIf:!<hasZeroPartyIDs>
                "167",
                "916",
                "54",
                "11",
                "67",
                "453",
                "448", // Inside @@includeIf:<hasInitiatorParty>
                "452", // Inside @@includeIf:<hasInitiatorParty>
                "54",
                "38",
                "58",
            )

        assertEquals(expectedTags.size, fields.size)
        expectedTags.forEachIndexed { index, expectedTag ->
            assertEquals(expectedTag, fields[index].tag, "Tag at index $index should be $expectedTag")
        }

        // Verify specific value transformations
        assertEquals("\${listId = UUID.randomUUID()}", fields[0].value) // LIST_ID
        assertEquals("2", fields[1].value) // BidType (comment stripped)
        assertEquals("CONTINGENT", fields[2].value) // ListExecInst (comment stripped)
        assertEquals("\${clOrdId = UUID.randomUUID()}", fields[5].value) // First CL_ORD_ID
        assertEquals("\${clOrdId}", fields[16].value) // Second CL_ORD_ID (captured)
        assertEquals("", fields[18].value) // <numOfPartyIds> template variable
        assertEquals("", fields[23].value) // <errorMessage> template variable
    }

    @Test
    fun testMultipleCapturedValues() {
        val template =
            """
            [ListID]    66 = CREATE_AND_CAPTURE_AS: LIST_ID
            [ClOrdID]    11 = CREATE_AND_CAPTURE_AS: CL_ORD_ID
            [ListID]    66 = CAPTURED_VALUE: LIST_ID
            [ClOrdID]    11 = CAPTURED_VALUE: CL_ORD_ID
            """.trimIndent()

        val fields = parseCucumberTemplateFormat(template)

        assertEquals(4, fields.size)
        assertEquals("\${listId = UUID.randomUUID()}", fields[0].value)
        assertEquals("\${clOrdId = UUID.randomUUID()}", fields[1].value)
        assertEquals("\${listId}", fields[2].value)
        assertEquals("\${clOrdId}", fields[3].value)
    }

    @Test
    fun testEmptyInput() {
        val fields = parseCucumberTemplateFormat("")

        assertEquals(1, fields.size) // Returns single empty field
        assertEquals("", fields[0].tag)
        assertEquals("", fields[0].value)
    }

    @Test
    fun testOnlyComments() {
        val template =
            """
            ######### COMMENT 1 #########
            ######### COMMENT 2 #########
            """.trimIndent()

        val fields = parseCucumberTemplateFormat(template)

        assertEquals(1, fields.size) // Returns single empty field when no valid fields found
    }

    @Test
    fun testNestedConditionalBlocks() {
        val template =
            """
            [ListID]    66 = 1
            @@includeIf:<condition1>
            [Field1]   100 = VALUE1
            @@includeIf:<condition2>
            [Field2]   101 = VALUE2
            @@/includeIf
            [Field3]   102 = VALUE3
            @@/includeIf
            [BidType]   394 = 2
            """.trimIndent()

        val fields = parseCucumberTemplateFormat(template)

        // All fields should be included, only @@includeIf directives are skipped
        assertEquals(5, fields.size)
        assertEquals("66", fields[0].tag)
        assertEquals("100", fields[1].tag)
        assertEquals("101", fields[2].tag)
        assertEquals("102", fields[3].tag)
        assertEquals("394", fields[4].tag)
    }

    @Test
    fun testFieldWithMultipleSpaces() {
        val template = "[ListID]         66      =      CREATE_AND_CAPTURE_AS: LIST_ID      "
        val fields = parseCucumberTemplateFormat(template)

        assertEquals(1, fields.size)
        assertEquals("66", fields[0].tag)
        assertEquals("\${listId = UUID.randomUUID()}", fields[0].value)
    }

    @Test
    fun testValueWithoutComment() {
        val template =
            """
            [Side]    54 = 1
            [StartDate]   916 = 20310108
            [PartyID]   448 = ADTESTF1
            """.trimIndent()

        val fields = parseCucumberTemplateFormat(template)

        assertEquals(3, fields.size)
        assertEquals("1", fields[0].value)
        assertEquals("20310108", fields[1].value)
        assertEquals("ADTESTF1", fields[2].value)
    }
}
