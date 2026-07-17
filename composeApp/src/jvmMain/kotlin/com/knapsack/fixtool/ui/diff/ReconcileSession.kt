package com.knapsack.fixtool.ui.diff

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioVariable
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.service.ExpectationEvaluator
import com.knapsack.fixtool.service.ExpectationSeeder
import com.knapsack.fixtool.service.FixMessageTemplate
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
import com.knapsack.fixtool.service.mintName
import java.time.Instant

/**
 * **What an edit did** — and a refusal is one of the three answers, not a silence.
 *
 * [EditOp.apply] used to be `(Expectation) -> Expectation?`, where `null` meant *both* "the engine refused"
 * and "nothing would change". So `MoveResult.Refused(why)` — every word of it hand-written, naming the exact
 * assertion the move would have re-aimed — was computed by the validator and **dropped on the floor** by the
 * op that called it. Invisible while the only movers were the ↑/↓ arrows, which are *disabled* where a move
 * is illegal: the sentence was never needed. A drag has no disabled state. The author is already holding the
 * row over the forbidden gap, and the drop either lands or it does not — and ground rule 6 says *a refused
 * action says why, where the user is looking*.
 *
 * [Unchanged] is kept apart from [Refused] deliberately: a drop that landed where the row already was must
 * say **nothing at all**, and folding the two together would put a refusal sentence under a drag that simply
 * did not go anywhere.
 */
sealed interface EditResult {
    data class Applied(val expectation: Expectation) : EditResult

    /** The engine said no, in its own words. The surface shows [why] at the cursor, verbatim. */
    data class Refused(val why: String) : EditResult

