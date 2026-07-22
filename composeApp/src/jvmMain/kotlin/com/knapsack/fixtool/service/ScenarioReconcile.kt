package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.MintingSide
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.model.scenario.compiled
import com.knapsack.fixtool.service.compare.EntrySource
import com.knapsack.fixtool.service.compare.GroupOverlay
import com.knapsack.fixtool.service.compare.NO_ANCHOR
import com.knapsack.fixtool.service.compare.ReferenceMessage
import java.time.Instant

/**
 * The edits a failed step can be repaired with — the engine behind the reconcile view, and the **only**
 * place an assertion is authored from a failure.
 *
 * Pure: every action takes an [Expectation] and returns a new one. The view holds a draft, applies
 * actions to it, and re-runs the diff after each — which is what gives the author a live "would this now
 * pass?" without anything being written until they Save.
 *
 * Everything here obeys one rule: **an action may never re-aim an assertion onto a field the author did
 * not choose.** A row's position among its same-tag siblings is its occurrence, so any edit that moves
 * rows around has to preserve, exactly, which occurrence each row refers to. That is why there are no
 * free-floating up/down arrows — see [acceptNewOrder].
 */
object ScenarioReconcile {
    /**
     * One line of the reconcile view. Either an expectation row (with its verdict against the message
     * that arrived), or a field the reply carried that no row mentions — which is a line the author can
     * turn *into* a row.
     */
    data class Row(
        /** Position in the expectation's `fields`, or null for a field the reply carried and no row lists. */
        val index: Int?,
        val tag: Int,
        val name: String,
        /** Which occurrence of [tag] this line refers to, 0-based. */
        val occurrence: Int,
        /** The matcher asserting it, or null when the expectation says nothing about this field. */
        val matcher: Matcher?,
        /** What this row asserts, e.g. "exact 2" — the matcher, not the engine's failure prose. */
        val expected: String,
        /** The engine's own words for why this row failed, when it did. */
        val reason: String,
        /** What the message actually carried here, or null when nothing paired with the row. */
        val actual: String?,
        val status: TagStatus,
        val passed: Boolean,
        /** Position in the message's fields — the anchor an "Add assertion" inserts against. */
        val wireIndex: Int?,
        /**
         * This row cannot be judged here at all — a `reference` matcher resolves against a *run's* variable
         * scope, and there is none outside a run.
         *
         * It is neither passing nor failing, and it must be shown as neither. Rendering it red (which is
         * what a null resolver produces) puts an "Accept actual" button under a row whose reference was
         * working perfectly: one click pins the assertion to this run's ClOrdID, the cross-step binding is
         * destroyed, and the scenario passes for the wrong reason on every future run.
         */
        val unknown: Boolean = false,
        /**
         * **The other end of a move.** The reply carries this field, and the row asserting its tag is
         * unpaired elsewhere — one divergence, reported once, by that row. This line exists so the right
         * column stays the reply, whole and in wire order; it is counted by nothing and offered nothing.
         */
        val ghost: Boolean = false,
    ) {
        /** A field the reply carried that the expectation never mentions. */
        val unasserted: Boolean get() = matcher == null

        /** Counts against the verdict: not an unasserted extra, and not something we cannot judge. */
        val judged: Boolean get() = !unasserted && !unknown
    }

    /**
     * The diff, as the view renders it: the expectation against the message that actually arrived.
     *
     * Straight off [ExpectationEvaluator.diff], so the lines the author sees are the very lines the
     * runner judged. A view that ran its own comparison would drift, and would eventually offer a fix
     * for a row the engine was not asserting.
     */
    fun rows(
        draft: Expectation,
        message: MessageView,
        dictionary: FixDictionaryAdapter?,
        referenceResolver: (String) -> String? = { null },
        now: () -> Instant = { Instant.now() },
    ): List<Row> {
        // A reference the resolver cannot answer is not the row failing — outside a run there is no scope,
        // and even WITH a run's scope in hand (it rides on the reference message now) a `${out.D.11}`
        // history expression still has no session to be answered from. Judged row by row: the rows the
        // scope covers get their real verdict, and each row it cannot cover stays unjudged. The old
        // any-reference-resolves flag judged ALL reference rows the moment ONE resolved — with a partial
        // resolver that is a confident red about an expression nobody evaluated ("unknown, never false").
        return ExpectationEvaluator.diff(message, draft, referenceResolver, now).map { d ->
            Row(
                index = d.alignment.index,
                tag = d.alignment.tag,
                name = dictionary?.getFieldName(d.alignment.tag) ?: "",
                occurrence = d.alignment.occurrence,
                matcher = d.alignment.row?.matcher,
                // The matcher, not `result.expected`: on a MOVED row the engine's expected text carries a
                // sentence of remedy, which belongs under the row and not squeezed into a column where it
                // truncates mid-word.
                expected = d.alignment.row?.let { ExpectationEvaluator.describe(it.matcher) } ?: "",
                reason = d.result.expected,
                // An `absent` row claims no wire field, so the alignment has nothing to show — but the
                // whole failure is that the reply DOES carry the tag. The verdict knows the value; the
                // alignment does not. Take it from the verdict, or the view tells the author the reply is
                // empty at a tag it is not, and "Accept actual" rewrites `absent` to `absent`: a button
                // that does nothing, on a row that can never be repaired.
                actual = d.result.actual ?: d.alignment.actual,
                status = d.result.status,
                passed = d.result.passed,
                wireIndex = d.alignment.wireIndex,
                ghost = d.ghost,
                unknown =
                    (d.alignment.row?.matcher as? Matcher.Reference)
                        ?.let { referenceResolver(it.expression) == null } ?: false,
            )
        }
    }

    /**
     * The diff against a [ReferenceMessage] — the same rows, judged at **the reference's own moment**.
     *
     * And where the reference has no moment (a paste with no `SendingTime`), a temporal row is *unjudged*
     * rather than failed. Judging it against the clock would produce a red that describes nothing but how
     * long ago the paste happened; the author's only repairs on a temporal row are to loosen it or drop it,
     * so that phantom red leads directly to deleted coverage. The tool does not know what moment that
     * message was sent at, and it says so.
     */
    fun rows(
        draft: Expectation,
        reference: ReferenceMessage,
        dictionary: FixDictionaryAdapter?,
        referenceResolver: (String) -> String? = { null },
    ): List<Row> {
        val anchor = reference.anchorInstant
        val judged = rows(draft, reference.view, dictionary, referenceResolver, now = { anchor ?: Instant.now() })
        if (anchor != null) return judged
        return judged.map { row ->
            if (row.matcher is Matcher.Temporal) row.copy(unknown = true, reason = NO_ANCHOR) else row
        }
    }

    // ------------------------------------------------------------------ the per-row actions

    /**
     * Re-baseline the row against the value that actually arrived — **keeping the kind of matcher it is**.
     * An absent actual → `absent`.
     *
     * This is the answer to "is a per-row re-seed worth having, or does Accept-actual cover it?". It did not
     * cover it: it wrote `Exact(actual)` over *whatever the row was*, so one click on a numeric row threw
     * away its tolerance and its format-robustness. `Numeric(500000, ±0)` and `Exact("500000")` are not the
     * same assertion — the first parses both sides as numbers and survives a venue that starts sending
     * `500000.00`, which is a formatting change and not a behaviour change; the second goes red on it. The
     * seeder chose numeric for that field on purpose, and the reconcile view was quietly un-choosing it
     * every time an author accepted a fill quantity.
     *
     * So Accept-actual re-seeds rather than flattens, and there is no separate per-row re-seed to add.
     * [canAcceptActual] says where the offer makes no sense at all.
     */
    fun acceptActual(draft: Expectation, index: Int, actual: String?): Expectation {
        if (actual == null) return replace(draft, index, Matcher.Absent)
        val current = draft.fields[index].matcher
        if (!canAcceptActual(current)) return draft
        // Keep it numeric, keep its tolerance: only the baseline moves.
        val asNumber = if (current is Matcher.Numeric) actual.toDoubleOrNull() else null
        val reseeded =
            if (asNumber != null && current is Matcher.Numeric) {
                Matcher.Numeric(asNumber, current.tolerance)
            } else {
                Matcher.Exact(actual)
            }
        return replace(draft, index, reseeded)
    }

    /**
     * Is there anything to *accept* on this row?
     *
     * For two matcher kinds there is not, and offering the button anyway hands the author a one-click way to
     * write an assertion that can never pass again:
     *
     * - **Temporal.** `~now ±60s` failing is a statement about a *moment*, not a value. Accepting the actual
     *   pins the row to `20260713-11:02:44` — a timestamp that will not recur — so the step is red on every
     *   run from then on. The author does the only thing left, loosens it to `presence` or drops it, and the
     *   scenario silently stops checking the timestamp at all. A red that leads to a deleted assertion is a
     *   green by a longer route.
     * - **Reference.** Accepting an echoed id pins the assertion to *this run's* ClOrdID and destroys the
     *   cross-step binding the row exists to express. (The view already refused this one; the rule lives here
     *   now, where the engine can enforce it, rather than only in the button that happens to draw it.)
     *
     * The honest offers on those rows are Loosen and Drop, and the view shows exactly those.
     */
    fun canAcceptActual(matcher: Matcher): Boolean =
        matcher !is Matcher.Temporal && matcher !is Matcher.Reference

    /** Keep the row, weaken the matcher — presence, a set, a tolerance, a pattern. */
    fun loosen(draft: Expectation, index: Int, matcher: Matcher): Expectation = replace(draft, index, matcher)

    /** The venue stopped sending this tag, and that is now the expected behaviour. */
    fun assertAbsent(draft: Expectation, index: Int): Expectation = replace(draft, index, Matcher.Absent)

    /**
     * Assert that a tag the expectation never mentioned appears **nowhere in the message** — the one
     * assertion an author can add about a field the reference does not carry, which is why the diff has no
     * row to hang it on and the gutter's `«` cannot reach it.
     *
     * Appended at the end, and that is a position the model cannot misread: an `absent` row takes no part
     * in [ExpectationEvaluator.align]'s scan — it claims no wire field, so it cannot shift what any other
     * row pairs with, and the judge checks it against the whole message wherever it sits. No precondition,
     * deliberately: an `absent` for a tag the reference *does* carry goes red on the very next re-judge,
     * which is the honest answer, where refusing to add it would only hide the question.
     */
    fun insertAbsent(draft: Expectation, tag: Int): Expectation =
        draft.copy(fields = draft.fields + FieldExpectation(tag, Matcher.Absent))

    /**
     * `absent` asserts the tag appears **nowhere in the message** — it is not scoped to one occurrence, and
     * it cannot be.
     *
     * So it may only be offered when the reply carries none of that tag. Offer it on the third row of a
     * three-party expectation whose venue now sends two, and the author gets an assertion that can never
     * pass while the other two entries exist: the row said "the reply has no 452 at all", which was false,
     * and the fix it suggested is unsatisfiable. Dropping the tag is the honest repair there.
     */
    fun canAssertAbsent(draft: Expectation, message: MessageView, index: Int): Boolean =
        message.fields().none { it.first == draft.fields[index].tag }

