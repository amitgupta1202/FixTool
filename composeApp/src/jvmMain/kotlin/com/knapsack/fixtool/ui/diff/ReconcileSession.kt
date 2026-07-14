package com.knapsack.fixtool.ui.diff

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.service.ExpectationEvaluator
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.ScenarioReconcile
import com.knapsack.fixtool.service.compare.Chunk
import com.knapsack.fixtool.service.compare.ChunkKind
import com.knapsack.fixtool.service.compare.EntryNode
import com.knapsack.fixtool.service.compare.GroupOverlay
import com.knapsack.fixtool.service.compare.MessageField
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.service.compare.SemanticsRegistry
import com.knapsack.fixtool.service.compare.Verdict
import java.time.Instant

/**
 * **One edit.** A label, and the operation it applies — nothing more.
 *
 * The plan asked for commands *with inverses*, and that would have been every operation written twice.
 * `drop` on a repeated tag removes **every** row of that tag (the whole-tag rule, and it must, or the
 * survivors would silently change which occurrence they check); `reseed` rebuilds from the seeder,
 * re-attaches the reference rows, *and* appends the echoes the reply no longer carries; `acceptNewOrder`
 * applies a permutation the engine verified. An inverse that is subtly wrong is a corruption that appears
 * only after ⌘Z — the quietest possible defect in the one surface whose whole promise is *"nothing is
 * written to the scenario until you save."*
 *
 * So undo is a **snapshot stack** ([ReconcileSession.snapshots]), which cannot be wrong, and an [EditOp] is
 * only the thing that produced the next snapshot. That is also exactly the shape a future auto-fix `FixPlan`
 * produces — a list of these — and the shape the footer reads to say *"loosen 151 · accept order"*.
 *
 * [apply] returns null when the operation declines (the engine refused, or nothing would change).
 */
class EditOp(
    val label: String,
    /**
     * **Consecutive edits that share a key are one edit.**
     *
     * The matcher chip's value field fires on every keystroke — that is the point, it is what makes the row
     * go green *as you type*. But a snapshot per character is an undo stack six deep for the word `500000`,
     * and `⌘Z` then walks back through `50000`, `5000`, `500`… which is not what anybody means by undoing a
     * value. The old view did exactly that. Typing is one edit; the key is what says so.
     */
    val coalesceKey: String? = null,
    private val apply: (Expectation) -> Expectation?,
) {
    fun applyTo(draft: Expectation): Expectation? = apply(draft)?.takeIf { it != draft }

    companion object {
        private fun describe(matcher: Matcher) = ExpectationEvaluator.describe(matcher)

        // The labels are the ones the old view staged under, verbatim — they are what the footer will list.
        fun acceptActual(index: Int, tag: Int, actual: String?) =
            EditOp("Accepted $tag = $actual") { ScenarioReconcile.acceptActual(it, index, actual) }

        fun loosen(index: Int, tag: Int, matcher: Matcher) =
            EditOp("Loosened $tag to ${describe(matcher)}") { ScenarioReconcile.loosen(it, index, matcher) }

        /** The chip and its value field. Consecutive edits to the same row coalesce — see [coalesceKey]. */
        fun setMatcher(index: Int, tag: Int, matcher: Matcher) =
            EditOp("Set $tag to ${describe(matcher)}", coalesceKey = "matcher:$index") {
                ScenarioReconcile.loosen(it, index, matcher)
            }

        fun assertAbsent(index: Int, tag: Int) =
            EditOp("Asserting $tag absent") { ScenarioReconcile.assertAbsent(it, index) }

        fun drop(index: Int, tag: Int) = EditOp("Dropped $tag") { ScenarioReconcile.drop(it, index) }

        fun assertIt(
            wireIndex: Int,
            tag: Int,
            actual: String?,
            message: MessageView,
            dictionary: FixDictionaryAdapter?,
        ) = EditOp("Now asserted: $tag = $actual") {
            ScenarioReconcile.addAssertion(it, message, wireIndex, dictionary)
        }

        fun acceptOrder(reordered: Expectation) = EditOp("Accepted the new order") { reordered }

        fun acceptAllShape(message: MessageView, dictionary: FixDictionaryAdapter?) =
            EditOp("Accepted every shape change") {
                ScenarioReconcile.acceptEveryShapeChange(it, message, dictionary)
            }

        fun reseed(message: MessageView, dictionary: FixDictionaryAdapter?) =
            EditOp("Re-seeded the step") { ScenarioReconcile.reseed(it, message, dictionary) }

        fun setMode(mode: MatchMode) =
            EditOp(if (mode == MatchMode.STRICT) "Switched to STRICT" else "Switched to OPEN") {
                it.copy(mode = mode)
            }

        /** The per-row drag. Refusals carry their sentence, which the surface shows at the cursor. */
        fun moveRow(overlay: GroupOverlay?, from: Int, to: Int) =
            EditOp("Moved a row") {
                (ScenarioReconcile.moveRow(it, overlay, from, to) as? ScenarioReconcile.MoveResult.Applied)
                    ?.expectation
            }

        fun moveEntry(overlay: GroupOverlay?, entry: IntRange, toSlot: Int) =
            EditOp("Moved the entry") {
                (ScenarioReconcile.moveEntry(it, overlay, entry, toSlot) as? ScenarioReconcile.MoveResult.Applied)
                    ?.expectation
            }
    }
}

