package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.ui.FixField
import com.knapsack.fixtool.ui.FixField.Companion.resolveTemplates
import org.junit.Test
import quickfix.Message
import quickfix.field.MsgType
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixMessageTemplateTest {
    // Helper to create a mock FIX message for testing
    private fun createMockFixMessage(messageType: String, vararg tagValues: Pair<Int, String>): FixMessage {
        val message = Message()
        message.header.setString(MsgType.FIELD, messageType)

        tagValues.forEach { (tag, value) ->
            message.setString(tag, value)
        }

        val rawMessage =
            "8=FIX.4.2|9=100|35=$messageType|" +
                tagValues.joinToString("|") { "${it.first}=${it.second}" } + "|10=123|"

        return FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = rawMessage,
            messageType = messageType,
            quickfixMessage = message,
        )
    }

    @Test
    fun testUuidRandomGeneration() {
        val template = "\${UUID.randomUUID()}"
        val result = FixMessageTemplate.evaluate(template)

        // Should be a valid UUID format (36 characters with dashes)
        assertTrue(result.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun testUuidGeneratesDifferentValues() {
        val template = "\${UUID.randomUUID()}"
        val result1 = FixMessageTemplate.evaluate(template)
        val result2 = FixMessageTemplate.evaluate(template)

        // Each evaluation should generate a different UUID
        assertTrue(result1 != result2)
    }

    @Test
    fun testLocalDateTimeNow() {
        val template = "\${LocalDateTime.now()}"
        val result = FixMessageTemplate.evaluate(template)

        // Should contain timestamp components
        assertTrue(result.isNotEmpty())
        assertTrue(result.contains("T") || result.contains("-"))
    }

    @Test
    fun testCustomTimestampFormat() {
        val template = "\${LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd\"))}"
        val result = FixMessageTemplate.evaluate(template)

        // Should match YYYYMMDD format
        assertTrue(result.matches(Regex("\\d{8}")))
    }

    @Test
    fun testCurrentTimeMillis() {
        val template = "\${System.currentTimeMillis()}"
        val result = FixMessageTemplate.evaluate(template)

        // Should be a valid timestamp (numeric)
        assertTrue(result.matches(Regex("\\d+")))

        // Should be a reasonable timestamp (after 2020)
        val timestamp = result.toLong()
        assertTrue(timestamp > 1577836800000L) // Jan 1, 2020
    }

    @Test
    fun testMultipleExpressionsInOneString() {
        val template = "QuoteReq-\${UUID.randomUUID()}-\${LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd\"))}"
        val result = FixMessageTemplate.evaluate(template)

        assertTrue(result.startsWith("QuoteReq-"))
        assertTrue(result.contains("-"))
        // Should contain a UUID and a date
        assertTrue(result.matches(Regex("QuoteReq-[0-9a-f-]+-\\d{8}")))
    }

    @Test
    fun testNoExpressionReturnsUnchanged() {
        val template = "Simple text without expressions"
        val result = FixMessageTemplate.evaluate(template)

        assertEquals("Simple text without expressions", result)
    }

    @Test
    fun testUnknownExpressionReturnsAsIs() {
        val template = "\${unknown.function()}"
        val result = FixMessageTemplate.evaluate(template)

        assertEquals("\${unknown.function()}", result)
    }

    @Test
    fun testInvalidExpressionReturnsAsIs() {
        val template = "\${this is not valid kotlin}"
        val result = FixMessageTemplate.evaluate(template)

        assertEquals("\${this is not valid kotlin}", result)
    }

    @Test
    fun testMixedContentWithExpressions() {
        val template = "Field has value: \${UUID.randomUUID()} and more text"
        val result = FixMessageTemplate.evaluate(template)

        assertTrue(result.startsWith("Field has value: "))
        assertTrue(result.endsWith(" and more text"))
        assertTrue(result.contains("-")) // UUID contains dashes
    }

    @Test
    fun testHasTemplateExpressionsDetectsExpressions() {
        assertTrue(FixMessageTemplate.hasTemplateExpressions("\${UUID.randomUUID()}"))
        assertTrue(FixMessageTemplate.hasTemplateExpressions("Text with \${LocalDateTime.now()} inside"))
        assertTrue(FixMessageTemplate.hasTemplateExpressions("Multiple \${UUID.randomUUID()} and \${System.currentTimeMillis()}"))
    }

    @Test
    fun testHasTemplateExpressionsReturnsFalseForPlainText() {
        assertFalse(FixMessageTemplate.hasTemplateExpressions("Plain text"))
        assertFalse(FixMessageTemplate.hasTemplateExpressions("Text with $ but no braces"))
        assertFalse(FixMessageTemplate.hasTemplateExpressions("Text with {braces} but no $"))
    }

    @Test
    fun testRealWorldFixMessageExample() {
        val template = "8=FIX.4.2|9=100|35=D|49=SENDER|56=TARGET|11=\${UUID.randomUUID()}|52=\${LocalDateTime.now()}|55=EUR/USD|10=123|"
        val result = FixMessageTemplate.evaluate(template)

        assertTrue(result.contains("8=FIX.4.2"))
        assertTrue(result.contains("35=D"))
        assertTrue(result.contains("11="))
        assertTrue(result.contains("52="))
        assertTrue(result.contains("|10=123|"))

        // Should have replaced the templates
        assertFalse(result.contains("\${UUID.randomUUID()}"))
        assertFalse(result.contains("\${LocalDateTime.now()}"))
    }

    @Test
    fun testEscapedBracesNotEvaluated() {
        // If the expression has malformed braces, it should be left as-is
        val template = "Text with \${ incomplete"
        val result = FixMessageTemplate.evaluate(template)

        assertEquals("Text with \${ incomplete", result)
    }

    @Test
    fun testStringConcatenation() {
        val template = "\${\"ORDER-\" + UUID.randomUUID()}"
        val result = FixMessageTemplate.evaluate(template)

        assertTrue(result.startsWith("ORDER-"))
        assertTrue(result.length > 6) // "ORDER-" + UUID
    }

    @Test
    fun testArithmeticExpression() {
        val template = "\${1 + 2 + 3}"
        val result = FixMessageTemplate.evaluate(template)

        assertEquals("6", result)
    }

    @Test
    fun testInstantNow() {
        val template = "\${Instant.now().toEpochMilli()}"
        val result = FixMessageTemplate.evaluate(template)

        // Should be a valid timestamp
        assertTrue(result.matches(Regex("\\d+")))
        val timestamp = result.toLong()
        assertTrue(timestamp > 1577836800000L)
    }

    @Test
    fun testValueOfTagFromHeader() {
        val message = createMockFixMessage("D", 11 to "ORDER123", 55 to "EUR/USD")
        val value = message.valueOfTag(11)

        assertEquals("ORDER123", value)
    }

    @Test
    fun testValueOfTagFromBody() {
        val message = createMockFixMessage("D", 55 to "EUR/USD", 54 to "1")
        val value = message.valueOfTag(55)

        assertEquals("EUR/USD", value)
    }

    @Test
    fun testValueOfTagMissingReturnsNull() {
        val message = createMockFixMessage("D", 11 to "ORDER123")
        val value = message.valueOfTag(999)

        assertNull(value)
    }

    @Test
    fun testTemplateWithIncomingMessageReference() {
        val incomingMessage = createMockFixMessage("D", 11 to "ORDER123", 55 to "EUR/USD")
        val incomingMap = mapOf("D" to incomingMessage)

        val template = "\${incoming[\"D\"].valueOfTag(11)}"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap)

        assertEquals("ORDER123", result)
    }

    @Test
    fun testTemplateWithOutgoingMessageReference() {
        val outgoingMessage = createMockFixMessage("R", 131 to "QUOTE456", 55 to "GBP/USD")
        val outgoingMap = mapOf("R" to outgoingMessage)

        val template = "\${outgoing[\"R\"].valueOfTag(131)}"
        val result = FixMessageTemplate.evaluate(template, outgoingMessages = outgoingMap)

        assertEquals("QUOTE456", result)
    }

    @Test
    fun testTemplateWithMissingMessageTypeReturnsNull() {
        val template = "\${incoming[\"X\"].valueOfTag(11)}"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = emptyMap())

        // Should return null when message type not found (MessageMap returns empty accessor)
        assertEquals("null", result)
    }

    @Test
    fun testTemplateWithMissingTagReturnsNull() {
        val incomingMessage = createMockFixMessage("D", 11 to "ORDER123")
        val incomingMap = mapOf("D" to incomingMessage)

        val template = "\${incoming[\"D\"].valueOfTag(999)}"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap)

        assertEquals("null", result)
    }

    @Test
    fun testCombinedTemplateWithMessageReferenceAndUuid() {
        val outgoingMessage = createMockFixMessage("D", 11 to "ORDER123", 55 to "EUR/USD")
        val outgoingMap = mapOf("D" to outgoingMessage)

        val template = "\${outgoing[\"D\"].valueOfTag(11)}-\${UUID.randomUUID()}"
        val result = FixMessageTemplate.evaluate(template, outgoingMessages = outgoingMap)

        assertTrue(result.startsWith("ORDER123-"))
        assertTrue(result.matches(Regex("ORDER123-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun testMultipleMessageReferencesInOneTemplate() {
        val incomingMessage = createMockFixMessage("R", 131 to "QUOTE456")
        val outgoingMessage = createMockFixMessage("D", 11 to "ORDER123")
        val incomingMap = mapOf("R" to incomingMessage)
        val outgoingMap = mapOf("D" to outgoingMessage)

        val template = "Response to \${incoming[\"R\"].valueOfTag(131)} for \${outgoing[\"D\"].valueOfTag(11)}"
        val result =
            FixMessageTemplate.evaluate(
                template,
                incomingMessages = incomingMap,
                outgoingMessages = outgoingMap,
            )

        assertEquals("Response to QUOTE456 for ORDER123", result)
    }

    @Test
    fun testRealWorldMessageReferenceExample() {
        // Simulate receiving a QuoteRequest with QuoteReqID
        val incomingQuoteRequest = createMockFixMessage("R", 131 to "QUOTEREQ-2025-001", 55 to "EUR/USD")
        val incomingMap = mapOf("R" to incomingQuoteRequest)

        // Template for QuoteResponse that references the incoming QuoteReqID
        val template =
            "8=FIX.4.2|9=100|35=AJ|131=\${incoming[\"R\"].valueOfTag(131)}|" +
                "55=\${incoming[\"R\"].valueOfTag(55)}|117=\${UUID.randomUUID()}|10=123|"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap)

        assertTrue(result.contains("131=QUOTEREQ-2025-001"))
        assertTrue(result.contains("55=EUR/USD"))
        assertTrue(result.contains("117="))
        assertFalse(result.contains("\${incoming"))
    }

    // ============ Variable Assignment Tests ============

    @Test
    fun testBasicVariableAssignment() {
        val template = "\${myVar = \"test123\"}"
        val result = FixMessageTemplate.evaluate(template)

        assertEquals("test123", result)
    }

    @Test
    fun testVariableAssignmentAndReuse() {
        val template = "11=\${orderId = UUID.randomUUID()}|37=\${orderId}"
        val result = FixMessageTemplate.evaluate(template)

        // Extract the two values
        val parts = result.split("|")
        val value1 = parts[0].substringAfter("=")
        val value2 = parts[1].substringAfter("=")

        // Both should be the same UUID
        assertEquals(value1, value2)
        assertTrue(value1.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun testMultipleVariableReuse() {
        val template = "11=\${id = UUID.randomUUID()}|37=\${id}|41=\${id}"
        val result = FixMessageTemplate.evaluate(template)

        // Extract all three values
        val parts = result.split("|")
        val value1 = parts[0].substringAfter("=")
        val value2 = parts[1].substringAfter("=")
        val value3 = parts[2].substringAfter("=")

        // All three should be the same
        assertEquals(value1, value2)
        assertEquals(value2, value3)
    }

    @Test
    fun testMultipleDifferentVariables() {
        val template = "\${var1 = \"AAA\"}|\${var2 = \"BBB\"}|\${var1}|\${var2}"
        val result = FixMessageTemplate.evaluate(template)

        assertEquals("AAA|BBB|AAA|BBB", result)
    }

    @Test
    fun testVariableWithUuid() {
        val template = "\${orderId = UUID.randomUUID()}|REUSE=\${orderId}"
        val result = FixMessageTemplate.evaluate(template)

        val parts = result.split("|")
        val uuid1 = parts[0]
        val uuid2 = parts[1].substringAfter("=")

        assertEquals(uuid1, uuid2)
        assertTrue(uuid1.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun testVariableWithTimestamp() {
        val template = "\${ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd\"))}|COPY=\${ts}"
        val result = FixMessageTemplate.evaluate(template)

        val parts = result.split("|")
        val date1 = parts[0]
        val date2 = parts[1].substringAfter("=")

        assertEquals(date1, date2)
        assertTrue(date1.matches(Regex("\\d{8}")))
    }

    @Test
    fun testVariableWithExpression() {
        val template = "\${sum = 1 + 2 + 3}|RESULT=\${sum}"
        val result = FixMessageTemplate.evaluate(template)

        assertEquals("6|RESULT=6", result)
    }

    @Test
    fun testUndefinedVariableReturnsAsIs() {
        val template = "\${undefinedVar}"
        val result = FixMessageTemplate.evaluate(template)

        assertEquals("\${undefinedVar}", result)
    }

    @Test
    fun testVariableDefinedAfterUseStillEvaluated() {
        // Variables are evaluated left-to-right, so undefined at first
        val template = "\${laterVar}|\${laterVar = \"VALUE\"}"
        val result = FixMessageTemplate.evaluate(template)

        // First occurrence should be unevaluated, second should be the value
        assertEquals("\${laterVar}|VALUE", result)
    }

    @Test
    fun testVariableWithIncomingMessageReference() {
        val incomingMessage = createMockFixMessage("D", 11 to "ORDER123")
        val incomingMap = mapOf("D" to incomingMessage)

        val template = "\${refId = incoming[\"D\"].valueOfTag(11)}|COPY=\${refId}"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap)

        assertEquals("ORDER123|COPY=ORDER123", result)
    }

    @Test
    fun testVariableWithOutgoingMessageReference() {
        val outgoingMessage = createMockFixMessage("R", 131 to "QUOTE456")
        val outgoingMap = mapOf("R" to outgoingMessage)

        val template = "\${quoteId = outgoing[\"R\"].valueOfTag(131)}|REF=\${quoteId}"
        val result = FixMessageTemplate.evaluate(template, outgoingMessages = outgoingMap)

        assertEquals("QUOTE456|REF=QUOTE456", result)
    }

    @Test
    fun testVariableNamesWithUnderscores() {
        val template = "\${my_var_name = \"test\"}|\${my_var_name}"
        val result = FixMessageTemplate.evaluate(template)

        assertEquals("test|test", result)
    }

    @Test
    fun testVariableNamesWithNumbers() {
        val template = "\${var123 = \"test\"}|\${var123}"
        val result = FixMessageTemplate.evaluate(template)

        assertEquals("test|test", result)
    }

    @Test
    fun testVariableAssignmentWithWhitespace() {
        val template = "\${  myVar   =   \"value\"  }|\${myVar}"
        val result = FixMessageTemplate.evaluate(template)

        assertEquals("value|value", result)
    }

    @Test
    fun testRealWorldFixMessageWithVariables() {
        val template =
            "8=FIX.4.2|9=100|35=D|49=SENDER|56=TARGET|11=\${orderId = UUID.randomUUID()}|" +
                "37=\${orderId}|41=\${orderId}|52=\${ts = LocalDateTime.now()}|60=\${ts}|10=123|"
        val result = FixMessageTemplate.evaluate(template)

        // Should not contain template expressions
        assertFalse(result.contains("\${"))

        // Should contain field tags
        assertTrue(result.contains("8=FIX.4.2"))
        assertTrue(result.contains("35=D"))
        assertTrue(result.contains("11="))
        assertTrue(result.contains("37="))
        assertTrue(result.contains("41="))

        // Extract orderIds and timestamps
        val parts = result.split("|")
        val orderId1 = parts.find { it.startsWith("11=") }?.substringAfter("=")
        val orderId2 = parts.find { it.startsWith("37=") }?.substringAfter("=")
        val orderId3 = parts.find { it.startsWith("41=") }?.substringAfter("=")
        val ts1 = parts.find { it.startsWith("52=") }?.substringAfter("=")
        val ts2 = parts.find { it.startsWith("60=") }?.substringAfter("=")

        // All orderIds should match
        assertEquals(orderId1, orderId2)
        assertEquals(orderId2, orderId3)

        // All timestamps should match
        assertEquals(ts1, ts2)
    }

    @Test
    fun testCombinedVariablesAndRegularExpressions() {
        val template = "\${id = UUID.randomUUID()}|NEW=\${UUID.randomUUID()}|COPY=\${id}"
        val result = FixMessageTemplate.evaluate(template)

        val parts = result.split("|")
        val id1 = parts[0]
        val id2 = parts[1].substringAfter("=")
        val id3 = parts[2].substringAfter("=")

        // First and third should match
        assertEquals(id1, id3)

        // Second should be different
        assertTrue(id1 != id2)

        // All should be valid UUIDs
        assertTrue(id1.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        assertTrue(id2.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun testVariableWithStringConcatenation() {
        val template = "\${prefix = \"ORDER-\" + UUID.randomUUID()}|COPY=\${prefix}"
        val result = FixMessageTemplate.evaluate(template)

        val parts = result.split("|")
        val value1 = parts[0]
        val value2 = parts[1].substringAfter("=")

        assertEquals(value1, value2)
        assertTrue(value1.startsWith("ORDER-"))
    }

    @Test
    fun testVariableReferenceInExpression() {
        val template = "\${base = 10}|\${doubled = base.toInt() * 2}"
        val result = FixMessageTemplate.evaluate(template)

        assertEquals("10|20", result)
    }

    @Test
    fun testSharedVariablesMapAcrossMultipleCalls() {
        // Simulates how MessageEditorPanel uses variables across multiple fields
        val variables = mutableMapOf<String, String>()

        // First field: assign variable
        val result1 = FixMessageTemplate.evaluate("\${orderId = UUID.randomUUID()}", variables = variables)

        // Second field: reuse variable
        val result2 = FixMessageTemplate.evaluate("\${orderId}", variables = variables)

        // Third field: reuse variable again
        val result3 = FixMessageTemplate.evaluate("\${orderId}", variables = variables)

        // All three should have the same value
        assertEquals(result1, result2)
        assertEquals(result2, result3)
        assertTrue(result1.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun testSharedVariablesWithMultipleVariables() {
        val variables = mutableMapOf<String, String>()

        val result1 = FixMessageTemplate.evaluate("\${id1 = UUID.randomUUID()}", variables = variables)
        val result2 = FixMessageTemplate.evaluate("\${id2 = UUID.randomUUID()}", variables = variables)
        val result3 = FixMessageTemplate.evaluate("\${id1}|\${id2}", variables = variables)

        // Should be: "uuid1|uuid2"
        assertEquals("$result1|$result2", result3)
    }

    // ============ MessageEditorPanel Integration Tests ============

    @Test
    fun testResolveTemplatesWithVariablesAcrossFields() {
        // Simulate message editor with multiple fields using the same variable
        val fields =
            listOf(
                FixField(tag = "131", value = "\${quoteReqId = UUID.randomUUID()}"),
                FixField(tag = "132", value = "\${quoteReqId}"),
                FixField(tag = "133", value = "\${quoteReqId}"),
            )

        val resolved = fields.resolveTemplates()

        // All three fields should have the same UUID value
        assertEquals(resolved[0].value, resolved[1].value)
        assertEquals(resolved[1].value, resolved[2].value)

        // Should be a valid UUID
        assertTrue(resolved[0].value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun testResolveTemplatesWithMultipleVariables() {
        val fields =
            listOf(
                FixField(tag = "131", value = "\${quoteReqId = UUID.randomUUID()}"),
                FixField(tag = "52", value = "\${timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd\"))}"),
                FixField(tag = "132", value = "\${quoteReqId}"),
                FixField(tag = "60", value = "\${timestamp}"),
                FixField(tag = "133", value = "\${quoteReqId}"),
            )

        val resolved = fields.resolveTemplates()

        // QuoteReqIds should match (tags 131, 132, 133)
        assertEquals(resolved[0].value, resolved[2].value)
        assertEquals(resolved[2].value, resolved[4].value)

        // Timestamps should match (tags 52, 60)
        assertEquals(resolved[1].value, resolved[3].value)

        // QuoteReqId and timestamp should be different
        assertNotEquals(resolved[0].value, resolved[1].value)

        // Validate formats
        assertTrue(resolved[0].value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        assertTrue(resolved[1].value.matches(Regex("\\d{8}")))
    }

    @Test
    fun testResolveTemplatesWithMixedFieldsAndVariables() {
        val fields =
            listOf(
                FixField(tag = "35", value = "R"), // Plain value
                FixField(tag = "131", value = "\${quoteReqId = UUID.randomUUID()}"), // Assign variable
                FixField(tag = "55", value = "EUR/USD"), // Plain value
                FixField(tag = "132", value = "\${quoteReqId}"), // Reuse variable
                FixField(tag = "54", value = "1"), // Plain value
                FixField(tag = "133", value = "\${quoteReqId}"), // Reuse variable again
            )

        val resolved = fields.resolveTemplates()

        // Plain values should remain unchanged
        assertEquals("R", resolved[0].value)
        assertEquals("EUR/USD", resolved[2].value)
        assertEquals("1", resolved[4].value)

        // Variable values should match
        assertEquals(resolved[1].value, resolved[3].value)
        assertEquals(resolved[3].value, resolved[5].value)

        assertTrue(resolved[1].value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun testResolveTemplatesWithVariableAndNewExpression() {
        // Test that regular expressions still generate new values each time
        val fields =
            listOf(
                FixField(tag = "131", value = "\${quoteReqId = UUID.randomUUID()}"),
                FixField(tag = "132", value = "\${quoteReqId}"),
                FixField(tag = "133", value = "\${UUID.randomUUID()}"), // New UUID without variable
                FixField(tag = "134", value = "\${quoteReqId}"),
            )

        val resolved = fields.resolveTemplates()

        // Tags 131, 132, 134 should have same value (variable)
        assertEquals(resolved[0].value, resolved[1].value)
        assertEquals(resolved[1].value, resolved[3].value)

        // Tag 133 should have different value (new expression)
        assertNotEquals(resolved[0].value, resolved[2].value)

        // All should be valid UUIDs
        assertTrue(resolved[0].value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        assertTrue(resolved[2].value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun testResolveTemplatesWithUndefinedVariable() {
        val fields =
            listOf(
                FixField(tag = "131", value = "\${undefinedVar}"), // Reference before assignment
                FixField(tag = "132", value = "\${quoteReqId = UUID.randomUUID()}"),
                FixField(tag = "133", value = "\${quoteReqId}"),
            )

        val resolved = fields.resolveTemplates()

        // Undefined variable should remain as template
        assertEquals("\${undefinedVar}", resolved[0].value)

        // Defined variables should work
        assertEquals(resolved[1].value, resolved[2].value)
        assertTrue(resolved[1].value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun testResolveTemplatesWithComplexExpressions() {
        val fields =
            listOf(
                FixField(tag = "131", value = "\${baseId = UUID.randomUUID()}"),
                FixField(tag = "132", value = "\${prefixedId = \"ORDER-\" + baseId}"),
                FixField(tag = "133", value = "\${baseId}"),
                FixField(tag = "134", value = "\${prefixedId}"),
            )

        val resolved = fields.resolveTemplates()

        // baseId should match
        assertEquals(resolved[0].value, resolved[2].value)

        // prefixedId should match and start with ORDER-
        assertEquals(resolved[1].value, resolved[3].value)
        assertTrue(resolved[1].value.startsWith("ORDER-"))

        // prefixedId should contain baseId
        assertTrue(resolved[1].value.contains(resolved[0].value))
    }

    @Test
    fun testResolveTemplatesWithIncomingMessageReference() {
        val incomingMessage = createMockFixMessage("D", 11 to "ORDER123", 55 to "EUR/USD")
        val incomingMap = mapOf("D" to incomingMessage)

        val fields =
            listOf(
                FixField(tag = "131", value = "\${orderId = incoming[\"D\"].valueOfTag(11)}"),
                FixField(tag = "132", value = "\${orderId}"),
                FixField(tag = "133", value = "\${orderId}"),
            )

        val resolved = fields.resolveTemplates(incomingMessages = incomingMap)

        // All should have the same value from the incoming message
        assertEquals("ORDER123", resolved[0].value)
        assertEquals("ORDER123", resolved[1].value)
        assertEquals("ORDER123", resolved[2].value)
    }

    @Test
    fun testResolveTemplatesRealWorldQuoteRequest() {
        // Simulates the exact use case: building a QuoteRequest with same ID in multiple tags
        val fields =
            listOf(
                FixField(tag = "35", value = "R"),
                FixField(tag = "131", value = "\${quoteReqId = UUID.randomUUID()}"),
                FixField(tag = "132", value = "\${quoteReqId}"),
                FixField(tag = "133", value = "\${quoteReqId}"),
                FixField(tag = "55", value = "EUR/USD"),
                FixField(tag = "52", value = "\${sendingTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}"),
                FixField(tag = "60", value = "\${sendingTime}"),
            )

        val resolved = fields.resolveTemplates()

        // MsgType and Symbol should be unchanged
        assertEquals("R", resolved[0].value)
        assertEquals("EUR/USD", resolved[4].value)

        // QuoteReqID should be the same in tags 131, 132, 133
        assertEquals(resolved[1].value, resolved[2].value)
        assertEquals(resolved[2].value, resolved[3].value)
        assertTrue(resolved[1].value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))

        // SendingTime and TransactTime should be the same (tags 52, 60)
        assertEquals(resolved[5].value, resolved[6].value)
        assertTrue(resolved[5].value.matches(Regex("\\d{8}-\\d{2}:\\d{2}:\\d{2}\\.\\d{3}")))

        // QuoteReqID and timestamp should be different
        assertNotEquals(resolved[1].value, resolved[5].value)
    }

    @Test
    fun testTemplateEvaluationResilientToSpecialCharactersInMessageData() {
        // This test verifies the fix for the script injection bug where unresolved template
        // expressions containing special characters ($, \, ") would corrupt the Kotlin script
        // engine and cause all subsequent template evaluations to fail.

        // Scenario: A malformed template fails to evaluate and returns the literal string.
        // This literal gets sent in a message and received back in a server response.
        // The response message containing the literal template syntax (${...}) is added to
        // the message maps. When building the next script, we must properly escape these
        // special characters to prevent breaking the Kotlin script syntax.

        // Create a message with values containing special characters that would break scripts:
        // - Template syntax: ${incoming["S"].valueOfTag(131)}
        // - Dollar signs, quotes, backslashes
        val messageWithSpecialChars =
            createMockFixMessage(
                "AI", // QuoteRequestReject
                131 to "\${incoming[\"S\"].valueOfTag(131)}", // Literal template syntax (from failed evaluation)
                117 to "UUID-\${UUID.randomUUID()}", // Literal template with UUID
                58 to "Error: Missing tag\\nPlease fix", // Backslashes
                372 to "AJ", // RefMsgType
            )

        // Add this message to the incoming messages map (simulating server response)
        val incomingMap = mapOf("AI" to messageWithSpecialChars)

        // Now try to evaluate a template that references this message
        // Before the fix, this would fail with script compilation errors because the
        // special characters in the message values would break the Kotlin script syntax
        val template = "\${incoming[\"AI\"].valueOfTag(131)}"
        val result =
            FixMessageTemplate.evaluate(
                template,
                incomingMessages = incomingMap,
            )

        // The template should successfully evaluate and return the escaped value
        // (not fail with script errors)
        assertEquals("\${incoming[\"S\"].valueOfTag(131)}", result)

        // Test with another field containing backslashes
        val template2 = "\${incoming[\"AI\"].valueOfTag(58)}"
        val result2 =
            FixMessageTemplate.evaluate(
                template2,
                incomingMessages = incomingMap,
            )
        assertEquals("Error: Missing tag\\nPlease fix", result2)

        // Test that UUID generation still works even with corrupted message data
        val template3 = "\${UUID.randomUUID()}"
        val result3 =
            FixMessageTemplate.evaluate(
                template3,
                incomingMessages = incomingMap,
            )
        assertTrue(result3.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))

        // Test that message references still work
        val template4 = "\${incoming[\"AI\"].valueOfTag(372)}"
        val result4 =
            FixMessageTemplate.evaluate(
                template4,
                incomingMessages = incomingMap,
            )
        assertEquals("AJ", result4)
    }

    @Test
    fun testTemplateEvaluationWithUserVariablesContainingSpecialChars() {
        // Test that user-defined variables containing special characters are properly escaped

        val variables = mutableMapOf<String, String>()

        // First, assign a variable with a normal value
        val fields1 =
            listOf(
                FixField(tag = "131", value = "\${quoteReqId = UUID.randomUUID()}"),
            )

        val resolved1 = fields1.resolveTemplates()
        val quoteReqId = resolved1[0].value
        assertTrue(quoteReqId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))

        // Now simulate that this variable's value got corrupted with special characters
        // (e.g., from a failed template that returned literal syntax)
        variables["corruptedVar"] = "\${incoming[\"S\"].valueOfTag(131)}" // Contains $, {, }, ", [, ]

        // Try to use this corrupted variable in a new template evaluation
        // Before the fix, this would break the script engine
        val template = "\${corruptedVar}"
        val result =
            FixMessageTemplate.evaluate(
                template,
                variables = variables,
            )

        // Should successfully return the escaped value
        assertEquals("\${incoming[\"S\"].valueOfTag(131)}", result)

        // And other templates should still work
        val template2 = "\${UUID.randomUUID()}"
        val result2 =
            FixMessageTemplate.evaluate(
                template2,
                variables = variables,
            )
        assertTrue(result2.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun testManyUndefinedVariablesDoesNotHang() {
        val variables = mutableMapOf<String, String>()

        // Test with many undefined variables (similar to user's QuoteRequest)
        val template = "35=R|131=\${var1}|11=\${var2}|69=\${var3}|126=\${var4}|423=\${var5}|453=\${var6}|448=\${var7}"

        val startTime = System.currentTimeMillis()
        val result = FixMessageTemplate.evaluate(template, variables = variables)
        val duration = System.currentTimeMillis() - startTime

        // Should complete quickly (under 5 seconds)
        assertTrue(duration < 5000, "Evaluation took too long: ${duration}ms")

        // Should return with unresolved variables
        assertTrue(result.contains("\${var1}"))
        assertTrue(result.contains("\${var2}"))
    }

    // ========== Tests for Repeating Groups Support ==========

    @Test
    fun testRepeatingTagAccessWithIndex() {
        // Create a Quote Request message with proper QuickFIX groups (NoLegs = 555)
        val message = Message()
        message.header.setString(quickfix.field.MsgType.FIELD, "R")
        message.setString(131, "quote-req-id")

        // Create first leg group
        val leg1 = quickfix.fix44.QuoteRequest.NoRelatedSym()
        leg1.setString(20013, "8c2b290c-f292-4ae9-af8f-66340623deec")
        leg1.setString(687, "1000")
        message.addGroup(leg1)

        // Create second leg group
        val leg2 = quickfix.fix44.QuoteRequest.NoRelatedSym()
        leg2.setString(20013, "d119dad0-2dd9-4ef3-af56-de9395d5b57e")
        leg2.setString(687, "2000")
        message.addGroup(leg2)

        val fixMessage =
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.4|9=100|35=R|131=quote-req-id|146=2|20013=...|10=123|",
                messageType = "R",
                quickfixMessage = message,
            )

        val incomingMap = mapOf("R" to fixMessage)

        // Test accessing first leg (index 0)
        val template1 = "\${incoming[\"R\"].valueOfTag(20013, 0)}"
        val result1 = FixMessageTemplate.evaluate(template1, incomingMessages = incomingMap)
        assertEquals("8c2b290c-f292-4ae9-af8f-66340623deec", result1)

        // Test accessing second leg (index 1)
        val template2 = "\${incoming[\"R\"].valueOfTag(20013, 1)}"
        val result2 = FixMessageTemplate.evaluate(template2, incomingMessages = incomingMap)
        assertEquals("d119dad0-2dd9-4ef3-af56-de9395d5b57e", result2)

        // Test accessing non-existent index (should return null)
        val template3 = "\${incoming[\"R\"].valueOfTag(20013, 2)}"
        val result3 = FixMessageTemplate.evaluate(template3, incomingMessages = incomingMap)
        assertEquals("null", result3)
    }

    @Test
    fun testRepeatingTagAccessBackwardsCompatibility() {
        // Create a message with groups - valueOfTag() without index should return first occurrence
        val message = Message()
        message.header.setString(quickfix.field.MsgType.FIELD, "R")
        message.setString(131, "quote-req-id")

        val leg1 = quickfix.fix44.QuoteRequest.NoRelatedSym()
        leg1.setString(20013, "first-value")
        message.addGroup(leg1)

        val leg2 = quickfix.fix44.QuoteRequest.NoRelatedSym()
        leg2.setString(20013, "second-value")
        message.addGroup(leg2)

        val fixMessage =
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.4|9=100|35=R|131=quote-req-id|146=2|...|10=123|",
                messageType = "R",
                quickfixMessage = message,
            )

        val incomingMap = mapOf("R" to fixMessage)

        // Test backwards compatibility: valueOfTag(tag) without index should return first value
        val template = "\${incoming[\"R\"].valueOfTag(20013)}"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap)
        assertEquals("first-value", result)
    }

    @Test
    fun testAllValuesOfTag() {
        // Create a message with 3 legs using proper groups
        val message = Message()
        message.header.setString(quickfix.field.MsgType.FIELD, "R")
        message.setString(131, "quote-req-id")

        val leg1 = quickfix.fix44.QuoteRequest.NoRelatedSym()
        leg1.setString(20013, "leg1-id")
        message.addGroup(leg1)

        val leg2 = quickfix.fix44.QuoteRequest.NoRelatedSym()
        leg2.setString(20013, "leg2-id")
        message.addGroup(leg2)

        val leg3 = quickfix.fix44.QuoteRequest.NoRelatedSym()
        leg3.setString(20013, "leg3-id")
        message.addGroup(leg3)

        val fixMessage =
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.4|9=100|35=R|131=quote-req-id|146=3|...|10=123|",
                messageType = "R",
                quickfixMessage = message,
            )

        val incomingMap = mapOf("R" to fixMessage)

        // Test getting all values as a list
        val template = "\${incoming[\"R\"].allValuesOfTag(20013)}"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap)
        assertEquals("[leg1-id, leg2-id, leg3-id]", result)

        // Test getting size of the list
        val template2 = "\${incoming[\"R\"].allValuesOfTag(20013).size}"
        val result2 = FixMessageTemplate.evaluate(template2, incomingMessages = incomingMap)
        assertEquals("3", result2)

        // Test accessing by index via allValuesOfTag
        val template3 = "\${incoming[\"R\"].allValuesOfTag(20013)[1]}"
        val result3 = FixMessageTemplate.evaluate(template3, incomingMessages = incomingMap)
        assertEquals("leg2-id", result3)
    }

    @Test
    fun testAllValuesOfTagForNonExistentTag() {
        val message = Message()
        message.header.setString(MsgType.FIELD, "R")
        message.setString(131, "single-value")

        val fixMessage =
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.4|9=100|35=R|131=single-value|10=123|",
                messageType = "R",
                quickfixMessage = message,
            )

        val incomingMap = mapOf("R" to fixMessage)

        // Test getting all values for non-existent tag (should return empty list)
        val template = "\${incoming[\"R\"].allValuesOfTag(99999)}"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap)
        assertEquals("[]", result)
    }

    @Test
    fun testRepeatingGroupsInRealWorldQuoteRequest() {
        // Simulate a real multi-leg Quote Request similar to user's example using proper groups
        val message = Message()
        message.header.setString(quickfix.field.MsgType.FIELD, "R")
        message.setString(131, "quote-req-id")

        // First leg
        val leg1 = quickfix.fix44.QuoteRequest.NoRelatedSym()
        leg1.setString(20013, "8c2b290c-f292-4ae9-af8f-66340623deec")
        leg1.setString(687, "1000")
        leg1.setString(556, "EUR")
        message.addGroup(leg1)

        // Second leg
        val leg2 = quickfix.fix44.QuoteRequest.NoRelatedSym()
        leg2.setString(20013, "d119dad0-2dd9-4ef3-af56-de9395d5b57e")
        leg2.setString(687, "2000")
        leg2.setString(556, "USD")
        message.addGroup(leg2)

        val fixMessage =
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.4|9=100|35=R|131=quote-req-id|146=2|...|10=123|",
                messageType = "R",
                quickfixMessage = message,
            )

        val incomingMap = mapOf("R" to fixMessage)

        // Build Quote response referencing both legs
        val template =
            "35=S|131=\${incoming[\"R\"].valueOfTag(131)}|" +
                "555=2|" +
                "20013=\${incoming[\"R\"].valueOfTag(20013, 0)}|687=\${incoming[\"R\"].valueOfTag(687, 0)}|" +
                "20013=\${incoming[\"R\"].valueOfTag(20013, 1)}|687=\${incoming[\"R\"].valueOfTag(687, 1)}"

        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap)

        assertEquals(
            "35=S|131=quote-req-id|555=2|" +
                "20013=8c2b290c-f292-4ae9-af8f-66340623deec|687=1000|" +
                "20013=d119dad0-2dd9-4ef3-af56-de9395d5b57e|687=2000",
            result,
        )
    }

    @Test
    fun testMixedSingleAndRepeatingTags() {
        // Message with both single tags (at message level) and repeating tags (in groups)
        val message = Message()
        message.header.setString(quickfix.field.MsgType.FIELD, "R")
        message.setString(131, "single-value") // Single tag at message level
        message.setString(11, "another-single") // Another single tag

        val leg1 = quickfix.fix44.QuoteRequest.NoRelatedSym()
        leg1.setString(20013, "repeat1")
        message.addGroup(leg1)

        val leg2 = quickfix.fix44.QuoteRequest.NoRelatedSym()
        leg2.setString(20013, "repeat2")
        message.addGroup(leg2)

        val fixMessage =
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.4|9=100|35=R|131=single-value|11=another-single|146=2|...|10=123|",
                messageType = "R",
                quickfixMessage = message,
            )

        val incomingMap = mapOf("R" to fixMessage)

        // Single tags should work as before
        val template1 = "\${incoming[\"R\"].valueOfTag(131)}"
        val result1 = FixMessageTemplate.evaluate(template1, incomingMessages = incomingMap)
        assertEquals("single-value", result1)

        // Repeating tags with index
        val template2 = "\${incoming[\"R\"].valueOfTag(20013, 0)}"
        val result2 = FixMessageTemplate.evaluate(template2, incomingMessages = incomingMap)
        assertEquals("repeat1", result2)

        val template3 = "\${incoming[\"R\"].valueOfTag(20013, 1)}"
        val result3 = FixMessageTemplate.evaluate(template3, incomingMessages = incomingMap)
        assertEquals("repeat2", result3)

        // Single tag accessed as list should have size 1
        val template4 = "\${incoming[\"R\"].allValuesOfTag(131).size}"
        val result4 = FixMessageTemplate.evaluate(template4, incomingMessages = incomingMap)
        assertEquals("1", result4)

        // Repeating tag accessed as list should have size 2
        val template5 = "\${incoming[\"R\"].allValuesOfTag(20013).size}"
        val result5 = FixMessageTemplate.evaluate(template5, incomingMessages = incomingMap)
        assertEquals("2", result5)
    }

    // ========================================
    // Template Expression Validation Tests
    // ========================================

    @Test
    fun testValidateExpressions_ValidExpressions_NoErrors() {
        // Given: A value with valid template expressions
        val value = "QuoteReq-\${UUID.randomUUID()}-\${System.currentTimeMillis()}"

        // When: Validating the expressions
        val errors = FixMessageTemplate.validateExpressions(value)

        // Then: Should return no errors
        assertTrue(errors.isEmpty(), "Valid expressions should not produce errors")
    }

    @Test
    fun testValidateExpressions_InvalidSyntax_ReturnsError() {
        // Given: A value with invalid Kotlin syntax
        val value = "\${this is not valid kotlin}"

        // When: Validating the expressions
        val errors = FixMessageTemplate.validateExpressions(value)

        // Then: Should return syntax error
        assertTrue(errors.isNotEmpty(), "Invalid syntax should produce errors")
        assertTrue(
            errors.any { it.contains("Template error") },
            "Error should indicate template error. Got: $errors",
        )
    }

    @Test
    fun testValidateExpressions_UndefinedVariable_ReturnsError() {
        // Given: A value referencing an undefined variable
        val value = "\${undefinedVar}"

        // When: Validating the expressions
        val errors = FixMessageTemplate.validateExpressions(value)

        // Then: Should return error about undefined variable
        assertTrue(errors.isNotEmpty(), "Undefined variable should produce errors")
        assertTrue(
            errors.any { it.contains("Undefined variable") },
            "Error should indicate undefined variable. Got: $errors",
        )
    }

    @Test
    fun testValidateExpressions_NullEvaluation_ReturnsError() {
        // Given: A value with an expression that evaluates to null
        val incomingMsg = createMockFixMessage("D")
        val incomingMap = mapOf("D" to incomingMsg)

        // Expression tries to access non-existent tag, which returns null
        val value = "\${incoming[\"D\"].valueOfTag(99999)}"

        // When: Validating the expressions
        val errors = FixMessageTemplate.validateExpressions(value, incomingMessages = incomingMap)

        // Then: Should return error about null evaluation
        assertTrue(errors.isNotEmpty(), "Null evaluation should produce errors")
        assertTrue(
            errors.any { it.contains("null") },
            "Error should indicate null evaluation. Got: $errors",
        )
    }

    @Test
    fun testValidateExpressions_MultipleErrors_ReturnsAllErrors() {
        // Given: A value with multiple invalid expressions
        val value = "Test-\${badSyntax!}-\${undefinedVar}-\${1 + 1}"

        // When: Validating the expressions
        val errors = FixMessageTemplate.validateExpressions(value)

        // Then: Should return multiple errors
        assertTrue(errors.size >= 2, "Should have at least 2 errors. Got: $errors")
    }

    @Test
    fun testValidateExpressions_MixedValidAndInvalid_ReturnsOnlyInvalidErrors() {
        // Given: A value with both valid and invalid expressions
        val value = "\${UUID.randomUUID()}-\${badSyntax!}"

        // When: Validating the expressions
        val errors = FixMessageTemplate.validateExpressions(value)

        // Then: Should return only errors for invalid expressions
        assertTrue(errors.size == 1, "Should have exactly 1 error. Got: $errors")
        assertTrue(
            errors[0].contains("badSyntax"),
            "Error should be about badSyntax. Got: ${errors[0]}",
        )
    }

    @Test
    fun testValidateExpressions_VariableAssignment_Valid() {
        // Given: A value with variable assignment
        val value = "\${myVar = UUID.randomUUID()}"

        // When: Validating the expressions
        val errors = FixMessageTemplate.validateExpressions(value)

        // Then: Should return no errors
        assertTrue(errors.isEmpty(), "Valid variable assignment should not produce errors. Got: $errors")
    }

    @Test
    fun testValidateExpressions_VariableAssignmentThenUse_Valid() {
        // Given: A value that assigns a variable and then uses it
        val value = "\${myVar = \"test\"} \${myVar}"

        // When: Validating the expressions
        val errors = FixMessageTemplate.validateExpressions(value)

        // Then: Should return no errors
        assertTrue(errors.isEmpty(), "Variable assignment and use should not produce errors. Got: $errors")
    }

    @Test
    fun testValidateExpressions_AssignmentEvaluatesNull_ReturnsError() {
        // Given: A value where variable assignment evaluates to null
        val incomingMsg = createMockFixMessage("D")
        val incomingMap = mapOf("D" to incomingMsg)

        val value = "\${myVar = incoming[\"D\"].valueOfTag(99999)}"

        // When: Validating the expressions
        val errors = FixMessageTemplate.validateExpressions(value, incomingMessages = incomingMap)

        // Then: Should return error about null evaluation
        assertTrue(errors.isNotEmpty(), "Null assignment should produce errors")
        assertTrue(
            errors.any { it.contains("null") },
            "Error should indicate null evaluation. Got: $errors",
        )
    }

    @Test
    fun testValidateExpressions_IncomingMessageAccess_Valid() {
        // Given: A value accessing incoming message fields
        val incomingMsg = createMockFixMessage("D", 38 to "1000", 44 to "100.50")
        val incomingMap = mapOf("D" to incomingMsg)

        val value = "Qty: \${incoming[\"D\"].valueOfTag(38)}, Price: \${incoming[\"D\"].valueOfTag(44)}"

        // When: Validating the expressions
        val errors = FixMessageTemplate.validateExpressions(value, incomingMessages = incomingMap)

        // Then: Should return no errors
        assertTrue(errors.isEmpty(), "Valid incoming message access should not produce errors. Got: $errors")
    }

    @Test
    fun testValidateExpressions_NoTemplateExpressions_NoErrors() {
        // Given: A value with no template expressions
        val value = "Just plain text with no expressions"

        // When: Validating the expressions
        val errors = FixMessageTemplate.validateExpressions(value)

        // Then: Should return no errors
        assertTrue(errors.isEmpty(), "Plain text should not produce errors")
    }

    @Test
    fun testValidateExpressions_EmptyString_NoErrors() {
        // Given: An empty value
        val value = ""

        // When: Validating the expressions
        val errors = FixMessageTemplate.validateExpressions(value)

        // Then: Should return no errors
        assertTrue(errors.isEmpty(), "Empty string should not produce errors")
    }

    @Test
    fun testValidateExpressions_ComplexExpression_Valid() {
        // Given: A value with complex but valid expression (arithmetic)
        val value = "\${(100 + 50) * 2 / 5}"

        // When: Validating the expressions
        val errors = FixMessageTemplate.validateExpressions(value)

        // Then: Should return no errors
        assertTrue(errors.isEmpty(), "Complex valid expression should not produce errors. Got: $errors")
    }

    @Test
    fun testValidateExpressions_RepeatingGroupAccess_Valid() {
        // Given: An incoming message with repeating groups
        val incomingMsg = createMockFixMessage("R", 131 to "quote1", 20013 to "value1")
        val incomingMap = mapOf("R" to incomingMsg)

        val value = "\${incoming[\"R\"].valueOfTag(20013, 0)}"

        // When: Validating the expressions
        val errors = FixMessageTemplate.validateExpressions(value, incomingMessages = incomingMap)

        // Then: Should return no errors
        assertTrue(errors.isEmpty(), "Valid repeating group access should not produce errors. Got: $errors")
    }

    @Test
    fun testValidateExpressions_SharedVariablesAcrossMultipleCalls() {
        // Given: Multiple field values where first field defines a variable and second uses it
        // This simulates how message editor validates fields sequentially
        val field1Value = "\${ordId = UUID.randomUUID().toString()}"
        val field2Value = "\${ordId}"

        // When: Validating with a shared variables map (like validateEditorMessage does)
        val sharedVariables = mutableMapOf<String, String>()
        val errors1 = FixMessageTemplate.validateExpressions(field1Value, variables = sharedVariables)
        val errors2 = FixMessageTemplate.validateExpressions(field2Value, variables = sharedVariables)

        // Then: Both should validate without errors
        assertTrue(errors1.isEmpty(), "Variable assignment should not produce errors. Got: $errors1")
        assertTrue(errors2.isEmpty(), "Variable reference should not produce errors when shared map is used. Got: $errors2")
        assertTrue(sharedVariables.containsKey("ordId"), "Variable should be stored in shared map")
    }

    @Test
    fun testValidateExpressions_SharedVariablesWithShorthandUuid() {
        // Given: Field with uuid shorthand assignment followed by field using that variable
        val field1Value = "\${ordId = uuid}"
        val field2Value = "\${ordId}"

        // When: Validating with a shared variables map
        val sharedVariables = mutableMapOf<String, String>()
        val errors1 = FixMessageTemplate.validateExpressions(field1Value, variables = sharedVariables)
        val errors2 = FixMessageTemplate.validateExpressions(field2Value, variables = sharedVariables)

        // Then: Both should validate without errors
        assertTrue(errors1.isEmpty(), "UUID shorthand assignment should not produce errors. Got: $errors1")
        assertTrue(errors2.isEmpty(), "Variable reference should not produce errors. Got: $errors2")
    }

    @Test
    fun testValidateExpressions_SharedVariablesWithTimestampOffset() {
        // Given: Field with timestamp offset assignment followed by field using that variable
        val field1Value = "\${expiry = now+1d}"
        val field2Value = "\${expiry}"

        // When: Validating with a shared variables map
        val sharedVariables = mutableMapOf<String, String>()
        val errors1 = FixMessageTemplate.validateExpressions(field1Value, variables = sharedVariables)
        val errors2 = FixMessageTemplate.validateExpressions(field2Value, variables = sharedVariables)

        // Then: Both should validate without errors
        assertTrue(errors1.isEmpty(), "Timestamp offset assignment should not produce errors. Got: $errors1")
        assertTrue(errors2.isEmpty(), "Variable reference should not produce errors. Got: $errors2")
    }

    @Test
    fun testValidateExpressions_WithoutSharedVariables_FailsOnUndefined() {
        // Given: Two separate validation calls WITHOUT shared variables
        val field1Value = "\${ordId = UUID.randomUUID().toString()}"
        val field2Value = "\${ordId}"

        // When: Validating WITHOUT a shared variables map (old buggy behavior)
        val errors1 = FixMessageTemplate.validateExpressions(field1Value)
        val errors2 = FixMessageTemplate.validateExpressions(field2Value)

        // Then: First field should pass, second should fail with undefined variable
        assertTrue(errors1.isEmpty(), "Variable assignment should not produce errors")
        assertTrue(errors2.isNotEmpty(), "Without shared variables, variable reference should fail")
        assertTrue(
            errors2.any { it.contains("Undefined variable") },
            "Error should indicate undefined variable. Got: $errors2",
        )
    }

    // ========================================
    // Shorthand Syntax Integration Tests
    // ========================================

    @Test
    fun testShorthandAutoDetectWithTagNumber() {
        // Given: An incoming message with ClOrdID
        val incomingMessage = createMockFixMessage("D", 11 to "ORDER123", 55 to "EUR/USD")
        val incomingMap = mapOf("D" to incomingMessage)

        // When: Using shorthand syntax ${D.11}
        val template = "\${D.11}"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap)

        // Then: Should resolve to the value from incoming message
        assertEquals("ORDER123", result)
    }

    @Test
    fun testShorthandAutoDetectWithTagName() {
        // Given: An incoming message with ClOrdID
        val incomingMessage = createMockFixMessage("D", 11 to "ORDER456", 55 to "GBP/USD")
        val incomingMap = mapOf("D" to incomingMessage)

        // When: Using shorthand syntax ${D.ClOrdID}
        val template = "\${D.ClOrdID}"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap)

        // Then: Should resolve ClOrdID (tag 11) to the value from incoming message
        assertEquals("ORDER456", result)
    }

    @Test
    fun testShorthandExplicitIncoming() {
        // Given: Both incoming and outgoing messages with same tag
        val incomingMessage = createMockFixMessage("D", 11 to "INCOMING-ORDER", 55 to "EUR/USD")
        val outgoingMessage = createMockFixMessage("D", 11 to "OUTGOING-ORDER", 55 to "GBP/USD")
        val incomingMap = mapOf("D" to incomingMessage)
        val outgoingMap = mapOf("D" to outgoingMessage)

        // When: Using explicit incoming shorthand ${in.D.11}
        val template = "\${in.D.11}"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap, outgoingMessages = outgoingMap)

        // Then: Should only get from incoming
        assertEquals("INCOMING-ORDER", result)
    }

    @Test
    fun testShorthandExplicitOutgoing() {
        // Given: Both incoming and outgoing messages with same tag
        val incomingMessage = createMockFixMessage("R", 131 to "INCOMING-QUOTE", 55 to "EUR/USD")
        val outgoingMessage = createMockFixMessage("R", 131 to "OUTGOING-QUOTE", 55 to "GBP/USD")
        val incomingMap = mapOf("R" to incomingMessage)
        val outgoingMap = mapOf("R" to outgoingMessage)

        // When: Using explicit outgoing shorthand ${out.R.131}
        val template = "\${out.R.131}"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap, outgoingMessages = outgoingMap)

        // Then: Should only get from outgoing
        assertEquals("OUTGOING-QUOTE", result)
    }

    @Test
    fun testShorthandAutoDetectFallsBackToOutgoing() {
        // Given: Only outgoing message exists
        val outgoingMessage = createMockFixMessage("D", 11 to "OUTGOING-ONLY", 55 to "USD/JPY")
        val outgoingMap = mapOf("D" to outgoingMessage)

        // When: Using auto-detect shorthand ${D.11} with no incoming
        val template = "\${D.11}"
        val result = FixMessageTemplate.evaluate(template, outgoingMessages = outgoingMap)

        // Then: Should fall back to outgoing message
        assertEquals("OUTGOING-ONLY", result)
    }

    @Test
    fun testShorthandWithTagNameSymbol() {
        // Given: An incoming message with Symbol
        val incomingMessage = createMockFixMessage("D", 11 to "ORDER789", 55 to "EUR/USD")
        val incomingMap = mapOf("D" to incomingMessage)

        // When: Using shorthand syntax ${D.Symbol}
        val template = "\${D.Symbol}"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap)

        // Then: Should resolve Symbol (tag 55) to the value
        assertEquals("EUR/USD", result)
    }

    @Test
    fun testShorthandWithTagNameQuoteReqID() {
        // Given: An incoming QuoteRequest
        val incomingMessage = createMockFixMessage("R", 131 to "QUOTE-REQ-001", 55 to "GBP/USD")
        val incomingMap = mapOf("R" to incomingMessage)

        // When: Using shorthand syntax ${R.QuoteReqID}
        val template = "\${R.QuoteReqID}"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap)

        // Then: Should resolve QuoteReqID (tag 131) to the value
        assertEquals("QUOTE-REQ-001", result)
    }

    @Test
    fun testShorthandMixedWithVerboseSyntax() {
        // Given: An incoming message
        val incomingMessage = createMockFixMessage("D", 11 to "ORDER-A", 55 to "EUR/USD")
        val incomingMap = mapOf("D" to incomingMessage)

        // When: Using both shorthand and verbose syntax in same template
        val template = "\${D.ClOrdID}-\${incoming[\"D\"].valueOfTag(55)}"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap)

        // Then: Both should resolve correctly
        assertEquals("ORDER-A-EUR/USD", result)
    }

    @Test
    fun testShorthandMixedWithUUID() {
        // Given: An incoming message
        val incomingMessage = createMockFixMessage("D", 11 to "BASE-ORDER", 55 to "EUR/USD")
        val incomingMap = mapOf("D" to incomingMessage)

        // When: Mixing shorthand with UUID
        val template = "\${D.ClOrdID}-\${UUID.randomUUID()}"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap)

        // Then: Shorthand resolves and UUID generates
        assertTrue(result.startsWith("BASE-ORDER-"))
        assertTrue(result.length > 20) // BASE-ORDER- + UUID
    }

    @Test
    fun testShorthandInResolveTemplates() {
        // Given: Fields with shorthand syntax and an incoming message
        val incomingMessage = createMockFixMessage("R", 131 to "QUOTE-REQ-XYZ", 55 to "EUR/USD")
        val incomingMap = mapOf("R" to incomingMessage)

        val fields =
            listOf(
                FixField(tag = "35", value = "AJ"),
                FixField(tag = "131", value = "\${R.QuoteReqID}"), // Shorthand with tag name
                FixField(tag = "55", value = "\${R.55}"), // Shorthand with tag number
                FixField(tag = "117", value = "QUOTE-\${UUID.randomUUID()}"),
            )

        // When: Resolving templates
        val resolved = fields.resolveTemplates(incomingMessages = incomingMap)

        // Then: Shorthand expressions should be resolved
        assertEquals("AJ", resolved[0].value)
        assertEquals("QUOTE-REQ-XYZ", resolved[1].value)
        assertEquals("EUR/USD", resolved[2].value)
        assertTrue(resolved[3].value.startsWith("QUOTE-"))
    }

    @Test
    fun testShorthandRealWorldQuoteResponse() {
        // Given: An incoming QuoteRequest (simulating real workflow)
        val quoteRequest =
            createMockFixMessage(
                "R",
                131 to "QUOTEREQ-001",
                55 to "EUR/USD",
                38 to "1000000",
            )
        val incomingMap = mapOf("R" to quoteRequest)

        // When: Building a QuoteResponse using shorthand syntax
        val fields =
            listOf(
                FixField(tag = "35", value = "AJ"),
                FixField(tag = "131", value = "\${R.QuoteReqID}"), // Reference request ID using tag name
                FixField(tag = "55", value = "\${R.Symbol}"), // Reference symbol using tag name
                FixField(tag = "117", value = "\${quoteId = UUID.randomUUID()}"),
                FixField(tag = "132", value = "1.0850"),
                FixField(tag = "133", value = "1.0852"),
            )

        val resolved = fields.resolveTemplates(incomingMessages = incomingMap)

        // Then: All shorthand references should resolve correctly
        assertEquals("AJ", resolved[0].value)
        assertEquals("QUOTEREQ-001", resolved[1].value)
        assertEquals("EUR/USD", resolved[2].value)
        assertTrue(resolved[3].value.matches(Regex("[0-9a-f-]+"))) // UUID
        assertEquals("1.0850", resolved[4].value)
        assertEquals("1.0852", resolved[5].value)
    }

    @Test
    fun testShorthandValidation() {
        // Given: A value with valid shorthand syntax
        val incomingMessage = createMockFixMessage("D", 11 to "ORDER123")
        val incomingMap = mapOf("D" to incomingMessage)

        val value = "\${D.ClOrdID}"

        // When: Validating the expression
        val errors = FixMessageTemplate.validateExpressions(value, incomingMessages = incomingMap)

        // Then: Should return no errors
        assertTrue(errors.isEmpty(), "Valid shorthand should not produce errors. Got: $errors")
    }

    @Test
    fun testShorthandValidationWithUnknownTagName() {
        // Given: A value with unknown tag name in explicit direction shorthand
        val value = "\${in.D.UnknownTagXYZ123}"

        // When: Validating the expression
        val errors = FixMessageTemplate.validateExpressions(value)

        // Then: Should return error about unknown tag
        assertTrue(errors.isNotEmpty(), "Unknown tag name should produce errors")
        assertTrue(errors.any { it.contains("UnknownTagXYZ123") }, "Error should mention the unknown tag. Got: $errors")
    }

    @Test
    fun testShorthandBackwardsCompatibility() {
        // Given: A message and both shorthand and verbose expressions
        val incomingMessage = createMockFixMessage("D", 11 to "ORDER999", 55 to "USD/JPY")
        val incomingMap = mapOf("D" to incomingMessage)

        // When: Using verbose syntax (should still work)
        val verboseTemplate = "\${incoming[\"D\"].valueOfTag(11)}"
        val verboseResult = FixMessageTemplate.evaluate(verboseTemplate, incomingMessages = incomingMap)

        // And: Using shorthand syntax
        val shorthandTemplate = "\${D.11}"
        val shorthandResult = FixMessageTemplate.evaluate(shorthandTemplate, incomingMessages = incomingMap)

        // Then: Both should produce the same result
        assertEquals("ORDER999", verboseResult)
        assertEquals("ORDER999", shorthandResult)
        assertEquals(verboseResult, shorthandResult)
    }
}