    /** It would have changed nothing. Not an error, and not worth a sentence. */
    object Unchanged : EditResult
}

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
     *
     * Moves never coalesce — with each other or with anything else. Each one is a discrete fact about where
     * a row belongs, and `⌘Z` after two of them must walk back one.
     */
    val coalesceKey: String? = null,
    private val apply: (Expectation) -> EditResult,
) {
    fun applyTo(draft: Expectation): EditResult =
        when (val result = apply(draft)) {
            is EditResult.Applied -> if (result.expectation == draft) EditResult.Unchanged else result
            else -> result
        }

    companion object {
        private fun describe(matcher: Matcher) = ExpectationEvaluator.describe(matcher)

        /**
         * An op that cannot be refused — every one of them except the two moves. It either produces a new
         * expectation or it produces nothing, and "nothing" here has only ever meant *nothing changed*.
         */
        private fun pure(label: String, coalesceKey: String? = null, apply: (Expectation) -> Expectation?) =
            EditOp(label, coalesceKey) { draft ->
                apply(draft)?.let { EditResult.Applied(it) } ?: EditResult.Unchanged
            }

        /** The engine's own answer, carried whole — including the sentence it gives when it says no. */
        private fun moving(label: String, move: (Expectation) -> ScenarioReconcile.MoveResult) =
            EditOp(label) { draft ->
                when (val result = move(draft)) {
                    is ScenarioReconcile.MoveResult.Applied -> EditResult.Applied(result.expectation)
                    is ScenarioReconcile.MoveResult.Refused -> EditResult.Refused(result.why)
                }
            }

        // The labels are the ones the old view staged under, verbatim — they are what the footer will list.
        fun acceptActual(index: Int, tag: Int, actual: String?) =
            pure("Accepted $tag = $actual") { ScenarioReconcile.acceptActual(it, index, actual) }

        fun loosen(index: Int, tag: Int, matcher: Matcher) =
            pure("Loosened $tag to ${describe(matcher)}") { ScenarioReconcile.loosen(it, index, matcher) }

        /** The chip and its value field. Consecutive edits to the same row coalesce — see [coalesceKey]. */
        fun setMatcher(index: Int, tag: Int, matcher: Matcher) =
            pure("Set $tag to ${describe(matcher)}", coalesceKey = "matcher:$index") {
                ScenarioReconcile.loosen(it, index, matcher)
            }

        fun assertAbsent(index: Int, tag: Int) =
            pure("Asserting $tag absent") { ScenarioReconcile.assertAbsent(it, index) }

        /** The row stops asserting this run's literal and starts asserting the correlation: `= ${name}`. */
        fun track(index: Int, tag: Int, name: String) =
            pure("Tracking \${$name} on $tag") {
                ScenarioReconcile.loosen(it, index, Matcher.Reference("\${$name}"))
            }

        /** Capture the value this row pairs with into the scope — or stop, with null. Asserts nothing new. */
        fun captureAs(index: Int, tag: Int, name: String?) =
            pure(if (name != null) "Capturing $tag as \${$name}" else "Stopped capturing $tag") {
                ScenarioReconcile.captureAs(it, index, name)
            }

        /** A new row for a tag the diff has no line for — see [ScenarioReconcile.insertAbsent]. */
        fun insertAbsent(tag: Int) =
            pure("Now asserting $tag absent") { ScenarioReconcile.insertAbsent(it, tag) }

        fun drop(index: Int, tag: Int) = pure("Dropped $tag") { ScenarioReconcile.drop(it, index) }

        fun assertIt(
            wireIndex: Int,
            tag: Int,
            actual: String?,
            message: MessageView,
            dictionary: FixDictionaryAdapter?,
        ) = pure("Now asserted: $tag = $actual") {
            ScenarioReconcile.addAssertion(it, message, wireIndex, dictionary)
        }

        fun acceptOrder(reordered: Expectation) = pure("Accepted the new order") { reordered }

        fun acceptAllShape(message: MessageView, dictionary: FixDictionaryAdapter?) =
            pure("Accepted every shape change") {
                ScenarioReconcile.acceptEveryShapeChange(it, message, dictionary)
            }

        /**
         * The fix plan, applied whole — **one snapshot, one ⌘Z**, like [acceptAllShape]. The plan was
         * previewed row by row before this op existed, so undoing it row by row would be walking back
         * through decisions the author made as one.
         */
        fun fixPlan(fixes: List<ScenarioReconcile.PlannedFix>) =
            pure("Loosened ${fixes.size} ${if (fixes.size == 1) "row" else "rows"} to fit the reply") { draft ->
                fixes.fold(draft) { d, fix -> ScenarioReconcile.loosen(d, fix.index, fix.proposed) }
            }

        fun reseed(message: MessageView, dictionary: FixDictionaryAdapter?) =
            pure("Re-seeded the step") { ScenarioReconcile.reseed(it, message, dictionary) }

        fun setMode(mode: MatchMode) =
            pure(if (mode == MatchMode.STRICT) "Switched to STRICT" else "Switched to OPEN") {
                it.copy(mode = mode)
            }

        /** The per-row drag, and `alt+↑/↓`. A refusal carries its sentence out to the cursor. */
        fun moveRow(overlay: GroupOverlay?, from: Int, to: Int) =
            moving("Moved a row") { ScenarioReconcile.moveRow(it, overlay, from, to) }

        /** The entry drag, and the band's ↑/↓. Same validator, larger unit. */
        fun moveEntry(overlay: GroupOverlay?, entry: IntRange, toSlot: Int) =
            moving("Moved the entry") { ScenarioReconcile.moveEntry(it, overlay, entry, toSlot) }

        /** The band's ✕ — the whole entry, gone: the repair for an environment that sends one fewer. */
        fun dropEntry(overlay: GroupOverlay?, entry: IntRange) =
            moving("Dropped the entry") { ScenarioReconcile.dropEntry(it, overlay, entry) }
    }
}

/** What the gutter may offer on a line — and it may offer nothing the engine would refuse. */
enum class OfferKind { ACCEPT_ACTUAL, LOOSEN, ASSERT_IT, ASSERT_ABSENT, DROP, TRACK, CAPTURE }

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

    /** What selecting this line means. Null for a line with no stable identity to hold on to. */
    val selection: DiffSelection?
        get() =
            when {
                row.unasserted -> row.wireIndex?.let(DiffSelection::Added)
                else -> row.index?.let(DiffSelection::Row)
            }
}

/**
 * **What is selected** — and it is an *index*, which is the whole of why it needs care.
 *
 * A move is precisely the thing that changes indices. Select row 3, press `alt+↓`: the row is now at 4, and
 * a selection still reading 3 points at whatever slid up into its place. Press `alt+↓` again and the author
 * has moved a **different row**, having pressed the same key twice while watching the same highlight. So
 * [ReconcileSession.moveSelection] re-anchors this from the move it just applied.
 */
sealed interface DiffSelection {
    /** A row of the expectation, by its index in the draft — the thing `alt+↑/↓` moves. */
    data class Row(val index: Int) : DiffSelection

