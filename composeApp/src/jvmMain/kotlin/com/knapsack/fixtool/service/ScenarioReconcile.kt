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
    fun acceptNewOrder(draft: Expectation, message: MessageView): Expectation? = plan(draft, message)?.reordered

    /**
     * The rows a re-order would move — what the view brackets as "this entry moved", and the reason it can
     * show a party arriving out of order as *one* thing rather than as six unrelated value mismatches.
     *
     * Defined by the fix itself: a row is moved exactly when Accept-new-order would put it somewhere else.
     * There is no second opinion about it to disagree with the first.
     */
    fun movedRows(draft: Expectation, message: MessageView): Set<Int> = plan(draft, message)?.moved ?: emptySet()

    private data class Plan(val reordered: Expectation, val moved: Set<Int>)

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
    private fun plan(draft: Expectation, message: MessageView): Plan? {
        val wire = message.fields()
        val before = ExpectationEvaluator.diff(message, draft)
        val paired = before.mapNotNull { d -> d.alignment.index?.let { it to d.alignment.wireIndex } }.toMap()
        val passes = before.mapNotNull { d -> d.alignment.index?.let { it to d.result.passed } }.toMap()

        val placement =
            placeByOccurrence(draft, wire)
                ?: placeByMovedEntry(draft, wire, passes, paired)
                ?: return null

        val moved = placement.filter { (index, at) -> paired[index] != at }.keys
        if (moved.isEmpty()) return null // nothing is out of place; a button here would do nothing at all

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
        val after = ExpectationEvaluator.diff(message, reordered)
        val passesNow = after.mapNotNull { d -> d.alignment.index?.let { it to d.result.passed } }.toMap()
        permutation.forEachIndexed { newIndex, originalIndex ->
            if (passes[originalIndex] == true && passesNow[newIndex] != true) return null
            if (originalIndex in moved && passesNow[newIndex] == false) return null
        }
        return Plan(reordered, moved)
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
    private fun placeByOccurrence(draft: Expectation, wire: List<Pair<Int, String>>): Map<Int, Int>? {
        val byTag = wire.withIndex().groupBy { it.value.first }
        val seen = mutableMapOf<Int, Int>()
        val place = mutableMapOf<Int, Int>()
        for ((index, row) in draft.fields.withIndex()) {
            if (row.matcher is Matcher.Absent) continue // takes no part in ordering
            val k = seen.merge(row.tag, 1, Int::plus)!! - 1
            val field = byTag[row.tag]?.getOrNull(k) ?: return null // the venue sends fewer: missing, not moved
            // A reference and a temporal row have no fixed value to compare — one resolves against a live
            // run's scope, the other against the clock — so asking "did this value change?" of them answers
            // nothing about the message's *shape*. They are placed, not value-checked. Placement is
            // occurrence-preserving, so each keeps checking exactly the field it always did, and both are
            // still judged for real at replay.
            val hasFixedValue = row.matcher !is Matcher.Reference && row.matcher !is Matcher.Temporal
            if (hasFixedValue && !ExpectationEvaluator.satisfies(row.matcher, field.value.second)) {
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
    private fun placeByMovedEntry(
        draft: Expectation,
        wire: List<Pair<Int, String>>,
        passes: Map<Int, Boolean>,
        paired: Map<Int, Int?>,
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
            val at = verbatimWindow(draft, block, wire, used) ?: continue
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
     */
    private fun verbatimWindow(
        draft: Expectation,
        block: List<Int>,
        wire: List<Pair<Int, String>>,
        used: Set<Int>,
    ): Int? {
        if (block.isEmpty() || wire.size < block.size) return null
        for (at in 0..wire.size - block.size) {
            if ((at until at + block.size).any { it in used }) continue
            val fits =
                block.withIndex().all { (offset, index) ->
                    val row = draft.fields[index]
                    val field = wire[at + offset]
                    field.first == row.tag && ExpectationEvaluator.satisfies(row.matcher, field.second)
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

        // Tags the venue stopped sending: assert them absent where that is meaningful, drop them otherwise.
        //
        // ...but NEVER at the cost of a value change. `drop` on a repeated tag takes *every* row for that tag
        // — it must, or the surviving rows would silently change which occurrence they check — so dropping a
        // missing 448 also deletes the 448 row that is failing because the venue sent a *different firm*. The
        // next loop then re-seeded that tag fresh from the reply, and the button that promises "it will not
        // touch a value mismatch" quietly accepted the regression it promised to leave alone. A row like that
        // is left exactly where it is, for the author to decide about one at a time, deliberately.
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
                canAssertAbsent(next, message, index) -> {
                    next = assertAbsent(next, index)
                    progressed = true
                }
                dropTakesWholeTag(next, index) &&
                    current.any { it.tag == missing.tag && isBehaviourChange(it) } -> {
                    // Dropping would delete a row that is failing on its VALUE. Leave the whole tag alone.
                    handled += missing.tag
                    progressed = true
                }
                else -> {
                    next = drop(next, index)
                    progressed = true
                }
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
    ): Expectation {
        val wire = message.fields()
        val fresh = ExpectationSeeder.seed(wire, dictionary).copy(mode = draft.mode, golden = draft.golden)

        // Carry every reference row across onto the same occurrence of the same tag it was asserting.
        val keep = mutableMapOf<Pair<Int, Int>, Matcher>()
        val seen = mutableMapOf<Int, Int>()
        for (row in draft.fields) {
            val k = seen.merge(row.tag, 1, Int::plus)!! - 1
            if (row.matcher is Matcher.Reference) keep[row.tag to k] = row.matcher
        }
        if (keep.isEmpty()) return fresh

        val counter = mutableMapOf<Int, Int>()
        return fresh.copy(
            fields = fresh.fields.map { row ->
                val k = counter.merge(row.tag, 1, Int::plus)!! - 1
                keep[row.tag to k]?.let { row.copy(matcher = it) } ?: row
            },
        )
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
