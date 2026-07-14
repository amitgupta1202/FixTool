package com.knapsack.fixtool.service.compare

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.ScenarioReconcile
import java.time.Instant

/**
 * **How two things are paired** — and nothing else.
 *
 * STRICT and OPEN differ only in what they do about the tags an expectation does not mention; both are
 * sequence alignments over `(tag, position)`. The comparisons the product wants next — ordered tree edit
 * distance, GumTree-style move detection — differ in *how rows pair*, not in what a diff looks like once
 * they have. So the seam goes between pairing and presentation: a semantics produces an [AlignmentModel],
 * and the diff surface renders one without knowing which semantics produced it.
 *
 * **The contract, which is the whole of the safety argument:** a semantics may consult tags, positions,
 * and value *equality* to pair — **never whether a matcher would pass**. A pairing that preferred the
 * occurrence a matcher happens to satisfy would re-aim an assertion onto whichever field makes it green:
 * the venue reorders two party entries, every row finds the entry that still matches, and the step passes
 * while asserting something nobody wrote. `SemanticsContractTest` runs that property, and the others, over
 * every registered semantics; [SemanticsRegistry] is where a semantics has to be registered to be usable,
 * and the test enumerates the registry, so a new one cannot ship without facing it.
 */
interface ComparisonSemantics {
    /** The id the scenario stores (`strict`, `open`; later `tree-ted`, `gumtree`). */
    val id: String

    /** What the mode chip in the diff header says. */
    val label: String

    /**
     * Line [expectation] up against [actual].
     *
     * @param at the instant the rows are judged at — **the instant the message arrived**, never the
     *   instant the diff was opened. A `~now ±60s` row that passed during the run must not read as a venue
     *   regression because the engineer took two minutes to click Reconcile.
     */
    fun align(
        expectation: Expectation,
        actual: MessageView,
        dictionary: FixDictionaryAdapter?,
        resolver: (String) -> String? = { null },
        at: Instant = Instant.now(),
    ): AlignmentModel

    /**
     * Line [expectation] up against whatever is currently in the reference slot — this run's failure, the
     * golden, a picked message, a paste — judged at **that message's own moment**. Swapping the slot and
     * re-aligning is the whole of "re-judge against something else".
     */
    fun align(
        expectation: Expectation,
        reference: ReferenceMessage,
        dictionary: FixDictionaryAdapter?,
        resolver: (String) -> String? = { null },
    ): AlignmentModel
}

/** The one shape the diff surface renders, whatever produced it. */
data class AlignmentModel(
    val chunks: List<Chunk>,
) {
    /** Every row of the diff, in reading order — the engine's own verdicts, ungrouped. */
    val rows: List<ScenarioReconcile.Row> get() = chunks.flatMap { it.rows }
}

/**
 * What happened to a run of rows. The gutter's offer is a function of this and nothing else.
 *
 * A row the diff cannot judge — a `reference`, which resolves against a live run's scope and has none
 * here — is [SAME]: it is *paired*, there is no difference to act on, and it is neither passing nor
 * failing. The amber "unjudged" state the mockups show comes off `Row.unknown`, and those rows are
 * excluded from the headline counts. Calling it a value mismatch (which is what its status says, because
 * a null resolver cannot satisfy it) would put an "Accept actual" under a row whose reference was working
 * perfectly — one click, and the cross-step binding the row exists to express is gone.
 */
enum class ChunkKind {
    /** Paired, and nothing to do: the matcher holds, or the row cannot be judged here at all. */
    SAME,

    /** Paired, and the value does not satisfy the matcher. The one kind bulk actions never touch. */
    VALUE,

    /** The expectation has a row the reply does not answer — a tag the venue stopped sending. */
    LEFT_ONLY,

    /** The reply carries a field no row mentions. A failure in STRICT; the point of OPEN. */
    RIGHT_ONLY,

    /** The rows are in the reply, intact, somewhere else — the engine proved it. See [Chunk.moveLink]. */
    MOVED,
}

