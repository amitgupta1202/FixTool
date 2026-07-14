package com.knapsack.fixtool.ui.diff

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.RawMessageView
import com.knapsack.fixtool.service.compare.ReferenceMessage
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **Authoring is the same surface, and this is what it inherits.**
 *
 * `ExpectationBuilder` — a separate row model (`ExpectationDrafts`), a separate preview loop, and the only one
 * of the two editors with no undo — is deleted. Every behaviour its tests pinned has to exist here first, in
 * the one surface that remains, against a **golden** in the reference slot instead of a failure. The list, and
 * where each one went:
 *
 * - *"a captured expectation previews green against its own golden"* — the live dots the builder was built on.
 * - *"a relaxed matcher is preserved, not re-seeded"* (`ScenarioEditTest`).
 * - *"an unticked tag reappears unticked instead of vanishing — no one-way door"* (`ScenarioEditTest`). The
 *   checkbox is gone; the row it protected is not. A golden tag the expectation does not assert is a row on the
 *   right with `«` in the gutter, which is the same door, opening the same way, and now visible on both sides.
 * - *"reopening a captured scenario shows the values it was captured from"*, pipe and all (`CaptureReopenTest`).
 * - *"verify generalizes flags the over-specified field"* (`ExpectationBuilderTest`) — which is a reference
 *   swap now, and the sentence it answers in is the whole of V5.
 */
class DiffAuthoringTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private companion object {
        /** Generous, and deliberately so: this is a floor under a cliff, not a benchmark to be tuned. */
        const val REJUDGE_CEILING_MS = 25.0
        const val REJUDGE_SAMPLES = 20
    }

    /**
     * The venue's own bytes, SOH-delimited, **with a pipe inside a value**. A golden is stored as the wire, and
     * a reader that establishes its delimiter by looking for `|` first shreds this one into fields the venue
     * never sent — which is how a captured scenario came to reopen to a wall of blank rows.
     */
    private val goldenWire =
        listOf("8=FIX.4.4", "35=8", "11=ORD-1", "31=1.2345", "39=8", "58=Rejected|insufficient margin", "10=000")
            .joinToString("", postfix = "")

    private val captured =
        Expectation(
            fields =
                listOf(
                    FieldExpectation(11, Matcher.Reference("\${id0}")),
                    // The author relaxed LastPx from numeric to presence, and that must survive a reopen.
                    FieldExpectation(31, Matcher.Presence),
                    FieldExpectation(39, Matcher.Exact("8")),
                    FieldExpectation(58, Matcher.Exact("Rejected|insufficient margin")),
                ),
            messageType = "8",
            mode = MatchMode.OPEN,
            golden = goldenWire,
        )

    private fun sessionOn(expectation: Expectation, golden: String = goldenWire) =
        ReconcileSession(
            original = expectation,
            initialReference = ReferenceMessage.golden(RawMessageView(golden)),
            dictionary = dictionary,
        )

    /** The property every live green dot in the old builder was built on, and the seeder's own contract. */
    @Test
    fun `a captured expectation opens green against its own golden`() {
        val model = sessionOn(captured).model

        assertEquals(0, model.verdict.attention, "nothing to do: it was captured from this very message")
        assertFalse(model.verdict.needsAttention)
        assertTrue(model.verdict.headline.startsWith("✓"), model.verdict.headline)
        // The reference row is the third state — not a pass, and not a failure. It resolves on a run.
        assertEquals(1, model.verdict.unknown, "the \${id0} echo cannot be judged outside a run, and says so")
    }

    /** The relaxed matcher is the author's decision. Reopening the step must not quietly re-seed it. */
    @Test
    fun `a relaxed matcher survives the reopen, and is judged as what it is`() {
        val model = sessionOn(captured).model

        val lastPx = model.lines.single { it.row.tag == 31 && !it.leftIsGap }
        assertEquals(Matcher.Presence, lastPx.row.matcher, "presence, not the numeric the seeder would choose")
        assertTrue(lastPx.row.passed, "and it passes: the tag is there")
        assertEquals("1.2345", lastPx.right?.value, "with the captured value beside it, where the author can see it")
    }

    /**
     * **No one-way door.** The builder showed a golden tag the expectation does not assert as an *unticked*
     * row, so that un-asserting a field was never irreversible. The checkbox is gone; the door is not. The tag
     * is a row on the right with `«` in the gutter — the same decision, in the same place as every other
     * decision, and now with the value visible on both sides of it.
     */
    @Test
    fun `a golden tag the expectation does not assert is still a row, and still one click from asserted`() {
        val without31 = captured.copy(fields = captured.fields.filterNot { it.tag == 31 })
        val session = sessionOn(without31)

        val line = session.model.lines.single { it.row.tag == 31 }
        assertTrue(line.leftIsGap, "the expectation does not assert it, so the left side is a gap")
        assertEquals("1.2345", line.right?.value, "and the golden's value is right there")
        val assertIt = line.offers.single { it.kind == OfferKind.ASSERT_IT }

        assertIs<EditResult.Applied>(session.apply(assertIt.op), "one click puts it back")
        assertTrue(
            session.draft.fields.any { it.tag == 31 },
            "un-asserting a field was never meant to be irreversible, and it still is not",
        )
    }

    /**
     * The golden is the **venue's bytes**, and it is read as such. Read it as the `|`-substituted display
     * string and `58=Rejected|insufficient margin` becomes `58=Rejected` plus a phantom field the venue never
     * sent — and the row the author is looking at is not the row that will be judged on the next run.
     */
    @Test
    fun `a golden with a pipe inside a value is read as the wire, not as the display string`() {
        val model = sessionOn(captured).model

        val text = model.lines.single { it.row.tag == 58 }
        assertEquals("Rejected|insufficient margin", text.right?.value, "the whole value, pipe and all")
        assertTrue(text.row.passed, "and the row that asserts it passes")
        assertTrue(model.lines.none { it.row.tag == 0 }, "no phantom field was invented by splitting on the pipe")
    }

    /**
     * **"Verify generalizes" is a reference swap** — the same rows, re-judged against a different live message
     * of the same shape. An `exact` timestamp passes against its own capture and fails against any other
     * message, which is the definition of over-specified.
     *
     * And the sentence must say so. Against this run's failure a red row means *the venue did something new*;
     * against a second instance it means *the assertion is too tight*, which is the author's doing and the
     * author's to loosen. Inheriting the failure language here would send them hunting a venue bug that does
     * not exist — so the verdict answers in the words the builder always answered in.
     */
    @Test
    fun `swapping to a second instance flags the over-specified row, and says it is over-specified`() {
        val overSpecified =
            Expectation(
                fields =
                    listOf(
                        FieldExpectation(39, Matcher.Exact("8")),
                        // Pinned to the exact instant it was captured at: it can never pass again.
                        FieldExpectation(60, Matcher.Exact("20260714-09:35:44")),
                    ),
                messageType = "8",
                mode = MatchMode.OPEN,
                golden = "8=FIX.4.435=839=860=20260714-09:35:4410=000",
            )
        val session = sessionOn(overSpecified, overSpecified.golden!!)

        assertEquals(0, session.model.verdict.attention, "it passes against the message it was captured from")
        assertTrue(
            session.model.verdict.headline
                .startsWith("✓"),
        )

        // A second, genuinely different message of the same shape — a later fill, one second later.
        session.swapReference(
            ReferenceMessage.live(
                view = RawMessageView("8=FIX.4.435=839=860=20260714-09:35:4510=000"),
                provenance = ReferenceMessage.Provenance.SECOND_INSTANCE,
                label = "second instance",
                arrivedAt = Instant.parse("2026-07-14T09:35:45Z"),
            ),
        )

        val verdict = session.model.verdict
        assertEquals(1, verdict.attention, "the pinned timestamp cannot pass against any other message")
        assertEquals(
            "⚠ 1 row is over-specified — it only passes against the message it was captured from",
            verdict.headlineAgainst(ReferenceMessage.Provenance.SECOND_INSTANCE),
            "a red row here is the author's assertion being too tight, NOT the venue regressing",
        )
        assertTrue(
            verdict.headline.contains("need"),
            "and against a failure the very same count still reads as a failure: ${verdict.headline}",
        )
    }

    /**
     * **The re-judge budget** — deferred from 1.2 to 1.3, and dropped by 1.3. This is where it lands, because
     * this is the phase where the surface it protects goes in front of a user.
     *
     * Every keystroke in a matcher's value field re-runs the whole model: the alignment, the rows, the overlay,
     * and `reorder` — which enumerates every contiguous block of the expectation and scans each one across the
     * wire. Nothing about that *fails* as it gets slower. It would merely become sluggish, one commit at a
     * time, for a reason nobody would ever go looking for. So: a 40-row expectation against a 60-field message,
     * rebuilt from scratch, under a ceiling.
     */
    @Test
    fun `a forty-row expectation re-judges against a sixty-field message under a fixed ceiling`() {
        val rows = (1..40).map { FieldExpectation(1000 + it, Matcher.Exact("v$it")) }
        val draft = Expectation(rows, messageType = "8", mode = MatchMode.OPEN)
        val wire =
            (1..60).joinToString("", postfix = "") { "${1000 + it}=v$it" }
        val session =
            ReconcileSession(
                original = draft,
                initialReference = ReferenceMessage.golden(RawMessageView("8=FIX.4.435=8$wire")),
                dictionary = dictionary,
            )

        // Warm the JIT, then measure a rebuild — the memo is invalidated by every edit, so this is what an
        // author pays per keystroke.
        repeat(20) { session.model }
        val started = System.nanoTime()
        repeat(REJUDGE_SAMPLES) {
            session.swapReference(session.reference.copy(label = "run $it"))
            session.model
        }
        val perRebuild = (System.nanoTime() - started) / REJUDGE_SAMPLES / 1_000_000.0

        assertTrue(
            perRebuild < REJUDGE_CEILING_MS,
            "a re-judge costs ${"%.1f".format(perRebuild)}ms — over the ${REJUDGE_CEILING_MS}ms ceiling, and " +
                "an author types faster than that",
        )
    }

    /** And when it does generalize, it says that too — the other half of the sentence the builder answered in. */
    @Test
    fun `an expectation that holds against a second instance is told that it generalizes`() {
        val portable =
            Expectation(
                fields = listOf(FieldExpectation(39, Matcher.Exact("8"))),
                messageType = "8",
                mode = MatchMode.OPEN,
                golden = "8=FIX.4.435=839=810=000",
            )
        val session = sessionOn(portable, portable.golden!!)
        session.swapReference(
            ReferenceMessage.live(
                view = RawMessageView("8=FIX.4.435=839=860=20260714-09:35:4510=000"),
                provenance = ReferenceMessage.Provenance.SECOND_INSTANCE,
                label = "second instance",
                arrivedAt = Instant.parse("2026-07-14T09:35:45Z"),
            ),
        )

        assertEquals(
            "✓ generalizes — every assertion holds against a different message of the same shape",
            session.model.verdict.headlineAgainst(ReferenceMessage.Provenance.SECOND_INSTANCE),
        )
    }
}