    /**
     * A field the reply carries that no row mentions. It can be selected (the gutter's `«` is on it) but it
     * is **not a row of the expectation**, so there is nothing about it to move — and nothing to refuse.
     */
    data class Added(val wireIndex: Int) : DiffSelection

    /** A group entry, by the rows it spans — what `alt+↑/↓` moves as a unit. */
    data class Entry(val rows: IntRange) : DiffSelection
}

/**
 * **What the body draws, in order** — a band opens an entry, and the lines inside it are indented under it.
 *
 * It lives here rather than in the composable because three things that are not drawing need it: `↑/↓` walks
 * it, `n`/`p` walks the chunks in it, and the crossing connector needs the *item indices* of its two ends to
 * ask `LazyListState.layoutInfo` where they are on screen. Left in `DiffSurface`, all three would have been
 * reachable only from a Compose test, which is where this project's defects go to hide.
 */
sealed interface DiffItem {
    val selection: DiffSelection?

    data class Band(
        val entry: EntryNode,
        val hue: Int,
        val depth: Int,
        val moved: Boolean,
        val rightLabel: String?,
        /**
         * The one-click re-order goes on the **first** moved band and nowhere else: `acceptNewOrder` rewrites
         * the whole expectation atomically, so a second button would be the same button pretending to be
         * about a different entry. Which band that is cannot be counted while *drawing* — a lazy list
         * composes what it likes, in whatever order it likes — so it is decided once, here.
         */
        val first: Boolean,
        /** The chunk the band's rows belong to — the connector's near end. */
        val chunkId: Int,
    ) : DiffItem {
        override val selection: DiffSelection get() = DiffSelection.Entry(entry.rows)
    }

    data class Line(
        val line: DiffLine,
        val depth: Int,
    ) : DiffItem {
        override val selection: DiffSelection? get() = line.selection
    }
}

