package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.ui.ScenarioDoc
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.Message
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The top toolbar's Capture button — and the rail's "Capture from sessions…", which now shares its path —
 * go **straight to the editor**. [FixMessageViewModel.captureAllSessionsToEditor] persists the whole flow
 * across every session and opens it as an editable [ScenarioDoc.Editor], never the read-only capture review
 * ([ScenarioDoc.Capture]). That "curation is editing, no review screen in between" contract is the whole
 * point of the change, so it is pinned here rather than left to a manual click-through.
 *
 * The store is isolated by `testSettingsDir`, so the persisted scenario lands under a temp dir and never
 * touches the shared `~/.fixtool` state.
 */
class CaptureToEditorTest {
    private lateinit var testDir: File
    private lateinit var viewModel: FixMessageViewModel

    @Before
    fun setup() {
        testDir = File(System.getProperty("java.io.tmpdir"), "fixtool-capture-${System.nanoTime()}").apply { mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    /** A business message with wire bytes capture can read (its `wireRaw`, SOH-delimited). */
    private fun business(raw: String, dir: FixMessage.Direction, second: Int): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.of(2026, 6, 30, 10, 0, second),
            direction = dir,
            rawMessage = raw,
            quickfixMessage = Message(),
            wireRaw = raw.replace('|', ''),
        )

    @Test
    fun `capture opens an editor directly, not the read-only review`() {
        val session = viewModel.createSessionForTest("VerifySession")
        session.addMessage(
            business("8=FIX.4.4|35=D|11=ORD-1|55=EUR/USD|54=1|38=1000000|40=1|60=20260630-10:00:00|", FixMessage.Direction.OUTGOING, 1),
        )
        session.addMessage(
            business("8=FIX.4.4|35=8|11=ORD-1|17=E-1|150=0|39=0|55=EUR/USD|", FixMessage.Direction.INCOMING, 2),
        )
        // addMessage only enqueues; the flow the scan reads is updated on flush.
        session.flushMessageQueue()

        viewModel.captureAllSessionsToEditor()

        val docs = viewModel.openDocuments.value
        assertTrue(docs.any { it is ScenarioDoc.Editor }, "capture should open an editable editor document")
        assertFalse(docs.any { it.id == ScenarioDoc.CAPTURE_ID }, "capture must NOT open the read-only review screen")
    }

    @Test
    fun `capture with no business messages opens nothing`() {
        viewModel.createSessionForTest("EmptySession")

        viewModel.captureAllSessionsToEditor()

        assertTrue(viewModel.openDocuments.value.isEmpty(), "with nothing to capture, no document should open")
    }
}
