package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.GroupPath
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TagResult
import com.knapsack.fixtool.model.scenario.TemporalKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A minimal, side-effect-free view of a parsed FIX message that the [ExpectationEvaluator]
 * can read. Keeping the evaluator behind this interface makes it pure and unit-testable
 * without constructing a real QuickFIX message.
 */
interface MessageView {
    /** The value of a top-level (header/body/trailer) tag, or null if absent. */
    fun valueOfTag(tag: Int): String?

    /** All top-level tags present in the message (used for STRICT-mode extra detection). */
    fun presentTags(): Set<Int>

    /** The entries of a repeating group, each itself a [MessageView]. */
    fun groupEntries(groupTag: Int): List<MessageView>
}

/**
 * Evaluates a received message against an [ExpectationModel] (a set of per-tag matchers),
 * producing one [TagResult] per field plus, in STRICT mode, a synthetic failure for every
 * unexpected tag. This is the shared evaluation engine behind both `fixtool_assert` and the
 * (future) scenario runner; it does no I/O and no selection/awaiting — the caller supplies the
 * already-selected message.
 */
@Suppress("TooManyFunctions")
object ExpectationEvaluator {
    /** Header/trailer tags whose values are volatile and never asserted in STRICT mode. */
    val VOLATILE_HEADER_TRAILER: Set<Int> = setOf(8, 9, 10, 34, 52)

    /**
     * @param referenceResolver resolves a `${...}` expression (for [Matcher.Reference]) against
     *   the active scope, returning null if it cannot be resolved.
     * @param now supplies the current instant, injectable for deterministic tests.
     */
    fun evaluate(
        message: MessageView,
        expectation: Expectation,
        referenceResolver: (String) -> String? = { null },
        now: () -> Instant = { Instant.now() },
    ): List<TagResult> {
        val results = mutableListOf<TagResult>()
        for (field in expectation.fields) {
            results += evaluateField(message, field, referenceResolver, now)
        }
        if (expectation.mode == MatchMode.STRICT) {
            val listedTopLevel = expectation.fields.filter { it.path == null }.map { it.tag }.toSet()
            val extras = (message.presentTags() - listedTopLevel - VOLATILE_HEADER_TRAILER).sorted()
            for (tag in extras) {
                results += TagResult(tag, "strict: unexpected", "<absent>", message.valueOfTag(tag), passed = false)
            }
        }
        return results
    }

    private fun evaluateField(
        message: MessageView,
        field: FieldExpectation,
        referenceResolver: (String) -> String?,
        now: () -> Instant,
    ): TagResult {
        val matcherDesc = describe(field.matcher)
        // Locate the value: top-level, or inside a group entry selected by identity.
        val actual: String?
        if (field.path != null) {
            val entry = resolveGroupEntry(message, field.path)
            if (entry == null) {
                return TagResult(
                    field.tag,
                    "$matcherDesc @${field.path.groupTag}",
                    expectedText(field.matcher, null),
                    "<no entry>",
                    passed = false,
                    path = field.path,
                )
            }
            actual = entry.valueOfTag(field.tag)
        } else {
            actual = message.valueOfTag(field.tag)
        }

        val (passed, expected) = applyMatcher(field.matcher, actual, referenceResolver, now)
        return TagResult(field.tag, matcherDesc, expected, actual, passed, path = field.path)
    }

    private fun resolveGroupEntry(message: MessageView, path: GroupPath): MessageView? =
        message.groupEntries(path.groupTag).firstOrNull { it.valueOfTag(path.identityTag) == path.identityValue }

    /** Returns (passed, human-readable expected text). */
    private fun applyMatcher(
        matcher: Matcher,
        actual: String?,
        referenceResolver: (String) -> String?,
        now: () -> Instant,
    ): Pair<Boolean, String> =
        when (matcher) {
            is Matcher.Absent -> (actual == null) to "<absent>"
            is Matcher.Presence -> (actual != null) to "<present>"
            is Matcher.Exact -> (actual == matcher.value) to matcher.value
            is Matcher.Regex -> (actual != null && Regex(matcher.pattern).matches(actual)) to "~/${matcher.pattern}/"
            is Matcher.OneOf -> (actual != null && actual in matcher.values) to matcher.values.joinToString(" | ")
            is Matcher.Numeric -> matchNumeric(matcher, actual) to numericExpected(matcher)
            is Matcher.Temporal -> matchTemporal(matcher, actual, now) to temporalExpected(matcher)
            is Matcher.Reference -> {
                val resolved = referenceResolver(matcher.expression)
                (resolved != null && actual == resolved) to (resolved ?: matcher.expression)
            }
        }

    private fun matchNumeric(matcher: Matcher.Numeric, actual: String?): Boolean {
        val a = actual?.toDoubleOrNull() ?: return false
        return kotlin.math.abs(a - matcher.expected) <= matcher.tolerance
    }

    private fun matchTemporal(matcher: Matcher.Temporal, actual: String?, now: () -> Instant): Boolean {
        if (actual == null) return false
        return when (matcher.kind) {
            TemporalKind.TODAY -> parseFixDate(actual) == LocalDate.ofInstant(now(), ZoneOffset.UTC)
            TemporalKind.NOW_WITHIN_TOLERANCE -> {
                val instant = parseFixTimestamp(actual) ?: return false
                kotlin.math.abs(instant.epochSecond - now().epochSecond) <= matcher.toleranceSeconds
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun parseFixDate(value: String): LocalDate? =
        try {
            LocalDate.parse(value.take(8), DateTimeFormatter.BASIC_ISO_DATE)
        } catch (e: Exception) {
            null
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun parseFixTimestamp(value: String): Instant? {
        for (pattern in TIMESTAMP_PATTERNS) {
            try {
                return LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern)).toInstant(ZoneOffset.UTC)
            } catch (e: Exception) {
                // try next pattern
            }
        }
        return null
    }

    // ----------------------------------------------------------------- descriptions

    /** Short matcher description for a [TagResult] / report row. */
    fun describe(matcher: Matcher): String =
        when (matcher) {
            is Matcher.Exact -> "exact ${matcher.value}"
            is Matcher.Presence -> "presence"
            is Matcher.Absent -> "absent"
            is Matcher.Regex -> "regex /${matcher.pattern}/"
            is Matcher.OneOf -> "oneOf [${matcher.values.joinToString(",")}]"
            is Matcher.Numeric -> "numeric ${numericExpected(matcher)}"
            is Matcher.Temporal -> "temporal ${temporalExpected(matcher)}"
            is Matcher.Reference -> "reference ${matcher.expression}"
        }

    private fun expectedText(matcher: Matcher, actual: String?): String =
        applyMatcher(matcher, actual, { null }, { Instant.now() }).second

    private fun numericExpected(matcher: Matcher.Numeric): String =
        if (matcher.tolerance == 0.0) matcher.expected.toString() else "${matcher.expected} ±${matcher.tolerance}"

    private fun temporalExpected(matcher: Matcher.Temporal): String =
        when (matcher.kind) {
            TemporalKind.TODAY -> "today"
            TemporalKind.NOW_WITHIN_TOLERANCE -> "~now ±${matcher.toleranceSeconds}s"
        }

    private val TIMESTAMP_PATTERNS =
        listOf(
            "yyyyMMdd-HH:mm:ss.SSSSSSSSS",
            "yyyyMMdd-HH:mm:ss.SSSSSS",
            "yyyyMMdd-HH:mm:ss.SSS",
            "yyyyMMdd-HH:mm:ss",
        )
}
