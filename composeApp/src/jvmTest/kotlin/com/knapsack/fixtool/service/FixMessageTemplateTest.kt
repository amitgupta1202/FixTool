package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import org.junit.Test
import quickfix.Message
import quickfix.field.MsgType
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

        val rawMessage = "8=FIX.4.2|9=100|35=$messageType|" +
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
        val result = FixMessageTemplate.evaluate(
            template,
            incomingMessages = incomingMap,
            outgoingMessages = outgoingMap
        )

        assertEquals("Response to QUOTE456 for ORDER123", result)
    }

    @Test
    fun testRealWorldMessageReferenceExample() {
        // Simulate receiving a QuoteRequest with QuoteReqID
        val incomingQuoteRequest = createMockFixMessage("R", 131 to "QUOTEREQ-2025-001", 55 to "EUR/USD")
        val incomingMap = mapOf("R" to incomingQuoteRequest)

        // Template for QuoteResponse that references the incoming QuoteReqID
        val template = "8=FIX.4.2|9=100|35=AJ|131=\${incoming[\"R\"].valueOfTag(131)}|55=\${incoming[\"R\"].valueOfTag(55)}|117=\${UUID.randomUUID()}|10=123|"
        val result = FixMessageTemplate.evaluate(template, incomingMessages = incomingMap)

        assertTrue(result.contains("131=QUOTEREQ-2025-001"))
        assertTrue(result.contains("55=EUR/USD"))
        assertTrue(result.contains("117="))
        assertFalse(result.contains("\${incoming"))
    }
}
