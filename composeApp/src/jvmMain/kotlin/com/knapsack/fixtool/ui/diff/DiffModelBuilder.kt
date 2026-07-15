package com.knapsack.fixtool.ui.diff

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.ScenarioReconcile
import com.knapsack.fixtool.service.compare.ChunkKind
import com.knapsack.fixtool.service.compare.EntryNode
import com.knapsack.fixtool.service.compare.GroupOverlay
import com.knapsack.fixtool.service.compare.SemanticsRegistry
import com.knapsack.fixtool.service.compare.Verdict

/**
 * **Everything the diff surface draws, from a draft and a reference — and the one piece the caller supplies is
 * what the gutter may offer.**
 *
 * This is the computation that used to be `ReconcileSession.build`, lifted out so a *second* surface can share
 * it without inheriting the first one's editing apparatus. The reconcile session passes its real
 * [ReconcileSession.offersFor]; the plain diff **viewer** passes `{ _, _, _ -> emptyList() }`, so its lines carry
 * no offers and its gutter has nothing to draw but the status glyph. That is why *"nothing can write"* in the
 * viewer is a property of the state holder and not a hidden button — there is no offer to click, because none
 * was ever generated (Phase 7, G2).
 *
 * The alignment itself is the runner's own `align` over `(draft, reference)` — the viewer's "left side is a
 * message" is expressed by seeding that message as an all-exact [Expectation] (G1), so this function never learns
 * whether it is diffing an assertion or two messages. Pairing stays blind to the matcher because it is the same
 * code the runner judges with.
 */
internal fun buildDiffModel(
    draft: Expectation,
    reference: com.knapsack.fixtool.service.compare.ReferenceMessage,
    dictionary: FixDictionaryAdapter?,
    resolver: (String) -> String?,
    offersFor: (ScenarioReconcile.Row, MessageView, Set<Int>) -> List<Offer> = { _, _, _ -> emptyList() },
): DiffModel {
    // Every judgement below is made against the **reference**, and never against a clock this call chose:
    // it carries the moment a `~now ±60s` row is judged at — the instant the message arrived, not the
    // instant the engineer got round to clicking — and it carries the fact that some references have no
    // moment at all, which is a thing both `rows` and `reorder` have to be told in the same words.
    val message = reference.view

    // From the PARAMETER's mode, never a session's: this also builds candidates that are not staged
    // (the drag tooltip's `verdictOf`), and a candidate judged under the draft's semantics instead of its own
    // would be a preview of something the author is not about to get.
    val alignment = SemanticsRegistry.forMode(draft.mode).align(draft, reference, dictionary, resolver)
    val rows = ScenarioReconcile.rows(draft, reference, dictionary, resolver)
    val overlay = GroupOverlay.of(draft, dictionary)
    // The reference, not the bare view: it carries whether there is a moment to judge a `~now` row by at
    // all, and `reorder` has to be asked the same question `rows` is. Ask it the other way and a golden
    // with no SendingTime(52) — which is every golden captured from a venue that does not send one —
    // silently loses Accept-new-order on the entries that plainly moved, and blames the venue for it.
    val reorder = ScenarioReconcile.reorder(draft, reference, resolver)
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

    val referenceOverlay = GroupOverlay.of(message, dictionary)
    return DiffModel(
        lines = lines,
        items = itemsOf(lines, overlay, referenceOverlay),
        chunks = alignment.chunks,
        verdict = Verdict.of(rows, moved, movedEntries, draft.mode),
        overlay = overlay,
        referenceOverlay = referenceOverlay,
        acceptOrder = possible?.let { EditOp.acceptOrder(it.reordered) },
        withheldMove = (reorder as? ScenarioReconcile.Reorder.Refused)?.why,
        canAcceptShape = Verdict.canAcceptShape(rows, draft.mode),
    )
}

/**
 * **The display list**: a band wherever the entry under the lines changes, then the lines themselves.
 *
 * Top-level, and pure, because nothing about it is a fact about a *session* — it is a fact about a list of
 * lines and an overlay. `↑/↓` walks it, `n`/`p` walks the chunks in it, and the crossing connector asks it
 * for the item indices of its two ends. See [DiffItem].
 */
private fun itemsOf(lines: List<DiffLine>, overlay: GroupOverlay, referenceOverlay: GroupOverlay): List<DiffItem> {
    val hues = overlay.entries.withIndex().associate { (i, e) -> e to i }
    val depths = overlay.entries.associateWith { e -> overlay.entries.count { e.rows.first in it.rows } }
    val out = mutableListOf<DiffItem>()
    var open: EntryNode? = null
    var movedSeen = false

    lines.forEach { line ->
        val entry = line.entry
        if (entry != null && entry != open) {
            val moved = line.kind == ChunkKind.MOVED
            out +=
                DiffItem.Band(
                    entry = entry,
                    hue = hues[entry] ?: 0,
                    depth = (depths[entry] ?: 1) - 1,
                    moved = moved,
                    rightLabel = line.right?.let { referenceOverlay.entryAt(it.wireIndex)?.label },
                    // The one-click re-order goes on the FIRST moved band and nowhere else — decided here,
                    // once, because a lazy list composes what it likes in whatever order it likes.
                    first = moved && !movedSeen,
                    chunkId = line.chunkId,
                )
            movedSeen = movedSeen || moved
        }
        open = entry
        out += DiffItem.Line(line, if (entry == null) 0 else depths[entry] ?: 1)
    }
    return out
}