    /**
     * Stop checking this field.
     *
     * Dropping a row of a **repeated** tag would promote its later siblings — the second `452` row would
     * become the first, silently re-aiming it at the executing firm's entry while it still claims to
     * check the clearing firm's. So a drop takes the tag's rows with it, all of them. To stop caring
     * about one entry while still checking another, [loosen] it to `presence`: its position is what
     * addresses the others.
     */
    fun drop(draft: Expectation, index: Int): Expectation {
        val tag = draft.fields[index].tag
        val repeated = draft.fields.count { it.tag == tag } > 1
        val kept = if (repeated) draft.fields.filterNot { it.tag == tag } else draft.fields.filterIndexed { i, _ -> i != index }
        return draft.copy(fields = kept)
    }

    /** True when [drop] would take more than the one row — the view has to say so before it happens. */
    fun dropTakesWholeTag(draft: Expectation, index: Int): Boolean =
        draft.fields.count { it.tag == draft.fields[index].tag } > 1

    // ------------------------------------------------------------------ the covering band, and the fix plan

    /**
     * The smallest numeric band that covers both the expectation and the reply — the arithmetic behind the
     * per-row `±` offer **and** the fix plan's default, in one place so the two cannot come to disagree
     * about what "covers" means.
     *
     * The subtraction runs on the decimal *strings*, because a double subtraction would put
     * `0.9149000000000001` in the tooltip and then in the scenario file. But the band must pass THE
     * EVALUATOR'S arithmetic, which is plain doubles: `abs(a - expected)` is not the decimal difference
     * (2.0 vs 1.9 differs from 0.1d by ~6 ulps, on the failing side). The pretty decimal is kept where it
     * already covers that; where rounding leaves it short, the evaluator's own difference is the tolerance
     * — ugly digits, but a Loosen that leaves the row red is a broken button.
     *
     * Null where there is no band to speak of: a matcher that is not Exact/Numeric, a side that does not
     * parse, or two sides that are already the same number (that is a formatting difference, not a value
     * difference, and [fixPlan] answers it separately — a band of ±0 "covering both sides" would be a
     * tooltip that describes nothing).
     */
    data class CoveringBand(val matcher: Matcher.Numeric, val expectedText: String, val decimalTolerance: String)

    fun coveringBand(current: Matcher, actualText: String?): CoveringBand? {
        if (actualText == null) return null
        val expectedText =
            when (current) {
                is Matcher.Exact -> current.value
                is Matcher.Numeric -> decimalText(current.expected)
                else -> return null
            }
        val expected = expectedText.toBigDecimalOrNull() ?: return null
        val actual = actualText.toBigDecimalOrNull() ?: return null
        if (expected.compareTo(actual) == 0) return null
        val decimalTolerance = (expected - actual).abs().stripTrailingZeros()
        val doubleDifference = kotlin.math.abs(expected.toDouble() - actual.toDouble())
        val tolerance = maxOf(decimalTolerance.toDouble(), doubleDifference)
        return CoveringBand(
            matcher = Matcher.Numeric(expected.toDouble(), tolerance),
            expectedText = expected.toPlainString(),
            decimalTolerance = decimalTolerance.toPlainString(),
        )
    }

    /** A double as the decimal it came from: no trailing `.0` on an integer, no scientific notation. */
    fun decimalText(d: Double): String =
        if (d == d.toLong().toDouble()) {
            d.toLong().toString()
        } else {
            // Via toString (shortest round-trip decimal), never BigDecimal(double) — which would expand
            // 1.9999 to its full binary form and put fifty digits in the tooltip.
            java.math.BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
        }

    /**
     * How the fix plan chooses each numeric band. [CoverBoth] asks each row for the smallest band covering
     * its own gap — every proposal repairs, by construction. [Uniform] is the knob: one tolerance for every
     * numeric row, and a row the tolerance does not reach is **marked, never hidden** — a policy that
     * quietly skipped the rows it cannot fix would report a plan that "fixes everything" over one that
     * does not.
     */
    sealed interface FixTolerance {
        object CoverBoth : FixTolerance

        data class Uniform(val tolerance: Double) : FixTolerance
    }

    /**
     * Which repair family a [PlannedFix] belongs to. The sheet groups by this, the gutter picks its glyph
     * by it, and [PlannedFix.defaultChecked] keys off it — one label, three consumers, no second opinion.
     */
    enum class FixClass { NUMERIC, TEMPORAL, ONE_OF, REGEX, PRESENCE }

    /**
     * One proposed edit of a fix plan: the row, what it asserts today, what the plan would write, why —
     * and whether the row would actually pass afterwards. The reason is a sentence because every offer on
     * the surface carries one; a bulk edit with less explanation than a single click would be the bulk
     * button teaching the author to stop reading.
     */
    data class PlannedFix(
        val index: Int,
        val tag: Int,
        val name: String,
        val current: Matcher,
        val proposed: Matcher,
        val reason: String,
        val repairs: Boolean,
        val klass: FixClass,
    ) {
        /**
         * D2 — **a proposal that still constrains the value defaults on; one that stops constraining it
         * defaults off.** A band, a set or a shape still pins the value and the reason names exactly what
         * widened; presence is the one class that asserts strictly less than the author had, so it takes
         * a deliberate opt-in — the bulk analogue of "value mismatches are accepted deliberately".
         */
        val defaultChecked: Boolean get() = klass != FixClass.PRESENCE
    }

    /**
     * **The fix plan: widen, admit or generalise until the reply fits — and touch nothing else.**
     *
     * The bulk answer to a re-run where the venue's *values drifted inside their meaning* — a fill price a
     * pip away, a timestamp outside its ±60s, a status one rung along its enum, an id that kept its scheme
     * and changed its counter. Five classes, one per way a value can drift (see [FixClass] and
     * [rowProposal], the per-row decider this maps): numeric bands and the temporal ladder as always; and
     * `oneOf` widening, pattern inference and presence demotion for the drifts that never had a bulk
     * answer. Every gate consults the seeder's classification ([ExpectationSeeder.numericFamily],
     * [ExpectationSeeder.textFamily], [ExpectationSeeder.identifierFamily], the dictionary's enum list) —
     * the same decider as the seed, so the plan can never call numeric what the seed called a code.
     *
     * What it must never do is decided by what it is given:
     * - **enum-coded ints** — `452 exact 4` failing as `1` is a role change, not drift; not in the numeric
     *   family, so no proposal exists for it;
     * - **rows the engine proved moved** — repaired by Accept-new-order as a unit, never per-row (D1);
     * - **references, unjudged rows, shape changes** (missing/unexpected/reordered) — the first two cannot
     *   be judged here, the rest are [acceptEveryShapeChange]'s territory and value-blind by design;
     * - **rebasing** — a proposal keeps the author's baseline and widens around it. Moving the baseline to
     *   this run's value is Accept-actual, per row, deliberately.
     */
    fun fixPlan(
        draft: Expectation,
        reference: ReferenceMessage,
        dictionary: FixDictionaryAdapter?,
        tolerance: FixTolerance = FixTolerance.CoverBoth,
        referenceResolver: (String) -> String? = { null },
    ): List<PlannedFix> {
        val moved = (reorder(draft, reference, referenceResolver) as? Reorder.Possible)?.moved.orEmpty()
        return rows(draft, reference, dictionary, referenceResolver).mapNotNull { row ->
            if (row.index != null && row.index in moved) return@mapNotNull null
            rowProposal(row, dictionary, tolerance, reference.anchorInstant)
        }
    }

    /**
     * **The one decider for what a failing value row may be offered** (A4). The plan maps every row
     * through this; the gutter renders the same call as the row's loosen-family offer — so the sheet and
     * the gutter cannot come to disagree about what a row is worth. The classes are disjoint by
     * construction — numeric family, enum-coded, and text are different answers from the same
     * classification — so a row receives at most one proposal, and only regex-vs-presence is ever
     * arbitrated: a tight pattern asserts strictly more, so it wins (D1).
     *
     * What no class may touch is unchanged from the plan's founding contract: rows that passed, rows
     * that cannot be judged, shape changes, references — and enum-coded ints never see a `±`, exactly
     * as before. The one thing this does **not** re-check is a proven move ([fixPlan] filters those with
     * the reorder in hand); a moved row's status is not [TagStatus.VALUE], so it cannot reach a class
     * here either.
     */
    fun rowProposal(
        row: Row,
        dictionary: FixDictionaryAdapter?,
        tolerance: FixTolerance = FixTolerance.CoverBoth,
        anchor: Instant? = null,
    ): PlannedFix? {
        val index = row.index ?: return null
        if (row.passed || row.unknown || row.status != TagStatus.VALUE) return null
        val name = dictionary?.getFieldName(row.tag) ?: ""
        return when (val matcher = row.matcher) {
            is Matcher.Temporal -> temporalFix(index, name, row, matcher, anchor)
            is Matcher.Numeric -> numericFix(index, name, row, matcher, tolerance)
            // A row the author made oneOf has had its kind decided — widening it by the actual is honest
            // on any field, exactly as an author-made Numeric is widenable off the numeric families. The
            // dictionary only decorates the reason with decoded names where it can.
            is Matcher.OneOf -> oneOfFix(index, name, row, matcher, matcher.values, dictionary)
            is Matcher.Exact ->
                when {
                    ExpectationSeeder.numericFamily(row.tag, dictionary) ->
                        numericFix(index, name, row, matcher, tolerance)
                    dictionary?.hasFieldValues(row.tag) == true ->
                        oneOfFix(index, name, row, matcher, listOf(matcher.value), dictionary)
                    else ->
                        regexFix(index, name, row, matcher, dictionary)
                            ?: presenceFix(index, name, row, matcher, dictionary)
                }
            else -> null
        }
    }

    /**
     * `∈` — the failing value joins the admitted set (S2). Only for a field whose values are a
     * vocabulary: the dictionary's enum list for an `Exact`, or a set the author already declared with
     * `oneOf`. The reason decodes every member it can — *"PartiallyFilled(1) | Filled(2)"* — because the
     * author is admitting a meaning, not a digit; and past three members it says what a set that wide
     * has become, instead of letting the row quietly turn into presence in disguise.
     */
    private fun oneOfFix(
        index: Int,
        name: String,
        row: Row,
        current: Matcher,
        values: List<String>,
        dictionary: FixDictionaryAdapter?,
    ): PlannedFix? {
        val actual = row.actual ?: return null
        if (values.isEmpty() || actual in values) return null
        val union = values + actual
        val decoded =
            union.joinToString(" | ") { v ->
                dictionary?.getFieldValueDescription(row.tag, v)?.let { "$it($v)" } ?: v
            }
        val admitted =
            when (union.size) {
                2 -> "both meanings now admitted"
                3 -> "all 3 meanings now admitted"
                else ->
                    "${union.size} meanings now admitted — if any value is acceptable, " +
                        "presence is the honest assertion"
            }
        return PlannedFix(
            index, row.tag, name, current, Matcher.OneOf(union),
            reason = "$decoded — $admitted",
            repairs = true,
            klass = FixClass.ONE_OF,
        )
    }

