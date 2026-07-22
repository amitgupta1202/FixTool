package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.describe
import com.knapsack.fixtool.model.scenario.TagResult
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.model.scenario.compiled
import com.knapsack.fixtool.model.scenario.validationError
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A FIX message as the assertion engine sees it: **an ordered list of `tag=value`**, and nothing else.
 *
 * There is no `groupEntries`, no `presentTags`, no notion of an entry — because the engine no longer
 * needs one to decide what an assertion refers to, and because two views that each had to decide
 * produced the defect that outlived three review rounds: one answered from `FixStructure`, the other
 * from QuickFIX/J, and they disagreed about which fields a group entry contained. Neither has to
 * decide now, so they cannot disagree.
 */
interface MessageView {
    /** Every field the message carries, in wire order, header through trailer. */
    fun fields(): List<Pair<Int, String>>
}

/**
 * Compares a received message against an [Expectation].
 *
 * **The model.** An expectation is an ordered list of rows. The *k*-th row for tag `T` refers to the
 * *k*-th occurrence of `T` in the message. That is the whole of it: no path, no group, no entry, no
 * identity. Two party entries carrying the same `448=FIRMA` under different roles are asserted
 * separately and correctly, because they are simply the first and second `452`.
 *
 * **The property worth protecting.** Pairing looks at the tag and the position — *never* at whether
 * the matcher would pass. It is the one thing in here that must not be made cleverer. A pairing that
 * preferred the occurrence a matcher happens to satisfy would re-aim an assertion onto whichever field
 * makes it green: the venue reorders two party entries, each row finds the entry that still matches,
 * and the step passes while asserting something nobody wrote. That is a false green, and it is the
 * failure this model was built to make structurally impossible. A benign reorder costs a red instead,
 * which the reconcile view fixes in a click. We take the trade — see docs/scenario-assertion-model.md.
 */
@Suppress("TooManyFunctions")
object ExpectationEvaluator {
    /**
     * Invisible to the engine: not paired, not asserted, never an unexpected extra. These identify the
     * connection and the moment, so a scenario captured on DEV would otherwise go red on QA on every
     * step. Same list the seeder omits, because a tag STRICT calls unexpected while the seeder refuses
     * to seed it is a step that can never pass.
     */
    val NEVER_ASSERTED: Set<Int> = SessionTags.NEVER_ASSERTED

