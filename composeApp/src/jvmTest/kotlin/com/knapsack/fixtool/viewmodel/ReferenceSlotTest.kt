package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.ScenarioVariable
import com.knapsack.fixtool.model.scenario.StepOrigin
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.withIds
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.service.compare.ReferenceOption
import com.knapsack.fixtool.service.compare.WirePaste
import com.knapsack.fixtool.ui.DiffWindowState
import com.knapsack.fixtool.ui.diff.EditOp
import com.knapsack.fixtool.ui.diff.ReconcileSession
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.Message
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The reference slot: what may be in it, who put it there, and what it costs the scenario.**
 *
 * The slot is what unifies four features that used to be separate — reconciling a failure, authoring against
 * the golden, "verify generalizes", and building an expectation against a real server's reply that was never
 * captured live. It is one mechanism, and these are its honesty rules.
 */
class ReferenceSlotTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-slot", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private val soh = "\u0001"

    private fun wire(vararg fields: String): String = fields.joinToString(soh, postfix = soh)

    /**
     * A **real** frame: BodyLength(9) and CheckSum(10) computed over the bytes, exactly as a venue computes
     * them. `WirePaste` refused the first version of these fixtures, which carried `10=000` — and it was right
     * to: a message whose own arithmetic disagrees with its bytes is a message nobody sent. The reader catching
     * the test's own dishonest fixture is the whole argument for the reader.
     */
    private fun frame(vararg body: String): String {
        val fields = body.joinToString(soh, postfix = soh)
        val head = "8=FIX.4.4${soh}9=${fields.length}$soh"
        val checksum = (head + fields).toByteArray(Charsets.ISO_8859_1).sumOf { it.toInt() and 0xFF } % 256
        return head + fields + "10=%03d".format(checksum) + soh
    }

    private val goldenWire = wire("8=FIX.4.4", "35=8", "11=ORD-1", "150=2", "58=filled|in full", "10=000")

    private fun scenario(golden: String? = goldenWire) =
        Scenario(
            id = "sc-1",
            name = "rfq flow",
            steps =
                listOf(
                    ScenarioStep.Send("35=D|11=X|"),
                    ScenarioStep.Expect(
                        expectation =
                            Expectation(
                                fields = listOf(FieldExpectation(150, Matcher.Exact("2"))),
                                messageType = "8",
                                mode = MatchMode.OPEN,
                                golden = golden,
                            ),
                    ),
                ),
        )

    private fun saved(golden: String? = goldenWire): Scenario {
        assertTrue(viewModel.scenarioService.save(scenario(golden)))
        return viewModel.scenarioService.load("sc-1")!!
    }

    private fun message(raw: String) =
        FixMessage(
            timestamp = LocalDateTime.of(2026, 7, 14, 9, 41, 2),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw.replace(soh[0], '|'),
            quickfixMessage = Message(),
            wireRaw = raw,
        )

    /** What the right-hand column shows for a tag — the reference's own value, as the reader sees it. */
    private fun valueAt(session: ReconcileSession, tag: Int): String? =
        session.model.lines
            .single { it.row.tag == tag }
            .right
            ?.value

    private fun draft(): Scenario = viewModel.scenarioDraft("sc-1")!!.draft

    private fun onlyWindow() = viewModel.openDiffWindows.value.single()

    private fun openDiff(onDisk: Scenario): DiffWindowState {
        val stepId = onDisk.withIds().steps[1].stepId
        viewModel.openScenarioEditor(onDisk)
        viewModel.openDiffWindow(onDisk, stepId)
        return viewModel.openDiffWindows.value.single()
    }

    // ---------------------------------------------------------------- the menu says what it cannot do (S11)

    /**
     * **A menu entry with nothing behind it is drawn, disabled, with the reason.** Hiding it teaches the author
     * that the feature was never built — which is what the withheld move taught them for three phases.
     */
    @Test
    fun `an option with nothing behind it is offered disabled, and says why`() {
        val doc = openDiff(saved())
        val options = viewModel.referenceOptions(doc)

        assertEquals(5, options.size, "all five are always drawn")
        val thisRun = options.single { it.kind == ReferenceOption.Kind.THIS_RUN }
        assertFalse(thisRun.enabled, "this step has not run, so there is nothing of this run's to bind")
        assertTrue(thisRun.detail.contains("has not run"), thisRun.detail)

        val golden = options.single { it.kind == ReferenceOption.Kind.GOLDEN }
        assertTrue(golden.enabled, "it was captured, so the golden is there")
        assertTrue(golden.selected, "and with no run, the golden is what authoring opened against")

        val second = options.single { it.kind == ReferenceOption.Kind.SECOND_INSTANCE }
        assertFalse(second.enabled, "no session is carrying a later ExecutionReport")
        assertTrue(second.detail.contains("no later live message"), second.detail)

        // Pick and paste are always available: they are ways of GETTING a message, not uses of one we have.
        assertTrue(options.single { it.kind == ReferenceOption.Kind.PICK }.enabled)
        assertTrue(options.single { it.kind == ReferenceOption.Kind.PASTE }.enabled)
    }

    @Test
    fun `a step that was never captured cannot offer a golden, and says so`() {
        val doc = openDiff(saved(golden = null))
        val golden = viewModel.referenceOptions(doc).single { it.kind == ReferenceOption.Kind.GOLDEN }

        assertFalse(golden.enabled)
        assertTrue(golden.detail.contains("never captured"), golden.detail)
    }

    // ---------------------------------------------------------------- the armed slot (S8)

    /**
     * **Cross-window arming (S8, F5·2).** The author leaves the diff to click a grid row, so the arming cannot
     * live in the diff: the diff is in its **own window** now, and the grid is in the main one. The armed flag
     * is the ViewModel's, the grid answers it, and the diff window raises itself back to the front once it is
     * bound (the epoch bump, F6) — because the author's eyes are about to go back to it.
     */
    @Test
    fun `arming the slot makes the next grid click the reference, and only the next one`() {
        val onDisk = saved()
        val doc = openDiff(onDisk)
        val epochBefore = onlyWindow().focusEpoch
        assertNull(viewModel.armedReferenceSlot.value)

        viewModel.selectReference(doc, ReferenceOption.Kind.PICK)
        assertEquals(doc.id, viewModel.armedReferenceSlot.value, "armed, and it says which diff window is waiting")

        viewModel.selectMessageFromGrid(message(wire("35=8", "11=ORD-9", "150=F")))

        val session = onlyWindow().session!!
        assertEquals(ReferenceMessage.Provenance.PICKED, session.reference.provenance)
        assertEquals(
            epochBefore + 1,
            onlyWindow().focusEpoch,
            "the diff window raises itself back to the front once the grid click has bound it (F6)",
        )
        assertEquals(
            "F",
            valueAt(session, 150),
            "the clicked message is what the rows are judged against now",
        )
        assertNull(viewModel.armedReferenceSlot.value, "and the slot disarms: one click means one thing")

        // The next grid click is an ordinary selection again.
        val other = message(wire("35=8", "150=8"))
        viewModel.selectMessageFromGrid(other)
        assertEquals("F", valueAt(session, 150), "still the picked one")
        assertEquals(other, viewModel.selectedMessage.value, "and the grid selected the row, as it always does")
    }

    /**
     * **Invariant 3: only `wireRaw` feeds a diff.** A message FixTool has no wire bytes for cannot be a
     * reference — the `|`-substituted display string is not what the venue sent. Refused *at the click*, in
     * words, rather than by a click that quietly does nothing.
     */
    @Test
    fun `a message with no wire bytes cannot be bound, and the refusal is said out loud`() {
        val doc = openDiff(saved())
        viewModel.selectReference(doc, ReferenceOption.Kind.PICK)

        val displayOnly =
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "35=8|150=2|",
                quickfixMessage = Message(),
                wireRaw = null,
            )
        viewModel.selectMessageFromGrid(displayOnly)

        val session = onlyWindow().session!!
        assertEquals(ReferenceMessage.Provenance.GOLDEN, session.reference.provenance, "the slot did not take it")
        val notification = viewModel.notifications.lastOrNull()
        assertNotNull(notification, "and a refused action says why — it does not merely fail to happen")
        assertTrue(notification.message.contains("wire bytes"), notification.message)
    }

    // ---------------------------------------------------------------- the paste, and what it costs (S3, S4)

    /**
     * **The whole of W2's first half.** The venue's own log line — SOH bytes, pipe inside `58` — binds, and
     * the rows re-judge against it without a single edit.
     */
    @Test
    fun `a pasted reference binds, re-judges, and never becomes the scenario's golden`() {
        val onDisk = saved()
        val doc = openDiff(onDisk)
        val pasted = frame("35=8", "11=ORD-1", "150=F", "58=filled|in full")

        val paste = WirePaste.read(pasted)
        assertTrue(paste.usable, paste.why ?: paste.lint)
        assertTrue(viewModel.bindPastedReference(doc, paste))

        val session = onlyWindow().session!!
        assertEquals(ReferenceMessage.Provenance.PASTED, session.reference.provenance)
        assertEquals("F", valueAt(session, 150), "re-judged, with no edit")
        assertEquals(1, session.model.verdict.attention, "and the row that no longer holds says so")

        // V4, and the reason S3 exists: a hand-doctored paste must NEVER become the scenario's canonical example.
        val step = draft().steps[1] as ScenarioStep.Expect
        assertEquals(goldenWire, step.expectation.golden, "the golden is still the capture, and only THIS_RUN moves it")
    }

    /**
     * **The badge is the sentence that explains the red.** Rows repaired against a paste describe the paste;
     * the golden still describes the capture (V4) — so the step opens red against its own canonical example,
     * for ever, and the `pasted` badge is the only thing on screen that says why. It has to survive the save.
     */
    @Test
    fun `provenance follows a repair made against a paste, all the way to disk and back`() {
        val onDisk = saved()
        val doc = openDiff(onDisk)
        val stepId = doc.stepId
        viewModel.bindPastedReference(doc, WirePaste.read(frame("35=8", "150=F")))
        val session = onlyWindow().session!!

        // Repair the row against the pasted bytes.
        session.apply(EditOp.acceptActual(0, 150, "F"))

        val drafted = draft().steps.single { it.stepId == stepId }
        assertEquals(StepOrigin.PASTED, drafted.origin, "the step knows what it was tightened against")

        assertTrue(viewModel.saveScenario("sc-1"))
        val reloaded = viewModel.scenarioService.load("sc-1")!!
        assertEquals(
            StepOrigin.PASTED,
            reloaded.steps
                .single { it.stepId == stepId }
                .origin,
            "and it still knows, after a save and a load — which is the whole promise",
        )
    }

    /** Undo the edit and the badge goes with it: nothing of the paste survives, so nothing claims it did. */
    @Test
    fun `undoing the repair takes the pasted badge with it`() {
        val doc = openDiff(saved())
        viewModel.bindPastedReference(doc, WirePaste.read(frame("35=8", "150=F")))
        val session = onlyWindow().session!!

        session.apply(EditOp.acceptActual(0, 150, "F"))
        assertEquals(StepOrigin.PASTED, draft().steps[1].origin)

        session.undo()

        assertEquals(
            StepOrigin.LIVE,
            draft().steps[1].origin,
            "the rows are back where they started, so nothing here came from a paste",
        )
    }

    /** A paste the bytes themselves disprove never reaches the slot. It cannot: the reading is wrong. */
    @Test
    fun `a refused paste cannot be bound at all`() {
        val doc = openDiff(saved())
        // The venue's own bytes, rendered with pipes — which is what its stdout prints, and what an engineer
        // copies out of a terminal. `58=filled|in full` cannot be read back through it, and the checksum says so.
        val refused = WirePaste.read(frame("35=8", "58=filled|in full").replace(soh, "|"))

        assertEquals(WirePaste.Verdict.REFUSED, refused.verdict)
        assertFalse(viewModel.bindPastedReference(doc, refused), "nothing is bound from a reading that is disproved")

        val session = onlyWindow().session!!
        assertEquals(ReferenceMessage.Provenance.GOLDEN, session.reference.provenance, "the slot is as it was")
    }

    // ---------------------------------------------------------------- swapping re-judges (the phase's own check)

    /** Golden green → this-run red, with no edit at all. That is the whole mechanism, in one assertion. */
    @Test
    fun `swapping the reference re-judges every row, and stages nothing`() {
        val onDisk = saved()
        val stepId = onDisk.withIds().steps[1].stepId
        val failing = message(wire("8=FIX.4.4", "35=8", "11=ORD-1", "150=F", "10=000"))
        viewModel.openScenarioEditor(onDisk)
        viewModel.noteScenarioRun(onDisk)
        viewModel.setAssertionResults(mapOf(failing to StepResult(1, "expect", "steps", false, stepId = stepId)))
        viewModel.openDiffWindow(onDisk, stepId, thisRunWire = failing.wireRaw)
        val doc = viewModel.openDiffWindows.value.single()
        val session = doc.session!!

        assertEquals(1, session.model.verdict.attention, "bound to the failure: 150 came back F where 2 was asserted")

        assertTrue(viewModel.selectReference(doc, ReferenceOption.Kind.GOLDEN))

        assertEquals(ReferenceMessage.Provenance.GOLDEN, session.reference.provenance)
        assertEquals(0, session.model.verdict.attention, "against its own capture it passes — and nothing was edited")
        assertEquals(0, session.staged, "a swap is not an edit: it stages nothing, dirties nothing (P8)")
        assertFalse(session.isDirty)
        assertFalse(session.canUndo, "and there is nothing to undo, because nothing was done")
    }

    // ---------------------------------------------------------------- the scope travels with THIS_RUN

    /**
     * **The run's variables ride the THIS_RUN reference — and only while the report stands for this
     * scenario.** Dismiss the report and a re-bound THIS_RUN carries no scope: the wire may still be
     * around (`thisRunWire` outlives the report), but the values that judged it are gone, and pretending
     * otherwise would resolve `${id0}` rows against a claim nobody can stand behind.
     */
    @Test
    fun `a THIS_RUN reference carries the run's scope, and loses it when the report is dismissed`() {
        val onDisk = saved()
        val stepId = onDisk.withIds().steps[1].stepId
        val failing = message(wire("8=FIX.4.4", "35=8", "11=ORD-1", "150=F", "10=000"))
        viewModel.openScenarioEditor(onDisk)
        viewModel.noteScenarioRun(onDisk)
        viewModel.setAssertionResults(mapOf(failing to StepResult(1, "expect", "steps", false, stepId = stepId)))
        viewModel.publishScenarioResult(
            ScenarioResult(
                onDisk.name,
                passed = false,
                steps = emptyList(),
                variables = listOf(ScenarioVariable("id0", "ORD-1", stepId)),
            ),
        )
        viewModel.openDiffWindow(onDisk, stepId, thisRunWire = failing.wireRaw)
        val window = onlyWindow()
        assertEquals(
            listOf(ScenarioVariable("id0", "ORD-1", stepId)),
            window.session!!.reference.variables,
            "the slot holds the run's own bytes, so it holds the run's own scope",
        )

        viewModel.dismissRunResult()
        assertTrue(viewModel.selectReference(window, ReferenceOption.Kind.THIS_RUN))
        assertEquals(
            emptyList(),
            onlyWindow().session!!.reference.variables,
            "no report, no scope — the wire alone does not entitle the rows to values",
        )
    }

    /** Someone else's run must not put values under this scenario's names. */
    @Test
    fun `another scenario's report lends this window no scope`() {
        val onDisk = saved()
        val window = openDiff(onDisk)
        // A different scenario runs, minting a name this scenario also uses.
        viewModel.noteScenarioRun(scenario().copy(id = "sc-2", name = "other"))
        viewModel.publishScenarioResult(
            ScenarioResult("other", passed = true, steps = emptyList(), variables = listOf(ScenarioVariable("id0", "THEIRS"))),
        )
        assertTrue(viewModel.selectReference(window.copy(thisRunWire = goldenWire), ReferenceOption.Kind.THIS_RUN))
        assertEquals(
            emptyList(),
            onlyWindow().session!!.reference.variables,
            "sc-2's scope on sc-1's bytes would judge \${id0} rows against a run they were never part of",
        )
    }

    /** A landing run re-binds the slot (V9/S2) — and its scope arrives in the same move as its bytes. */
    @Test
    fun `a landing run re-binds the slot with its scope aboard`() {
        val onDisk = saved()
        openDiff(onDisk) // bound to the golden: a slot the run owns, so the landing run may replace it
        val failing = message(wire("8=FIX.4.4", "35=8", "11=ORD-1", "150=F", "10=000"))
        viewModel.noteScenarioRun(onDisk)
        viewModel.setAssertionResults(
            mapOf(failing to StepResult(1, "expect", "steps", false, stepId = onlyWindow().stepId)),
        )
        viewModel.publishScenarioResult(
            ScenarioResult(onDisk.name, passed = false, steps = emptyList(), variables = listOf(ScenarioVariable("id0", "ORD-1"))),
        )
        val session = onlyWindow().session!!
        assertEquals(ReferenceMessage.Provenance.THIS_RUN, session.reference.provenance)
        assertEquals(
            "ORD-1",
            session.reference.variables.single().value,
            "wire and scope travel as one unit, or the judgments lie",
        )
    }
}