    /**
     * `≈` — the shape both runs share, as a pattern (S3). Gated by [ExpectationSeeder.textFamily] and by
     * [inferPattern]'s own tightness rule; the pattern is verified to full-match both sides before it is
     * offered, so a proposal that does not repair — or that stops matching the golden it was inferred
     * from — cannot exist.
     */
    private fun regexFix(
        index: Int,
        name: String,
        row: Row,
        current: Matcher.Exact,
        dictionary: FixDictionaryAdapter?,
    ): PlannedFix? {
        if (!ExpectationSeeder.textFamily(row.tag, dictionary)) return null
        val actual = row.actual ?: return null
        val pattern = inferPattern(current.value, actual) ?: return null
        return PlannedFix(
            index, row.tag, name, current, Matcher.Regex(pattern),
            reason = "/$pattern/ is the shape both runs share — the varying run is the venue's",
            repairs = true,
            klass = FixClass.REGEX,
        )
    }

    /**
     * `∃` — the venue mints this fresh per run; assert it arrives, not what it says (S1). Last resort by
     * design: offered only where [regexFix] found no shape to keep (D1 — a tight pattern asserts strictly
     * more), and only on [ExpectationSeeder.identifierFamily] fields. The reason carries the warning the
     * default-unchecked checkbox (D2) exists for: a *stable* id that changed is a regression, and its
     * honest repair is Accept actual, not this.
     */
    private fun presenceFix(
        index: Int,
        name: String,
        row: Row,
        current: Matcher,
        dictionary: FixDictionaryAdapter?,
    ): PlannedFix? {
        if (!ExpectationSeeder.identifierFamily(row.tag, dictionary)) return null
        val actual = row.actual ?: return null
        return PlannedFix(
            index, row.tag, name, current, Matcher.Presence,
            reason =
                "no shape shared with $actual — the venue mints this fresh per run; " +
                    "a stable id that changed is a regression: Accept actual instead",
            repairs = true,
            klass = FixClass.PRESENCE,
        )
    }

    // ------------------------------------------------------------------ the same fix, travelling (C1)

    /**
     * **The signature a per-row repair travels under** — what "fails the same way" means, made precise,
     * because same-tag alone is not intent (D4).
     *
     * A [Substitution] is Accept-actual on an `Exact` row: it travels only to rows failing with the very
     * same expected→actual pair — `FIRMA→FIRMB` on four party rows is one decision; the same tag failing
     * four different ways is four. A [LoosenClass] is a loosen-family repair: it travels to rows of the
     * same tag whose [rowProposal] lands in the same class — each sibling gets its *own* proposal (its
     * own band, its own pattern), because "the same fix" is the same policy, not the same bytes. Drop
     * never travels (D3): [dropTakesWholeTag] and Accept-all-shape-changes own bulk removal, and a
     * travelling drop would re-aim survivors silently. Capture and track never travel either — they are
     * wiring, not repairs, and their names are row-scoped.
     */
    sealed interface SameFix {
        data class Substitution(val tag: Int, val expected: String, val actual: String) : SameFix

        data class LoosenClass(val tag: Int, val klass: FixClass) : SameFix
    }

    /** One sibling the same fix would repair: the row, and exactly what it would become. */
    data class SiblingFix(val index: Int, val tag: Int, val proposed: Matcher, val reason: String)

    /** One *other* step the same fix reaches (C2): its rows, re-gated in that step's own diff. */
    data class StepFixes(val stepId: String, val label: String, val fixes: List<SiblingFix>)

    /**
     * Every failing row the signature reaches, **re-gated row by row** — a substitution requires the same
     * Exact pair; a class requires [rowProposal] to fire in that class for that row. A row the gates
     * refuse (a reference, a different drift, a different class) is simply not a sibling: the same
     * decider that refuses it per-row refuses it here, so travelling cannot hand out what a click would
     * not.
     */
    fun siblings(
        rows: List<Row>,
        fix: SameFix,
        dictionary: FixDictionaryAdapter?,
        anchor: Instant? = null,
    ): List<SiblingFix> =
        when (fix) {
            is SameFix.Substitution ->
                rows
                    .filter { r ->
                        r.index != null && !r.passed && !r.unknown && r.status == TagStatus.VALUE &&
                            r.tag == fix.tag &&
                            (r.matcher as? Matcher.Exact)?.value == fix.expected &&
                            r.actual == fix.actual
                    }.map { r ->
                        SiblingFix(
                            r.index!!, r.tag, Matcher.Exact(fix.actual),
                            "the same ${fix.expected} → ${fix.actual} substitution",
                        )
                    }
            is SameFix.LoosenClass ->
                rows.mapNotNull { r ->
                    if (r.tag != fix.tag) return@mapNotNull null
                    val proposal = rowProposal(r, dictionary, FixTolerance.CoverBoth, anchor)
                    if (proposal == null || proposal.klass != fix.klass) return@mapNotNull null
                    SiblingFix(proposal.index, proposal.tag, proposal.proposed, proposal.reason)
                }
        }

    /**
     * The character classes a varying run may generalise to, narrowest first — the first that covers both
     * sides wins, so `0117`/`0245` becomes `\d+` and never the `[A-Za-z0-9]+` that would also fit.
     */
    private val CLASS_LADDER =
        listOf<Pair<String, (Char) -> Boolean>>(
            "\\d+" to { c -> c.isDigit() },
            "[A-Z]+" to { c -> c in 'A'..'Z' },
            "[a-z]+" to { c -> c in 'a'..'z' },
            "[A-Z0-9]+" to { c -> c.isDigit() || c in 'A'..'Z' },
            "[A-Za-z0-9]+" to { c -> c.isDigit() || c in 'A'..'Z' || c in 'a'..'z' },
        )

    private val REGEX_META = setOf('\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}')

    /** The literal parts of an inferred pattern, escaped character by character — never `\Q…\E`, which is what [Regex.escape] writes and no author would type into a scenario file. */
    private fun escapeLiteral(s: String): String =
        buildString {
            for (c in s) {
                if (c in REGEX_META) append('\\')
                append(c)
            }
        }

    /**
     * **The conservative pattern two values share, or null when they share none worth asserting.**
     *
     * Longest common prefix and suffix stay literal; both varying middles must be non-empty and drawn
     * from the same rung of [CLASS_LADDER]. A boundary character of the winning class is pulled *into*
     * the varying run — `ORD-1` vs `ORD-12` shares the prefix `ORD-1`, but the honest pattern is
     * `ORD-\d+`, not `ORD-1\d*`: the trailing digit of the shared prefix belongs to the counter, not the
     * scheme. **Tight means at least one literal character survives** — an unanchored `[A-Z0-9]+`
     * full-matches almost anything while reading like an assertion, which is the regex spelling of the
     * `452 ± 3` trap. The result is verified to full-match both inputs before it is returned.
     */
    internal fun inferPattern(expected: String, actual: String): String? {
        if (expected == actual || expected.isEmpty() || actual.isEmpty()) return null
        var p = 0
        val maxShared = minOf(expected.length, actual.length)
        while (p < maxShared && expected[p] == actual[p]) p++
        var s = 0
        while (s < maxShared - p && expected[expected.length - 1 - s] == actual[actual.length - 1 - s]) s++
        for ((token, member) in CLASS_LADDER) {
            var pp = p
            while (pp > 0 && member(expected[pp - 1])) pp--
            var ss = s
            while (ss > 0 && member(expected[expected.length - ss])) ss--
            val midExpected = expected.substring(pp, expected.length - ss)
            val midActual = actual.substring(pp, actual.length - ss)
            if (midExpected.isEmpty() || midActual.isEmpty()) continue
            if (!midExpected.all(member) || !midActual.all(member)) continue
            val prefix = expected.substring(0, pp)
            val suffix = expected.substring(expected.length - ss)
            if (prefix.isEmpty() && suffix.isEmpty()) continue // no literal anchor — asserts nothing
            val pattern = escapeLiteral(prefix) + token + escapeLiteral(suffix)
            val compiled = Matcher.Regex(pattern).compiled() ?: continue
            if (compiled.matches(expected) && compiled.matches(actual)) return pattern
        }
        return null
    }

    private fun numericFix(
        index: Int,
        name: String,
        row: Row,
        current: Matcher,
        mode: FixTolerance,
    ): PlannedFix? {
        val actualText = row.actual ?: return null
        val actual = actualText.toDoubleOrNull() ?: return null
        val expected =
            when (current) {
                is Matcher.Numeric -> current.expected
                is Matcher.Exact -> current.value.toDoubleOrNull() ?: return null
                else -> return null
            }
        // The same number in different clothes — `1.0` against `1.00` — is an Exact row failing over
        // formatting. No band is needed and none would read sensibly; `numeric ±0` is the repair, because
        // numeric parses both sides and a venue that pads its decimals stops mattering.
        if (expected == actual) {
            val proposed = Matcher.Numeric(expected, 0.0)
            if (proposed == current) return null
            return PlannedFix(
                index, row.tag, name, current, proposed,
                reason = "the same number, formatted differently — numeric compares values, not text",
                repairs = true,
                klass = FixClass.NUMERIC,
            )
        }
        return when (mode) {
            is FixTolerance.CoverBoth -> {
                val band = coveringBand(current, actualText) ?: return null
                PlannedFix(
                    index, row.tag, name, current, band.matcher,
                    reason = "${band.expectedText} ± ${band.decimalTolerance} is the smallest band covering both sides",
                    repairs = true,
                    klass = FixClass.NUMERIC,
                )
            }
            is FixTolerance.Uniform -> {
                val proposed = Matcher.Numeric(expected, mode.tolerance)
                val repairs = ExpectationEvaluator.satisfies(proposed, actualText)
                PlannedFix(
                    index, row.tag, name, current, proposed,
                    reason =
                        if (repairs) {
                            "± ${decimalText(mode.tolerance)} covers the reply's $actualText"
                        } else {
                            "± ${decimalText(mode.tolerance)} does not reach the reply's $actualText — this row stays red"
                        },
                    repairs = repairs,
                    klass = FixClass.NUMERIC,
                )
            }
        }
    }

    /**
     * The steps a widened `~now` is offered at. Friendly numbers, because `±83s` in a scenario file reads
     * as a measurement where `±120s` reads as a decision — and the next run's skew is not this run's, so
     * the headroom the next step up buys is the point. Beyond a day, no rung: a timestamp a day from its
     * anchor is not clock drift, and widening over it would assert nothing.
     */
    private val TEMPORAL_LADDER = listOf(60L, 120L, 300L, 600L, 1800L, 3600L, 7200L, 86400L)

    private fun temporalFix(
        index: Int,
        name: String,
        row: Row,
        current: Matcher.Temporal,
        anchor: Instant?,
    ): PlannedFix? {
        if (current.kind != TemporalKind.NOW_WITHIN_TOLERANCE) return null // TODAY has no band to widen
        if (anchor == null) return null // unanchored is unjudged, and never got here — belt to that braces
        val actualText = row.actual ?: return null
        val instant = ExpectationEvaluator.parseTimestamp(actualText) { anchor } ?: return null
        val skew = kotlin.math.abs(instant.epochSecond - anchor.epochSecond)
        val widened = TEMPORAL_LADDER.firstOrNull { it >= skew && it > current.toleranceSeconds } ?: return null
        return PlannedFix(
            index, row.tag, name, current, Matcher.Temporal(current.kind, widened),
            reason = "the reply's moment is ${skew}s from the reference's — ±${widened}s covers it with headroom",
            repairs = true,
            klass = FixClass.TEMPORAL,
        )
    }

