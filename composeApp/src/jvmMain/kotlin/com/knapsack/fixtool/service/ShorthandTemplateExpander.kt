package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter

/**
 * Expands shorthand message reference syntax to full Kotlin expressions.
 *
 * Supported shorthand patterns:
 * - `${D.11}` or `${D.ClOrdID}` → auto-detect (tries incoming first, then outgoing)
 * - `${in.D.11}` or `${in.D.ClOrdID}` → explicit incoming
 * - `${out.R.131}` or `${out.R.QuoteReqID}` → explicit outgoing
 * - `${D.11.0}` → repeating group access (first occurrence)
 *
 * Non-shorthand expressions pass through unchanged for backwards compatibility.
 */
object ShorthandTemplateExpander {

    // Pattern to match ${...} expressions
    private val EXPRESSION_REGEX = """\$\{([^}]+)}""".toRegex()

    // Shorthand patterns (compiled once for performance)
    // Pattern: in.MsgType.TagOrName or in.MsgType.TagOrName.Index
    private val EXPLICIT_IN_PATTERN = """^\s*in\.([A-Za-z0-9]+)\.([A-Za-z0-9]+)(?:\.(\d+))?\s*$""".toRegex()

    // Pattern: out.MsgType.TagOrName or out.MsgType.TagOrName.Index
    private val EXPLICIT_OUT_PATTERN = """^\s*out\.([A-Za-z0-9]+)\.([A-Za-z0-9]+)(?:\.(\d+))?\s*$""".toRegex()

    // Pattern: MsgType.TagOrName or MsgType.TagOrName.Index (auto-detect)
    // Note: Must not match expressions that look like method calls or other Kotlin syntax
    private val AUTO_DETECT_PATTERN = """^\s*([A-Za-z0-9]+)\.([A-Za-z0-9]+)(?:\.(\d+))?\s*$""".toRegex()

    // Keywords and patterns that should NOT be treated as shorthand
    // (to avoid false matches with Kotlin expressions)
    private val EXCLUDED_FIRST_PARTS = setOf(
        "UUID", "System", "LocalDateTime", "LocalDate", "LocalTime",
        "Instant", "DateTimeFormatter", "Math", "String", "Int", "Long",
        "incoming", "outgoing", "in", "out"  // Already explicit or reserved
    )

    /**
     * Expands shorthand syntax in a template string.
     * Non-shorthand expressions are left unchanged for backwards compatibility.
     *
     * @param template The template string potentially containing ${...} expressions
     * @param dictionary Optional data dictionary for tag name resolution
     * @return The template with shorthand expressions expanded to full syntax
     */
    fun expand(template: String, dictionary: FixDictionaryAdapter?): String {
        // Quick check: if no expressions, return as-is
        if (!template.contains("\${")) {
            return template
        }

        return EXPRESSION_REGEX.replace(template) { match ->
            val expression = match.groupValues[1]
            val expanded = expandExpression(expression.trim(), dictionary)
            "\${$expanded}"
        }
    }

    /**
     * Expands a single expression (the content inside ${...}).
     * Returns the original expression if it's not a shorthand pattern.
     */
    private fun expandExpression(expression: String, dictionary: FixDictionaryAdapter?): String {
        // Try explicit incoming: ${in.D.11}
        EXPLICIT_IN_PATTERN.matchEntire(expression)?.let { match ->
            val msgType = match.groupValues[1]
            val tagOrName = match.groupValues[2]
            val index = match.groupValues[3].takeIf { it.isNotEmpty() }
            return expandToIncoming(msgType, tagOrName, index, dictionary)
        }

        // Try explicit outgoing: ${out.R.131}
        EXPLICIT_OUT_PATTERN.matchEntire(expression)?.let { match ->
            val msgType = match.groupValues[1]
            val tagOrName = match.groupValues[2]
            val index = match.groupValues[3].takeIf { it.isNotEmpty() }
            return expandToOutgoing(msgType, tagOrName, index, dictionary)
        }

        // Try auto-detect: ${D.11}
        AUTO_DETECT_PATTERN.matchEntire(expression)?.let { match ->
            val firstPart = match.groupValues[1]
            val tagOrName = match.groupValues[2]
            val index = match.groupValues[3].takeIf { it.isNotEmpty() }

            // Check if this looks like a Kotlin expression (e.g., UUID.randomUUID)
            if (firstPart in EXCLUDED_FIRST_PARTS) {
                return expression  // Not shorthand, return unchanged
            }

            // Check if the second part is a valid tag reference
            // If it looks like a method call (e.g., "randomUUID"), skip it
            if (tagOrName.contains("(") || tagOrName.contains(")")) {
                return expression
            }

            // Validate that tagOrName is actually a valid tag (number or known name)
            val resolvedTag = FixTagDictionary.resolveTagOrName(tagOrName, dictionary)
            if (resolvedTag == null) {
                // Not a known tag, might be a Kotlin expression - return unchanged
                return expression
            }

            return expandToAutoDetect(firstPart, tagOrName, index, dictionary)
        }

        // Not a shorthand pattern, return unchanged
        return expression
    }