/** What the gutter may offer on a line — and it may offer nothing the engine would refuse. */
enum class OfferKind { ACCEPT_ACTUAL, ASSERT_IT, ASSERT_ABSENT, DROP }

/** One gutter control: its glyph, what it says when hovered, and the edit it would stage. */
data class Offer(
    val kind: OfferKind,
    val glyph: String,
    val tooltip: String,
    val op: EditOp,
)

/**
 * **One line of the diff: a row, and the field it faces.**
 *
 * The pairing is not decided here — it comes off [Chunk.pairs], where `wireIndex`, the reorder's placement
 * and the `absent`-row fallback already live. This adds only what the *surface* needs on top of it: the
 * entry that bands the line, and the offers its gutter may draw.
 */
data class DiffLine(
    val chunkId: Int,
    val kind: ChunkKind,
    /** The engine's row, verbatim. Never null: a field the reply carries that no row mentions still has one. */
    val row: ScenarioReconcile.Row,
    /** The message field it faces, or null where it faces a gap. */
    val right: MessageField?,
    /** The innermost group entry this line sits in, or null at the top level. */
    val entry: EntryNode?,
    /** The chunk this line's chunk traded places with — the crossing connector's other end. */
    val moveLink: Int?,
    val offers: List<Offer>,
) {
    /** A field the reply carried that the expectation never mentions: the left column is a gap. */
    val leftIsGap: Boolean get() = row.unasserted

    /** The expectation asserts something here that the reply did not answer: the right column is a gap. */
    val rightIsGap: Boolean get() = right == null

    /** Neither passing nor failing — a `reference` has no scope here, a temporal no moment. Amber, `◌`. */
    val unjudged: Boolean get() = row.unknown
}

/** Everything the surface draws, computed once per (draft, reference) and cached. */
data class DiffModel(
    val lines: List<DiffLine>,
    val chunks: List<Chunk>,
    val verdict: Verdict,
    val overlay: GroupOverlay,
    /**
     * The **reference's** entries, from the same dictionary — so the two sides of the diff cannot come to
     * disagree about where an entry starts. It is what the right-hand band is labelled from.
     */
    val referenceOverlay: GroupOverlay,
    /** The one-click re-order, when the engine proved one — or null, in which case [withheldMove] says why. */
    val acceptOrder: EditOp?,
    /** The engine's own sentence for the move it declined to offer. Never silence. */
    val withheldMove: String?,
    val canAcceptShape: Boolean,
)