    /**
     * Assert a field the reply carried and the expectation never mentioned.
     *
     * The new row is inserted **where it pairs** — after every row that claims an earlier field of the
     * message, before every row that claims a later one. Appending it at the end would have made the
     * expectation stop being a subsequence of the very message it was just built from: the row would
     * pair with nothing and report itself `moved` the moment it was added.
     */
    fun addAssertion(
        draft: Expectation,
        message: MessageView,
        wireIndex: Int,
        dictionary: FixDictionaryAdapter?,
    ): Expectation {
        val wire = message.fields()

        fun seed(at: Int): FieldExpectation {
            val (tag, value) = wire[at]
            return ExpectationSeeder
                .seedDetailed(listOf(tag to value), dictionary)
                .firstOrNull()
                ?.field
                ?: FieldExpectation(tag, Matcher.Exact(value))
        }

        // Ask the engine where the row would land, rather than guessing from the claimed positions.
        //
        // The guess was: insert after the last row claiming an earlier field. That is right until an earlier
        // occurrence of the same tag is *unclaimed* — and then the engine's greedy cursor gives the new row
        // THAT occurrence instead of the one the author clicked. Click "Assert it" on the second 448 of a
        // two-party group whose first 448 is unasserted, and you assert the first. Both being FIRMA, the row
        // goes green immediately, while the entry you actually clicked stays unasserted for ever: a false
        // green and a silent coverage hole from one click, on the surface that exists to close them.
        //
        // One decider: the engine says where a row pairs, and this tries positions until the engine agrees
        // the new row is checking the field that was clicked.
        fun insertPairingWith(target: Expectation, at: Int): Expectation? {
            val before =
                ExpectationEvaluator.align(target, wire)
                    .filter { it.row != null }
                    .mapNotNull { a -> a.index?.let { it to a.wireIndex } }

            for (position in 0..target.fields.size) {
                val candidate =
                    target.copy(fields = target.fields.toMutableList().apply { add(position, seed(at)) })
                val after = ExpectationEvaluator.align(candidate, wire)
                if (after.firstOrNull { it.index == position }?.wireIndex != at) continue

                // ...and nothing that was already pairing may be knocked off its field. Inserting a row too
                // early pairs it with the clicked field just as well, and pushes the engine's cursor past the
                // field the NEXT row was checking — which then reports itself missing. The engine is asked
                // both questions; neither is guessed.
                val intact =
                    before.all { (oldIndex, oldAt) ->
                        val shifted = if (oldIndex >= position) oldIndex + 1 else oldIndex
                        after.firstOrNull { it.index == shifted }?.wireIndex == oldAt
                    }
                if (intact) return candidate
            }
            return null
        }

        insertPairingWith(draft, wireIndex)?.let { return it }

        // No single row can reach that field: the engine's cursor cannot get past the earlier occurrences of
        // the same tag that nothing is asserting. Assert those first, in order — the clicked field then
        // becomes addressable. Asserting more than was asked for is visible and correctable; asserting a
        // field the author did not pick is neither.
        // Claimed by a ROW. align() also emits the reply's unasserted extras, and they carry a wireIndex —
        // counting those made every field look claimed, so nothing was ever added.
        val claimed =
            ExpectationEvaluator.align(draft, wire)
                .filter { it.row != null }
                .mapNotNull { it.wireIndex }
                .toSet()
        val tag = wire[wireIndex].first
        var next = draft
        for (at in (0..wireIndex).filter { wire[it].first == tag && it !in claimed }) {
            next = insertPairingWith(next, at) ?: next.copy(fields = next.fields + seed(at))
        }
        return next
    }

    /**
     * Re-order the expectation's rows to match the reply — the one-click answer to a venue that reshuffled.
     * Returns **null when there is no re-ordering that helps**, in which case the view must not offer it.
     *
     * The rule that makes this safe: **a row that already pairs with a field keeps that exact field.** Only
     * the rows that pair with nothing are relocated, and they are given the next *unclaimed* occurrence of
     * their tag — chosen by position, never by which occurrence would make the matcher pass.
     *
     * The first version of this asked a different question — it mapped the *k*-th row for tag `T` to the
     * *k*-th `T` in the reply — and that was a second pairing rule, which disagreed with the engine's. The
     * engine pairs with a greedy cursor, so a lone `452` row can legitimately be checking the *second*
     * `452` (an earlier row having pushed the cursor past the first). Re-ordering under the k-th rule then
     * silently moved that row onto the first `452`, turning a green row red — and the author, offered
     * "Accept actual" on it, would rebase their assertion onto a party entry they never chose while the
     * one they did choose quietly stopped being asserted at all. Green build, coverage gone. Two clicks.
     *
     * So the result is **verified before it is returned**: re-run the engine's own alignment on the new
     * order and require that every row lands on exactly the field it was given. If it does not, or if the
     * re-order does not actually repair the rows it was offered for, this returns null. A button that
     * claims to fix and does not is worse than no button.
     *
     * This is also why there are no per-row up/down arrows. Arrows let a user interleave rows into an order
     * no message has, and moving the second `452` above the first swaps which occurrence each checks — so a
     * row reading "the clearing firm's role is 4" quietly becomes "the executing firm's role is 4" while
     * still saying `452 exact 4` on screen.
     */
    fun acceptNewOrder(
        draft: Expectation,
        message: MessageView,
        referenceResolver: (String) -> String? = { null },
        now: () -> Instant = { Instant.now() },
    ): Expectation? = (reorder(draft, message, now, referenceResolver) as? Reorder.Possible)?.reordered

    /**
     * The rows a re-order would move — what the view brackets as "this entry moved", and the reason it can
     * show a party arriving out of order as *one* thing rather than as six unrelated value mismatches.
     *
     * Defined by the fix itself: a row is moved exactly when Accept-new-order would put it somewhere else.
     * There is no second opinion about it to disagree with the first.
     */
    fun movedRows(
        draft: Expectation,
        message: MessageView,
        referenceResolver: (String) -> String? = { null },
        now: () -> Instant = { Instant.now() },
    ): Set<Int> = (reorder(draft, message, now, referenceResolver) as? Reorder.Possible)?.moved ?: emptySet()

    /**
     * What the engine decided about re-ordering — **including, when it decided against, the reason**.
     *
     * The reason is not a nicety. A view that quietly declines to offer a move tells the author nothing, and
     * an author who is looking at six red rows in a party group and is offered no re-order concludes that
     * re-ordering is not implemented — which is precisely what happened. The engine knows exactly why it
     * withheld the move; it simply never said. Now it says.
     */
    sealed interface Reorder {
        /** A safe, verified re-order: [reordered] is what Accept-new-order applies, [moved] the rows it moves. */
        data class Possible(
            val reordered: Expectation,
            val moved: Set<Int>,
            /**
             * Row index → the field of the reply it lands on. **Where the moved rows actually are**, which
             * nothing else in here can answer: a moved row pairs with nothing, so its own alignment has no
             * wire position to show, and a diff that draws the entry crossing to the other side has to get
             * the other side from somewhere. It is what the engine already decided, published rather than
             * discarded.
             */
            val placement: Map<Int, Int> = emptyMap(),
        ) : Reorder

        /** No move is offered, and [why] is the sentence the author gets instead of silence. */
        data class Refused(
            val why: String,
        ) : Reorder

        /** Nothing is out of place. There was never a move to offer, and nothing to say about it. */
        object None : Reorder
    }

    /** The reason no re-order is on offer, or null when one is (or when there was never one to consider). */
    fun whyNoReorder(
        draft: Expectation,
        message: MessageView,
        referenceResolver: (String) -> String? = { null },
        now: () -> Instant = { Instant.now() },
    ): String? = (reorder(draft, message, now, referenceResolver) as? Reorder.Refused)?.why

    /**
     * **A row this diff cannot judge AT ALL.** Two of them, and they are the same row wearing two matchers:
     *
     * - a `reference`, whose expression resolves against a live run's variable scope, and there is none here —
     *   no caller has a resolver to pass, because there is nothing to pass;
     * - a `temporal`, when the message on the other side carries no moment of its own (a golden or a paste with
     *   no `SendingTime(52)`). There is no clock to judge "~now" by, and the wall clock is not one: it measures
     *   how long ago the message was written, which is not a fact about the venue.
     *
     * Asking *"does its value still hold?"* of such a row answers nothing about the message's **shape**, and
     * answering "no" — which is what a missing resolver and a missing clock must both answer — is what hid an
     * entry that had plainly moved. It is placed, never value-checked; placement is occurrence- or
     * block-preserving, so it keeps checking exactly the field it always did, and it is judged for real at
     * replay. [placeByOccurrence] has always known this; [verbatimWindow] did not; and the second bullet was
     * still missing after R2 fixed the first.
     */
    private fun judgeablePredicate(
        referenceResolver: (String) -> String?,
        temporalsJudgeable: Boolean,
    ): (Matcher) -> Boolean =
        { matcher ->
            when (matcher) {
                is Matcher.Reference -> referenceResolver(matcher.expression) != null
                is Matcher.Temporal -> temporalsJudgeable
                else -> true
            }
        }

    /**
     * The re-order, asked of a **[ReferenceMessage]** — which is the only overload the diff surface may use.
     *
     * It carries the moment to judge at *and* whether there is a moment at all, and both matter. A reference
     * with no `SendingTime(52)` of its own — a golden captured without one, a paste from a log — has nothing to
     * judge a `~now ±60s` row against, and [rows] already says so by returning that row `unknown`. Hand the
     * same rows to the plain [reorder] and its wall-clock fallback value-checks them anyway: the row "fails",
     * the block does not fit, the entry that plainly moved is refused its re-order, and the author is told the
     * values changed in place. That is R2 exactly, one matcher along — so the two must be asked the same
     * question, and this is where they are.
     */
    fun reorder(
        draft: Expectation,
        reference: ReferenceMessage,
        referenceResolver: (String) -> String? = { null },
    ): Reorder =
        reorder(
            draft = draft,
            message = reference.view,
            now = { reference.anchorInstant ?: Instant.now() },
            referenceResolver = referenceResolver,
            temporalsJudgeable = !reference.unanchored,
        )

