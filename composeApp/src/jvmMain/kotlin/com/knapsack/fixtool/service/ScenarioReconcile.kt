package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TagStatus
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
        // Outside a run there is no scope, so a reference resolves to nothing. That is not the row failing.
        val resolves = draft.fields.any { it.matcher is Matcher.Reference } &&
            draft.fields.filterIsInstance<FieldExpectation>()
                .mapNotNull { (it.matcher as? Matcher.Reference)?.expression }
                .any { referenceResolver(it) != null }
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
                unknown = d.alignment.row?.matcher is Matcher.Reference && !resolves,
            )
        }
    }

    // ------------------------------------------------------------------ the per-row actions

    /** Re-baseline: the value that actually arrived becomes the expected one. An absent actual → `absent`. */
    fun acceptActual(draft: Expectation, index: Int, actual: String?): Expectation =
        replace(draft, index, actual?.let { Matcher.Exact(it) } ?: Matcher.Absent)

    /** Keep the row, weaken the matcher — presence, a set, a tolerance, a pattern. */
    fun loosen(draft: Expectation, index: Int, matcher: Matcher): Expectation = replace(draft, index, matcher)

    /** The venue stopped sending this tag, and that is now the expected behaviour. */
    fun assertAbsent(draft: Expectation, index: Int): Expectation = replace(draft, index, Matcher.Absent)

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
        val (tag, value) = wire[wireIndex]
        val seeded =
            ExpectationSeeder
                .seedDetailed(listOf(tag to value), dictionary)
                .firstOrNull()
                ?.field
                ?: FieldExpectation(tag, Matcher.Exact(value))

        val claims = ExpectationEvaluator.align(draft, wire).mapNotNull { a -> a.index?.let { it to a.wireIndex } }
        val insertAt =
            claims.filter { (_, at) -> at != null && at < wireIndex }.maxOfOrNull { (rowIndex, _) -> rowIndex + 1 }
                ?: 0
        return draft.copy(fields = draft.fields.toMutableList().apply { add(insertAt, seeded) })
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
    fun acceptNewOrder(draft: Expectation, message: MessageView): Expectation? {
        val wire = message.fields()
        val before = ExpectationEvaluator.align(draft, wire)
        val claimed = before.mapNotNull { if (it.row != null) it.wireIndex else null }.toSet()

        // Where each row must end up. A row that pairs keeps its field; a row that pairs with nothing takes
        // the next occurrence of its tag that no other row is checking.
        val free = wire.withIndex().filter { it.index !in claimed }.groupBy({ it.value.first }, { it.index })
        val taken = mutableMapOf<Int, Int>()
        val target = mutableMapOf<Int, Int>()
        for (a in before) {
            val index = a.index ?: continue
            val at = a.wireIndex
                ?: free[a.tag]?.getOrNull(taken.merge(a.tag, 1) { n, _ -> n + 1 }!! - 1)
                ?: return null // nothing left in the reply for this row: a re-order cannot help it
            target[index] = at
        }

        // Nothing is out of place, so there is nothing to re-order. Two party entries that genuinely swapped
        // land here: every row pairs, and the failures are *values*, not positions. Returning the draft
        // unchanged would put a button on screen that does nothing when clicked.
        val unpaired = before.filter { it.row != null && it.wireIndex == null }.mapNotNull { it.index }
        if (unpaired.isEmpty()) return null

        // The permutation: new position -> the row it came from.
        val permutation = draft.fields.indices.sortedBy { target.getValue(it) }
        val reordered = draft.copy(fields = permutation.map { draft.fields[it] })

        // Verify against the engine itself, not against our own reasoning about it. Every row must land on
        // exactly the field it was given — otherwise this re-order moved an assertion, and the whole point of
        // it was that it must not.
        val landedAt = ExpectationEvaluator.align(reordered, wire).mapNotNull { a -> a.index?.let { it to a.wireIndex } }.toMap()
        permutation.forEachIndexed { newIndex, originalIndex ->
            if (landedAt[newIndex] != target[originalIndex]) return null
        }

        // And it must actually repair what it was offered for, without breaking anything that worked. A
        // venue that reshapes its group entries — sending the delimiter second — leaves every row paired
        // with some *other* entry's field, and a re-order there swaps mismatches around rather than fixing
        // them. That is not an order problem, and the honest repair is to re-seed from the message.
        val was = ExpectationEvaluator.diff(message, draft).mapNotNull { d -> d.alignment.index?.let { it to d.result.passed } }.toMap()
        val now = ExpectationEvaluator.diff(message, reordered).mapNotNull { d -> d.alignment.index?.let { it to d.result.passed } }.toMap()
        permutation.forEachIndexed { newIndex, originalIndex ->
            val passedBefore = was[originalIndex] == true
            val passesNow = now[newIndex] == true
            if (passedBefore && !passesNow) return null // it broke a row that was fine
            if (originalIndex in unpaired && !passesNow) return null // it did not fix the row it was for
        }
        return reordered
    }

    /** The re-order, if there is one that helps — what the view checks before offering the button. */
    fun canAcceptNewOrder(draft: Expectation, message: MessageView): Boolean = acceptNewOrder(draft, message) != null

    /** Throw the step's expectation away and seed a fresh one from the message that actually arrived. */
    fun reseed(message: MessageView, dictionary: FixDictionaryAdapter?, mode: com.knapsack.fixtool.model.scenario.MatchMode): Expectation {
        val wire = message.fields()
        return ExpectationSeeder.seed(wire, dictionary).copy(mode = mode)
    }

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