/**
 * **The draft, the reference, and the stack** — the state behind the diff surface, and not a composable.
 *
 * Three rules it exists to enforce, each of which was a defect in the view it replaces:
 *
 * - **Nothing is written until Save.** Every edit lands in [snapshots]; [discard] restores [original]
 *   exactly. The host is told about each change through `onChange` so that navigating away cannot destroy a
 *   session's worth of repairs — and *that feedback loop is why this class exists at all*: the old view kept
 *   its staging in `remember`ed composable state, and keying it wrong destroyed the history on every click.
 *   Construct this with `remember(stepId) { … }` and the whole class of bug is gone: a step's identity is
 *   now a thing the model can name.
 * - **The surface may not offer what the engine would refuse.** [DiffModel.lines] carry their [Offer]s, and
 *   every one of them is gated on the engine's own predicates — `canAcceptActual`, `canAssertAbsent`. The
 *   gutter draws what it is given and decides nothing.
 * - **The semantics is derived, never stored.** `SemanticsRegistry.forMode(draft.mode)` — a stored copy
 *   would be a second source of truth, and it would eventually say STRICT over a step that saves OPEN.
 */
class ReconcileSession(
    val original: Expectation,
    initialReference: ReferenceMessage,
    val dictionary: FixDictionaryAdapter?,
    private val resolver: (String) -> String? = { null },
    private val onChange: (Expectation) -> Unit = {},
) {
    /** `(label, expectation)`. The first is the original, under a label nothing ever shows. */
    private val snapshots = mutableStateListOf(Snapshot("", original, null))

    /** Which snapshot is current. Undo walks it back; a fresh edit truncates everything after it. */
    private var cursor by mutableStateOf(0)

    /**
     * **Whose message is on the right.** Swapping it is *not an edit*: it changes what you are comparing
     * against, not what you are asserting. It re-judges every row, and it does not stage, dirty, or push
     * undo — offering to "undo" a change of reference would be offering to undo looking at something.
     */
    var reference: ReferenceMessage by mutableStateOf(initialReference)
        private set

    val draft: Expectation get() = snapshots[cursor].expectation

    /** What the footer counts. The original is not an edit, so the cursor *is* the number of them. */
    val staged: Int get() = cursor

    /** The labels of the edits applied so far — `loosen 151 · accept order`, in the footer. */
    val stagedLabels: List<String> get() = (1..cursor).map { snapshots[it].label }

    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < snapshots.lastIndex
    val isDirty: Boolean get() = draft != original

    /** The semantics this step is judged under. Derived from the mode — never a second copy of it. */
    val semantics get() = SemanticsRegistry.forMode(draft.mode)

    // ---------------------------------------------------------------------------- the stack

    /** Stage an edit. A refused or no-op operation changes nothing and is not stacked. */
    fun apply(op: EditOp): Boolean {
        val next = op.applyTo(draft) ?: return false
        // A fresh edit after an undo abandons the redo branch — the ordinary editor contract.
        while (snapshots.lastIndex > cursor) snapshots.removeAt(snapshots.lastIndex)

        // Typing into one row's value is ONE edit, however many keystrokes it took — see EditOp.coalesceKey.
        // The snapshot it replaces still holds the state from *before* the first keystroke, so undo lands
        // where the author started typing rather than one character back into a word they were mid-way
        // through. That is what "undo the value I just set" means to everyone who has ever meant it.
        val coalesces = op.coalesceKey != null && cursor > 0 && snapshots[cursor].coalesceKey == op.coalesceKey
        if (coalesces) {
            snapshots[cursor] = Snapshot(op.label, next, op.coalesceKey)
        } else {
            snapshots.add(Snapshot(op.label, next, op.coalesceKey))
            cursor = snapshots.lastIndex
        }
        onChange(next)
        return true
    }

    fun undo() {
        if (!canUndo) return
        cursor -= 1
        onChange(draft)
    }

    fun redo() {
        if (!canRedo) return
        cursor += 1
        onChange(draft)
    }

    /** Back to exactly what was loaded — and the redo branch goes with it. Cancel must leave no trace. */
    fun discard() {
        if (snapshots.size > 1) snapshots.removeRange(1, snapshots.size)
        cursor = 0
        onChange(original)
    }

    /** Re-judge against something else. See [reference]: not an edit, so nothing here touches the stack. */
    fun swapReference(next: ReferenceMessage) {
        reference = next
    }

    // ---------------------------------------------------------------------------- the model

    /**
     * **Memoized, and the key is by value — which is the whole trick.**
     *
     * `RawMessageView` is a plain class with no `equals`, so [ReferenceMessage] — a data class — inherits
     * *identity* equality on its view. Build the reference inside the composable (the natural way to write
     * it) and a key holding the `ReferenceMessage` would change on every recomposition: the memo would never
     * hit, and every frame would re-run `reorder`, which enumerates every contiguous block of the
     * expectation and scans each across the wire. Nothing would fail. It would merely be slow, for a reason
     * nobody would ever find.
     *
     * So the key is the bytes, the moment, and the provenance — things that are equal when they are equal.
     */
    private data class Key(
        val draft: Expectation,
        val wire: List<Pair<Int, String>>,
        val anchor: Instant?,
        val provenance: ReferenceMessage.Provenance,
    )

    private data class Snapshot(
        val label: String,
        val expectation: Expectation,
        /** What made it. Two consecutive edits with the same key are one edit — see [EditOp.coalesceKey]. */
        val coalesceKey: String?,
    )

    private var memo: Pair<Key, DiffModel>? = null

    /** For tests: how many times the model has actually been rebuilt. A memo that never hits is a bug. */
    var rebuilds: Int = 0
        private set

    val model: DiffModel
        get() {
            val key = Key(draft, reference.view.fields(), reference.anchorInstant, reference.provenance)
            memo?.let { (cached, model) -> if (cached == key) return model }
            val built = build()
            rebuilds += 1
            memo = key to built
            return built
        }

    private fun build(): DiffModel {
        val message = reference.view
        // The reference's own moment — never the instant the diff was opened. A `~now ±60s` row that passed
        // during the run must not read as a venue regression because the engineer took two minutes to click.
        val at = reference.anchorInstant ?: Instant.now()
        val now = { at }

        val alignment = semantics.align(draft, reference, dictionary, resolver)
        val rows = ScenarioReconcile.rows(draft, reference, dictionary, resolver)
        val overlay = GroupOverlay.of(draft, dictionary)
        val reorder = ScenarioReconcile.reorder(draft, message, now, resolver)
        val possible = reorder as? ScenarioReconcile.Reorder.Possible
        val moved = possible?.moved.orEmpty()

        val lines =
            alignment.chunks.flatMap { chunk ->
                chunk.pairs.map { (row, field) ->
                    DiffLine(
                        chunkId = chunk.id,
                        kind = chunk.kind,
                        row = row,
                        right = field,
                        entry = row.index?.let { overlay.entryAt(it) },
                        moveLink = chunk.moveLink,
                        offers = offersFor(row, message, moved),
                    )
                }
            }

        // A moved chunk the overlay recognises as an entry is an ENTRY moved; a moved run of loose fields is
        // not, and saying "1 entry moved" over it would be a lie the verdict has told before.
        val movedEntries =
            alignment.chunks.count { chunk ->
                chunk.kind == ChunkKind.MOVED &&
                    chunk.rows
                        .firstOrNull()
                        ?.index
                        ?.let { overlay.entryAt(it) } != null
            }

        return DiffModel(
            lines = lines,
            chunks = alignment.chunks,
            verdict = Verdict.of(rows, moved, movedEntries),
            overlay = overlay,
            referenceOverlay = GroupOverlay.of(message, dictionary),
            acceptOrder = possible?.let { EditOp.acceptOrder(it.reordered) },
            withheldMove = (reorder as? ScenarioReconcile.Reorder.Refused)?.why,
            canAcceptShape = Verdict.canAcceptShape(rows, draft.mode),
        )
    }

    /**
     * **What the gutter may offer on this row — asked of the engine, never inferred from the chunk kind.**
     *
     * Whether an offer is *honest* is a row-level engine question, and the engine already answers it:
     * `canAcceptActual` refuses a temporal (accepting the actual pins the row to a timestamp that will not
     * recur, so the step is red for ever and the author's only way out is to delete the assertion) and a
     * reference (accepting an echoed id destroys the cross-step binding the row exists to express).
     * `canAssertAbsent` refuses when the reply still carries the tag elsewhere, because `absent` is not
     * scoped to one occurrence and the row could then never pass. `dropTakesWholeTag` changes what `×` even
     * means. The surface asks; it does not decide.
     */
    private fun offersFor(row: ScenarioReconcile.Row, message: MessageView, moved: Set<Int>): List<Offer> {
        // A row the diff cannot read has nothing to accept and nothing to drop that would mean anything: its
        // honest repairs are to loosen it or leave it, and both live on the matcher chip.
        if (row.unknown || row.passed && !row.unasserted) return emptyList()

        // **A ROW THAT MOVED IS OFFERED NOTHING.** Its status is VALUE — FIRMA's `448` now faces FIRMB, so it
        // *looks* exactly like a value mismatch, and a gutter keyed on status alone would put an Accept-actual
        // under it. One click and FIRMA's row asserts FIRMB, while the `452` rows stay where they are: the
        // expectation now reads FIRMB/role-1 and FIRMB/role-4, "FIRMA holds role 1" is gone, and the step is
        // green. That is the false green this whole model exists to make impossible, walked back in through
        // the gutter. The entry moved as a unit and it is repaired as one — by Accept-new-order, and by
        // nothing else. (Screenshots caught this; no assertion did, which is the argument for looking.)
        if (row.index in moved) return emptyList()

        val index = row.index
        return buildList {
            if (row.unasserted && row.wireIndex != null) {
                add(
                    Offer(
                        OfferKind.ASSERT_IT,
                        "«",
                        "Assert it — seeded from the dictionary, inserted where it pairs",
                        EditOp.assertIt(row.wireIndex, row.tag, row.actual, message, dictionary),
                    ),
                )
                return@buildList
            }
            if (index == null) return@buildList

            when (row.status) {
                TagStatus.VALUE, TagStatus.INVALID -> {
                    if (row.actual != null && row.matcher != null && ScenarioReconcile.canAcceptActual(row.matcher)) {
                        add(
                            Offer(
                                OfferKind.ACCEPT_ACTUAL,
                                "«",
                                "Accept actual — keeps the kind of matcher this row is",
                                EditOp.acceptActual(index, row.tag, row.actual),
                            ),
                        )
                    }
                    add(dropOffer(index, row.tag))
                }
                TagStatus.MISSING -> {
                    if (ScenarioReconcile.canAssertAbsent(draft, message, index)) {
                        add(
                            Offer(
                                OfferKind.ASSERT_ABSENT,
                                "∅",
                                "Assert absent — the venue stopped sending this, and that is the new truth",
                                EditOp.assertAbsent(index, row.tag),
                            ),
                        )
                    }
                    add(dropOffer(index, row.tag))
                }
                else -> Unit // moved, or a status with no honest per-row repair
            }
        }
    }

    /**
     * Dropping a row of a **repeated** tag takes the tag's rows with it, all of them — or the survivors would
     * be promoted and silently re-aimed at another entry. The gutter has to say so before it happens.
     */
    private fun dropOffer(index: Int, tag: Int): Offer {
        val wholeTag = ScenarioReconcile.dropTakesWholeTag(draft, index)
        return Offer(
            OfferKind.DROP,
            "×",
            if (wholeTag) {
                "Drop tag $tag — every row for $tag goes, or the survivors would start checking another entry"
            } else {
                "Drop this row — the step stops checking $tag"
            },
            EditOp.drop(index, tag),
        )
    }
}