    /**
     * A re-ordering that is safe, complete and verified — or nothing at all.
     *
     * There are exactly **two** ways a reply can be a reorder of the expectation rather than a regression of
     * it, and they need different proofs. Neither is "each failing row finds a field its matcher likes":
     * that is the matcher-driven pairing [ExpectationEvaluator.align] is forbidden from using, and it cannot
     * tell a venue reordering its two party entries (shape — FIRMA still holds role 1) from the two firms
     * swapping roles (behaviour — FIRMA now holds role 4). Both present as "the 448 rows fail". Re-aiming row
     * by row greens them both, brackets the regression as "entry moved — same values, different position",
     * and deletes the assertion that FIRMA holds role 1 in one click, silently. That is the false green this
     * model exists to make impossible, walked back in through the editor.
     */
    @Suppress("ReturnCount")
    fun reorder(
        draft: Expectation,
        message: MessageView,
        /**
         * The instant the rows are judged at — the instant the message ARRIVED, not the instant the view was
         * opened. It must be the same clock the view renders the diff with, or the engine and the view judge
         * the same rows differently: a `~now ±60s` row that passed during the run fails against the wall clock
         * an hour later, no placement is found, and a note explaining that "the values changed in place"
         * prints underneath a diff in which every row is green.
         */
        now: () -> Instant = { Instant.now() },
        /**
         * The scope a `reference` row resolves against. Without it, an echoed id inside a party entry can
         * never be recognised as part of an entry that moved — see [ExpectationEvaluator.satisfies].
         */
        referenceResolver: (String) -> String? = { null },
        /**
         * False when the message on the other side carries **no moment of its own**, so a `~now` row has no
         * clock to be judged by and must be placed rather than value-checked. See the [ReferenceMessage]
         * overload, which is the only thing that knows.
         */
        temporalsJudgeable: Boolean = true,
    ): Reorder {
        val wire = message.fields()
        val before = ExpectationEvaluator.diff(message, draft, referenceResolver, now)
        val paired = before.mapNotNull { d -> d.alignment.index?.let { it to d.alignment.wireIndex } }.toMap()
        val passes = before.mapNotNull { d -> d.alignment.index?.let { it to d.result.passed } }.toMap()
        val holds = { matcher: Matcher, value: String ->
            ExpectationEvaluator.satisfies(matcher, value, referenceResolver, now)
        }
        val judgeable = judgeablePredicate(referenceResolver, temporalsJudgeable)

        // Which strategy produced the placement decides what a refusal is allowed to SAY. placeByOccurrence is
        // structurally occurrence-preserving — it exists to cover a venue reshaping an entry internally — so a
        // refusal on top of it is a conservative withholding, not evidence that anything swapped.
        val byEntry = placeByMovedEntry(draft, wire, passes, paired, holds, judgeable)
        val placement =
            placeByOccurrence(draft, wire, passes, paired, holds)
                ?: byEntry
                ?: return noPlacement(draft, before)
        val fromMovedEntry = placement === byEntry

        val moved = placement.filter { (index, at) -> paired[index] != at }.keys
        if (moved.isEmpty()) return Reorder.None // nothing is out of place; a button here would do nothing

        // THE MOVED ROWS MUST FORM A SOLID SPAN. No stationary row may be stranded inside it.
        //
        // This is what stops an entry being torn in half, and it is the difference between a reorder and a
        // regression on the one shape that matters. Two firms swap roles: the venue's 448 and 447 now match
        // the *other* entry's, while both 452 rows still pass exactly where they are. A block rule alone
        // happily moves `448 447` across to the other entry and leaves the `452` sitting where it was — and
        // the expectation then reads "FIRMA, D, role 4", which is the regression, wearing the words "entry
        // moved · same values, different position". One click and "FIRMA holds role 1" is gone.
        //
        // An entry that really moved takes ALL of itself. So if row 3 sits between two rows that moved, and
        // row 3 did not, nothing coherent moved and the offer is withdrawn. The rows then say what they are:
        // values that changed.
        val span = moved.min()..moved.max()
        val stranded = span.filter { it !in moved && draft.fields[it].matcher !is Matcher.Absent }
        if (stranded.isNotEmpty()) {
            // Only the block rule's refusal is a torn entry. The occurrence rule can be withheld here too —
            // a venue that swaps 447 and 452 *inside* each party is a pure shape change, and the greedy cursor
            // can leave a row stranded in the span — but "the values changed in place" is then simply false,
            // and it would point the author at an Accept actual that re-aims their assertion onto another
            // party's field. When the tool is not certain what happened, it says nothing.
            return if (fromMovedEntry) tornEntry(draft, moved, stranded) else Reorder.None
        }

        // Rows that take no part in ordering (absent) park beside the row they already follow.
        val key = mutableMapOf<Int, Double>()
        var lastKey = -1.0
        for (index in draft.fields.indices) {
            val at = placement[index]
            if (at != null) {
                key[index] = at.toDouble()
                lastKey = at.toDouble()
            } else {
                lastKey += EPSILON
                key[index] = lastKey
            }
        }

        val permutation = draft.fields.indices.sortedBy { key.getValue(it) }
        val reordered = draft.copy(fields = permutation.map { draft.fields[it] })

        // Verify against the engine itself, never against our own reasoning about it. Every row this was
        // offered for must actually be repaired, and nothing that already worked may break.
        val after = ExpectationEvaluator.diff(message, reordered, referenceResolver, now)
        val passesNow = after.mapNotNull { d -> d.alignment.index?.let { it to d.result.passed } }.toMap()
        permutation.forEachIndexed { newIndex, originalIndex ->
            if (passes[originalIndex] == true && passesNow[newIndex] != true) {
                return Reorder.Refused(
                    "The only re-ordering that would explain these rows would stop a row that passes today " +
                        "(${draft.fields[originalIndex].tag}) from passing. A move that breaks a working " +
                        "assertion is not a move — so this is a value change, not a re-order.",
                )
            }
            // A reference resolves against a live run's scope and a temporal against the clock; neither can
            // pass HERE, offline. Demanding that every moved row "now passes" therefore refused any legitimate
            // re-order that carried one — and a captured scenario's ClOrdID row is a reference, so that was
            // most of them. They are placed by the rules above, which never re-aim, and judged for real on the
            // next run.
            val unjudgeable = draft.fields[originalIndex].matcher.let { it is Matcher.Reference || it is Matcher.Temporal }
            if (originalIndex in moved && !unjudgeable && passesNow[newIndex] == false) {
                return Reorder.Refused(
                    "The re-ordering that would put these rows where the reply has them does not actually " +
                        "repair them — tag ${draft.fields[originalIndex].tag} still would not match there. " +
                        "A button that claims to fix and does not is worse than no button, so none is offered.",
                )
            }
        }
        return Reorder.Possible(reordered, moved, placement)
    }

    /**
     * No placement at all: every row is already where the reply has it, and what changed is what the fields
     * *say*.
     *
     * Said only when a re-order was ever plausible — that is, when a failing row's tag appears more than once,
     * so there are entries that *could* have swapped. On a lone `151` that went from 0 to 500000 there is no
     * re-order to withhold and nothing to explain; announcing "no move is offered" there would be noise.
     */
    private fun noPlacement(draft: Expectation, before: List<ExpectationEvaluator.DiffRow>): Reorder {
        val counts = draft.fields.groupingBy { it.tag }.eachCount()
        val failing = before.filter { !it.result.passed && it.alignment.row != null }

        // Only a VALUE change may be described as one.
        //
        // This sentence used to fire on any failing row of a repeated tag, which includes the commonest shape
        // change there is: the venue stopped sending the third party. It then told the author, over a verdict
        // bar reading "3 tags missing · it is all shape", that the values had changed in place and the venue
        // was behaving differently — and advised an Accept actual that missing rows do not even offer. It also
        // fired on a `~now ±60s` row that only "fails" because it is being judged outside its run.
        //
        // A row this view cannot judge offline is not a row that changed, and a row that never arrived did not
        // change its value either. Neither is evidence of anything, and the tool does not speculate.
        val valueChanged =
            failing.any { d ->
                val repeated = (counts[d.alignment.tag] ?: 0) > 1
                val judgeable = d.alignment.row?.matcher.let { it !is Matcher.Reference && it !is Matcher.Temporal }
                repeated && judgeable &&
                    (d.result.status == TagStatus.VALUE || d.result.status == TagStatus.INVALID)
            }
        if (!valueChanged) return Reorder.None
        return Reorder.Refused(
            "These entries did not move. Every row is already paired with the field the reply has at that " +
                "position — it is the values there that changed, so this is the venue behaving differently, " +
                "not re-arranging its message. That is why no re-order is offered. Accept actual on the rows " +
                "that are now correct, if that is the new truth.",
        )
    }

    /**
     * A placement existed, and it would have torn an entry in half — the role swap.
     *
     * This is the refusal that matters most, and the one that read as a missing feature: two firms exchange
     * roles, the `448` rows fail, the `452` rows still pass exactly where they are, and a block rule alone
     * would happily carry `448` and `447` across to the other entry and leave the `452` behind. The
     * expectation would then read "FIRMA, D, role 4" under the words "entry moved · same values, different
     * position", and one click would delete "FIRMA holds role 1".
     */
    private fun tornEntry(draft: Expectation, moved: Set<Int>, stranded: List<Int>): Reorder {
        fun tags(indices: Collection<Int>) =
            indices
                .map { draft.fields[it].tag }
                .distinct()
                .sorted()
                .joinToString(", ")
        return Reorder.Refused(
            "These rows did not move. A re-order here would have to carry ${tags(moved)} across to the other " +
                "entry while ${tags(stranded)} stayed exactly where it is — and an entry that really moved " +
                "takes all of itself. So the entries did not swap places; the values changed in place. That " +
                "is the venue behaving differently, not re-arranging its message. Accept actual on the rows " +
                "that are now correct, if that is the new truth.",
        )
    }

    /**
     * Move a bracketed block of rows, as **one unit**, past its sibling — the manual override for when the
     * diff's own alignment is not the one the author knows to be true.
     *
     * The block swaps with the adjacent run of the same length whose **tag sequence is identical**: its
     * sibling entry. Two properties follow, and together they are the whole safety argument.
     *
     * - **The expectation's tag order does not change at all.** Swapping two adjacent runs that carry the
     *   same tags in the same order leaves the sequence of tags exactly as it was; only the matchers travel.
     *   So this cannot build the order no real message has — `448, 447, 448, 452, 452` — which is one of the
     *   two things a per-row arrow does.
     * - **No row travels alone.** Each row keeps its place *within its run*, and both runs stay contiguous, so
     *   the row asserting FIRMA stays adjacent to the row asserting role 1. The entry still describes one
     *   party; it describes it at a different position. That is the other thing a per-row arrow breaks: move
     *   the second `452` above the first and it silently begins checking the other firm's role while still
     *   reading `452 exact 4` on screen.
     *
     * Null when there is no such sibling — at the ends of a group, against a run of a different shape, or for
     * a **lone row of a repeated tag**, which is a per-row move wearing a block's clothes and is refused as
     * one.
     */
    fun moveBlock(draft: Expectation, block: IntRange, up: Boolean): Expectation? {
        // The block must BE an entry — not merely a window with an identically-tagged neighbour.
        //
        // Requiring only that the neighbouring run carry the same tags in the same order is necessary and
        // *not sufficient*, and the gap is not academic. In a group of three parties the window `447,452,448`
        // (rows 1..3) has exactly that neighbour in rows 4..6, so the swap would be accepted — and it welds
        // FIRMA's PartyID to FIRMC's role while every row still reads `452 exact 4` on screen. The tag order
        // survives, which is why the invariant test did not notice; the *entries* do not. Alignment to a real
        // entry boundary is the guard, and [entries] is the only thing allowed to say where one is.
        //
        // Now a caller of [moveEntry], which is the same rule generalised: swapping with the neighbour is
        // moving to the adjacent slot, and on entries of identical shape — which is all the row-order
        // heuristic can produce — the two are the same rearrangement. Passing no overlay keeps the entry
        // boundaries exactly where this has always taken them from.
        val siblings = entryRegions(draft).firstOrNull { region -> block in region } ?: return null
        val at = siblings.indexOf(block)
        return (moveEntry(draft, null, block, if (up) at - 1 else at + 1) as? MoveResult.Applied)?.expectation
    }

