package com.knapsack.fixtool.service

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

    @Test
    fun `test uuid length shorthand expands to truncated dashless uuid`() {
        val template = "\${uuid:20}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${UUID.randomUUID().toString().replace(\"-\", \"\").take(20)}", expanded)
    }

    @Test
    fun `test uuid length shorthand in variable assignment`() {
        // What capture mints: the shorthand must expand to exactly the longhand it replaced,
        // so a re-captured scenario puts the same shape on the wire as one captured last month.
        val template = "\${id0 = uuid:20}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${id0 = UUID.randomUUID().toString().replace(\"-\", \"\").take(20)}", expanded)
    }

    @Test
    fun `test uuid length shorthand out of range passes through and validates with error`() {
        // 33 > the 32 hex chars a dash-less UUID holds: expand leaves it alone, validate names it.
        val template = "\${uuid:33}"
        assertEquals(template, ShorthandTemplateExpander.expand(template, null))
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.any { it.contains("uuid:N") }, "expected a uuid:N range error, got $errors")
    }

    @Test
    fun `test uuid length shorthand zero validates with error`() {
        val errors = ShorthandTemplateExpander.validateShorthand("\${id0 = uuid:0}", null)
        assertTrue(errors.any { it.contains("uuid:N") }, "expected a uuid:N range error, got $errors")
    }

    @Test
    fun `test uuid length shorthand in range validates clean`() {
        assertTrue(ShorthandTemplateExpander.validateShorthand("\${id0 = uuid:20}", null).isEmpty())
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

    // ===== Timestamp Offset Tests =====

    @Test
    fun `test now plus hours offset`() {
        val template = "\${now+1h}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusHours(1).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now minus hours offset`() {
        val template = "\${now-2h}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().minusHours(2).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now plus days offset`() {
        val template = "\${now+1d}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now minus days offset`() {
        val template = "\${now-3d}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().minusDays(3).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now plus weeks offset`() {
        val template = "\${now+1w}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusWeeks(1).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now minus weeks offset`() {
        val template = "\${now-2w}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().minusWeeks(2).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now plus months offset`() {
        val template = "\${now+1m}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusMonths(1).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now minus months offset`() {
        val template = "\${now-6m}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().minusMonths(6).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now plus years offset`() {
        val template = "\${now+1y}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusYears(1).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now minus years offset`() {
        val template = "\${now-5y}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().minusYears(5).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset case insensitive`() {
        val template = "\${NOW+1D}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset with whitespace`() {
        val template = "\${  now + 1 d  }"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset with large number`() {
        val template = "\${now+100d}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusDays(100).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset with zero`() {
        val template = "\${now+0d}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusDays(0).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset mixed case unit H`() {
        val template = "\${now+3H}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusHours(3).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset mixed case unit W`() {
        val template = "\${Now-2W}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().minusWeeks(2).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset mixed case unit M`() {
        val template = "\${NOW+6M}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusMonths(6).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset mixed case unit Y`() {
        val template = "\${now-10Y}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().minusYears(10).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset with text prefix`() {
        val template = "EXPIRY-\${now+30d}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("EXPIRY-\${LocalDateTime.now().plusDays(30).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset with text suffix`() {
        val template = "\${now+1y}-ANNUAL"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusYears(1).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}-ANNUAL", expanded)
    }

    @Test
    fun `test now offset with text prefix and suffix`() {
        val template = "START-\${now-7d}-END"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("START-\${LocalDateTime.now().minusDays(7).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}-END", expanded)
    }

    @Test
    fun `test now offset 24 hours`() {
        val template = "\${now+24h}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusHours(24).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset 365 days`() {
        val template = "\${now+365d}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusDays(365).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset 52 weeks`() {
        val template = "\${now+52w}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusWeeks(52).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset 12 months`() {
        val template = "\${now+12m}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusMonths(12).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    // ===== Timestamp Offset with Custom Format Tests =====

    @Test
    fun `test now offset with custom date format`() {
        val template = "\${now+1d:yyyyMMdd}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ofPattern(\"yyyyMMdd\"))}", expanded)
    }

    @Test
    fun `test now offset with custom ISO format`() {
        val template = "\${now-1w:yyyy-MM-dd}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().minusWeeks(1).format(DateTimeFormatter.ofPattern(\"yyyy-MM-dd\"))}", expanded)
    }

    @Test
    fun `test now offset with custom datetime format`() {
        val template = "\${now+2h:HH:mm:ss}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusHours(2).format(DateTimeFormatter.ofPattern(\"HH:mm:ss\"))}", expanded)
    }

    @Test
    fun `test now offset format case insensitive`() {
        val template = "\${NOW+1M:yyyyMMdd}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusMonths(1).format(DateTimeFormatter.ofPattern(\"yyyyMMdd\"))}", expanded)
    }

    @Test
    fun `test now offset with FIX timestamp format`() {
        val template = "\${now+1d:yyyyMMdd-HH:mm:ss}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss\"))}", expanded)
    }

    @Test
    fun `test now offset with slash date format`() {
        val template = "\${now-1m:yyyy/MM/dd}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().minusMonths(1).format(DateTimeFormatter.ofPattern(\"yyyy/MM/dd\"))}", expanded)
    }

    @Test
    fun `test now offset with ISO datetime format`() {
        val template = "\${now+2w:yyyy-MM-dd'T'HH:mm:ss}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusWeeks(2).format(DateTimeFormatter.ofPattern(\"yyyy-MM-dd'T'HH:mm:ss\"))}", expanded)
    }

    @Test
    fun `test now offset with year month format`() {
        val template = "\${now+3m:yyyyMM}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusMonths(3).format(DateTimeFormatter.ofPattern(\"yyyyMM\"))}", expanded)
    }

    @Test
    fun `test now offset with year only format`() {
        val template = "\${now+5y:yyyy}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().plusYears(5).format(DateTimeFormatter.ofPattern(\"yyyy\"))}", expanded)
    }

    @Test
    fun `test now offset minus with custom format`() {
        val template = "\${now-90d:yyyyMMdd}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${LocalDateTime.now().minusDays(90).format(DateTimeFormatter.ofPattern(\"yyyyMMdd\"))}", expanded)
    }

    @Test
    fun `test now offset format with text around it`() {
        val template = "Valid until: \${now+1y:yyyy-MM-dd}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("Valid until: \${LocalDateTime.now().plusYears(1).format(DateTimeFormatter.ofPattern(\"yyyy-MM-dd\"))}", expanded)
    }

    // ===== Timestamp Offset in Variable Assignment Tests =====

    @Test
    fun `test now offset in variable assignment`() {
        val template = "\${tomorrow = now+1d}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${tomorrow = LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset with format in variable assignment`() {
        val template = "\${lastWeek = now-1w:yyyyMMdd}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${lastWeek = LocalDateTime.now().minusWeeks(1).format(DateTimeFormatter.ofPattern(\"yyyyMMdd\"))}", expanded)
    }

    @Test
    fun `test now offset hours in variable assignment`() {
        val template = "\${twoHoursAgo = now-2h}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${twoHoursAgo = LocalDateTime.now().minusHours(2).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset weeks in variable assignment`() {
        val template = "\${nextWeek = now+1w}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${nextWeek = LocalDateTime.now().plusWeeks(1).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset months in variable assignment`() {
        val template = "\${nextQuarter = now+3m}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${nextQuarter = LocalDateTime.now().plusMonths(3).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset years in variable assignment`() {
        val template = "\${nextYear = now+1y}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${nextYear = LocalDateTime.now().plusYears(1).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset minus in variable assignment with format`() {
        val template = "\${lastMonth = now-1m:yyyyMM}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${lastMonth = LocalDateTime.now().minusMonths(1).format(DateTimeFormatter.ofPattern(\"yyyyMM\"))}", expanded)
    }

    @Test
    fun `test now offset variable assignment with whitespace`() {
        val template = "\${  expiry  =  now + 30 d  }"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${expiry = LocalDateTime.now().plusDays(30).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now offset variable assignment case insensitive`() {
        val template = "\${deadline = NOW+2W}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${deadline = LocalDateTime.now().plusWeeks(2).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
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

    @Test
    fun `test uuid now offset and message reference combined`() {
        val template = "\${uuid}-\${D.11}-\${now+1d}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("UUID.randomUUID().toString()"))
        assertTrue(expanded.contains("valueOfTag(11)"))
        assertTrue(expanded.contains("plusDays(1)"))
    }

    @Test
    fun `test multiple offset expressions in same template`() {
        val template = "Start: \${now-1d:yyyyMMdd}, End: \${now+1d:yyyyMMdd}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("minusDays(1)"))
        assertTrue(expanded.contains("plusDays(1)"))
    }

    @Test
    fun `test now and now offset in same template`() {
        val template = "Created: \${now}, Expires: \${now+30d}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("LocalDateTime.now().format"))
        assertTrue(expanded.contains("plusDays(30)"))
    }

    @Test
    fun `test all offset units in same template`() {
        val template = "\${now+1h}|\${now+1d}|\${now+1w}|\${now+1m}|\${now+1y}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("plusHours(1)"))
        assertTrue(expanded.contains("plusDays(1)"))
        assertTrue(expanded.contains("plusWeeks(1)"))
        assertTrue(expanded.contains("plusMonths(1)"))
        assertTrue(expanded.contains("plusYears(1)"))
    }

    @Test
    fun `test offset with uuid and message reference`() {
        val template = "\${uuid}-\${now+1d:yyyyMMdd}-\${D.11}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("UUID.randomUUID().toString()"))
        assertTrue(expanded.contains("plusDays(1)"))
        assertTrue(expanded.contains("valueOfTag(11)"))
    }

    @Test
    fun `test realistic FIX message with offset timestamps`() {
        val template = "EffectiveTime=\${now+1h:yyyyMMdd-HH:mm:ss}|ExpireTime=\${now+24h:yyyyMMdd-HH:mm:ss}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("plusHours(1)"))
        assertTrue(expanded.contains("plusHours(24)"))
        assertTrue(expanded.contains("yyyyMMdd-HH:mm:ss"))
    }

    @Test
    fun `test offset variable followed by reference`() {
        val template = "\${expiry = now+7d}\${D.11}-\${expiry}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertTrue(expanded.contains("expiry = LocalDateTime.now().plusDays(7)"))
        assertTrue(expanded.contains("valueOfTag(11)"))
        assertTrue(expanded.contains("\${expiry}"))
    }

    // ===== Variable Assignment Tests =====

    @Test
    fun `test uuid shorthand in variable assignment expands correctly`() {
        // ${quoteRequestId = uuid} should expand uuid on the right side
        val template = "\${quoteRequestId = uuid}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${quoteRequestId = UUID.randomUUID().toString()}", expanded)
    }

    @Test
    fun `test UUID shorthand in variable assignment case insensitive`() {
        val template = "\${myId = UUID}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${myId = UUID.randomUUID().toString()}", expanded)
    }

    @Test
    fun `test now shorthand in variable assignment expands correctly`() {
        val template = "\${timestamp = now}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
    }

    @Test
    fun `test now with format in variable assignment expands correctly`() {
        val template = "\${date = now:yyyyMMdd}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${date = LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd\"))}", expanded)
    }

    @Test
    fun `test regular expression assignment passes through unchanged`() {
        val template = "\${myId = UUID.randomUUID()}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        // Not a shorthand value, should pass through unchanged
        assertEquals(template, expanded)
    }

    @Test
    fun `test arithmetic expression assignment passes through unchanged`() {
        val template = "\${x = 5 + 3}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals(template, expanded)
    }

    @Test
    fun `test uuid as variable name does not expand`() {
        // When uuid is used as a variable NAME (left side), it should NOT be expanded
        // because the value 'something' is not a shorthand
        val template = "\${uuid = something}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals(template, expanded)
    }

    @Test
    fun `test now as variable name does not expand`() {
        // When now is used as a variable NAME (left side), it should NOT be expanded
        // because the value 'something' is not a shorthand
        val template = "\${now = something}"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals(template, expanded)
    }

    @Test
    fun `test variable assignment with uuid and whitespace`() {
        val template = "\${  myVar  =  uuid  }"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${myVar = UUID.randomUUID().toString()}", expanded)
    }

    @Test
    fun `test variable assignment with now and whitespace`() {
        val template = "\${  ts  =  now  }"
        val expanded = ShorthandTemplateExpander.expand(template, null)
        assertEquals("\${ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}", expanded)
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
    fun `test validation returns no error for uuid shorthand in variable assignment`() {
        // ${quoteRequestId = uuid} is valid - uuid is the VALUE not the variable name
        val template = "\${quoteRequestId = uuid}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.isEmpty(), "Should not return error when uuid is used as value")
    }

    @Test
    fun `test validation returns no error for now shorthand in variable assignment`() {
        // ${timestamp = now} is valid - now is the VALUE not the variable name
        val template = "\${timestamp = now}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.isEmpty(), "Should not return error when now is used as value")
    }

    @Test
    fun `test validation returns no error for now with format in variable assignment`() {
        val template = "\${date = now:yyyyMMdd}"
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.isEmpty(), "Should not return error for now:format as value")
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