    /**
     * How one row of the expectation lines up against the message. Produced once, by [align], and read
     * by both the evaluator (to judge) and the reconcile view (to offer a fix), so the two cannot draw
     * different diffs of the same failure.
     */
    data class Alignment(
        /** Position in `expectation.fields`, or null for a field the reply carried that no row lists. */
        val index: Int?,
        val row: FieldExpectation?,
        /** Position in the message's asserted fields, or null for a row nothing paired with. */
        val wireIndex: Int?,
        val tag: Int,
        /** Which occurrence of [tag] this is — of the message where paired, of the expectation where not. */
        val occurrence: Int,
        val actual: String?,
        /**
         * **The other end of a move.** The reply carries this field, and the row asserting its tag is
         * unpaired elsewhere — one divergence, and that row is the one reporting it. This line exists so
         * the right column stays the reply, whole and in wire order; it is judged by nobody and counted
         * by nothing.
         */
        val ghost: Boolean = false,
    )

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
    ): List<TagResult> =
        diff(message, expectation, referenceResolver, now)
            // A ghost is display, not judgement — in EITHER mode. The unpaired row it echoes is the one
            // result the runner reports about that divergence.
            .filterNot { it.ghost || (it.unasserted && expectation.mode == MatchMode.OPEN) }
            .map { it.result }

    /** One line of the diff: where a row landed, and the verdict on it. */
    data class DiffRow(
        val alignment: Alignment,
        val result: TagResult,
    ) {
        /** A field the reply carried that no row mentions. OPEN ignores it; the reconcile view offers it. */
        val unasserted: Boolean get() = alignment.row == null

        /** The other end of a move — see [Alignment.ghost]. Shown for completeness, counted nowhere. */
        val ghost: Boolean get() = alignment.ghost
    }

    /**
     * The whole diff between an expectation and a message — **including the fields the reply carries that
     * no row mentions**, which OPEN drops from its results and the reconcile view has to show.
     *
     * The one alignment. The runner judges from it and the reconcile view draws from it, so the two
     * cannot disagree about what failed or where: a view that ran its own diff would eventually offer a
     * fix for a row the engine was not actually asserting.
     */
    fun diff(
        message: MessageView,
        expectation: Expectation,
        referenceResolver: (String) -> String? = { null },
        now: () -> Instant = { Instant.now() },
    ): List<DiffRow> {
        val wire = message.fields()
        val alignment = align(expectation, wire)
        val claimed = alignment.mapNotNull { if (it.row != null) it.wireIndex else null }.toSet()
        return alignment.map { a ->
            DiffRow(a, judge(a, expectation, wire, claimed, referenceResolver, now))
        }
    }

    /**
     * Lines the expectation's rows up against the message's fields.
     *
     * A single greedy left-to-right scan: each row claims the next unclaimed occurrence of its tag *at
     * or after* the field the previous row claimed. That one rule delivers both halves of the model —
     * the *k*-th row for tag `T` lands on the *k*-th `T` when the reply is shaped as captured, and a
     * row whose tag has moved ahead of an earlier row's finds nothing left to claim, which is how a
     * reordering is caught rather than absorbed.
     *
     * Rows matched `absent` take no part in the scan: they assert a tag is *not* there, so they claim
     * nothing and are checked against the whole message.
     *
     * **The whole message is scanned, envelope included.** The connection's own tags are never *seeded*
     * and never counted as an extra (see [NEVER_ASSERTED]) — but a row that names one explicitly is an
     * assertion an author wrote, and it is evaluated like any other. Filtering them out of the wire
     * before pairing made `{"tag":34,"matcher":{"type":"exact","value":"5"}}` — a legitimate check on a
     * gap-fill test — permanently red with "the reply has no such tag", while the raw pane sat there
     * showing `34=5`.
     *
     * Fields left unclaimed are the reply's extras — a failure in STRICT, ignored in OPEN.
     */
    fun align(expectation: Expectation, wire: List<Pair<Int, String>>): List<Alignment> {
        val claimedBy = arrayOfNulls<Int>(wire.size) // wire position -> row index
        val claimOf = HashMap<Int, Int>() // row index -> wire position

        var cursor = 0
        for ((index, row) in expectation.fields.withIndex()) {
            if (row.matcher is Matcher.Absent) continue
            val at = (cursor until wire.size).firstOrNull { wire[it].first == row.tag } ?: continue
            claimedBy[at] = index
            claimOf[index] = at
            cursor = at + 1
        }

        // A tag is not reported as an extra when the expectation has *already spoken about it*. An `absent`
        // row speaks for every occurrence (its failure says the tag is present; saying it twice reads as
        // two problems). A row that could not be paired speaks for exactly ONE occurrence — the same field
        // it reports as MOVED. STRICT used to emit both for a reordered tag, with contradictory text:
        // "expected <absent>, actual Y" beside "expected presence — present, but not in this position".
        //
        // But spoken for is not invisible. The field the venue sent is still there, and swallowing it made
        // the right column read as a message no venue sent — and, when the moved value had ALSO changed, it
        // hid the one piece of evidence that distinguishes "the venue dropped a field" from "the venue moved
        // it and changed it". So the occurrence an unpaired row speaks for is emitted as a GHOST: shown at
        // its wire position, judged by nobody, counted by nothing. And the credit is per-occurrence, not
        // per-tag — one unpaired row must not amnesty every unclaimed occurrence of its tag, or a genuine
        // extra rides in under a move's excuse.
        val absentTags =
            expectation.fields
                .filter { it.matcher is Matcher.Absent }
                .mapTo(mutableSetOf()) { it.tag }
        val ghostCredits = HashMap<Int, Int>()
        for ((index, row) in expectation.fields.withIndex()) {
            if (row.matcher !is Matcher.Absent && index !in claimOf.keys) {
                ghostCredits.merge(row.tag, 1, Int::plus)
            }
        }

        // Emit in reading order: each row where it sits, with the reply's unclaimed fields interleaved
        // at the position they actually occupy — so the result reads like a diff, top to bottom.
        val out = mutableListOf<Alignment>()
        var emittedUpTo = 0

        fun emitExtrasBefore(limit: Int) {
            while (emittedUpTo < limit) {
                val w = emittedUpTo++
                if (claimedBy[w] != null || wire[w].first in NEVER_ASSERTED) continue
                val tag = wire[w].first
                when {
                    tag in absentTags -> Unit // the absent row already faces it, and speaks for the whole tag
                    (ghostCredits[tag] ?: 0) > 0 -> {
                        ghostCredits[tag] = ghostCredits.getValue(tag) - 1
                        out += Alignment(null, null, w, tag, occurrenceAt(wire, w), wire[w].second, ghost = true)
                    }
                    else -> out += Alignment(null, null, w, tag, occurrenceAt(wire, w), wire[w].second)
                }
            }
        }

        for ((index, row) in expectation.fields.withIndex()) {
            val at = claimOf[index]
            if (at != null) {
                emitExtrasBefore(at)
                emittedUpTo = at + 1
                out += Alignment(index, row, at, row.tag, occurrenceAt(wire, at), wire[at].second)
            } else {
                out += Alignment(index, row, null, row.tag, expectedOccurrence(expectation, index), null)
            }
        }
        emitExtrasBefore(wire.size)
        return out
    }

    /** How many fields with the same tag precede [at] — i.e. which occurrence [at] is. */
    private fun occurrenceAt(wire: List<Pair<Int, String>>, at: Int): Int =
        (0 until at).count { wire[it].first == wire[at].first }

    /** Which occurrence of its tag a row is, counting rows — used when nothing paired with it. */
    private fun expectedOccurrence(expectation: Expectation, index: Int): Int =
        (0 until index).count { expectation.fields[it].tag == expectation.fields[index].tag }

    @Suppress("LongParameterList")
    private fun judge(
        a: Alignment,
        expectation: Expectation,
        wire: List<Pair<Int, String>>,
        /** Wire positions some row already claimed — a value sitting in one of them has not "moved" here. */
        claimed: Set<Int>,
        referenceResolver: (String) -> String?,
        now: () -> Instant,
    ): TagResult {
        val row = a.row
        if (row == null) {
            if (a.ghost) {
                // The other end of a move — the mirror of the moved row's own "present in the reply — but
                // not in this position". Kept short on purpose: the crossing connector already shows where
                // the two ends are, so the words only name what this end is. STRICT adds the one thing the
                // connector cannot show — that an unclaimed field here counts as an extra.
                val sentence = "asserted, but not in this position"
                return TagResult(
                    a.tag,
                    "spoken for",
                    if (expectation.mode == MatchMode.STRICT) "$sentence (a STRICT extra)" else sentence,
                    a.actual,
                    passed = true,
                    index = null,
                    occurrence = a.occurrence,
                    status = TagStatus.UNEXPECTED,
                )
            }
            // A field the reply carried that no row mentions. OPEN was told to tolerate exactly this, so
            // it is not a failure there — but it is still a *row of the diff*, and the reconcile view
            // needs it to offer "Add assertion". Only STRICT calls it a failure.
            val strict = expectation.mode == MatchMode.STRICT
            return TagResult(
                a.tag,
                if (strict) "strict: unexpected" else "not asserted",
                "<absent>",
                a.actual,
                passed = !strict,
                index = null,
                occurrence = a.occurrence,
                status = TagStatus.UNEXPECTED,
            )
        }

        val describe = describe(row.matcher)
        row.matcher.validationError()?.let {
            return TagResult(
                tag = a.tag,
                matcher = describe,
                // The reason names the kind itself ("…not a usable pattern…", "oneOf has no values…"), so
                // the prefix stays generic — a oneOf row must not be labelled "invalid regex".
                expected = "invalid: $it",
                actual = a.actual,
                passed = false,
                index = a.index,
                occurrence = a.occurrence,
                status = TagStatus.INVALID,
            )
        }

        if (row.matcher is Matcher.Absent) {
            val present = wire.firstOrNull { it.first == row.tag }?.second
            return TagResult(
                tag = a.tag,
                matcher = describe,
                expected = "<absent>",
                actual = present,
                passed = present == null,
                index = a.index,
                occurrence = a.occurrence,
                status = if (present == null) TagStatus.OK else TagStatus.VALUE,
            )
        }

        if (a.wireIndex == null) {
            // Nothing left to pair with. If the value is in the reply and would satisfy this row, it is
            // not gone — it is somewhere else, and saying so is the difference between "the venue
            // dropped a field" and "the venue moved it", which are not the same bug.
            //
            // This is also the failure an author hits when they list their rows in an order the venue
            // does not use: an expectation is a *subsequence* of the reply, so naming 37 after 11 when
            // the venue sends 37 first describes a message nobody sent. It is a real failure and it
            // stays one — but the row says how to fix it, because "moved" on its own reads like a venue
            // bug when it is usually a hand-written expectation in the wrong order.
            // "Moved" means the value is somewhere **no other row is already checking**. Without that
            // qualifier, a captured two-party expectation replayed against a one-party reply reports its
            // surplus rows as "moved" — because the surviving entry satisfies them — and the view offers
            // to re-order an expectation that has nothing to re-order. The venue sent fewer entries; the
            // rows are missing, and saying so is the only thing that leads the author anywhere.
            val elsewhere =
                wire.withIndex().any { (i, f) ->
                    i !in claimed && f.first == row.tag && matches(row.matcher, f.second, referenceResolver, now)
                }
            val expected = expectedText(row.matcher, referenceResolver, now)
            return TagResult(
                a.tag,
                describe,
                if (elsewhere) "$expected — present, but not in this position: list the rows in the order the " +
                    "venue sends them (a capture does this for you), or use the reconcile view to accept the new order"
                else expected,
                actual = null, passed = false, index = a.index, occurrence = a.occurrence,
                status = if (elsewhere) TagStatus.MOVED else TagStatus.MISSING,
            )
        }

        val (passed, expected) = applyMatcher(row.matcher, a.actual, referenceResolver, now)
        return TagResult(
            a.tag, describe, expected, a.actual, passed,
            index = a.index, occurrence = a.occurrence,
            status = if (passed) TagStatus.OK else TagStatus.VALUE,
        )
    }

    private fun matches(
        matcher: Matcher,
        actual: String?,
        referenceResolver: (String) -> String?,
        now: () -> Instant,
    ): Boolean = applyMatcher(matcher, actual, referenceResolver, now).first

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
            is Matcher.Range -> matchRange(matcher, actual) to matcher.describe()
            is Matcher.Temporal -> matchTemporal(matcher, actual, now) to temporalExpected(matcher)
            is Matcher.Reference -> {
                val resolved = referenceResolver(matcher.expression)
                (resolved != null && actual == resolved) to (resolved ?: matcher.expression)
            }
        }

    /**
     * An unusable pattern is a failed assertion, not an exception — the builder re-evaluates on every
     * keystroke, so compiling unguarded took the workbench down the moment an author typed a lone `[`.
     * A row that reaches here with a bad pattern has already been reported as [TagStatus.INVALID]; this
     * is the belt to that braces.
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

    /**
     * A non-numeric value fails rather than throwing, exactly as [matchNumeric] does — a venue sending
     * text where a price belongs is a failed assertion, not a crashed run. A bound-less range is caught
     * upstream as [TagStatus.INVALID] by [validationError]; reaching here it would accept anything, so
     * the guard below refuses it rather than reporting a pass nobody asserted.
     */
    private fun matchRange(matcher: Matcher.Range, actual: String?): Boolean {
        if (matcher.min == null && matcher.max == null) return false
        val value = actual?.toDoubleOrNull() ?: return false
        val aboveMin = matcher.min?.let { if (matcher.minInclusive) value >= it else value > it } ?: true
        val belowMax = matcher.max?.let { if (matcher.maxInclusive) value <= it else value < it } ?: true
        return aboveMin && belowMax
    }

    private fun matchTemporal(matcher: Matcher.Temporal, actual: String?, now: () -> Instant): Boolean {
        if (actual == null) return false
        return when (matcher.kind) {
            TemporalKind.TODAY -> parseFixDate(actual) == LocalDate.ofInstant(now(), ZoneOffset.UTC)
            TemporalKind.NOW_WITHIN_TOLERANCE -> {
                val instant = parseFixTimestamp(actual, now) ?: return false
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

    /**
     * Parse the timestamp shapes the **seeder actually seeds**, not just the one this used to know about.
     *
     * `ExpectationSeeder` maps `UTCTIMESTAMP`, `UTCTIMEONLY`, `TZTIMESTAMP`, `TZTIMEONLY` and `TIME` to a
     * temporal matcher, and this accepted only `yyyyMMdd-HH:mm:ss[.SSS]`. So a `UTCTIMEONLY` field — an
     * MDEntryTime on any MarketDataSnapshot — parsed as null, the matcher returned false, and the row was
     * **hard-wired to fail**: red on the very message it was captured from, and on every run after that, with
     * "~now ±60s" sitting beside the perfectly correct value. The only repairs the UI offers on a temporal row
     * are Loosen and Drop, so the field silently stopped being checked. A red that leads straight to deleted
     * coverage is a green by a longer route, and a seeder that seeds a matcher its own evaluator cannot satisfy
     * is two components disagreeing about what a timestamp is.
     *
     * A time-only value carries no date, so it is read on whichever of yesterday/today/tomorrow brings
     * it **nearest the judging instant** — which is what the stamp meant on the wire. "Today" alone is
     * the wrong date on either side of midnight: a 23:59:59.5 stamp judged at 00:00:01Z is 1.5 seconds
     * old, and reading it as TODAY's 23:59:59.5 made it ~86398s away — a phantom red on a correct value
     * whose one-click "repair" was toleranceSeconds=86400, an assertion of nothing. Every nightly run
     * that crosses midnight UTC walks through this.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun parseFixTimestamp(value: String, now: () -> Instant): Instant? {
        for (pattern in TIMESTAMP_PATTERNS) {
            try {
                return LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern)).toInstant(ZoneOffset.UTC)
            } catch (e: Exception) {
                // try next pattern
            }
        }
        for (pattern in OFFSET_TIMESTAMP_PATTERNS) {
            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ofPattern(pattern)).toInstant()
            } catch (e: Exception) {
                // try next pattern
            }
        }
        for (pattern in TIME_ONLY_PATTERNS) {
            try {
                val time = LocalTime.parse(value, DateTimeFormatter.ofPattern(pattern))
                return nearestInstant(time, now, ZoneOffset.UTC)
            } catch (e: Exception) {
                // try next pattern
            }
        }
        // TZTIMEONLY: a time of day carrying an offset, and no date. The seeder seeds it temporal, so it has
        // to parse here or the row is hard-wired to fail.
        for (pattern in OFFSET_TIME_ONLY_PATTERNS) {
            try {
                val parsed = DateTimeFormatter.ofPattern(pattern).parse(value)
                val time = LocalTime.from(parsed)
                val offset = ZoneOffset.from(parsed)
                return nearestInstant(time, now, offset)
            } catch (e: Exception) {
                // try next pattern
            }
        }
        return null
    }

    /** The instant this time-of-day names on the date (in [offset]) that brings it nearest to now. */
    private fun nearestInstant(time: LocalTime, now: () -> Instant, offset: ZoneOffset): Instant {
        val anchor = now()
        val today = LocalDate.ofInstant(anchor, offset)
        return listOf(today.minusDays(1), today, today.plusDays(1))
            .map { it.atTime(time).toInstant(offset) }
            .minBy { kotlin.math.abs(it.epochSecond - anchor.epochSecond) }
    }

    // ----------------------------------------------------------------- descriptions

    /**
     * Does this matcher accept this value?
     *
     * Exposed for the reconcile view, which has to work out *where in the reply* a failing row's field
     * actually is before it can offer to re-order the rows. It is deliberately not used by [align]: pairing
     * looks at the tag and the position and never at whether the matcher would pass, because a pairing that
     * preferred the occurrence which satisfies a matcher would silently re-aim an assertion onto whichever
     * field makes it green.
     *
     * **[now] and [referenceResolver] are not optional extras.** This hard-coded `Instant.now()` and a null
     * resolver, and the caller that needed them most was `ScenarioReconcile.verbatimWindow` — which asks
     * whether a *whole entry* appears in the reply intact. An entry carrying an `MDEntryTime` (every
     * market-data snapshot has one) therefore never matched, because one row of it was judged against the
     * reader's wall clock instead of the instant the message arrived; and an entry carrying an echoed id
     * never matched either, because the resolver said nothing resolves. The re-order was withheld, and the
     * author was told — in the tool's own words — that *"these rows did not move; the values changed in
     * place"*, which was false about a message whose entries had plainly swapped.
     */
    fun satisfies(
        matcher: Matcher,
        value: String,
        referenceResolver: (String) -> String? = { null },
        now: () -> Instant = { Instant.now() },
    ): Boolean = applyMatcher(matcher, value, referenceResolver, now).first

    /**
     * The instant a FIX timestamp names — the same parse the evaluator judges temporals with, exposed so a
     * pasted message can be anchored to its own `SendingTime(52)`.
     *
     * One parser, deliberately. A second one would eventually disagree with this one about what a timestamp
     * is, and the row would then be judged against a moment the anchor never meant.
     */
    fun parseTimestamp(value: String, now: () -> Instant = { Instant.now() }): Instant? = parseFixTimestamp(value, now)

    /** Short matcher description for a [TagResult] / report row. */
    fun describe(matcher: Matcher): String =
        when (matcher) {
            is Matcher.Exact -> "exact ${matcher.value}"
            is Matcher.Presence -> "presence"
            is Matcher.Absent -> "absent"
            is Matcher.Regex -> "regex /${matcher.pattern}/"
            is Matcher.OneOf -> "oneOf [${matcher.values.joinToString(",")}]"
            is Matcher.Numeric -> "numeric ${numericExpected(matcher)}"
            is Matcher.Range -> "range ${matcher.describe()}"
            is Matcher.Temporal -> "temporal ${temporalExpected(matcher)}"
            is Matcher.Reference -> "reference ${matcher.expression}"
        }

    private fun expectedText(matcher: Matcher, referenceResolver: (String) -> String?, now: () -> Instant): String =
        applyMatcher(matcher, null, referenceResolver, now).second

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

    /** TZTIMESTAMP — the same moment, carrying its offset. Same fraction precisions as the plain shapes. */
    private val OFFSET_TIMESTAMP_PATTERNS =
        listOf(
            "yyyyMMdd-HH:mm:ss.SSSSSSSSSXXX",
            "yyyyMMdd-HH:mm:ss.SSSSSSXXX",
            "yyyyMMdd-HH:mm:ss.SSSXXX",
            "yyyyMMdd-HH:mm:ssXXX",
        )

    /** TZTIMEONLY — a time of day with an offset, and no date at all. Same fraction precisions too. */
    private val OFFSET_TIME_ONLY_PATTERNS =
        listOf(
            "HH:mm:ss.SSSSSSSSSXXX",
            "HH:mm:ss.SSSSSSXXX",
            "HH:mm:ss.SSSXXX",
            "HH:mm:ssXXX",
        )

    /** UTCTIMEONLY / TIME — a time of day, read as that time TODAY. */
    private val TIME_ONLY_PATTERNS =
        listOf(
            "HH:mm:ss.SSSSSSSSS",
            "HH:mm:ss.SSSSSS",
            "HH:mm:ss.SSS",
            "HH:mm:ss",
        )
}