    /** True when [moveBlock] would do something — what the block header's ↑/↓ are enabled by. */
    fun canMoveBlock(draft: Expectation, block: IntRange, up: Boolean): Boolean =
        moveBlock(draft, block, up) != null

    // ------------------------------------------------------------------ moving, by one rule

    /** A hand move either happened, or it did not and the author is told why. Never a silent nothing. */
    sealed interface MoveResult {
        data class Applied(val expectation: Expectation) : MoveResult

        data class Refused(val why: String) : MoveResult
    }

    /**
     * **The rule, entire**, and everything below is an expression of it:
     *
     * > *No row is ever separated from the entry it belongs to; a row of a repeated tag may cross a
     * > same-tag sibling only if its whole entry crosses with it; and no move may land strictly inside
     * > another entry.*
     *
     * Every case falls out of that one sentence. A top-level scalar is an entry of one — it has no same-tag
     * siblings, so it moves freely, which is what fixes a hand-authored expectation whose rows are not in
     * the venue's order. A lone row of a repeated tag crossing its sibling is the per-row arrow, and it is
     * refused: move the second `452` above the first and the two rows swap *which occurrence* each checks,
     * so the row reading "the clearing firm's role is 4" quietly comes to mean "the executing firm's" while
     * still saying `452 exact 4` on screen. An entry crossing an entry carries all of itself, and that is
     * the same-tag crossing made safe — FIRMA's `448` becomes the second `448` *and* FIRMA's `452` becomes
     * the second `452`, in the same step, so the two rows that jointly say "FIRMA holds role 1" still say
     * it. And nothing may be dropped into the middle of a group, because no venue sends a message shaped
     * like that.
     *
     * [overlay] is what says where an entry begins. Given none, this falls back to [entries] — the row-order
     * heuristic — which is exactly what [moveBlock] has always used, and is why its behaviour is unchanged.
     */
    @Suppress("ReturnCount") // One guard per reason, each with its own sentence. Nesting them reads far worse.
    fun moveRow(draft: Expectation, overlay: GroupOverlay?, from: Int, to: Int): MoveResult {
        if (from == to) return MoveResult.Applied(draft)
        val moved = draft.fields.toMutableList().apply { add(to, removeAt(from)) }

        // The occurrence mapping is asked first, because when it is what breaks, it is what the author needs
        // to be told: not "that row belongs somewhere else" but *this move would silently re-aim two
        // assertions*. It is also the only guard that catches a repeated tag no entry brackets — a flat
        // expectation of `448, 11, 448` has no repeating run for the heuristic to find, and no group for the
        // dictionary to name, so nothing else here stops the two 448s from crossing.
        if (!occurrencesPreserved(draft.fields, moved)) {
            return MoveResult.Refused(occurrenceSwap(draft, from))
        }

        val entries = entryRangesOf(draft, overlay)
        val home = innermost(entries, from)
        val gap = if (to <= from) to else to + 1 // where the row lands, between two of the original rows

        homeRefusal(draft, entries, home, from, gap)?.let { return it }
        if (home == null) {
            spansOf(draft, overlay)
                .firstOrNull { gap > it.first && gap <= it.last }
                ?.let { return MoveResult.Refused(NOT_INSIDE_A_GROUP) }
        }
        return MoveResult.Applied(draft.copy(fields = moved))
    }

    /** Why this row may not leave where it is — or null when it may. */
    @Suppress("ReturnCount") // One guard per reason, each with its own sentence.
    private fun homeRefusal(
        draft: Expectation,
        entries: List<IntRange>,
        home: IntRange?,
        from: Int,
        gap: Int,
    ): MoveResult.Refused? {
        if (home == null) return null
        if (from == home.first) {
            return MoveResult.Refused(
                "An entry begins at tag ${draft.fields[from].tag}, so this row cannot move within it — the " +
                    "entry would start somewhere else, and a message shaped like that is one no venue sends. " +
                    "To move the entry, move the entry.",
            )
        }
        if (gap <= home.first || gap > home.last + 1) {
            return MoveResult.Refused(
                "This row belongs to the entry at rows ${home.first + 1}–${home.last + 1}. A row that leaves " +
                    "its entry starts asserting a field in a different one while still reading the same on " +
                    "screen — so entries move as units, and a row moves within its own. Move the entry instead.",
            )
        }
        // ...and not into a group nested inside its own entry, for the same reason.
        val nested = entries.filter { it != home && it.first >= home.first && it.last <= home.last }
        if (nested.any { gap > it.first && gap <= it.last }) return MoveResult.Refused(NOT_INSIDE_A_GROUP)
        return null
    }

    /**
     * Move a whole entry to another slot among its siblings — the entry drag, and the generalisation of
     * [moveBlock], which could only ever swap with the neighbour next door.
     *
     * Safe because the unit is an *entry*: every row travels, in order, so no row is separated from the
     * rows that give it its meaning. [overlay] is what says where an entry begins, and a window that begins
     * one row late is not an entry, whatever its tags look like.
     */
    @Suppress("ReturnCount") // One guard per reason, each with its own sentence.
    fun moveEntry(draft: Expectation, overlay: GroupOverlay?, entry: IntRange, toSlot: Int): MoveResult {
        val siblings =
            siblingRunsOf(draft, overlay).firstOrNull { entry in it }
                ?: return MoveResult.Refused(
                    "These rows are not an entry. An entry begins where its first field does — a run that " +
                        "begins one row late is the tail of one entry welded to the head of the next, which is " +
                        "why it looks so much like the rows above it. Only an entry may move.",
                )

        // A single-row "entry" of a repeated tag, where the DICTIONARY DID NOT SAY SO, is not an entry at all:
        // it is a bare run of the same tag — `448, 448, 448` — that the row-order heuristic bracketed because
        // a run of period 1 repeats like anything else does. Nothing travels with such a row, so swapping it
        // past its sibling re-aims two assertions, and it is the per-row arrow wearing a block's clothes.
        //
        // Where the dictionary DOES say so, it is a real entry that happens to hold one field —
        // `NoMDEntryTypes` is delimited by `MDEntryType` and contains nothing else — and moving it is the
        // ordinary entry move. Yes, the occurrence mapping changes: that is what an entry move IS. The rule
        // permits it because *the whole entry crosses*, and the danger it guards against — a row arriving
        // beside another entry's rows and being silently re-aimed onto them — cannot arise where the entry has
        // no other rows to be separated from. Refusing it, with a sentence about re-aiming assertions, took
        // the entry drag away from every one-field group and blamed the move for something it does not do.
        if (entry.count() == 1 &&
            draft.fields.count { it.tag == draft.fields[entry.first].tag } > 1 &&
            !dictated(overlay, entry)
        ) {
            return MoveResult.Refused(occurrenceSwap(draft, entry.first))
        }

        val at = siblings.indexOf(entry)
        if (toSlot !in siblings.indices) {
            // Say where the entry actually is, not "first or last" — an entry dragged past the end of a group
            // of three from the MIDDLE was told it "is already last in its group", which it plainly is not, and
            // a refusal that describes the wrong situation is worse than one that says nothing.
            return MoveResult.Refused(
                "This group has ${siblings.size} ${if (siblings.size == 1) "entry" else "entries"} and this one " +
                    "is number ${at + 1}, so there is nowhere for it to go at slot ${toSlot + 1}. An entry may " +
                    "only change places with its own siblings.",
            )
        }
        if (toSlot == at) return MoveResult.Applied(draft)

        val reordered = siblings.toMutableList().apply { add(toSlot, removeAt(at)) }
        val region = siblings.first().first..siblings.last().last
        val rows =
            draft.fields.subList(0, region.first) +
                reordered.flatMap { range -> range.map { draft.fields[it] } } +
                draft.fields.subList(region.last + 1, draft.fields.size)
        return MoveResult.Applied(draft.copy(fields = rows))
    }

    /**
     * Drop a whole entry — the repair for "this environment sends one fewer party".
     *
     * [drop] refuses to take a single row of a repeated tag precisely because deleting it silently re-aims
     * the survivors. Deleting an ENTRY re-aims its later siblings too — and there it is the point: the
     * entry is gone from this venue's replies, so the siblings that remain must move up one occurrence to
     * describe the entries that remain. What makes it honest where the row delete is not: the whole entry
     * goes at once — its delimiter and every row under it — so no entry is ever left half-described, and
     * the re-judge the surface runs on every edit shows immediately which occurrences the survivors now
     * face. Without this, "QA has one less party" meant either dropping every row of each of the entry's
     * tags (taking the other parties' assertions with them) or a full re-seed (losing every hand-tuned
     * matcher on every other row). Both were the wrong surgery for a one-entry difference.
     */
    fun dropEntry(draft: Expectation, overlay: GroupOverlay?, entry: IntRange): MoveResult {
        if (siblingRunsOf(draft, overlay).none { entry in it }) {
            return MoveResult.Refused(
                "These rows are not an entry. An entry begins where its first field does — a run that " +
                    "begins one row late is the tail of one entry welded to the head of the next. Only an " +
                    "entry may be dropped whole.",
            )
        }
        // Same rule as the entry move: a bare run of one repeated tag that the heuristic bracketed is not
        // an entry, and "dropping" it is the per-row delete wearing a band's clothes — the exact silent
        // re-aim [drop]'s whole-tag rule exists to prevent. Where the dictionary says the one-field entry
        // is real (NoMDEntryTypes), it drops like any entry.
        if (entry.count() == 1 &&
            draft.fields.count { it.tag == draft.fields[entry.first].tag } > 1 &&
            !dictated(overlay, entry)
        ) {
            return MoveResult.Refused(
                "This is a bare run of tag ${draft.fields[entry.first].tag}, not a dictionary entry — " +
                    "deleting one row of it would silently re-aim which occurrence every row below it " +
                    "checks. Drop the tag (which takes all of its rows), or loosen the rows instead.",
            )
        }
        return MoveResult.Applied(draft.copy(fields = draft.fields.filterIndexed { index, _ -> index !in entry }))
    }

    /**
     * **Every row still asserts the occurrence it asserted before.** The one thing a per-row move may never
     * change, and the reason an entry move is allowed to change it: there, the whole entry crosses, so the
     * rows that jointly describe one party keep describing it.
     */
    private fun occurrencesPreserved(before: List<FieldExpectation>, after: List<FieldExpectation>): Boolean =
        before.map { it.tag }.distinct().all { tag ->
            val was = before.withIndex().filter { it.value.tag == tag }.map { it.value }
            val now = after.filter { it.tag == tag }
            was.size == now.size && was.indices.all { was[it] === now[it] }
        }

