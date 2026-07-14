package com.knapsack.fixtool.service.compare

import com.knapsack.fixtool.service.ExpectationEvaluator
import com.knapsack.fixtool.service.MessageView
import java.time.Instant

/**
 * **Whose message is on the right** — and *when* it is from.
 *
 * The right-hand side of the diff is not hard-wired to "this run's failure". It is a slot: the message that
 * failed, the golden the step was captured from, a second live instance of the same shape, a row picked out
 * of a session grid, or wire bytes pasted from a real server's log. Swapping it re-judges every row.
 *
 * Two honesty rules ride along, and they are the whole reason this is a type rather than a `MessageView`
 * parameter.
 *
 * **Provenance is visible.** A hand-doctored paste must never be mistakable for a live capture, so the slot
 * says where its bytes came from and anything saved while a paste was bound carries that with it.
 *
 * **A moment is judged against the message's own moment.** A live message anchors at the instant it
 * arrived; a pasted one at its own `SendingTime(52)`. Judge a `~now ±60s` row against the wall clock and a
 * message pasted an hour ago manufactures a red that describes nothing but the reader's lateness — and the
 * author's only repairs on a temporal row are to loosen it or drop it, so a phantom red leads directly to
 * deleted coverage. Where there is no moment to be had at all, the row is [unanchored] and renders unjudged
 * rather than failed.
 */
data class ReferenceMessage(
    val view: MessageView,
    val provenance: Provenance,
    /** What the chip says: `this run · 09:35:44`, `golden`, `pasted`. */
    val label: String,
    /**
     * The instant a temporal row is judged at, or null when this message carries no moment of its own —
     * a paste with no `SendingTime(52)`.
     */
    val anchorInstant: Instant?,
) {
    /** No moment to judge a `~now` row against. The rows say so; they do not guess. */
    val unanchored: Boolean get() = anchorInstant == null

    enum class Provenance {
        /** The message that failed this run — the default the reconcile deep-link binds. */
        THIS_RUN,

        /** The message the step was captured from. Authoring is the same diff, against this. */
        GOLDEN,

        /** A later live message of the same shape — what "verify generalizes" always was. */
        SECOND_INSTANCE,

        /** Picked out of a session grid by the author. */
        PICKED,

        /** Wire bytes from somewhere FixTool cannot vouch for: a log, an email, another environment. */
        PASTED,
    }

    companion object {
        /** A message FixTool saw arrive, anchored at the instant it did. */
        fun live(view: MessageView, provenance: Provenance, label: String, arrivedAt: Instant) =
            ReferenceMessage(view, provenance, label, arrivedAt)

        /**
         * Bytes the author pasted, anchored to **the message's own `SendingTime(52)`** — the only moment a
         * paste carries. Parsed by the evaluator's own parser, because two parsers would eventually disagree
         * about what a timestamp is and the row would then be judged against a moment the anchor never meant.
         */
        fun pasted(view: MessageView, label: String = "pasted"): ReferenceMessage {
            val sendingTime = view.fields().firstOrNull { it.first == 52 }?.second
            return ReferenceMessage(
                view = view,
                provenance = Provenance.PASTED,
                label = label,
                anchorInstant = sendingTime?.let { ExpectationEvaluator.parseTimestamp(it) },
            )
        }

        /**
         * The golden. It carries a `SendingTime` if it was captured from the wire, and if it does not, its
         * temporals are unjudged — which is honest: a golden with no moment cannot say whether "~now" held.
         */
        fun golden(view: MessageView): ReferenceMessage = pasted(view, "golden").copy(provenance = Provenance.GOLDEN)
    }
}

/** Why a `~now` row cannot be judged against a reference that carries no moment of its own. */
const val NO_ANCHOR: String =
    "This message carries no SendingTime(52), so there is no moment to judge '~now' against. Judging it " +
        "against the clock would manufacture a red describing nothing but how long ago the message was " +
        "pasted — and the only repairs on a temporal row are to loosen it or drop it, so a phantom red here " +
        "leads straight to deleted coverage. The row is left unjudged."
