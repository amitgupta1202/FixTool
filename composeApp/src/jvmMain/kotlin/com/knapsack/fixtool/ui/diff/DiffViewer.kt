package com.knapsack.fixtool.ui.diff

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.RawMessageView
import com.knapsack.fixtool.service.SessionTags
import com.knapsack.fixtool.service.compare.MessageField
import com.knapsack.fixtool.service.compare.ReferenceMessage

/** Which side *"Seed expectation from …"* mints an assertion out of. */
enum class SeedFrom { A, B }

/**
 * **One side of a plain diff** — a message the author put here, and how the header names it.
 *
 * Both sides are the venue's own **bytes** (invariant 3: only `wireRaw` feeds a diff, never the `|`-substituted
 * display string) — a picked grid message's `wireRaw`, or a `WirePaste` reading. Neither side is privileged:
 * the viewer diagnoses nothing, so [provenance] is for the chip's colour and its word, and for nothing that
 * judges — there is nothing here to judge (Phase 7, G5).
 */
data class DiffSide(
    val wire: String,
    /** What the A/B chip says: `A · ExecutionReport · TRADE · 09:35:44`, `B · pasted · UAT log · 08:12:31`. */
    val label: String,
    val provenance: ReferenceMessage.Provenance,
) {
    val view: MessageView get() = RawMessageView(wire)

    /** MsgType(35), for the title's `8 vs 8`. */
    val messageType: String? get() = view.fields().firstOrNull { it.first == 35 }?.second
}

/**
 * The envelope — `BeginString`, `BodyLength`, `CheckSum`, `MsgSeqNum`, `SendingTime`, the CompIDs,
 * `LastMsgSeqNumProcessed`. It differs by construction on any two distinct messages, so a viewer that showed it
 * would drown the real diff in `≠` rows nobody can act on. Dropped from **both** sides (Phase 7, G1) — dropping
 * it from only one would resurface every envelope tag as a spurious `+B`.
 */
internal fun businessFields(view: MessageView): List<Pair<Int, String>> =
    view.fields().filterNot { it.first in SessionTags.NEVER_ASSERTED }

private fun businessView(view: MessageView): MessageView =
    object : MessageView {
        override fun fields(): List<Pair<Int, String>> = businessFields(view)
    }

/**
 * **Side A as an all-exact expectation** — every field `Exact`, so a value that differs is a diff and nothing
 * is loosened into hiding one.
 *
 * NOT `ExpectationSeeder.seed`: the seeder would loosen a `UTCTIMESTAMP` to `~now ±60s` and an id to `presence`,
 * so two messages whose `SendingTime`s differ would pair `SAME` and the viewer would hide the very difference it
 * exists to show. The diff is then the runner's own `align(expectation, referenceB)` (Phase 7, G1), so "pairing
 * never consults the matcher" is true by construction — an `Exact` matcher consults value equality, which the
 * contract permits.
 */
internal fun exactExpectation(side: DiffSide, mode: MatchMode): Expectation {
    val fields = businessFields(side.view)
    return Expectation(
        fields = fields.map { (tag, value) -> FieldExpectation(tag = tag, matcher = Matcher.Exact(value)) },
        messageType = fields.firstOrNull { it.first == 35 }?.second,
        mode = mode,
    )
}

/**
 * **Side B as a reference**, envelope stripped to match A. No anchor: an all-exact expectation carries no
 * temporal row, so there is no `~now` to judge and no moment to judge it at — the reference's `SendingTime`
 * would only ever be noise here.
 */
internal fun referenceOf(side: DiffSide): ReferenceMessage =
    ReferenceMessage(
        view = businessView(side.view),
        provenance = side.provenance,
        label = side.label,
        anchorInstant = null,
    )

/**
 * **The plain diff viewer's state holder — and it holds no way to write.**
 *
 * This is the viewer's answer to [ReconcileSession], and the difference *is* the phase: it produces a
 * [DiffModel] and it can [swapSides] and [setMode], and it has **no `apply`, no undo stack, no `onChange`**. The
 * shared [buildDiffModel] does the work; the viewer hands it an offer generator that offers nothing, so its
 * lines carry no [Offer] and its gutter has nothing to draw but the status glyph. *"Nothing can write"* is
 * therefore a property of this class, not a hidden button — there is no mutation to reach (Phase 7, G2).
 *
 * [swapSides] and [setMode] are the two things it *can* do, and — like `SwapReference` in the editor — neither
 * is an edit: they change what you are looking at, and there is nothing staged to undo.
 */