    private fun occurrenceSwap(draft: Expectation, index: Int): String {
        val row = draft.fields[index]
        return "Moving this row past another tag ${row.tag} would swap which occurrence each of them checks. " +
            "The row reading '${ExpectationEvaluator.describe(row.matcher)}' would quietly begin asserting the " +
            "other entry's field " +
            "while still saying exactly the same thing on screen — which is the assert-the-wrong-field failure " +
            "this model exists to make impossible. Move the whole entry, and every row of it travels together."
    }

    private const val NOT_INSIDE_A_GROUP =
        "A field cannot sit inside a repeating group's entries — no venue sends a message shaped like that, " +
            "so the expectation would stop being a subsequence of any reply it could ever be judged against. " +
            "Drop it before or after the group."

    /** Every entry, at every depth: the dictionary's answer where there is one, the row order's where there is not. */
    private fun entryRangesOf(draft: Expectation, overlay: GroupOverlay?): List<IntRange> =
        overlay?.entries?.map { it.rows } ?: entries(draft)

    /** The sibling lists an entry may move within. */
    private fun siblingRunsOf(draft: Expectation, overlay: GroupOverlay?): List<List<IntRange>> =
        overlay?.siblingGroups?.map { group -> group.map { it.rows } } ?: entryRegions(draft)

    /** Each group's whole footprint — nothing from outside it may be dropped in. */
    private fun spansOf(draft: Expectation, overlay: GroupOverlay?): List<IntRange> =
        overlay?.spans ?: entryRegions(draft).map { it.first().first..it.last().last }

    /**
     * Did the **dictionary** say these rows are an entry, or did the row order merely suggest it?
     *
     * It is the difference between `NoMDEntryTypes` — a real group whose entries hold one field each — and a
     * bare run of the same tag that the period-detection bracketed because a period of 1 repeats like any
     * other. The first may move as an entry; the second is not an entry, and the row inside it moves alone or
     * not at all. Nothing else in here needs to tell them apart, and this is the only thing that can.
     */
    private fun dictated(overlay: GroupOverlay?, entry: IntRange): Boolean =
        overlay?.entries?.any { it.rows == entry && it.source == EntrySource.DICTIONARY } == true

    /** The innermost entry containing [row] — the one a move of it is bounded by. */
    private fun innermost(entries: List<IntRange>, row: Int): IntRange? =
        entries.filter { row in it }.minByOrNull { it.last - it.first }

    /**
     * **Where the entries are** — the only thing permitted to say where a move may cut.
     *
     * The model has no groups, and it is not getting any: position is the address, and there is no path, no
     * group, no entry in an [Expectation]. But a repeating group still leaves a *footprint* in the row order —
     * a maximal run of rows whose tag sequence repeats, `448,447,452,448,447,452` — and that footprint is
     * structural, derivable from the rows alone, and enough to say where one party ends and the next begins.
     *
     * Anchoring matters more than it looks. Entries are the period-sized chunks of the run **counted from the
     * run's start**, never from wherever a diff happened to begin bracketing: a window that starts one row
     * late is `447,452,448`, which is the same three tags in a rotation, has an identically-shaped neighbour,
     * and is exactly the fragment that tears a party in half.
     */
    fun entries(draft: Expectation): List<IntRange> = entryRegions(draft).flatten()

    /**
     * The row-order heuristic over an expectation's tags — **the documented fallback**, not the primary source.
     * Since the dictionary overlay landed (Phase 0), [com.knapsack.fixtool.service.compare.GroupOverlay] decides
     * entry boundaries wherever the dictionary defines the group; this is reached only where it does not (the
     * proposal's *"demoted to fallback behind the dictionary overlay"*). The overlay-aware callers here
     * ([entryRangesOf], [siblingRunsOf], [spansOf]) fall back to it with `?:`, and `moveBlock` — kept as the
     * regression net that proves the D1 rule subsumes the old behaviour — still takes it directly.
     */
    fun entryRegions(draft: Expectation): List<List<IntRange>> = entryRegions(draft.fields.map { it.tag })

    /**
     * The entries, grouped by the repeating run they belong to — siblings may only swap within their own run.
     *
     * Taken over a bare tag sequence rather than an [Expectation] because the same guess has to be made
     * about a *message* whose group the dictionary does not define — see
     * [com.knapsack.fixtool.service.compare.GroupOverlay], which is the primary source of entry boundaries
     * now and calls this only where the dictionary has nothing to say.
     */
    fun entryRegions(tags: List<Int>): List<List<IntRange>> {
        val regions = mutableListOf<List<IntRange>>()
        var i = 0
        while (i < tags.size) {
            val best = longestRepeat(tags, i)
            if (best == null) {
                i++
                continue
            }
            val (period, repeats) = best
            regions += (0 until repeats).map { k -> (i + k * period)..(i + (k + 1) * period - 1) }
            i += period * repeats
        }
        return regions
    }

