package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the ShorthandTemplateExpander that converts shorthand syntax
 * like ${D.11} to full verbose syntax like ${incoming["D"].valueOfTag(11)}.
 */
class ShorthandTemplateExpanderTest {

    // ===== Basic Expansion Tests =====

    @Test
    fun `test auto-detect with tag number expands correctly`() {
        val template = "\${D.11}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${(incoming[\"D\"].valueOfTag(11) ?: outgoing[\"D\"].valueOfTag(11))}", expanded)
    }

    @Test
    fun `test auto-detect with common tag name expands correctly`() {
        val template = "\${D.ClOrdID}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        // ClOrdID is tag 11 in common tags
        assertEquals("\${(incoming[\"D\"].valueOfTag(11) ?: outgoing[\"D\"].valueOfTag(11))}", expanded)
    }

    @Test
    fun `test explicit incoming with tag number expands correctly`() {
        val template = "\${in.D.11}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${incoming[\"D\"].valueOfTag(11)}", expanded)
    }

    @Test
    fun `test explicit outgoing with tag number expands correctly`() {
        val template = "\${out.R.131}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${outgoing[\"R\"].valueOfTag(131)}", expanded)
    }

    @Test
    fun `test explicit incoming with tag name expands correctly`() {
        val template = "\${in.D.ClOrdID}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${incoming[\"D\"].valueOfTag(11)}", expanded)
    }

    @Test
    fun `test explicit outgoing with tag name expands correctly`() {
        val template = "\${out.R.QuoteReqID}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        // QuoteReqID is tag 131 in common tags
        assertEquals("\${outgoing[\"R\"].valueOfTag(131)}", expanded)
    }

    // ===== Repeating Group Index Tests =====

    @Test
    fun `test auto-detect with repeating group index expands correctly`() {
        val template = "\${D.11.0}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${(incoming[\"D\"].valueOfTag(11, 0) ?: outgoing[\"D\"].valueOfTag(11, 0))}", expanded)
    }

    @Test
    fun `test explicit incoming with repeating group index expands correctly`() {
        val template = "\${in.R.20013.1}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${incoming[\"R\"].valueOfTag(20013, 1)}", expanded)
    }

    @Test
    fun `test explicit outgoing with repeating group index expands correctly`() {
        val template = "\${out.D.55.2}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${outgoing[\"D\"].valueOfTag(55, 2)}", expanded)
    }

    // ===== Backwards Compatibility Tests =====

    @Test
    fun `test verbose syntax is unchanged`() {
        val template = "\${incoming[\"D\"].valueOfTag(11)}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals(template, expanded)
    }

    @Test
    fun `test UUID expression is unchanged`() {
        val template = "\${UUID.randomUUID()}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals(template, expanded)
    }

    @Test
    fun `test LocalDateTime expression is unchanged`() {
        val template = "\${LocalDateTime.now()}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals(template, expanded)
    }

    @Test
    fun `test System currentTimeMillis is unchanged`() {
        val template = "\${System.currentTimeMillis()}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals(template, expanded)
    }

    @Test
    fun `test Instant now is unchanged`() {
        val template = "\${Instant.now()}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals(template, expanded)
    }

    @Test
    fun `test variable assignment is unchanged`() {
        val template = "\${orderId = UUID.randomUUID()}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals(template, expanded)
    }

    @Test
    fun `test simple variable reference is unchanged`() {
        val template = "\${orderId}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals(template, expanded)
    }

    // ===== Mixed Content Tests =====

    @Test
    fun `test multiple shorthands in one string`() {
        val template = "Order: \${D.11}, Quote: \${R.131}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("incoming[\"D\"].valueOfTag(11)"))
        assertTrue(expanded.contains("incoming[\"R\"].valueOfTag(131)"))
    }

    @Test
    fun `test mixed shorthand and verbose in one string`() {
        val template = "\${D.11} and \${incoming[\"R\"].valueOfTag(131)}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("incoming[\"D\"].valueOfTag(11)"))
        assertTrue(expanded.contains("incoming[\"R\"].valueOfTag(131)"))
    }

    @Test
    fun `test shorthand with text prefix and suffix`() {
        val template = "ORDER-\${D.11}-SUFFIX"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("ORDER-\${(incoming[\"D\"].valueOfTag(11) ?: outgoing[\"D\"].valueOfTag(11))}-SUFFIX", expanded)
    }

    @Test
    fun `test mixed shorthand and UUID`() {
        val template = "\${UUID.randomUUID()}-\${D.ClOrdID}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("UUID.randomUUID()"))
        assertTrue(expanded.contains("incoming[\"D\"].valueOfTag(11)"))
    }

    // ===== Whitespace Handling Tests =====

    @Test
    fun `test shorthand with leading whitespace`() {
        val template = "\${ D.11}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("incoming[\"D\"].valueOfTag(11)"))
    }

    @Test
    fun `test shorthand with trailing whitespace`() {
        val template = "\${D.11 }"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("incoming[\"D\"].valueOfTag(11)"))
    }

    @Test
    fun `test shorthand with surrounding whitespace`() {
        val template = "\${ in.D.11 }"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("incoming[\"D\"].valueOfTag(11)"))
    }

    // ===== Tag Name Resolution Tests =====

    @Test
    fun `test common tag name OrderID resolves correctly`() {
        val template = "\${D.OrderID}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        // OrderID is tag 37
        assertTrue(expanded.contains("valueOfTag(37)"))
    }

    @Test
    fun `test common tag name Symbol resolves correctly`() {
        val template = "\${D.Symbol}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        // Symbol is tag 55
        assertTrue(expanded.contains("valueOfTag(55)"))
    }

    @Test
    fun `test common tag name Side resolves correctly`() {
        val template = "\${D.Side}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        // Side is tag 54
        assertTrue(expanded.contains("valueOfTag(54)"))
    }

    @Test
    fun `test common tag name Price resolves correctly`() {
        val template = "\${8.Price}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        // Price is tag 44
        assertTrue(expanded.contains("valueOfTag(44)"))
    }

    @Test
    fun `test unknown tag name is not expanded`() {
        // UnknownTagXYZ is not a known tag name
        val template = "\${D.UnknownTagXYZ}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        // Should remain unchanged as it's not a valid shorthand
        assertEquals(template, expanded)
    }

    // ===== Edge Cases =====

    @Test
    fun `test empty expression is unchanged`() {
        val template = "\${}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals(template, expanded)
    }

    @Test
    fun `test string without expressions is unchanged`() {
        val template = "Just plain text"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals(template, expanded)
    }

    @Test
    fun `test numeric message type`() {
        val template = "\${8.11}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        // 8 is ExecutionReport message type
        assertTrue(expanded.contains("incoming[\"8\"].valueOfTag(11)"))
    }

    @Test
    fun `test single character message type`() {
        val template = "\${D.55}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("incoming[\"D\"].valueOfTag(55)"))
    }

    @Test
    fun `test two character message type`() {
        val template = "\${AJ.117}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        // AJ is QuoteStatusReport
        assertTrue(expanded.contains("incoming[\"AJ\"].valueOfTag(117)"))
    }

    // ===== Validation Tests =====

    @Test
    fun `test validation returns empty list for valid shorthand`() {
        val template = "\${D.11}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `test validation returns empty list for valid tag name`() {
        val template = "\${D.ClOrdID}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `test validation returns error for unknown tag name with explicit direction`() {
        val template = "\${in.D.UnknownTagXYZ}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.isNotEmpty())
        assertTrue(errors[0].contains("UnknownTagXYZ"))
    }

    @Test
    fun `test validation returns empty for non-shorthand expressions`() {
        val template = "\${UUID.randomUUID()}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.isEmpty())
    }

    // ===== UUID Shorthand Tests =====

    @Test
    fun `test uuid shorthand expands correctly`() {
        val template = "\${uuid}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${UUID.randomUUID().toString()}", expanded)
    }

    @Test
    fun `test UUID shorthand case insensitive`() {
        val template = "\${UUID}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${UUID.randomUUID().toString()}", expanded)
    }

    @Test
    fun `test Uuid shorthand mixed case`() {
        val template = "\${Uuid}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${UUID.randomUUID().toString()}", expanded)
    }

    @Test
    fun `test uuid shorthand with whitespace`() {
        val template = "\${  uuid  }"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${UUID.randomUUID().toString()}", expanded)
    }

    // ===== Timestamp Shorthand Tests =====

    @Test
    fun `test now shorthand expands to FIX timestamp format`() {
        val template = "\${now}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test NOW shorthand case insensitive`() {
        val template = "\${NOW}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test Now shorthand mixed case`() {
        val template = "\${Now}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now shorthand with whitespace`() {
        val template = "\${  now  }"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    // ===== Custom Timestamp Format Tests =====

    @Test
    fun `test now with custom date only format`() {
        val template = "\${now:yyyyMMdd}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd\"))}", expanded)
    }

    @Test
    fun `test now with custom ISO format`() {
        val template = "\${now:yyyy-MM-dd'T'HH:mm:ss}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyy-MM-dd'T'HH:mm:ss\"))}", expanded)
    }

    @Test
    fun `test now with time only format`() {
        val template = "\${now:HH:mm:ss}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"HH:mm:ss\"))}", expanded)
    }

    @Test
    fun `test NOW with custom format case insensitive`() {
        val template = "\${NOW:yyyyMMdd-HH:mm:ss}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss\"))}", expanded)
    }

    @Test
    fun `test now with slash date format`() {
        val template = "\${now:yyyy/MM/dd}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyy/MM/dd\"))}", expanded)
    }

    // ===== Combined Tests =====

    @Test
    fun `test multiple uuid and now in same template`() {
        val template = "ID-\${uuid}-\${now:yyyyMMdd}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("UUID.randomUUID().toString()"))
        assertTrue(expanded.contains("DateTimeFormatter.ofPattern(\"yyyyMMdd\")"))
    }

    @Test
    fun `test uuid now and message reference combined`() {
        val template = "\${uuid}-\${D.11}-\${now}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("UUID.randomUUID().toString()"))
        assertTrue(expanded.contains("valueOfTag(11)"))
        assertTrue(expanded.contains("DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\")"))
    }

    // ===== Variable Assignment Tests =====

    @Test
    fun `test uuid assigned to different variable name works`() {
        // User assigns uuid to a variable with different name - this should work
        val template = "\${myId = UUID.randomUUID()}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        // Should pass through unchanged (not shorthand)
        assertEquals(template, expanded)
    }

    @Test
    fun `test now assigned to different variable name works`() {
        // User assigns timestamp to a variable with different name - this should work
        val template = "\${myTs = LocalDateTime.now()}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        // Should pass through unchanged (not shorthand)
        assertEquals(template, expanded)
    }

    @Test
    fun `test expression with equals sign passes through unchanged`() {
        val template = "\${x = 5 + 3}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals(template, expanded)
    }

    @Test
    fun `test uuid as variable name does not expand`() {
        // When uuid is used as a variable NAME (assignment), it should NOT be expanded
        val template = "\${uuid = something}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        // Expression contains =, so it passes through unchanged
        assertEquals(template, expanded)
    }

    @Test
    fun `test now as variable name does not expand`() {
        // When now is used as a variable NAME (assignment), it should NOT be expanded
        val template = "\${now = something}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        // Expression contains =, so it passes through unchanged
        assertEquals(template, expanded)
    }

    // ===== Validation Tests for Reserved Keywords =====

    @Test
    fun `test validation returns error when uuid used as variable name`() {
        val template = "\${uuid = UUID.randomUUID()}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.isNotEmpty(), "Should return error for uuid as variable name")
        assertTrue(errors[0].contains("uuid"), "Error should mention uuid")
        assertTrue(errors[0].contains("reserved"), "Error should mention reserved")
    }

    @Test
    fun `test validation returns error when UUID used as variable name case insensitive`() {
        val template = "\${UUID = something}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.isNotEmpty(), "Should return error for UUID as variable name")
        assertTrue(errors[0].contains("UUID"), "Error should mention UUID")
    }

    @Test
    fun `test validation returns error when now used as variable name`() {
        val template = "\${now = LocalDateTime.now()}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.isNotEmpty(), "Should return error for now as variable name")
        assertTrue(errors[0].contains("now"), "Error should mention now")
        assertTrue(errors[0].contains("reserved"), "Error should mention reserved")
    }

    @Test
    fun `test validation returns error when NOW used as variable name case insensitive`() {
        val template = "\${NOW = something}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.isNotEmpty(), "Should return error for NOW as variable name")
        assertTrue(errors[0].contains("NOW"), "Error should mention NOW")
    }

    @Test
    fun `test validation returns no error for valid uuid shorthand`() {
        val template = "\${uuid}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.isEmpty(), "Should not return error for valid uuid shorthand")
    }

    @Test
    fun `test validation returns no error for valid now shorthand`() {
        val template = "\${now}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.isEmpty(), "Should not return error for valid now shorthand")
    }

    @Test
    fun `test validation returns no error for valid now with format`() {
        val template = "\${now:yyyyMMdd}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.isEmpty(), "Should not return error for valid now with format")
    }

    @Test
    fun `test validation returns no error for regular variable assignment`() {
        val template = "\${myVar = 123}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.isEmpty(), "Should not return error for regular variable assignment")
    }

    @Test
    fun `test validation returns multiple errors for multiple reserved keywords`() {
        val template = "\${uuid = x}|\${now = y}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertEquals(2, errors.size, "Should return 2 errors for both reserved keywords")
    }

    @Test
    fun `test validation error message suggests correct usage for uuid`() {
        val template = "\${uuid = x}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors[0].contains("UUID generation"), "Error should suggest UUID generation")
        assertTrue(errors[0].contains("\${uuid}"), "Error should show correct syntax")
    }

    @Test
    fun `test validation error message suggests correct usage for now`() {
        val template = "\${now = x}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors[0].contains("timestamp"), "Error should suggest timestamp")
        assertTrue(errors[0].contains("\${now}"), "Error should show correct syntax")
    }
}

