package com.knapsack.fixtool.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixMessageTemplateTest {

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
    fun testTimestampGeneration() {
        val template = "\${timestamp()}"
        val result = FixMessageTemplate.evaluate(template)

        // Should match FIX timestamp format: YYYYMMDD-HH:MM:SS.sss
        assertTrue(result.matches(Regex("\\d{8}-\\d{2}:\\d{2}:\\d{2}\\.\\d{3}")))
    }

    @Test
    fun testNowIsAliasForTimestamp() {
        val template = "\${now()}"
        val result = FixMessageTemplate.evaluate(template)

        // Should match FIX timestamp format
        assertTrue(result.matches(Regex("\\d{8}-\\d{2}:\\d{2}:\\d{2}\\.\\d{3}")))
    }

    @Test
    fun testCustomTimestampFormat() {
        val template = "\${timestamp(\"yyyyMMdd\")}"
        val result = FixMessageTemplate.evaluate(template)

        // Should match YYYYMMDD format
        assertTrue(result.matches(Regex("\\d{8}")))
    }

    @Test
    fun testCustomTimestampFormatWithSingleQuotes() {
        val template = "\${timestamp('HHmmss')}"
        val result = FixMessageTemplate.evaluate(template)

        // Should match HHmmss format
        assertTrue(result.matches(Regex("\\d{6}")))
    }

    @Test
    fun testCurrentTimeMillis() {
        val template = "\${currentTimeMillis()}"
        val result = FixMessageTemplate.evaluate(template)

        // Should be a valid timestamp (numeric)
        assertTrue(result.matches(Regex("\\d+")))

        // Should be a reasonable timestamp (after 2020)
        val timestamp = result.toLong()
        assertTrue(timestamp > 1577836800000L) // Jan 1, 2020
    }

    @Test
    fun testMultipleExpressionsInOneString() {
        val template = "QuoteReq-\${UUID.randomUUID()}-\${timestamp(\"yyyyMMdd\")}"
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
        val template = "\${timestamp(\"invalid format\")}"
        val result = FixMessageTemplate.evaluate(template)

        assertEquals("\${timestamp(\"invalid format\")}", result)
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
        assertTrue(FixMessageTemplate.hasTemplateExpressions("Text with \${timestamp()} inside"))
        assertTrue(FixMessageTemplate.hasTemplateExpressions("Multiple \${now()} and \${currentTimeMillis()}"))
    }

    @Test
    fun testHasTemplateExpressionsReturnsFalseForPlainText() {
        assertFalse(FixMessageTemplate.hasTemplateExpressions("Plain text"))
        assertFalse(FixMessageTemplate.hasTemplateExpressions("Text with $ but no braces"))
        assertFalse(FixMessageTemplate.hasTemplateExpressions("Text with {braces} but no $"))
    }

    @Test
    fun testRealWorldFixMessageExample() {
        val template = "8=FIX.4.2|9=100|35=D|49=SENDER|56=TARGET|11=\${UUID.randomUUID()}|52=\${timestamp()}|55=EUR/USD|10=123|"
        val result = FixMessageTemplate.evaluate(template)

        assertTrue(result.contains("8=FIX.4.2"))
        assertTrue(result.contains("35=D"))
        assertTrue(result.contains("11="))
        assertTrue(result.contains("52="))
        assertTrue(result.contains("|10=123|"))

        // Should have replaced the templates
        assertFalse(result.contains("\${UUID.randomUUID()}"))
        assertFalse(result.contains("\${timestamp()}"))
    }

    @Test
    fun testEscapedBracesNotEvaluated() {
        // If the expression has malformed braces, it should be left as-is
        val template = "Text with \${ incomplete"
        val result = FixMessageTemplate.evaluate(template)

        assertEquals("Text with \${ incomplete", result)
    }

    @Test
    fun testMultipleTimestampsGeneratedSequentially() {
        val template = "\${timestamp()}|\${timestamp()}|\${timestamp()}"
        val result = FixMessageTemplate.evaluate(template)

        // All timestamps should be valid
        val parts = result.split("|")
        assertEquals(3, parts.size)
        parts.forEach { part ->
            assertTrue(part.matches(Regex("\\d{8}-\\d{2}:\\d{2}:\\d{2}\\.\\d{3}")))
        }
    }
}