    /** The longest run of repeats starting at [from]: its period and how many times it repeats (≥ 2), or null. */
    private fun longestRepeat(tags: List<Int>, from: Int): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        for (period in 1..(tags.size - from) / 2) {
            var repeats = 1
            while (from + (repeats + 1) * period <= tags.size &&
                (0 until period).all { tags[from + repeats * period + it] == tags[from + it] }
            ) {
                repeats++
            }
            if (repeats >= 2 && (best == null || period * repeats > best.first * best.second)) {
                best = period to repeats
            }
        }
        return best
    }

    /**
     * **The venue reordered the fields, and every row still checks the occurrence it always checked.**
     *
     * The *k*-th row for a tag is placed on the *k*-th occurrence of that tag — which is the model's own
     * pairing rule, so this placement **cannot re-aim an assertion**: no row can end up describing a
     * different entry than it did, whatever the reply's field order. That is the entire safety argument, and
     * it is structural rather than a check that might miss a case.
     *
     * It covers the venue that reshapes an entry internally — sending `447` before `448` where it used to
     * send `448` first — which is a pure shape change and a legitimate one-click accept.
     *
     * Null when this is not merely a reorder: a tag with fewer occurrences than the expectation has rows for
     * it (the venue dropped an entry — that is missing, not moved), or a row whose value no longer holds at
     * its own occurrence (the venue changed a value — that is behaviour, not shape). A `reference` row cannot
     * be judged offline, so it is placed but not value-checked; placement is occurrence-preserving, so it
     * keeps checking exactly the field it always did and the reference is still verified at replay.
     */
    private fun placeByOccurrence(
        draft: Expectation,
        wire: List<Pair<Int, String>>,
        passes: Map<Int, Boolean>,
        paired: Map<Int, Int?>,
        holds: (Matcher, String) -> Boolean,
    ): Map<Int, Int>? {
        val byTag = wire.withIndex().groupBy { it.value.first }
        val seen = mutableMapOf<Int, Int>()
        val place = mutableMapOf<Int, Int>()
        for ((index, row) in draft.fields.withIndex()) {
            if (row.matcher is Matcher.Absent) continue // takes no part in ordering
            val k = seen.merge(row.tag, 1, Int::plus)!! - 1
            val field = byTag[row.tag]?.getOrNull(k) ?: return null // the venue sends fewer: missing, not moved

            // A row that ALREADY PASSES keeps the field the engine has it checking — and here is why this
            // rule is not redundant with the occurrence rule that produced the placement.
            //
            // The engine's greedy cursor and "the k-th row for a tag → the k-th occurrence of it" can
            // disagree. An earlier row can push the cursor past the first 452, so the engine has a lone 452
            // row checking the SECOND party entry — and passing there. This placement calls it occurrence 0
            // and moves it onto the FIRST entry, where `presence` passes just as well. Both green, and the
            // assertion now describes a party the author never chose while the one they did choose silently
            // stops being asserted. A second decider for "which field does this row assert", which is the one
            // thing this model exists to make impossible.
            if (passes[index] == true && paired[index] != field.index) return null
            // A reference and a temporal row have no fixed value to compare — one resolves against a live
            // run's scope, the other against the clock — so asking "did this value change?" of them answers
            // nothing about the message's *shape*. They are placed, not value-checked. Placement is
            // occurrence-preserving, so each keeps checking exactly the field it always did, and both are
            // still judged for real at replay.
            val hasFixedValue = row.matcher !is Matcher.Reference && row.matcher !is Matcher.Temporal
            if (hasFixedValue && !holds(row.matcher, field.value.second)) {
                return null // the value at this row's own occurrence changed: behaviour, not shape
            }
            place[index] = field.index
        }
        return place
    }

    /**
     * **A whole entry moved**, and it appears **verbatim** in the reply — same tags, same values,
     * contiguously, in the same order.
     *
     * The view's own label says exactly this ("same tags, same values, different position"), and it is what
     * separates the two party entries swapping places from the two firms swapping roles. An entry that really
     * moved is still there, intact, somewhere else. A role swap leaves no such run anywhere, because the
     * `(firm, role)` pairing is precisely what changed.
     *
     * A **one-row** block is only allowed when its tag occurs once in the expectation. Without that, this rule
     * degenerates into the row-by-row re-aiming it exists to replace: a lone row "matching a window" is just
     * the row matching some field somewhere, and shifting one `452` past another silently swaps which
     * occurrence the two rows check — a row reading "the clearing firm's role is 4" comes to mean "the
     * executing firm's", while still saying `452 exact 4` on screen.
     */
    @Suppress("LongParameterList") // The engine's inputs, threaded whole: dropping one is how a clock gets lost.
    private fun placeByMovedEntry(
        draft: Expectation,
        wire: List<Pair<Int, String>>,
        passes: Map<Int, Boolean>,
        paired: Map<Int, Int?>,
        holds: (Matcher, String) -> Boolean,
        judgeable: (Matcher) -> Boolean,
    ): Map<Int, Int>? {
        val occurrences = draft.fields.groupingBy { it.tag }.eachCount()

        fun movable(i: Int) = draft.fields[i].matcher !is Matcher.Absent

        // Longest first, so a whole party is preferred over any single row inside it.
        val blocks = mutableListOf<List<Int>>()
        for (length in draft.fields.size downTo 1) {
            for (start in 0..draft.fields.size - length) {
                val run = (start until start + length).toList()
                if (!run.all(::movable)) continue
                if (run.none { passes[it] == false }) continue // nothing here is broken; nothing to move
                if (length == 1 && occurrences.getOrDefault(draft.fields[start].tag, 0) > 1) continue
                blocks += run
            }
        }

        val place = mutableMapOf<Int, Int>()
        val used = mutableSetOf<Int>()
        for (block in blocks) {
            if (block.any { it in place }) continue
            val at = verbatimWindow(draft, block, wire, used, holds, judgeable) ?: continue
            block.forEachIndexed { offset, index ->
                place[index] = at + offset
                used += at + offset
            }
        }
        if (place.isEmpty()) return null

        // Everything else keeps **the field the engine already has it checking** — not "the first free field
        // of the right tag", which is the re-aiming this whole rule exists to prevent. A green row checking
        // the *second* 452 (an earlier row having pushed the cursor past the first) would otherwise be handed
        // the first one, turn red, and the author — offered "Accept actual" on it — would rebase their
        // assertion onto a party entry they never chose while the one they did choose silently stopped being
        // asserted. Green build, coverage gone, two clicks.
        for ((index, row) in draft.fields.withIndex()) {
            if (index in place || row.matcher is Matcher.Absent) continue
            val at = paired[index] ?: continue // nothing was checking it; leave it unplaced
            if (at in used) return null // a moved block wants the field this row is checking — not a safe move
            place[index] = at
            used += at
        }
        return place
    }

    /**
     * Where [block] appears in the reply verbatim — every row satisfied by the field at its own offset, the
     * whole run contiguous and in order, over fields nothing else has claimed.
     *
     * This is the one place a matcher is asked about *position*, and it is safe precisely because it is asked
     * about the whole run at once. A contiguous run of rows matching a contiguous run of fields, value for
     * value, is an entry that moved. A single row matching some field somewhere is not.
     *
     * **A row that cannot be judged here contributes its tag and nothing else.** This asked `holds` of every
     * row, including a `reference` — which resolves against a live run's variable scope, and there is none in
     * a reconcile view, so it answered *false* about a row it simply could not read. One echoed id inside a
     * party entry was therefore enough to hide the fact that the entry had moved: the block never fitted, no
     * placement was found, and the tool told the author — in its own words — that *"these entries did not
     * move; it is the values there that changed"* about a message whose entries had plainly swapped. The
     * anchor threading fixed the same bug for temporals, which have a moment to be judged at once the arrival
     * instant is passed down; a reference has no such moment, and it needed this instead.
     *
     * The proof survives, because the *other* rows still pin the window value for value, and a block with no
     * judgeable row in it proves nothing at all and is refused outright — a run of rows nobody can read
     * "matching" a run of fields is just a tag sequence, and tag sequences repeat. That is the rotation trap
     * with the values taken away.
     */
    @Suppress("LongParameterList") // The engine's inputs, threaded whole: dropping one is how a clock gets lost.
    private fun verbatimWindow(
        draft: Expectation,
        block: List<Int>,
        wire: List<Pair<Int, String>>,
        used: Set<Int>,
        holds: (Matcher, String) -> Boolean,
        judgeable: (Matcher) -> Boolean,
    ): Int? {
        if (block.isEmpty() || wire.size < block.size) return null
        if (block.none { judgeable(draft.fields[it].matcher) }) return null
        for (at in 0..wire.size - block.size) {
            if ((at until at + block.size).any { it in used }) continue
            val fits =
                block.withIndex().all { (offset, index) ->
                    val row = draft.fields[index]
                    val field = wire[at + offset]
                    field.first == row.tag &&
                        (!judgeable(row.matcher) || holds(row.matcher, field.second))
                }
            if (fits) return at
        }
        return null
    }

    /** Enough to keep an unplaceable row beside its neighbour without colliding with a real wire position. */
    private const val EPSILON = 0.001

    /** The re-order, if there is one that helps — what the view checks before offering the button. */
    fun canAcceptNewOrder(draft: Expectation, message: MessageView): Boolean = acceptNewOrder(draft, message) != null

    /**
     * **Shape or behaviour** — the split the whole view is organised around, and the one thing a reader
     * should never have to work out for themselves.
     *
     * A venue that reorders its party entries, adds an optional tag, or stops sending one has changed the
     * *shape* of the message. A venue that fills 500,000 where it used to fill nothing has changed its
     * *behaviour*. Only the second is a regression; the first is a scenario that needs re-baselining. Four
     * red rows on screen say nothing about which of those just happened — the verdict line does.
     */
    fun isBehaviourChange(row: Row): Boolean =
        !row.passed && !row.unknown && (row.status == TagStatus.VALUE || row.status == TagStatus.INVALID)

    /** Every failing row that is the message's shape changing rather than the venue's behaviour. */
    fun isShapeChange(row: Row): Boolean =
        !row.passed && !row.unknown &&
            (row.status == TagStatus.MISSING || row.status == TagStatus.MOVED || row.status == TagStatus.UNEXPECTED)

    /**
     * Accept every *shape* change at once — the reorder, the tags the venue added, the ones it stopped
     * sending — and leave every behaviour change alone.
     *
     * The bulk button exists because shape churn is the common case and it is tedious, not interesting. It
     * deliberately will not touch a value mismatch: those are the rows that mean something, and they are
     * accepted one at a time, deliberately, or not at all.
     */
    fun acceptEveryShapeChange(
        draft: Expectation,
        message: MessageView,
        dictionary: FixDictionaryAdapter?,
    ): Expectation {
        var next = draft

        // Tags the venue stopped sending: assert them absent where that is meaningful.
        //
        // What it must NEVER do is DROP a repeated tag. `drop` takes every row for that tag — it must, or the
        // survivors would silently change which occurrence they check — so dropping one missing 448 also
        // deletes the 448 that is failing because the venue sent a different firm, and every matcher the
        // author deliberately loosened or wired to a scenario variable along with it. The next loop then
        // re-seeded the whole tag `Exact` from the reply. A button whose contract is "it will not touch a
        // value mismatch" was quietly accepting the regression AND throwing away the author's work.
        //
        // A repeated tag with a missing occurrence is left exactly as it is, for the author to decide about
        // one row at a time, deliberately.
        val handled = mutableSetOf<Int>()
        var progressed = true
        while (progressed) {
            progressed = false
            val current = rows(next, message, dictionary)
            val missing =
                current.firstOrNull { it.status == TagStatus.MISSING && it.index != null && it.tag !in handled }
                    ?: continue
            val index = missing.index!!
            when {
                canAssertAbsent(next, message, index) -> next = assertAbsent(next, index)
                dropTakesWholeTag(next, index) -> handled += missing.tag // never in bulk
                else -> next = drop(next, index)
            }
            progressed = true
        }

        // Tags the venue added: assert them, seeded from the dictionary, at the position they arrive in.
        //
        // Only in STRICT. In OPEN an unmentioned tag is not a failure — it is the entire point of OPEN — so
        // asserting every one of them is not "accepting a shape change", it is rewriting the author's
        // decision about what this step is for. The button was guarded against this and the action was not,
        // which is two deciders and one of them wrong.
        if (next.mode == MatchMode.STRICT) {
            progressed = true
            while (progressed) {
                progressed = false
                // Never a ghost: its field is already asserted by the moved row the final re-order is about
                // to relocate, and asserting it here would seed a second row contending for one field.
                val added =
                    rows(next, message, dictionary).firstOrNull { it.unasserted && !it.ghost && it.wireIndex != null }
                if (added != null) {
                    next = addAssertion(next, message, added.wireIndex!!, dictionary)
                    progressed = true
                }
            }
        }

        // ...and only then the order, so that a tag the venue stopped sending cannot suppress the re-order
        // for the whole step.
        return acceptNewOrder(next, message) ?: next
    }

    /**
     * Throw the step's expectation away and seed a fresh one from the message that actually arrived —
     * **keeping the echo assertions**, which the seeder cannot know about.
     *
     * `ExpectationSeeder` knows nothing of scenario variables, so a fresh seed turned
     * `Reference("${id0}")` — "the reply's ClOrdID must echo the id this run minted" — into
     * `Exact("ORD-9f3a-…")`, this run's literal uuid. The Send step mints a fresh one next run, so the row
     * could never match again: a permanent red the author can only silence by loosening or dropping it, and
     * the cross-step correlation the scenario existed to verify is gone.
     *
     * [canAcceptActual] already refuses that rewrite one row at a time; the bulk button had no such guard,
     * which is two deciders on one rule. The rule lives here now, and both go through it.
     */
    fun reseed(
        draft: Expectation,
        message: MessageView,
        dictionary: FixDictionaryAdapter?,
        side: MintingSide = MintingSide.CLIENT,
    ): Expectation {
        val wire = message.fields()
        // Same side the step was captured under, or a re-seed inverts every id role it re-decides: on an
        // acceptor step the counterparty's ClOrdID would go back to Exact and red on the next run.
        val fresh = ExpectationSeeder.seed(wire, dictionary, side).copy(mode = draft.mode, golden = draft.golden)

        // Carry every reference row — and every capture — across onto the same occurrence of the same tag.
        // A `bindAs` is scenario wiring the seeder cannot know about, exactly as a reference is: reseeding
        // away the capture would leave every later `${name}` unresolvable, silently.
        val keep = mutableMapOf<Pair<Int, Int>, FieldExpectation>()
        val seen = mutableMapOf<Int, Int>()
        for (row in draft.fields) {
            val k = seen.merge(row.tag, 1, Int::plus)!! - 1
            if (row.matcher is Matcher.Reference || row.bindAs != null) keep[row.tag to k] = row
        }
        if (keep.isEmpty()) return fresh

        val counter = mutableMapOf<Int, Int>()
        val carried = mutableSetOf<Pair<Int, Int>>()
        val rebuilt =
            fresh.fields.map { row ->
                val k = counter.merge(row.tag, 1, Int::plus)!! - 1
                keep[row.tag to k]?.let { kept ->
                    carried += row.tag to k
                    // Only a Reference matcher is carried — a captured Exact row still re-seeds fresh,
                    // with its capture riding along.
                    row.copy(
                        matcher = if (kept.matcher is Matcher.Reference) kept.matcher else row.matcher,
                        bindAs = kept.bindAs,
                    )
                } ?: row
            }

        // An echo the reply no longer carries is not a row to delete — it is the venue having STOPPED echoing
        // the id, which is the regression the assertion exists to catch. Dropping it silently would re-seed
        // the step green over the top of it. It is kept, and it reports itself missing.
        val lost = keep.entries.filter { it.key !in carried }.map { FieldExpectation(it.key.first, it.value.matcher, it.value.bindAs) }
        return fresh.copy(fields = rebuilt + lost)
    }

    /**
     * Name — or, with null, un-name — the capture on a row. See [FieldExpectation.bindAs]: this is the
     * receive→send half of correlation, authored from the diff's gutter. Not an assertion change — the
     * matcher is untouched, and what the row *checks* is exactly what it checked before.
     */
    fun captureAs(draft: Expectation, index: Int, name: String?): Expectation =
        draft.copy(
            fields =
                draft.fields.mapIndexed { i, f ->
                    if (i == index) f.copy(bindAs = name?.takeIf { it.isNotBlank() }) else f
                },
        )

    /**
     * The contiguous runs of rows that moved — bracketed so the view can offer one **Accept new order**
     * per run rather than a fix per row.
     *
     * A venue does not move a `PartyRole`; it moves a *party* — the delimiter and everything under it,
     * three to six tags travelling together. Offering a fix per row would be several clicks to express
     * one fact, and each click would leave the expectation in a state that is momentarily wrong.
     */
    fun movedBlocks(rows: List<Row>): List<IntRange> {
        val blocks = mutableListOf<IntRange>()
        var start = -1
        rows.forEachIndexed { i, row ->
            val moved = row.status == TagStatus.MOVED
            if (moved && start < 0) start = i
            if (!moved && start >= 0) {
                blocks += start until i
                start = -1
            }
        }
        if (start >= 0) blocks += start until rows.size
        return blocks
    }

    private fun replace(draft: Expectation, index: Int, matcher: Matcher): Expectation =
        draft.copy(fields = draft.fields.mapIndexed { i, f -> if (i == index) f.copy(matcher = matcher) else f })
}