/** One field of the message, as the right-hand column shows it. */
data class MessageField(
    val wireIndex: Int,
    val tag: Int,
    val name: String,
    /** Which occurrence of [tag] this is, 0-based. */
    val occurrence: Int,
    val value: String,
    /** The dictionary's word for this value ("Filled"), or null where it has none. */
    val description: String?,
)

/**
 * A run of rows that share a fate — the unit the gutter acts on.
 *
 * Contiguous rows of the same kind group together, `git diff`-style, because that is the unit the *user*
 * acts on: a venue does not move a `PartyRole`, it moves a party, and offering one fix per row would be
 * several clicks to express one fact with the expectation momentarily wrong between each.
 */
data class Chunk(
    val id: Int,
    val kind: ChunkKind,
    /**
     * The engine's rows for this chunk, **verbatim** — the very rows the runner judged. A [RIGHT_ONLY]
     * chunk's rows are the reply's extras (`matcher == null`), which is what "assert it" acts on; they
     * are rows of the diff without being rows of the expectation, which is why [left] is not simply this.
     */
    val rows: List<ScenarioReconcile.Row>,
    /** The message fields on the right. Empty for [LEFT_ONLY] — the venue sent nothing here. */
    val right: List<MessageField>,
    /**
     * The chunk this one traded places with. Two party entries that swapped are two [MOVED] chunks
     * pointing at each other, which is the crossing connector the mockup draws between them.
     */
    val moveLink: Int? = null,
) {
    /** The expectation rows — the editable left column. Empty for [RIGHT_ONLY]. */
    val left: List<ScenarioReconcile.Row> get() = rows.filter { it.matcher != null }
}

/**
 * STRICT and OPEN, as thin wrappers over the engine that already implements them.
 *
 * They **re-mode the expectation** before diffing rather than reading its stored mode, which is what lets
 * the editor preview a failure under a semantics the scenario was not authored under (read-only, no
 * rewrite). [SemanticsRegistry.forMode] is what the runner's own mode resolves through, so nothing about
 * judging changes.
 */
