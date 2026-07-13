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
    fun acceptNewOrder(draft: Expectation, message: MessageView): Expectation? = plan(draft, message)?.reordered

    /**
     * The rows a re-order would move — what the view brackets as "this entry moved", and the reason it can
     * show a party arriving out of order as *one* thing rather than as six unrelated value mismatches.
     *
     * Defined by the fix itself: a row is moved exactly when Accept-new-order would put it somewhere else.
     * There is no second opinion about it to disagree with the first.
     */
    fun movedRows(draft: Expectation, message: MessageView): Set<Int> = plan(draft, message)?.moved ?: emptySet()

    /** A re-ordering that is safe, complete, and verified — or nothing at all. */
    private data class Plan(val reordered: Expectation, val moved: Set<Int>)

    @Suppress("ReturnCount")
    private fun plan(draft: Expectation, message: MessageView): Plan? {
        val wire = message.fields()
        val before = ExpectationEvaluator.diff(message, draft)
        val paired = before.mapNotNull { d -> d.alignment.index?.let { it to d.alignment.wireIndex } }.toMap()
        val passes = before.mapNotNull { d -> d.alignment.index?.let { it to d.result.passed } }.toMap()

        // A row that already PASSES is locked to the field it is checking. This is the safety rule, and the
        // first version of this did not have it: it re-mapped every row by a k-th-row/k-th-occurrence rule of
        // its own, so a green row checking the *second* 452 — an earlier row having pushed the engine's
        // cursor past the first — was dragged onto the first one and turned red. The author, offered "Accept
        // actual" on it, would then rebase their assertion onto a party entry they never chose while the one
        // they did choose silently stopped being asserted. Green build, coverage gone, two clicks.
        val key = mutableMapOf<Int, Double>()
        val used = mutableSetOf<Int>()
        for (index in draft.fields.indices) {
            val at = paired[index]
            if (passes[index] == true && at != null) {
                key[index] = at.toDouble()
                used += at
            }
        }

        // A row that is FAILING is free to go where the field it actually describes is. It carries its own
        // matcher with it, so the row asserting FIRMA lands on the entry that *is* FIRMA — the assertion is
        // unchanged, only its position. That is what makes a venue's reorder a one-click acknowledgement
        // rather than a redesign of the scenario, and it is not a re-aiming: nothing about what the row
        // checks for has changed.
        //
        // A row that describes nothing in the reply is *parked* where it already sits — it is a value the
        // venue changed, or a tag it stopped sending, and neither is an ordering problem. Bailing out here
        // instead (the first attempt) meant one genuine value change anywhere in the message suppressed the
        // re-order for the whole step.
        val moved = mutableSetOf<Int>()
        var lastKey = -1.0
        for (index in draft.fields.indices) {
            if (index in key) { lastKey = key.getValue(index); continue }
            val row = draft.fields[index]
            val at =
                wire.withIndex()
                    .firstOrNull { (i, f) ->
                        i !in used && f.first == row.tag && ExpectationEvaluator.satisfies(row.matcher, f.second)
                    }
                    ?.index
            when {
                at != null -> {
                    key[index] = at.toDouble()
                    used += at
                    if (at != paired[index]) moved += index
                    lastKey = at.toDouble()
                }
                paired[index] != null -> {
                    key[index] = paired.getValue(index)!!.toDouble()
                    lastKey = key.getValue(index)
                }
                else -> {
                    // Nowhere to be. Keep it beside the row it already follows.
                    lastKey += EPSILON
                    key[index] = lastKey
                }
            }
        }
        if (moved.isEmpty()) return null // nothing is out of place; a button here would do nothing at all

        val permutation = draft.fields.indices.sortedBy { key.getValue(it) }
        val reordered = draft.copy(fields = permutation.map { draft.fields[it] })

        // Verify against the engine itself, never against our own reasoning about it. Nothing that worked may
        // break, and every row this was offered for must actually be repaired by it.
        val after = ExpectationEvaluator.diff(message, reordered)
        val landedAt = after.mapNotNull { d -> d.alignment.index?.let { it to d.alignment.wireIndex } }.toMap()
        val passesNow = after.mapNotNull { d -> d.alignment.index?.let { it to d.result.passed } }.toMap()
        permutation.forEachIndexed { newIndex, originalIndex ->
            if (passes[originalIndex] == true) {
                if (landedAt[newIndex] != paired[originalIndex]) return null // it re-aimed a row that was fine
                if (passesNow[newIndex] != true) return null
            }
            if (originalIndex in moved && passesNow[newIndex] != true) return null // it did not fix what it was for
        }
        return Plan(reordered, moved)
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

        // Tags the venue stopped sending: assert them absent where that is meaningful, drop them otherwise.
        var progressed = true
        while (progressed) {
            progressed = false
            val missing = rows(next, message, dictionary).firstOrNull { it.status == TagStatus.MISSING && it.index != null }
            if (missing != null) {
                val index = missing.index!!
                next = if (canAssertAbsent(next, message, index)) assertAbsent(next, index) else drop(next, index)
                progressed = true
            }
        }

        // Tags the venue added: assert them, seeded from the dictionary, at the position they arrive in.
        progressed = true
        while (progressed) {
            progressed = false
            val added = rows(next, message, dictionary).firstOrNull { it.unasserted && it.wireIndex != null }
            if (added != null) {
                next = addAssertion(next, message, added.wireIndex!!, dictionary)
                progressed = true
            }
        }

        // ...and only then the order, so that a tag the venue stopped sending cannot suppress the re-order
        // for the whole step.
        return acceptNewOrder(next, message) ?: next
    }

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
