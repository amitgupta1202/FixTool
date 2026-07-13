package com.knapsack.fixtool.model.scenario

/**
 * Per-tag comparison vocabulary for asserting a received FIX message against an *expectation*.
 *
 * This is the keystone schema from the repeatable-scenarios design
 * (see `docs/fixtool-assert-spec.md` for the JSON encodings). The scenario format reuses it
 * verbatim, so it is defined here in its own package, free of any UI/control dependencies.
 */
sealed interface Matcher {
    /** Literal string equality (default for business fields). */
    data class Exact(val value: String) : Matcher

    /** The tag must be present; its value is ignored (e.g. OrderID, ExecID). */
    object Presence : Matcher

    /** The tag must NOT be present (negative assertion). */
    object Absent : Matcher

    /** The value must match the given regular expression. */
    data class Regex(val pattern: String) : Matcher

    /** The value must be a member of the given set (e.g. OrdStatus in {1,2}). */
    data class OneOf(val values: List<String>) : Matcher

    /**
     * Float comparison with tolerance: `abs(actual - expected) <= tolerance`.
     * A tolerance of 0 still ignores formatting (`1.2345` == `1.23450`) because both sides
     * are parsed as numbers first. Use a non-zero tolerance for fuzzy price/qty checks.
     */
    data class Numeric(val expected: Double, val tolerance: Double = 0.0) : Matcher

    /** Format-aware date/time comparison (UTCTimestamp / UTCDateOnly). */
    data class Temporal(val kind: TemporalKind, val toleranceSeconds: Long = 0) : Matcher

    /**
     * The value must equal a `${...}` expression resolved over the scenario/session scope,
     * e.g. `Reference("\${out.D.11}")` to assert an ExecutionReport echoes the order's ClOrdID.
     */
    data class Reference(val expression: String) : Matcher
}

/**
 * The compiled pattern, or null if it does not compile.
 *
 * The single place a [Matcher.Regex] is compiled. Both callers — the evaluator, which has to match a
 * value, and the editor, which has to tell the author what is wrong — go through here, so they cannot
 * drift into disagreeing about whether a pattern is usable, or into describing the same fault two
 * different ways.
 */
fun Matcher.Regex.compiled(): kotlin.text.Regex? =
    try {
        kotlin.text.Regex(pattern)
    } catch (e: java.util.regex.PatternSyntaxException) {
        null
    }

/**
 * What is wrong with this matcher, in the author's words, or null if it is usable.
 *
 * Judged where it can be acted on — live in the editor as it is typed, and on the failing row of a run
 * — and **never** by the codec or the file format. A matcher that does not compile is a bad assertion,
 * not a corrupt scenario: refusing to write it lost the author every other assertion in the file along
 * with it. The evaluator reports such a row as failed and quotes this reason on it, so the reader of a
 * CI report or an `fixtool_assert` response is told *why* the pattern is bad, not merely that it is.
 */
@Suppress("SwallowedException")
fun Matcher.validationError(): String? {
    if (this !is Matcher.Regex) return null
    return try {
        kotlin.text.Regex(pattern)
        null
    } catch (e: java.util.regex.PatternSyntaxException) {
        "'$pattern' is not a usable pattern: ${e.description}"
    }
}

/** Temporal comparison kinds. */
enum class TemporalKind {
    /** The date portion must equal today's UTC date. */
    TODAY,

    /** The timestamp must be within `toleranceSeconds` of "now" (UTC). */
    NOW_WITHIN_TOLERANCE,
}

/** How tags present in the message but not listed in the expectation are treated. */
enum class MatchMode {
    /** Assert only the listed tags; ignore extras. Robust to venues adding optional fields. */
    OPEN,

    /** Any unexpected tag (besides header/trailer volatiles) is a failure. */
    STRICT,
}

/**
 * Locates a single entry within a repeating group by **identity**, never by position
 * (group entry order is not guaranteed). Mirrors `fixtool_detail_search` mode `identity`.
 *
 * Example: `GroupPath(453, 452, "1")` → "the entry whose PartyRole(452) = 1".
 *
 * This locates an entry only while the identity is **unique** within its group. Where it is not — a
 * market-data snapshot with two MDEntries of the same MDEntryType, two legs on the same symbol —
 * FixTool does not guess: the seeder refuses to assert that group and says so, and the evaluator
 * fails such an assertion as ambiguous rather than binding it to whichever entry came first. A
 * scenario that asserts the wrong entry and passes is worse than one that admits it cannot check.
 */
data class GroupPath(
    val groupTag: Int,
    val identityTag: Int,
    val identityValue: String,
)

/** One tag's expectation: which tag, where to find it (optional group path), and how to compare. */
data class FieldExpectation(
    val tag: Int,
    val matcher: Matcher,
    val path: GroupPath? = null,
)

/**
 * A captured message's expected shape: a set of per-tag matchers plus a comparison mode.
 * `golden` (the captured raw message) is optional and kept only for display/diff.
 */
data class Expectation(
    val fields: List<FieldExpectation>,
    val messageType: String? = null,
    val mode: MatchMode = MatchMode.OPEN,
    val golden: String? = null,
)

/** The outcome of evaluating one [FieldExpectation] against a message. */
data class TagResult(
    val tag: Int,
    /** Human-readable matcher description, e.g. "oneOf [1,2]". */
    val matcher: String,
    /** Human-readable expected value, e.g. "1 | 2". */
    val expected: String,
    /** The actual value found, or null if the tag was absent. */
    val actual: String?,
    val passed: Boolean,
    /**
     * The originating [FieldExpectation]'s group path. Without it, two assertions on the same tag
     * under different group entries (e.g. PartyID per PartyRole) are indistinguishable in results.
     */
    val path: GroupPath? = null,
)