/** Everything the surface draws, computed once per (draft, reference) and cached. */
data class DiffModel(
    val lines: List<DiffLine>,
    /** The display list — bands and lines, in reading order. See [DiffItem]. */
    val items: List<DiffItem>,
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
) {
    /** Where [selection] sits in [items] — what the list scrolls to, and what `↑/↓` walks from. */
    fun indexOf(selection: DiffSelection?): Int? =
        selection?.let { wanted -> items.indexOfFirst { it.selection == wanted }.takeIf { it >= 0 } }

    /**
     * The chunks `n`/`p` stop at: the ones that are a **difference**. A [ChunkKind.SAME] chunk is what the
     * author is navigating *past* — that is what "next diff" means — and a moved entry is a difference, so
     * it is one of them.
     */
    val diffChunks: List<Int> get() = chunks.filter { it.kind != ChunkKind.SAME }.map { it.id }

    /** The first item of a chunk — where `n` lands, band included, so an entry is selected as an entry. */
    fun firstItemOf(chunkId: Int): Int? =
        items
            .indexOfFirst { item ->
                when (item) {
                    is DiffItem.Band -> item.chunkId == chunkId
                    is DiffItem.Line -> item.line.chunkId == chunkId
                }
            }.takeIf { it >= 0 }

    /** Every item of a chunk — the block the crossing connector is drawn from the middle of. */
    fun itemsOfChunk(chunkId: Int): List<Int> =
        items.indices.filter { i ->
            when (val item = items[i]) {
                is DiffItem.Band -> item.chunkId == chunkId
                is DiffItem.Line -> item.line.chunkId == chunkId
            }
        }

    /** Every item an entry occupies — its band, and the lines under it. What an entry drag picks up. */
    fun itemsOfEntry(rows: IntRange): List<Int> =
        items.indices.filter { i ->
            when (val item = items[i]) {
                is DiffItem.Band -> item.entry.rows == rows
                is DiffItem.Line -> item.line.entry?.rows == rows
            }
        }

    /** The line whose **right-hand** cell holds this field of the reply — the connector's far end. */
    fun itemFacingWire(wireIndex: Int): Int? =
        items
            .indexOfFirst { it is DiffItem.Line && it.line.right?.wireIndex == wireIndex }
            .takeIf { it >= 0 }
}

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
    original: Expectation,
    initialReference: ReferenceMessage,
    val dictionary: FixDictionaryAdapter?,
    private val resolver: (String) -> String? = { null },
    private val onChange: (Expectation) -> Unit = {},
) {
    /**
     * What [discard] goes back to, and what [staged] and [isDirty] are measured against.
     *
     * A `var`, because **Save moves it.** "3 edits staged · nothing is written to the scenario until you save"
     * is a promise, and once the author has saved it has been kept — going on counting those three edits would
     * make the footer describe a danger that no longer exists, and `Cancel` would then offer to throw away work
     * that is already on disk.
     */
    var original: Expectation = original
        private set

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

    /**
     * **The last thing the engine refused, in the engine's words** — or null.
     *
     * A drag shows it at the cursor; `alt+↑/↓` has no cursor, so the surface renders it inline instead. It is
     * cleared by anything that means the author has moved on: a successful edit, an undo, a redo, a discard,
     * or the start of the next drag. A refusal that outlives its cause is a sentence about a situation that
     * no longer exists, which is the failure mode this area keeps producing in the other direction.
     */
    var refusal: String? by mutableStateOf(null)
        private set

    fun clearRefusal() {
        refusal = null
    }

    /**
     * **What this op would do, without doing it** — the drag tooltip's answer, and the drop's, from one
     * function, so the two cannot come to disagree about whether a landing is legal.
     *
     * Stages nothing, dirties nothing, pushes no undo, sets no [refusal]. The caller decides what to say.
     */
    fun preview(op: EditOp): EditResult = op.applyTo(draft)

    /** Stage an edit. A refused or no-op operation changes the draft in no way and is not stacked. */
    fun apply(op: EditOp): EditResult {
        val result = op.applyTo(draft)
        val next =
            when (result) {
                is EditResult.Applied -> result.expectation
                is EditResult.Refused -> {
                    refusal = result.why
                    return result
                }
                EditResult.Unchanged -> return result
            }
        refusal = null
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
        return result
    }

    fun undo() {
        if (!canUndo) return
        cursor -= 1
        refusal = null
        onChange(draft)
    }

    fun redo() {
        if (!canRedo) return
        cursor += 1
        refusal = null
        onChange(draft)
    }

    /** Back to exactly what was loaded — and the redo branch goes with it. Cancel must leave no trace. */
    fun discard() {
        if (snapshots.size > 1) snapshots.removeRange(1, snapshots.size)
        cursor = 0
        refusal = null
        onChange(original)
    }

    /**
     * **Saved.** [saved] is the new [original]: nothing is staged, nothing is dirty, and the stack starts
     * again from what is now on disk.
     *
     * Not `onChange`d — the host is the one telling us, and echoing it back would write the expectation into a
     * draft that already holds it. The reference slot is untouched: what the author is comparing against is not
     * a thing a Save has any opinion about.
     */
    fun rebase(saved: Expectation) {
        snapshots.clear()
        snapshots.add(Snapshot("", saved, null))
        cursor = 0
        original = saved
    }

    /** Re-judge against something else. See [reference]: not an edit, so nothing here touches the stack. */
    fun swapReference(next: ReferenceMessage) {
        reference = next
        // The highlight names a variable of the OLD reference's scope; a scope that no longer has it would
        // leave every row dim for a reason the strip no longer shows.
        if (next.variables.none { it.name == highlightedVariable }) highlightedVariable = null
    }

    // ---------------------------------------------------------------------------- the variables strip

    /**
     * The variable chip the author clicked in the strip, or null. A reading aid, not an edit: it highlights
     * every line that mentions the name or carries its value, which is the fastest answer to "where did
     * `${id0}` go in this message" — and to "why did this step bind to the wrong reply".
     */
    var highlightedVariable: String? by mutableStateOf(null)

    /** Does this line mention [highlightedVariable] by name, or carry its value on either side? */
    fun highlights(line: DiffLine): Boolean {
        val name = highlightedVariable ?: return false
        val value = reference.variables.firstOrNull { it.name == name }?.value
        val matcher = line.row.matcher
        val mentionsName =
            matcher is Matcher.Reference &&
                BARE_NAMES.findAll(matcher.expression).any { it.groupValues[1] == name }
        val carriesValue =
            value != null && (line.right?.value == value || (matcher as? Matcher.Exact)?.value == value)
        return mentionsName || carriesValue
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
     * So the key is the bytes, the moment, the provenance — and the scope, which re-judges reference rows
     * exactly as the moment re-judges temporal ones — things that are equal when they are equal.
     */
    private data class Key(
        val draft: Expectation,
        val wire: List<Pair<Int, String>>,
        val anchor: Instant?,
        val provenance: ReferenceMessage.Provenance,
        val variables: List<ScenarioVariable>,
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
            val key = Key(draft, reference.view.fields(), reference.anchorInstant, reference.provenance, reference.variables)
            memo?.let { (cached, model) -> if (cached == key) return model }
            val built = build(draft)
            rebuilds += 1
            memo = key to built
            return built
        }

    /**
     * **What the step would look like if this were the draft** — the drag tooltip's *"would every row pass
     * here?"*, answered by building the model that landing would produce.
     *
     * It is the same [build] the surface renders, so the tooltip and the drop cannot come to disagree, and
     * the verdict is not counted a second way. That is not free — the build enumerates every contiguous
     * block of the expectation and scans each across the wire — so the caller must ask it **once per
     * landing**, not once per pointer event. A drag fires sixty times a second, and nothing would fail if it
     * were asked that often: it would merely be slow, for a reason nobody would ever find (P4).
     */
    fun verdictOf(candidate: Expectation): Verdict = build(candidate).verdict

    /**
     * The fix plan for the current draft against the current reference — the sheet's preview, recomputed
     * when the knob moves. Asks the engine and stages nothing; applying is [EditOp.fixPlan], one snapshot.
     */
    fun fixPlan(
        tolerance: ScenarioReconcile.FixTolerance = ScenarioReconcile.FixTolerance.CoverBoth,
    ): List<ScenarioReconcile.PlannedFix> =
        ScenarioReconcile.fixPlan(draft, reference, dictionary, tolerance, effectiveResolver())

    /**
     * **The run scope the reference carries, in front of whatever the host supplied.**
     *
     * The scope rides on the [reference] (see `ReferenceMessage.variables`), so this is re-derived rather
     * than fixed at construction: swap the run's message out for a paste and the `${id0}` rows fall back to
     * unjudged in the same move — resolving them against a scope that other message never ran under would
     * be a confident verdict about bytes nobody judged. The constructor's resolver stays underneath as the
     * fallback for hosts (and tests) that inject their own.
     */
    private fun effectiveResolver(): (String) -> String? {
        val scope = reference.variables
        if (scope.isEmpty()) return resolver
        val fromScope = FixMessageTemplate.scopeResolver(scope.associate { it.name to it.value })
        return { expression -> fromScope(expression) ?: resolver(expression) }
    }

    /**
     * [draft] shadows the property on purpose: [verdictOf] builds this for a candidate that is not staged.
     *
     * The body moved to the top-level [buildDiffModel] so the plain diff **viewer** can share it (Phase 7, G2):
     * the reconcile session hands it the real [offersFor]; the viewer hands it one that offers nothing, and that
     * is the whole of why the viewer cannot write. Passing [offersFor] as a member reference keeps this session's
     * behaviour byte-identical — the offers still close over *this* session's `draft`.
     */
    private fun build(draft: Expectation): DiffModel =
        buildDiffModel(draft, reference, dictionary, effectiveResolver(), ::offersFor)

    // ------------------------------------------------------------------------- selection and navigation

    /**
     * Where the author's cursor is. It lives in the **session**, not in a `remember`, because only the active
     * document is composed: left in the composable, a glance at the session grid would destroy it and `n`
     * would start again from the top of a diff the author was halfway down.
     */
    var selection: DiffSelection? by mutableStateOf(null)

    /** `↑`/`↓`: the next item of the body, bands included, so an entry can be selected as an entry. */
    fun selectNext() = step(+1)

    fun selectPrev() = step(-1)

    private fun step(by: Int) {
        val items = model.items
        if (items.isEmpty()) return
        val at = model.indexOf(selection)
        val next = if (at == null) (if (by > 0) 0 else items.lastIndex) else (at + by).coerceIn(0, items.lastIndex)
        selection = items[next].selection
    }

    /** `n`/`p`: the next chunk that is a **difference**, never wrapping — a wrap silently teleports the reader. */
    fun nextChunk() = chunk(+1)

    fun prevChunk() = chunk(-1)

    private fun chunk(by: Int) {
        val model = model
        val diffs = model.diffChunks
        if (diffs.isEmpty()) return
        val here = model.indexOf(selection)
        val ranked = diffs.mapNotNull { id -> model.firstItemOf(id)?.let { id to it } }
        val next =
            when {
                here == null -> ranked.firstOrNull()
                by > 0 -> ranked.firstOrNull { it.second > here }
                else -> ranked.lastOrNull { it.second < here }
            } ?: return
        selection = model.items[next.second].selection
    }

    /**
     * `alt+↑`/`alt+↓` — and the selection **follows what it moved**.
     *
     * Off the end of the list (the first row pressing `alt+↑`) is [EditResult.Unchanged] and silent: that is
     * not a *forbidden* move, it is no move, and the author can see the row is first. A move the engine
     * refuses is a different thing entirely, and it says so.
     */
    fun moveSelection(down: Boolean): EditResult {
        val model = model
        return when (val sel = selection) {
            is DiffSelection.Row -> {
                val to = if (down) sel.index + 1 else sel.index - 1
                if (to !in draft.fields.indices) return EditResult.Unchanged
                // `add(to, removeAt(from))` puts the row AT `to`, so that is where the selection now is.
                apply(EditOp.moveRow(model.overlay, sel.index, to))
                    .also { if (it is EditResult.Applied) selection = DiffSelection.Row(to) }
            }
            is DiffSelection.Entry -> moveEntrySelection(model, sel, down)
            else -> EditResult.Unchanged // an added field is not a row of the expectation: nothing to move
        }
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
        // **A ROW THAT MOVED IS OFFERED NOTHING.** Its status is VALUE — FIRMA's `448` now faces FIRMB, so it
        // *looks* exactly like a value mismatch, and a gutter keyed on status alone would put an Accept-actual
        // under it. One click and FIRMA's row asserts FIRMB, while the `452` rows stay where they are: the
        // expectation now reads FIRMB/role-1 and FIRMB/role-4, "FIRMA holds role 1" is gone, and the step is
        // green. That is the false green this whole model exists to make impossible, walked back in through
        // the gutter. The entry moved as a unit and it is repaired as one — by Accept-new-order, and by
        // nothing else. (Screenshots caught this; no assertion did, which is the argument for looking.)
        if (row.index in moved) return emptyList()

        // A GHOST IS OFFERED NOTHING EITHER. It is the other end of a move: its tag is already asserted by
        // the unpaired row, whose repairs are the honest ones (accept the new order, or drop the row — at
        // which point this field stops being spoken for and earns the « like any extra). An « here would
        // seed a second row contending for one field.
        if (row.ghost) return emptyList()

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

            // A passing row has nothing to REPAIR, and a row the diff cannot read has nothing to accept or
            // loosen that would mean anything — but deleting an assertion is *authoring*, not repair, and the
            // author who has just captured a step is looking at a wall of green rows over an expectation that
            // asserts more than they mean. This surface is the app's only assertion editor, so the drop is
            // offered on every asserted row, and the capture wherever a value paired — both assert nothing.
            // The repairs stay gated on there being a failure to fix.
            if (row.unknown || row.passed) {
                // A GREEN row can still be a landmine: an Exact pinning a value that IS one of the run's
                // variables passes today precisely because the id was minted today — and is red on every
                // run after. The track offer is the defusal, and the passing row is where it matters most.
                if (row.passed) trackOffer(index, row)?.let { add(it) }
                add(dropOffer(index, row.tag, message))
                captureOffer(index, row)?.let { add(it) }
                return@buildList
            }

            when (row.status) {
                TagStatus.VALUE, TagStatus.INVALID -> {
                    // First, because when it applies the offers beside it are traps: accepting the literal
                    // of an echoed id pins THIS run's value and is red again tomorrow.
                    trackOffer(index, row)?.let { add(it) }
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
                    // The repair Accept-actual is NOT: a fill price that varies per run re-pinned to this
                    // run's value is red again on the next one. This is the one-click answer for a value
                    // that is allowed to move — explicit, staged and undoable, never seeded by default,
                    // because a silent tolerance on a price is a regression the scenario stops catching.
                    loosenOffer(index, row)?.let { add(it) }
                    add(dropOffer(index, row.tag, message))
                    // Capture rides on red rows too. The runner captures what a row PAIRED with whether or
                    // not it passed (teardown runs after a failure and may need the venue's id), and the id
                    // an author most wants to capture is exactly the one going red against a pinned literal
                    // every run. It is last because it repairs nothing — the tooltip says so.
                    captureOffer(index, row)?.let { add(it) }
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
                    add(dropOffer(index, row.tag, message))
                    // Only ever the UN-capture here — nothing paired, so there is nothing to capture — and
                    // it matters: a stale capture on a row the venue stopped sending must be removable
                    // without repairing the row first, or it is stranded behind its own failure.
                    captureOffer(index, row)?.let { add(it) }
                }
                else -> Unit // moved, or a status with no honest per-row repair
            }
        }
    }

    /**
     * `↧` — capture the value this row pairs with into the run's scope (see [FieldExpectation.bindAs]):
     * the receive→send half of correlation, for a value the **venue** chose that a later send must echo
     * back. Offered wherever the row **paired with a value** — pass or fail, which is the runner's own
     * stance: it captures what a row paired with whether or not the row passed, because teardown runs
     * after a failure and may need the venue's id. The one thing capture is NOT is a repair — it asserts
     * nothing, so on a failing row the tooltip says the red stays until the repair beside it is taken,
     * and the row on screen stays red to make the same point. Independent of the run scope on purpose:
     * authoring against the golden is exactly where the dealer echo gets wired. The name is derived from
     * the dictionary (`quoteReqID`), collision-suffixed, and the tooltip says it before the click; a row
     * already capturing is offered the un-capture instead — on every status, including MISSING, so a
     * stale capture is never stranded behind its own failure.
     */
    private fun captureOffer(index: Int, row: ScenarioReconcile.Row): Offer? {
        val current = draft.fields.getOrNull(index)?.bindAs
        if (current != null) {
            return Offer(
                OfferKind.CAPTURE,
                "↧",
                "Stop capturing \${$current} — later \${$current} references stop resolving, and the " +
                    "never-minted warning will name them",
                EditOp.captureAs(index, row.tag, null),
            )
        }
        val actual = row.actual ?: return null
        val name = captureName(row.tag)
        val tooltip =
            if (row.passed || row.unknown) {
                "Capture as \${$name} — the venue chose this value ($actual); capturing it lets a later " +
                    "send or bind predicate echo it back as \${$name}. Asserts nothing new."
            } else {
                // The red-row wording carries the warning the green-only gate used to enforce: capture is
                // not the repair, and clicking it must not read as one.
                "Capture as \${$name} — the venue chose this value ($actual). A failed row that paired " +
                    "still captures (teardown may need it), but capture repairs nothing: this row stays " +
                    "red until repaired."
            }
        return Offer(OfferKind.CAPTURE, "↧", tooltip, EditOp.captureAs(index, row.tag, name))
    }

    /** [mintName], with what this surface can see taken: the run's scope and the draft's own captures. */
    private fun captureName(tag: Int): String =
        mintName(
            tag,
            dictionary?.getFieldName(tag),
            (reference.variables.map { it.name } + draft.fields.mapNotNull { it.bindAs }).toSet(),
        )

    /**
     * `$` — this row's actual value **is** one of the run's variables, so what the venue echoed is the id
     * this run minted, and the honest assertion is the correlation, not the literal. The reconcile-time
     * analogue of capture's echo detection: offered exactly where the scope proves the equality, on a row
     * that is not already a reference. Value-equality can coincide by accident (`1` is a Side and a
     * PartyRole and a quantity), which is why this is an *offer* the author reads — with the variable named
     * in the tooltip — and never a seed applied silently.
     */
    private fun trackOffer(index: Int, row: ScenarioReconcile.Row): Offer? {
        val actual = row.actual ?: return null
        if (row.matcher == null || row.matcher is Matcher.Reference) return null
        val variable = reference.variables.firstOrNull { it.value == actual } ?: return null
        return Offer(
            OfferKind.TRACK,
            "$",
            "Track \${${variable.name}} — this value is the run's \${${variable.name}}. Assert the echo, " +
                "not the literal: the next run mints a fresh value, and a pinned literal is red the moment " +
                "it does.",
            EditOp.track(index, row.tag, variable.name),
        )
    }

    /**
     * `±` — widen this row to a numeric band that covers both the expectation and the reply, for a value
     * that legitimately varies per run (a fill price, a remaining quantity). Offered only where both sides
     * are numbers and the matcher is Exact/Numeric. The arithmetic lives in
     * [ScenarioReconcile.coveringBand], shared with the fix plan's bulk loosen — one decider for what
     * "covers both sides" means, including the decimal-vs-double ulp trap documented there.
     *
     * And an **Exact** earns a band only where the seed would have made one:
     * [ExpectationSeeder.numericFamily] is the one decider of "may this field honestly carry a numeric
     * tolerance", shared with [ScenarioReconcile.fixPlan] — so the gutter cannot hand out per-row what the
     * plan refuses in bulk. PartyRole(452) failing `4` against `1` is a role change, not drift; `4 ± 3`
     * over it accepts seven different meanings while reading like a tolerance, a false green one click
     * wide. (A row that already *is* Numeric was classified when it became one; widening it stays offered.)
     */
    private fun loosenOffer(index: Int, row: ScenarioReconcile.Row): Offer? {
        val matcher = row.matcher ?: return null
        if (matcher is Matcher.Exact && !ExpectationSeeder.numericFamily(row.tag, dictionary)) return null
        val band = ScenarioReconcile.coveringBand(matcher, row.actual) ?: return null
        return Offer(
            OfferKind.LOOSEN,
            "±",
            "Loosen — ${band.expectedText} ± ${band.decimalTolerance} covers both sides, " +
                "for a value that varies per run (a fill price, a remaining quantity)",
            EditOp.loosen(index, row.tag, band.matcher),
        )
    }

    /**
     * Dropping a row of a **repeated** tag takes the tag's rows with it, all of them — or the survivors would
     * be promoted and silently re-aimed at another entry. The gutter has to say so before it happens.
     *
     * And in STRICT there is a second consequence the author cannot see from here: a tag no row mentions is
     * an **unexpected extra**, so dropping a row for a tag the venue still sends does not stop the step
     * caring about it — it flips *which way* the step cares, and the next run is red where this edit looked
     * like a clean-up. The tooltip is the one moment to say so before it happens. (A tag the engine never
     * asserts — the connection's own — is not counted as an extra, so it earns no warning it hasn't.)
     */
    private fun dropOffer(index: Int, tag: Int, message: MessageView): Offer {
        val wholeTag = ScenarioReconcile.dropTakesWholeTag(draft, index)
        val base =
            if (wholeTag) {
                "Drop tag $tag — every row for $tag goes, or the survivors would start checking another entry"
            } else {
                "Drop this row — the step stops checking $tag"
            }
        val strictExtra =
            draft.mode == MatchMode.STRICT &&
                tag !in ExpectationEvaluator.NEVER_ASSERTED &&
                message.fields().any { it.first == tag }
        return Offer(
            OfferKind.DROP,
            "×",
            if (strictExtra) {
                "$base. The venue still sends this, and STRICT counts a tag no row mentions as an " +
                    "unexpected extra — dropping it makes the next run red"
            } else {
                base
            },
            EditOp.drop(index, tag),
        )
    }
}

