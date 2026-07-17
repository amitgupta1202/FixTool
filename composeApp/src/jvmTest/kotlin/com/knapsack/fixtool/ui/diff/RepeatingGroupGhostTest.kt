package com.knapsack.fixtool.ui.diff

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.service.ExpectationEvaluator
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.service.wireView
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals

/**
 * **The ghost/move machinery under repeating groups — missing, misplaced, and extra fields, jumbled.**
 *
 * A flat greedy aligner is where these interact worst: a field missing from one party entry slides that
 * entry's later rows onto the *next* party's occurrences, so "missing here" and "wrong place there" are not
 * independent. This renders the real engine's diff for a family of party-group messages and checks the two
 * invariants the ghost line exists to hold, on every one of them:
 *
 *  1. **Right column is the reply, whole, in wire order** — every field the venue sent (bar the never-asserted
 *     envelope) appears exactly once, in order, across the left-gap ghosts and the paired rows.
 *  2. **Each divergence is counted once** — a ghost is never also tallied as an added tag.
 *
 * The rendered tables are written to the scratchpad so a human can read whether the *labels* make sense, not
 * only whether the invariants hold.
 */
class RepeatingGroupGhostTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val arrival = Instant.parse("2025-01-01T00:00:00Z")
    private val out = StringBuilder()

    private fun session(expectation: Expectation, wire: MessageView) =
        ReconcileSession(
            expectation,
            ReferenceMessage.live(wire, ReferenceMessage.Provenance.THIS_RUN, "this run", arrival),
            dictionary,
        )

    /** A party entry as expectation rows: 448 PartyID, 447 PartyIDSource, 452 PartyRole. */
    private fun party(id: String, role: String) =
        listOf(
            FieldExpectation(448, Matcher.Exact(id)),
            FieldExpectation(447, Matcher.Exact("D")),
            FieldExpectation(452, Matcher.Exact(role)),
        )

    private fun expectation(entries: Int, mode: MatchMode = MatchMode.STRICT): Expectation =
        Expectation(
            listOf(FieldExpectation(35, Matcher.Exact("8")), FieldExpectation(453, Matcher.Exact("$entries"))) +
                (1..entries).flatMap { party("FIRM$it", "$it") },
            messageType = "8",
            mode = mode,
        )

    // ----------------------------------------------------------------- the renderer

    private fun render(title: String, expectation: Expectation, wire: MessageView) {
        val session = session(expectation, wire)
        val model = session.model
        out.appendLine("=".repeat(96))
        out.appendLine(title)
        out.appendLine("  verdict : ${model.verdict.headline}")
        out.appendLine("  parts   : ${model.verdict.partsUnder(open = expectation.mode == MatchMode.OPEN)}")
        out.appendLine("  added=${model.verdict.added} missing=${model.verdict.missing} " +
            "values=${model.verdict.values} movedRows=${model.verdict.movedRows} " +
            "movedEntries=${model.verdict.movedEntries} unresolved=${model.verdict.unresolved} " +
            "attention=${model.verdict.attention}")
        out.appendLine("-".repeat(96))
        out.appendLine("  %-34s | %-9s | %-30s".format("EXPECTATION (left)", "gutter", "RECEIVED (right)"))
        out.appendLine("-".repeat(96))
        model.items.forEach { item ->
            when (item) {
                is DiffItem.Band ->
                    out.appendLine("  ┌─ entry: ${item.entry.label.ifBlank { "(unlabelled)" }}" +
                        if (item.moved) "   [MOVED]" else "")
                is DiffItem.Line -> out.appendLine(renderLine(item.line))
            }
        }
        out.appendLine()
    }

    private fun renderLine(line: DiffLine): String {
        val left =
            when {
                line.row.ghost -> "· ghost ·  (${line.row.reason})"
                line.leftIsGap -> "· not asserted ·"
                else -> "%d#%d  %s".format(line.row.tag, line.row.occurrence + 1, ExpectationEvaluator.describe(line.row.matcher!!))
            }
        val gutter =
            buildString {
                append(line.kind.name.take(4).lowercase())
                if (line.row.ghost) append(" G")
                if (!line.row.unasserted) append(" ${line.row.status.name.take(4)}")
                if (line.row.passed && !line.row.unasserted) append(" ok")
            }
        val right =
            when {
                line.right != null -> "%d#%d = %s".format(line.right!!.tag, line.right!!.occurrence + 1, line.right!!.value)
                line.row.status == TagStatus.MOVED -> "· elsewhere ·"
                else -> "· not sent ·"
            }
        return "  %-34s | %-9s | %-30s".format(left, gutter, right)
    }

    // ----------------------------------------------------------------- the invariants

    /** Every wire field the engine is willing to assert appears once on the right, in wire order. */
    private fun assertRightColumnWhole(expectation: Expectation, wire: MessageView) {
        val model = session(expectation, wire).model
        val shown = model.lines.mapNotNull { it.right?.wireIndex }
        val expected =
            wire.fields().indices.filter { wire.fields()[it].first !in ExpectationEvaluator.NEVER_ASSERTED }
        assertEquals(expected, shown, "right column must be every venue field once, in wire order")
    }

    /** A ghost is display only: it is never also counted as an added tag. */
    private fun assertGhostsNotDoubleCounted(expectation: Expectation, wire: MessageView) {
        val model = session(expectation, wire).model
        val ghosts = model.lines.count { it.row.ghost }
        val addedLines = model.lines.count { it.row.unasserted && !it.row.ghost && it.right != null }
        assertEquals(addedLines, model.verdict.added, "verdict.added counts real extras only, never ghosts")
        // and every ghost's tag really is asserted by some unpaired row elsewhere
        model.lines.filter { it.row.ghost }.forEach { g ->
            val hasUnpairedRowForTag =
                model.lines.any { !it.row.unasserted && it.row.tag == g.row.tag && it.rightIsGap }
            assertEquals(true, hasUnpairedRowForTag, "ghost for tag ${g.row.tag} must have its unpaired row on screen")
        }
        if (ghosts > 0) out.appendLine("  (checked: $ghosts ghost(s), none double-counted)\n")
    }

    /** One entry is one band: a ghost interleaving its rows must not split it into two same-named bands. */
    private fun assertEachEntryBandsOnce(expectation: Expectation, wire: MessageView) {
        val model = session(expectation, wire).model
        val banded = model.items.filterIsInstance<DiffItem.Band>().map { it.entry.rows }
        assertEquals(banded.distinct(), banded, "each entry must produce exactly one band: $banded")
    }

    private fun check(title: String, expectation: Expectation, wire: MessageView) {
        render(title, expectation, wire)
        assertRightColumnWhole(expectation, wire)
        assertGhostsNotDoubleCounted(expectation, wire)
        assertEachEntryBandsOnce(expectation, wire)
    }

    // ----------------------------------------------------------------- the cases

    @Test
    fun `repeating group ghost cases render and hold their invariants`() {
        // A — clean three-party reply: sanity, everything green, no ghosts.
        check(
            "A · clean 3-party reply (baseline)",
            expectation(3),
            wireView(
                35 to "8", 453 to "3",
                448 to "FIRM1", 447 to "D", 452 to "1",
                448 to "FIRM2", 447 to "D", 452 to "2",
                448 to "FIRM3", 447 to "D", 452 to "3",
            ),
        )

        // B — two whole parties swapped: the classic place-change inside a repeating group.
        check(
            "B · parties 1 and 2 swapped (place change)",
            expectation(3),
            wireView(
                35 to "8", 453 to "3",
                448 to "FIRM2", 447 to "D", 452 to "2",
                448 to "FIRM1", 447 to "D", 452 to "1",
                448 to "FIRM3", 447 to "D", 452 to "3",
            ),
        )

        // C — the user's three conditions, one per entry:
        //   entry 1: a tag MISSING (452 gone)
        //   entry 2: a tag at the WRONG PLACE (452 before 447)
        //   entry 3: an EXTRA (523 PartySubID the expectation never mentions)
        check(
            "C · e1 missing tag · e2 misplaced tag · e3 extra tag",
            expectation(3),
            wireView(
                35 to "8", 453 to "3",
                448 to "FIRM1", 447 to "D",                          // 452 missing
                448 to "FIRM2", 452 to "2", 447 to "D",              // 452 and 447 out of order
                448 to "FIRM3", 447 to "D", 452 to "3", 523 to "SUB", // extra 523
            ),
        )

        // D — six parties, the three conditions jumbled across them:
        //   e1 extra · e2 clean · e3 missing · e4 misplaced · e5 clean · e6 missing+extra
        check(
            "D · six parties, conditions jumbled",
            expectation(6),
            wireView(
                35 to "8", 453 to "6",
                448 to "FIRM1", 447 to "D", 452 to "1", 523 to "S1",   // e1: extra 523
                448 to "FIRM2", 447 to "D", 452 to "2",                // e2: clean
                448 to "FIRM3", 447 to "D",                            // e3: 452 missing
                448 to "FIRM4", 452 to "4", 447 to "D",                // e4: 452 misplaced
                448 to "FIRM5", 447 to "D", 452 to "5",                // e5: clean
                448 to "FIRM6", 452 to "6",                            // e6: 447 missing, 452 misplaced
            ),
        )

        // E — same as D but OPEN, so the extras are ignored rather than failures: the ghosts must not move.
        check(
            "E · six parties jumbled, OPEN mode",
            expectation(6, mode = MatchMode.OPEN),
            wireView(
                35 to "8", 453 to "6",
                448 to "FIRM1", 447 to "D", 452 to "1", 523 to "S1",
                448 to "FIRM2", 447 to "D", 452 to "2",
                448 to "FIRM3", 447 to "D",
                448 to "FIRM4", 452 to "4", 447 to "D",
                448 to "FIRM5", 447 to "D", 452 to "5",
                448 to "FIRM6", 452 to "6",
            ),
        )

        File("/private/tmp/claude-501/-Users-amitgupta-FixTool/f2b11810-fd5b-4df2-ac82-05d1eef8ba9e/scratchpad/repeating_group_render.txt")
            .writeText(out.toString())
    }
}