/**
 * Tests for FixTagDictionary tag name resolution.
 */
class FixTagDictionaryTest {

    @Test
    fun `test resolveTagName returns correct tag for ClOrdID`() {
        val tag = FixTagDictionary.resolveTagName("ClOrdID", null)
        assertEquals(11, tag)
    }

    @Test
    fun `test resolveTagName returns correct tag for OrderID`() {
        val tag = FixTagDictionary.resolveTagName("OrderID", null)
        assertEquals(37, tag)
    }

    @Test
    fun `test resolveTagName returns correct tag for Symbol`() {
        val tag = FixTagDictionary.resolveTagName("Symbol", null)
        assertEquals(55, tag)
    }

    @Test
    fun `test resolveTagName returns correct tag for QuoteReqID`() {
        val tag = FixTagDictionary.resolveTagName("QuoteReqID", null)
        assertEquals(131, tag)
    }

    @Test
    fun `test resolveTagName returns null for unknown name`() {
        val tag = FixTagDictionary.resolveTagName("NonExistentTag", null)
        assertEquals(null, tag)
    }

    @Test
    fun `test resolveTagOrName handles numeric string`() {
        val tag = FixTagDictionary.resolveTagOrName("55", null)
        assertEquals(55, tag)
    }

    @Test
    fun `test resolveTagOrName handles tag name`() {
        val tag = FixTagDictionary.resolveTagOrName("Symbol", null)
        assertEquals(55, tag)
    }

    @Test
    fun `test isValidTagReference returns true for known number`() {
        assertTrue(FixTagDictionary.isValidTagReference("11", null))
    }

    @Test
    fun `test isValidTagReference returns true for known name`() {
        assertTrue(FixTagDictionary.isValidTagReference("ClOrdID", null))
    }

    @Test
    fun `test isValidTagReference returns false for unknown name`() {
        assertTrue(!FixTagDictionary.isValidTagReference("InvalidTag", null))
    }
}