/**
 * Where an entry ends up when it changes places with a sibling — the same arithmetic `moveEntry` uses to
 * rewrite the rows, which is the only honest way to keep the highlight on the thing that travelled.
 */
private fun ReconcileSession.moveEntrySelection(model: DiffModel, sel: DiffSelection.Entry, down: Boolean): EditResult {
    val entry = model.overlay.entries.firstOrNull { it.rows == sel.rows } ?: return EditResult.Unchanged
    val siblings = model.overlay.siblingsOf(entry)
    val at = siblings.indexOf(entry)
    val toSlot = if (down) at + 1 else at - 1
    if (at < 0 || toSlot !in siblings.indices) return EditResult.Unchanged
    return apply(EditOp.moveEntry(model.overlay, sel.rows, toSlot)).also { result ->
        if (result !is EditResult.Applied) return@also
        // Where the entry ended up — the same arithmetic `moveEntry` used to rewrite the rows, which is
        // the only honest way to keep the highlight on the thing that travelled.
        val ranges = siblings.map { it.rows }.toMutableList().apply { add(toSlot, removeAt(at)) }
        val start = siblings.first().rows.first + ranges.take(toSlot).sumOf { it.count() }
        selection = DiffSelection.Entry(start until start + sel.rows.count())
    }
}

/** Bare variable names inside `${...}` — the same shape `ScenarioAnnotations` badges by. */
private val BARE_NAMES = Regex("""\$\{\s*(\w+)\s*}""")
