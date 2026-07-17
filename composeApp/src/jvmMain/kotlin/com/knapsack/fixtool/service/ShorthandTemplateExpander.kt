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
 * - `${uuid}` → UUID.randomUUID().toString()
 * - `${uuid:20}` → a dash-less UUID truncated to 20 chars (what capture mints for correlation ids —
 *   short enough for venues that cap ClOrdID length)
 * - `${now}` → current **local** timestamp in FIX format (yyyyMMdd-HH:mm:ss.SSS)
 * - `${utcnow}` → current **UTC** timestamp — what capture mints for UTCTimestamp fields (TransactTime,
 *   ValidUntilTime, …), so a replay's stamp does not carry the capturer's local offset. Takes the same
 *   `:pattern` and `+/-` offsets as `now`.
 * - `${now:pattern}` → current timestamp with custom pattern (e.g., ${now:yyyyMMdd})
 * - `${now+1h}` → timestamp 1 hour from now (units: min=minutes, h=hours, d=days, w=weeks, m=months, y=years)
 * - `${now-2d}` → timestamp 2 days ago
 * - `${utcnow+5min}` → UTC timestamp 5 minutes from now (min is minutes; a bare `m` is months)
 * - `${now+1d:yyyyMMdd}` → timestamp with offset and custom format
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

    // Pattern: uuid (case insensitive)
    private val UUID_PATTERN = """^\s*uuid\s*$""".toRegex(RegexOption.IGNORE_CASE)

    // Pattern: uuid:N — a dash-less UUID truncated to N chars (same shape capture has always minted)
    private val UUID_LEN_PATTERN = """^\s*uuid:(\d+)\s*$""".toRegex(RegexOption.IGNORE_CASE)

    // A dash-less UUID holds 32 hex chars; asking for more (or zero) is a typo worth naming.
    private val UUID_LEN_RANGE = 1..32

    // Pattern: now / utcnow (case insensitive). 'now' is local; the optional 'utc' prefix is the UTC clock
    // (LocalDateTime.now(ZoneOffset.UTC)) — what capture mints so a replayed UTCTimestamp does not drift by
    // the capturer's local offset. 'now' (not 'ts') keeps it clear of variable names.
    private val TIMESTAMP_PATTERN = """^\s*(utc)?now\s*$""".toRegex(RegexOption.IGNORE_CASE)

    // Pattern: now:pattern / utcnow:pattern (custom format)
    private val TIMESTAMP_FORMAT_PATTERN = """^\s*(utc)?now:(.+)\s*$""".toRegex(RegexOption.IGNORE_CASE)

    // Pattern: now+1h, utcnow+5min, NOW-2d, etc. (offset without format). 'min' is minutes — spelled out and
    // matched first so 5min never reads as 5 months; a bare 'm' is still months.
    private val TIMESTAMP_OFFSET_PATTERN = """^\s*(utc)?now\s*([+-])\s*(\d+)\s*(min|[hdwmy])\s*$""".toRegex(RegexOption.IGNORE_CASE)

    // Pattern: now+1d:yyyyMMdd, utcnow+5min:yyyyMMdd (offset with custom format)
    private val TIMESTAMP_OFFSET_FORMAT_PATTERN = """^\s*(utc)?now\s*([+-])\s*(\d+)\s*(min|[hdwmy]):(.+)\s*$""".toRegex(RegexOption.IGNORE_CASE)

    // Pattern to detect variable assignment with shorthand keywords as variable name
    // e.g., ${uuid = something} or ${now = something}. 'utcnow' is matched before 'now' so the longer
    // keyword wins the alternation.
    private val SHORTHAND_VAR_ASSIGNMENT_PATTERN = """^\s*(uuid|utcnow|now)\s*=.*$""".toRegex(RegexOption.IGNORE_CASE)

    // Reserved shorthand keywords that cannot be used as variable names
    private val SHORTHAND_KEYWORDS = setOf("uuid", "now", "utcnow")

    // Keywords and patterns that should NOT be treated as shorthand
    // (to avoid false matches with Kotlin expressions)
    private val EXCLUDED_FIRST_PARTS =
        setOf(
            "UUID",
            "System",
            "LocalDateTime",
            "LocalDate",
            "LocalTime",
            "Instant",
            "DateTimeFormatter",
            "Math",
            "String",
            "Int",
            "Long",
            "incoming",
            "outgoing",
            "in",
            "out", // Already explicit or reserved
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
        // Check for variable assignment pattern: ${varName = value}
        // If value is a shorthand keyword, expand it
        if (expression.contains("=")) {
            val parts = expression.split("=", limit = 2)
            if (parts.size == 2) {
                val varName = parts[0].trim()
                val value = parts[1].trim()

                // Check if the value is a shorthand keyword
                if (UUID_PATTERN.matches(value)) {
                    return "$varName = UUID.randomUUID().toString()"
                }
                UUID_LEN_PATTERN.matchEntire(value)?.let { m ->
                    val n = m.groupValues[1].toIntOrNull()
                    // Out of range falls through unchanged, so validateShorthand can name the typo.
                    if (n != null && n in UUID_LEN_RANGE) return "$varName = ${expandUuidLen(n)}"
                }
                TIMESTAMP_PATTERN.matchEntire(value)?.let { m ->
                    return "$varName = ${expandTimestamp(m.utc(), null, null, null, null)}"
                }
                TIMESTAMP_FORMAT_PATTERN.matchEntire(value)?.let { m ->
                    return "$varName = ${expandTimestamp(m.utc(), null, null, null, m.groupValues[2].trim())}"
                }
                // Check for timestamp offset: now+1d, utcnow+5min, etc.
                TIMESTAMP_OFFSET_PATTERN.matchEntire(value)?.let { m ->
                    return "$varName = ${expandTimestamp(m.utc(), m.groupValues[2], m.groupValues[3].toLong(), m.groupValues[4], null)}"
                }
                // Check for timestamp offset with format: now+1d:yyyyMMdd
                TIMESTAMP_OFFSET_FORMAT_PATTERN.matchEntire(value)?.let { m ->
                    return "$varName = ${expandTimestamp(m.utc(), m.groupValues[2], m.groupValues[3].toLong(), m.groupValues[4], m.groupValues[5].trim())}"
                }
            }
            // Not a shorthand assignment, return unchanged
            return expression
        }

        // Try UUID shorthand: ${uuid}
        if (UUID_PATTERN.matches(expression)) {
            return "UUID.randomUUID().toString()"
        }

        // Try truncated UUID shorthand: ${uuid:20}
        UUID_LEN_PATTERN.matchEntire(expression)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n in UUID_LEN_RANGE) return expandUuidLen(n)
        }

        // Try timestamp shorthand: ${now}, ${utcnow}
        TIMESTAMP_PATTERN.matchEntire(expression)?.let { m ->
            return expandTimestamp(m.utc(), null, null, null, null)
        }

        // Try timestamp with custom format: ${now:pattern}, ${utcnow:pattern}
        TIMESTAMP_FORMAT_PATTERN.matchEntire(expression)?.let { m ->
            return expandTimestamp(m.utc(), null, null, null, m.groupValues[2].trim())
        }

        // Try timestamp offset: ${now+1h}, ${utcnow+5min}, etc.
        TIMESTAMP_OFFSET_PATTERN.matchEntire(expression)?.let { m ->
            return expandTimestamp(m.utc(), m.groupValues[2], m.groupValues[3].toLong(), m.groupValues[4], null)
        }

        // Try timestamp offset with format: ${now+1d:yyyyMMdd}
        TIMESTAMP_OFFSET_FORMAT_PATTERN.matchEntire(expression)?.let { m ->
            return expandTimestamp(m.utc(), m.groupValues[2], m.groupValues[3].toLong(), m.groupValues[4], m.groupValues[5].trim())
        }

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
                return expression // Not shorthand, return unchanged
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
        dictionary: FixDictionaryAdapter?,
    ): String {
        val tag =
            FixTagDictionary.resolveTagOrName(tagOrName, dictionary)
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
        dictionary: FixDictionaryAdapter?,
    ): String {
        val tag =
            FixTagDictionary.resolveTagOrName(tagOrName, dictionary)
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
        dictionary: FixDictionaryAdapter?,
    ): String {
        val tag =
            FixTagDictionary.resolveTagOrName(tagOrName, dictionary)
                ?: return """null /* unknown tag: $tagOrName */"""

        return if (index != null) {
            """(incoming["$msgType"].valueOfTag($tag, $index) ?: outgoing["$msgType"].valueOfTag($tag, $index))"""
        } else {
            """(incoming["$msgType"].valueOfTag($tag) ?: outgoing["$msgType"].valueOfTag($tag))"""
        }
    }

    /**
     * A fresh dash-less UUID cut to [n] chars — byte-for-byte the expression capture used to write out
     * longhand, so a scenario re-authored with the shorthand puts the same shape on the wire.
     */
    private fun expandUuidLen(n: Int): String = """UUID.randomUUID().toString().replace("-", "").take($n)"""

    /** True when the timestamp keyword carried the `utc` prefix — group 1 of every timestamp pattern. */
    private fun MatchResult.utc(): Boolean = groupValues[1].isNotEmpty()

    /**
     * Builds a LocalDateTime expression: local or UTC clock, an optional +/- offset, and a format.
     *
     * `${now}` is the local clock (unchanged); `${utcnow}` is `LocalDateTime.now(ZoneOffset.UTC)` — what
     * capture mints for UTCTimestamp fields so a replay's stamp does not carry the capturer's local offset.
     * Units: min=minutes, h/d/w/m/y = hours/days/weeks/months/years (m is months — minutes is `min`). Passing
     * `sign`/`amount`/`unit` all null yields the bare clock with no offset.
     */
    private fun expandTimestamp(utc: Boolean, sign: String?, amount: Long?, unit: String?, pattern: String?): String {
        val nowCall = if (utc) "LocalDateTime.now(ZoneOffset.UTC)" else "LocalDateTime.now()"
        val offset =
            if (sign != null && amount != null && unit != null) {
                val method =
                    when (unit.lowercase()) {
                        "min" -> if (sign == "+") "plusMinutes" else "minusMinutes"
                        "h" -> if (sign == "+") "plusHours" else "minusHours"
                        "d" -> if (sign == "+") "plusDays" else "minusDays"
                        "w" -> if (sign == "+") "plusWeeks" else "minusWeeks"
                        "m" -> if (sign == "+") "plusMonths" else "minusMonths"
                        "y" -> if (sign == "+") "plusYears" else "minusYears"
                        else -> error("Unknown time unit: $unit")
                    }
                ".$method($amount)"
            } else {
                ""
            }
        val formatPattern = pattern ?: "yyyyMMdd-HH:mm:ss.SSS"
        return """$nowCall$offset.format(DateTimeFormatter.ofPattern("$formatPattern"))"""
    }

    /**
     * Validates shorthand expressions and returns errors for unknown tag names
     * and reserved shorthand keywords used as variable names.
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

            // Check for shorthand keywords used as variable names
            SHORTHAND_VAR_ASSIGNMENT_PATTERN.matchEntire(expression)?.let { m ->
                val keyword = m.groupValues[1]
                errors.add(
                    "'$keyword' is a reserved shorthand keyword and cannot be used as a variable name. Use \${$keyword} directly for ${if (keyword
                            .equals(
                                "uuid",
                                ignoreCase = true,
                            )
                    ) {
                        "UUID generation"
                    } else {
                        "timestamp"
                    }}.",
                )
            }

            // A uuid:N whose N cannot mean anything: named here, because expand() left it alone and the
            // evaluator would otherwise report it as an inscrutable Kotlin error.
            UUID_LEN_PATTERN.matchEntire(expression.substringAfter("=").trim())?.let { m ->
                val n = m.groupValues[1].toIntOrNull()
                if (n == null || n !in UUID_LEN_RANGE) {
                    errors.add(
                        "\${uuid:N} needs N between ${UUID_LEN_RANGE.first} and ${UUID_LEN_RANGE.last} " +
                            "(a dash-less UUID has 32 chars), got '\${$expression}'",
                    )
                }
            }

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
