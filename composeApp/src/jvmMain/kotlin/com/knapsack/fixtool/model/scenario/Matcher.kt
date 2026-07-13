package com.knapsack.fixtool.model.scenario

/**
 * Per-tag comparison vocabulary for asserting a received FIX message against an *expectation*.
 *
 * This is the keystone schema from the repeatable-scenarios design
 * (see `docs/scenario-assertion-model.md` for the JSON encodings). The scenario format reuses it
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

/**
 * How the reply is compared against the expectation's list of rows.
 *
 * Both modes pair a row with a field the same way — the *k*-th row for tag `T` refers to the *k*-th
 * occurrence of `T` — and both enforce the order the rows are listed in. They differ only in what
 * they do about a field the expectation never mentions.
 */
enum class MatchMode {
    /**
     * A **subsequence** match: the listed rows must appear in the reply in the listed order, with
     * anything else allowed in between. A tag the reply carries that no row mentions is ignored, so a
     * venue adding an optional field does not break the scenario.
     *
     * Relative order is still enforced, and that is what keeps OPEN honest about repeating groups.
     * Under set semantics an expectation listing `452=1` then `452=4` would pass against a reply that
     * sent the two party roles the other way round — the entries swapped, the assertion none the wiser.
     */
    OPEN,

    /**
     * The reply must carry **the same tags, the same number of times, in the same order**. A tag that
     * appears, disappears or moves is a failure — a change in shape *is* the regression STRICT exists
     * to report.
     */
    STRICT,
}

/**
 * One row of an expectation: a tag and how to compare it.
 *
 * There is no path, no group and no entry — deliberately. A row's position in [Expectation.fields] is
 * the whole of its addressing: the *k*-th row for tag `T` asserts the *k*-th occurrence of `T` in the
 * message. Naming a group entry by its delimiter's value (the model this replaces) does not work on
 * real FIX, because the same firm appears twice under different PartyRoles and both entries carry
 * `448=FIRMA` — so the identity does not identify, and every repair for that produced a scenario that
 * either asserted the wrong entry and passed, or refused to assert an entry it could have.
 */
data class FieldExpectation(
    val tag: Int,
    val matcher: Matcher,
)

/**
 * A captured message's expected shape: an **ordered** list of rows plus a comparison mode.
 * `golden` (the captured raw message) is optional and kept only for display/diff.
 *
 * The order of [fields] is not cosmetic. Rows are stored in captured wire order, and that order *is*
 * the assertion: it decides which occurrence of a repeated tag each row refers to, and in STRICT it is
 * itself asserted. Reordering this list re-aims its assertions.
 */
data class Expectation(
    val fields: List<FieldExpectation>,
    val messageType: String? = null,
    val mode: MatchMode = MatchMode.OPEN,
    val golden: String? = null,
)

/** Why a row passed or failed — what the reconcile view offers an action for. */
enum class TagStatus {
    /** The matcher was satisfied. */
    OK,

    /** The tag was found where expected; its value did not satisfy the matcher. */
    VALUE,

    /** The expectation lists this row; the reply has no occurrence left to pair it with. */
    MISSING,

    /** The reply carries this field; no row mentions it. Only a failure in STRICT. */
    UNEXPECTED,

    /** The value is somewhere in the reply and would satisfy the matcher — but not in this position. */
    MOVED,

    /** The matcher itself is unusable (an uncompilable regex). Not the venue's fault; the row's. */
    INVALID,
}

/** The outcome of evaluating one row against a message. */
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
     * The row's position in [Expectation.fields], or null for an [TagStatus.UNEXPECTED] field, which
     * the reply carried and the expectation never listed. This is what makes a failure addressable:
     * two rows on the same tag (the two party entries) are otherwise indistinguishable in a report.
     */
    val index: Int? = null,
    /** Which occurrence of this tag in the message the row refers to, 0-based. */
    val occurrence: Int = 0,
    val status: TagStatus = TagStatus.OK,
)