class DiffViewerSession(
    left: DiffSide,
    right: DiffSide,
    val dictionary: FixDictionaryAdapter?,
    mode: MatchMode = MatchMode.OPEN,
) {
    var left: DiffSide by mutableStateOf(left)
        private set

    var right: DiffSide by mutableStateOf(right)
        private set

    /** OPEN by default: it tolerates the extras a benign message carries rather than flagging them as moves. */
    var mode: MatchMode by mutableStateOf(mode)
        private set

    /** **Swap A and B.** Both sides move — neither is privileged — which is what makes this the viewer's swap. */
    fun swapSides() {
        val l = left
        left = right
        right = l
    }

    fun selectMode(next: MatchMode) {
        mode = next
    }

    // Value-typed, like ReconcileSession's: the wire bytes and the mode are equal when they are equal, so the
    // memo actually hits. Keying on the DiffSide (whose `view` is a RawMessageView with no `equals`) would miss
    // on every recomposition and re-run `reorder` for a reason nobody would ever find.
    private data class Key(val left: String, val right: String, val mode: MatchMode)

    private var memo: Pair<Key, DiffModel>? = null

    /** For tests: a memo that never hits is a bug. */
    var rebuilds: Int = 0
        private set

    val model: DiffModel
        get() {
            val key = Key(left.wire, right.wire, mode)
            memo?.let { (cached, model) -> if (cached == key) return model }
            val built =
                buildDiffModel(
                    draft = exactExpectation(left, mode),
                    reference = referenceOf(right),
                    dictionary = dictionary,
                    resolver = { null },
                    // No offer generator: the viewer's lines carry no offers, which is the whole of G2.
                )
            rebuilds += 1
            memo = key to built
            return built
        }
}

/**
 * **The viewer window's subject — and it belongs to no scenario at all.**
 *
 * `DiffWindowState.scenarioId` is a non-null `String`, and Phase 6's twelve slot/rebind callers key to it; a
 * plain diff has neither a scenario nor a step (F9 predicted exactly this). So this is a **second** window
 * subject, sibling to `DiffWindowState`, not a widening of it — which is what keeps those twelve callers
 * untouched. It reuses the Phase-6 window mechanism (application-scope `Window`, `rememberWindowState` keyed by
 * [id], the by-title `/screenshot` selector) rather than building a second one (Phase 7, G3).
 *
 * It carries no `ScenarioDraft` and no undo stack — until [editing] is non-null, which is the one-way door:
 * Seed floats a scenario-less [ReconcileSession] here, and *"Add to scenario…"* is what turns it into a
 * scenario-bound step (G6).
 */
data class DiffViewerState(
    val id: String,
    /** Present once both sides are bound — the read-only diff. Null only for the empty *"Diff messages…"* opener. */
    val session: DiffViewerSession? = null,
    /**
     * The empty opener fills these one at a time (a pick or a paste); when both are non-null the ViewModel
     * promotes them into a [session]. A slot the author has not filled yet is a prompt, not a diff against
     * nothing — the same rule the reconcile window's empty reference follows.
     */
    val pendingLeft: DiffSide? = null,
    val pendingRight: DiffSide? = null,
    /**
     * **Seed pressed.** A scenario-less editable expectation floats here, the other side as its reference, until
     * *"Add to scenario…"* files it (G6). While it is non-null the window renders the editor, not the viewer.
     */
    val editing: ReconcileSession? = null,
    /** Bumped by every re-open of the same pair; the window raises itself on the bump (reused from Phase 6, F6). */
    val focusEpoch: Int = 0,
) {
    /** The window's title-worthy subject: `8 vs 8`, from the two MsgTypes (or the pending sides before both bind). */
    val subjectTypes: String
        get() {
            val a = session?.left?.messageType ?: pendingLeft?.messageType ?: "?"
            val b = session?.right?.messageType ?: pendingRight?.messageType ?: "?"
            return "$a vs $b"
        }

    companion object {
        /**
         * **Keyed on the message pair**, so opening *Diff selected* on the same two rows focuses the window
         * already showing them rather than minting a second (Phase 7, G3). The empty *"Diff messages…"* opener
         * has no pair yet, so it is given a freshly-minted id by the caller — two empty viewers are two
         * intentions.
         */
        fun pairId(leftWire: String, rightWire: String): String =
            "viewer:${leftWire.hashCode()}:${rightWire.hashCode()}"
    }
}

/**
 * **Side A's field, reconstructed from the all-exact row** — the viewer's left column is a *message*, not an
 * editor, so it renders a plain value like the right column. A's value is exactly what the row's `Exact` matcher
 * carries (that is how [exactExpectation] seeds it); a right-only line has no A field, and says so with a null.
 */
internal fun DiffLine.leftField(dictionary: FixDictionaryAdapter?): MessageField? {
    if (row.unasserted) return null
    val value = (row.matcher as? Matcher.Exact)?.value ?: return null
    return MessageField(
        wireIndex = row.wireIndex ?: -1,
        tag = row.tag,
        name = row.name,
        occurrence = row.occurrence,
        value = value,
        description = dictionary?.getFieldValueDescription(row.tag, value),
    )
}