private open class SequenceSemantics(
    override val id: String,
    override val label: String,
    private val mode: MatchMode,
) : ComparisonSemantics {
    override fun align(
        expectation: Expectation,
        actual: MessageView,
        dictionary: FixDictionaryAdapter?,
        resolver: (String) -> String?,
        at: Instant,
    ): AlignmentModel {
        val draft = expectation.copy(mode = mode)
        val rows = ScenarioReconcile.rows(draft, actual, dictionary, resolver) { at }
        return build(draft, actual, dictionary, resolver, { at }, rows)
    }

    override fun align(
        expectation: Expectation,
        reference: ReferenceMessage,
        dictionary: FixDictionaryAdapter?,
        resolver: (String) -> String?,
    ): AlignmentModel {
        val draft = expectation.copy(mode = mode)
        // The reference's own moment, and — where it has none — rows that say so instead of guessing.
        val anchor = reference.anchorInstant ?: Instant.now()
        return build(
            draft,
            reference.view,
            dictionary,
            resolver,
            { anchor },
            ScenarioReconcile.rows(draft, reference, dictionary, resolver),
        )
    }

    @Suppress("LongParameterList") // The engine's inputs, threaded whole: dropping one is how a clock gets lost.
    private fun build(
        draft: Expectation,
        actual: MessageView,
        dictionary: FixDictionaryAdapter?,
        resolver: (String) -> String?,
        now: () -> Instant,
        // Straight off ScenarioReconcile, which is straight off ExpectationEvaluator.diff — so the lines
        // the author sees are the lines the runner judged. A view that ran its own comparison would drift,
        // and would eventually offer a fix for a row the engine was not asserting.
        rows: List<ScenarioReconcile.Row>,
    ): AlignmentModel {
        val wire = actual.fields()

        // **What moved is what the engine proved moved** — not what a row's status says.
        //
        // Two party entries that trade places keep the expectation's tag sequence exactly, so every row
        // still *pairs*: the rows go red as value mismatches (FIRMA's 448 landing on FIRMB) and not one of
        // them carries TagStatus.MOVED, which means "this row paired with nothing and its value is
        // elsewhere". The move is a fact about the whole entry, and `reorder` is the only thing that
        // establishes it — verbatim block, occurrence-preserving, verified against the engine's own
        // alignment. So the moved rows come from there, and with them the placement that says where each
        // one landed; a moved row pairs with nothing, so nothing else can say what to draw opposite it.
        val reorder =
            ScenarioReconcile.reorder(draft, actual, now, resolver) as? ScenarioReconcile.Reorder.Possible

        return AlignmentModel(
            linkMoves(
                chunk(rows, wire, dictionary, reorder?.moved ?: emptySet(), reorder?.placement ?: emptyMap()),
            ),
        )
    }

    /** Group the diff into the runs the gutter acts on. */
    private fun chunk(
        rows: List<ScenarioReconcile.Row>,
        wire: List<Pair<Int, String>>,
        dictionary: FixDictionaryAdapter?,
        movedRows: Set<Int>,
        placement: Map<Int, Int>,
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var i = 0
        while (i < rows.size) {
            val kind = kindOf(rows[i], movedRows)
            var j = i + 1
            while (j < rows.size && continues(rows, j, kind, movedRows, placement)) j++
            val run = rows.subList(i, j).toList()
            val right =
                when (kind) {
                    ChunkKind.MOVED -> run.mapNotNull { it.index?.let(placement::get) }.sorted()
                    else -> run.mapNotNull { rightOf(it, wire) }
                }
            val fields = right.map { field(it, wire, dictionary) }
            chunks += Chunk(id = chunks.size, kind = kind, rows = run, right = fields)
            i = j
        }
        return chunks
    }

    /**
     * Does the row at [j] belong to the run that is being built?
     *
     * Same kind, and — for a move — **the same journey**. Two party entries that traded places are six
     * consecutive moved rows, and they are *two* entries: the first three land on fields 3,4,5 and the next
     * three on 0,1,2. Grouping them by "moved and adjacent" would fuse both parties into one chunk with a
     * single Accept-new-order across the pair. So the break is taken from the engine's own placement, which
     * is the only thing here that knows a block travelled together.
     */
    @Suppress("ReturnCount") // One guard per reason; nesting them reads far worse than four early exits.
    private fun continues(
        rows: List<ScenarioReconcile.Row>,
        j: Int,
        kind: ChunkKind,
        movedRows: Set<Int>,
        placement: Map<Int, Int>,
    ): Boolean {
        if (kindOf(rows[j], movedRows) != kind) return false
        if (kind != ChunkKind.MOVED) return true
        val from = rows[j - 1].index?.let(placement::get) ?: return false
        val to = rows[j].index?.let(placement::get) ?: return false
        return to == from + 1
    }

    private fun field(at: Int, wire: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter?): MessageField {
        val (tag, value) = wire[at]
        return MessageField(
            wireIndex = at,
            tag = tag,
            name = dictionary?.getFieldName(tag) ?: "",
            occurrence = (0 until at).count { wire[it].first == tag },
            value = value,
            description = dictionary?.getFieldValueDescription(tag, value),
        )
    }

    /**
     * Where the right column shows this row. An `absent` row claims no field — but its *failure* is that the
     * reply carries the tag at all, so the field it is failing against is the one to put opposite it.
     */
    private fun rightOf(row: ScenarioReconcile.Row, wire: List<Pair<Int, String>>): Int? =
        row.wireIndex
            ?: if (row.matcher is Matcher.Absent && row.actual != null) {
                wire.indexOfFirst { it.first == row.tag }.takeIf { it >= 0 }
            } else {
                null
            }

    /**
     * [ChunkKind.MOVED] is reserved for a move the engine will actually *offer* — anything else would band
     * an entry violet and put an "Accept new order" over rows nothing can re-order.
     *
     * A row whose own status is MOVED but which no re-ordering covers (the engine looked and refused) is a
     * row the reply does not answer at that position: structurally [ChunkKind.LEFT_ONLY], rendered against
     * a gap. It keeps its status and its sentence — *"present, but not in this position"* — so the surface
     * still says the true thing about it; what it does not do is promise a button that is not there.
     */
    private fun kindOf(row: ScenarioReconcile.Row, moved: Set<Int>): ChunkKind =
        when {
            row.unasserted -> ChunkKind.RIGHT_ONLY
            // Moved is asked BEFORE unjudgeable, because a row the engine proved moved is a row Accept-new-order
            // is about to relocate — whatever the diff can or cannot say about its value. An echoed id inside a
            // party entry that travelled is exactly that row, and calling it SAME would have banded the entry's
            // rows as two moved chunks with a placid one wedged between them: one crossing connector drawn to
            // the wrong place, and a row silently rewritten by a button whose chunk said there was nothing here.
            row.index in moved -> ChunkKind.MOVED
            row.unknown -> ChunkKind.SAME // paired, and unjudgeable here: there is no difference to act on
            row.status == TagStatus.MOVED || row.status == TagStatus.MISSING -> ChunkKind.LEFT_ONLY
            row.status == TagStatus.VALUE || row.status == TagStatus.INVALID -> ChunkKind.VALUE
            else -> ChunkKind.SAME
        }

    /**
     * Pair the moved chunks up, so each one knows what it swapped with.
     *
     * The *k*-th moved chunk in the expectation's order took the *k*-th of the wire spans the moved rows
     * landed on — so for the two party entries that traded places, chunk 1 points at chunk 2 and chunk 2
     * points back. A single entry that moved among fields nobody asserted has no counterpart, and says so
     * with a null.
     */
    private fun linkMoves(chunks: List<Chunk>): List<Chunk> {
        val moved = chunks.filter { it.kind == ChunkKind.MOVED && it.right.isNotEmpty() }
        if (moved.size < 2) return chunks
        val byLandingPlace = moved.sortedBy { it.right.first().wireIndex }
        val link = moved.mapIndexed { rank, chunk -> chunk.id to byLandingPlace[rank].id }.toMap()
        return chunks.map { if (it.id in link) it.copy(moveLink = link[it.id]) else it }
    }
}