    /**
     * Expands to incoming-only syntax.
     */
    private fun expandToIncoming(
        msgType: String,
        tagOrName: String,
        index: String?,
        dictionary: FixDictionaryAdapter?
    ): String {
        val tag = FixTagDictionary.resolveTagOrName(tagOrName, dictionary)
            ?: return """null /* unknown tag: $tagOrName */"""

        return if (index != null) {
            """incoming["$msgType"].valueOfTag($tag, $index)"""
        } else {
            """incoming["$msgType"].valueOfTag($tag)"""
        }
    }

    /**
     * Expands to outgoing-only syntax.
     */
    private fun expandToOutgoing(
        msgType: String,
        tagOrName: String,
        index: String?,
        dictionary: FixDictionaryAdapter?
    ): String {
        val tag = FixTagDictionary.resolveTagOrName(tagOrName, dictionary)
            ?: return """null /* unknown tag: $tagOrName */"""

        return if (index != null) {
            """outgoing["$msgType"].valueOfTag($tag, $index)"""
        } else {
            """outgoing["$msgType"].valueOfTag($tag)"""
        }
    }

    /**
     * Expands to auto-detect syntax (tries incoming first, then outgoing).
     */
    private fun expandToAutoDetect(
        msgType: String,
        tagOrName: String,
        index: String?,
        dictionary: FixDictionaryAdapter?
    ): String {
        val tag = FixTagDictionary.resolveTagOrName(tagOrName, dictionary)
            ?: return """null /* unknown tag: $tagOrName */"""

        return if (index != null) {
            """(incoming["$msgType"].valueOfTag($tag, $index) ?: outgoing["$msgType"].valueOfTag($tag, $index))"""
        } else {
            """(incoming["$msgType"].valueOfTag($tag) ?: outgoing["$msgType"].valueOfTag($tag))"""
        }
    }

    /**
     * Validates shorthand expressions and returns errors for unknown tag names.
     * This is useful for validation before evaluation.
     *
     * @param template The template string to validate
     * @param dictionary Optional data dictionary for tag name resolution
     * @return List of error messages for any invalid shorthand expressions
     */
    fun validateShorthand(template: String, dictionary: FixDictionaryAdapter?): List<String> {
        if (!template.contains("\${")) {
            return emptyList()
        }

        val errors = mutableListOf<String>()

        EXPRESSION_REGEX.findAll(template).forEach { match ->
            val expression = match.groupValues[1].trim()

            // Check each shorthand pattern
            listOf(EXPLICIT_IN_PATTERN, EXPLICIT_OUT_PATTERN).forEach { pattern ->
                pattern.matchEntire(expression)?.let { m ->
                    val tagOrName = m.groupValues[2]
                    if (FixTagDictionary.resolveTagOrName(tagOrName, dictionary) == null) {
                        errors.add("Unknown FIX tag: '$tagOrName' in \${$expression}")
                    }
                }
            }

            // Check auto-detect pattern (only if it looks like shorthand)
            AUTO_DETECT_PATTERN.matchEntire(expression)?.let { m ->
                val firstPart = m.groupValues[1]
                val tagOrName = m.groupValues[2]

                // Only validate if it's actually a shorthand pattern
                if (firstPart !in EXCLUDED_FIRST_PARTS &&
                    !tagOrName.contains("(") &&
                    !tagOrName.contains(")")
                ) {
                    if (FixTagDictionary.resolveTagOrName(tagOrName, dictionary) == null) {
                        errors.add("Unknown FIX tag: '$tagOrName' in \${$expression}")
                    }
                }
            }
        }

        return errors
    }
}
