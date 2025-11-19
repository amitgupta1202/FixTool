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
}