/** "The venue sent me the same message" — same tags, same count, same order, every matcher satisfied. */
object StrictSemantics : ComparisonSemantics by SequenceSemantics("strict", "STRICT", MatchMode.STRICT)

/** "The venue said at least this much, in the order it said it" — the expectation is a subsequence. */
object OpenSemantics : ComparisonSemantics by SequenceSemantics("open", "OPEN", MatchMode.OPEN)

/**
 * The semantics that exist, and the gate they pass to get here.
 *
 * The gate is a **test**, not a runtime assertion: `SemanticsContractTest` enumerates this registry and
 * fails the build for anything in it that violates the pairing contract. Nothing can run a
 * hundred-thousand-pair property harness at app start, and pretending otherwise would have produced a
 * check that either lied or never ran. What *does* happen at start is the cheap half — every [MatchMode]
 * must resolve to a registered semantics, or the class will not initialise.
 */
object SemanticsRegistry {
    private val byId: Map<String, ComparisonSemantics> =
        listOf(StrictSemantics, OpenSemantics).associateBy { it.id }

    init {
        MatchMode.entries.forEach { forMode(it) }
    }

    fun all(): List<ComparisonSemantics> = byId.values.toList()

    fun byId(id: String): ComparisonSemantics? = byId[id]

    /** The semantics a mode judges under. The runner's behaviour, unchanged, expressed through the seam. */
    fun forMode(mode: MatchMode): ComparisonSemantics =
        byId[semanticsId(mode)]
            ?: throw IllegalStateException(
                "no comparison semantics registered for mode $mode — the diff surface would have nothing to " +
                    "align with, so this fails here rather than at the first failing step.",
            )

    /** The id a mode serializes as. The scenario format stores the mode; the seam speaks in ids. */
    fun semanticsId(mode: MatchMode): String =
        when (mode) {
            MatchMode.STRICT -> "strict"
            MatchMode.OPEN -> "open"
        }
}
