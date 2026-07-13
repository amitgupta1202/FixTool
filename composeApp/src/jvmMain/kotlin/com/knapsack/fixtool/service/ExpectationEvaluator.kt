package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.GroupPath
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TagResult
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.model.scenario.compiled
import com.knapsack.fixtool.model.scenario.validationError
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

    /**
     * The **top-level** tags present in the message — never a repeating group's members, which are
     * reached through [groupEntries]. STRICT mode subtracts the expectation's top-level tags from
     * this to find extras, so returning group members here fails every grouped message.
     */
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
    /**
     * Never asserted, so never an "extra" either. The same list the seeder omits ([SessionTags]) — if
     * STRICT used a different one it would fail on the very tags the seeder deliberately left out
     * (SenderCompID and friends).
     */
    val VOLATILE_HEADER_TRAILER: Set<Int> = SessionTags.NEVER_ASSERTED

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
        if (expectation.mode == MatchMode.STRICT) results += strictExtras(message, expectation)
        return results
    }

    /**
     * Tags the message carries that the expectation never mentions — at top level, and inside each
     * group entry it asserts.
     *
     * Both levels, because a venue's new field lands wherever its dictionary puts it: a tag the
     * dictionary defines as a group member is absorbed into an entry, so checking only the top level
     * would let exactly the change STRICT exists to catch go by unnoticed. Entries the expectation
     * does not assert are not inspected — an assertion nobody wrote cannot be violated.
     */
    private fun strictExtras(message: MessageView, expectation: Expectation): List<TagResult> {
        val results = mutableListOf<TagResult>()

        val listedTopLevel = expectation.fields.filter { it.path == null }.map { it.tag }.toSet()
        for (tag in (message.presentTags() - listedTopLevel - VOLATILE_HEADER_TRAILER).sorted()) {
            results += TagResult(tag, "strict: unexpected", "<absent>", message.valueOfTag(tag), passed = false)
        }

        val assertedByEntry = expectation.fields.filter { it.path != null }.groupBy { it.path!! }
        for ((path, asserted) in assertedByEntry) {
            val entry = (resolveGroupEntry(message, path) as? EntryLookup.Found)?.entry ?: continue
            val listed = asserted.map { it.tag }.toSet() + path.identityTag
            for (tag in (entry.presentTags() - listed - VOLATILE_HEADER_TRAILER).sorted()) {
                results += TagResult(
                    tag,
                    "strict: unexpected @${path.groupTag}",
                    "<absent>",
                    entry.valueOfTag(tag),
                    passed = false,
                    path = path,
                )
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
            fun unresolved(reason: String) =
                TagResult(
                    field.tag,
                    "$matcherDesc @${field.path.groupTag}",
                    expectedText(field.matcher, null),
                    reason,
                    passed = false,
                    path = field.path,
                )
            when (val lookup = resolveGroupEntry(message, field.path)) {
                is EntryLookup.Missing -> return unresolved("<no entry>")
                is EntryLookup.Ambiguous -> {
                    val identity = "${field.path.identityTag}=${field.path.identityValue}"
                    return unresolved("<ambiguous: ${lookup.count} entries with $identity>")
                }
                is EntryLookup.Found -> actual = lookup.entry.valueOfTag(field.tag)
            }
        } else {
            actual = message.valueOfTag(field.tag)
        }

        val (passed, expected) = applyMatcher(field.matcher, actual, referenceResolver, now)
        return TagResult(field.tag, matcherDesc, expected, actual, passed, path = field.path)
    }

    /** How an assertion's group entry resolved: exactly one match, none, or several. */
    private sealed interface EntryLookup {
        data class Found(val entry: MessageView) : EntryLookup

        object Missing : EntryLookup

        /** Several entries carry this identity, so it does not identify one. */
        data class Ambiguous(val count: Int) : EntryLookup
    }

    /**
     * The entry with this identity — if there is exactly one.
     *
     * Binding to the first of several would assert one entry while reporting it as another's, and
     * pass or fail for reasons the author cannot see. Ambiguity is a failure with a reason, not a
     * coin toss: the seeder refuses to author such assertions in the first place, and this catches
     * the case where a message that was unambiguous at capture arrives with repeated identities.
     */
    private fun resolveGroupEntry(message: MessageView, path: GroupPath): EntryLookup {
        val matches =
            message.groupEntries(path.groupTag)
                .filter { it.valueOfTag(path.identityTag) == path.identityValue }
        return when (matches.size) {
            0 -> EntryLookup.Missing
            1 -> EntryLookup.Found(matches.single())
            else -> EntryLookup.Ambiguous(matches.size)
        }
    }

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
            is Matcher.Regex -> matchRegex(matcher, actual)
            is Matcher.OneOf -> (actual != null && actual in matcher.values) to matcher.values.joinToString(" | ")
            is Matcher.Numeric -> matchNumeric(matcher, actual) to numericExpected(matcher)
            is Matcher.Temporal -> matchTemporal(matcher, actual, now) to temporalExpected(matcher)
            is Matcher.Reference -> {
                val resolved = referenceResolver(matcher.expression)
                (resolved != null && actual == resolved) to (resolved ?: matcher.expression)
            }
        }

    /**
     * An unusable pattern is a failed assertion, not an exception.
     *
     * The expectation builder re-evaluates on every keystroke, so compiling unguarded took the
     * workbench down (with the author's unsaved edits) the moment they typed a lone `[` — and a bad
     * pattern arriving in a saved scenario or over `fixtool_assert` surfaced as a 500 rather than a
     * red row.
     *
     * The row says **why**, not just that it is invalid. The reader who most needs the reason is the
     * one who cannot open the editor to see it: an agent reading a failed `fixtool_assert`, or an
     * engineer reading a red step in a CI report. Told only "(invalid regex)", they cannot tell a
     * typo'd assertion from a venue that genuinely broke.
     */
    private fun matchRegex(matcher: Matcher.Regex, actual: String?): Pair<Boolean, String> {
        val compiled = matcher.compiled()
            ?: return false to "~/${matcher.pattern}/ — invalid regex: ${matcher.validationError()}"
        return (actual != null && compiled.matches(actual)) to "~/${matcher.pattern}/"
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
