package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.ScenarioCapture
import org.junit.Rule
import org.junit.Test
import quickfix.Message
import java.io.File
import java.time.LocalDateTime
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Capture-review curation: nothing saves blind. Unticking a row excludes it from the built scenario,
 * "Start here" trims everything before the selected row, and the correlation badges react to the
 * selection (excluding the send that mints an id removes its echo badge).
 */
class ScenarioCaptureReviewTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private val outDir = File("build/scenario-screenshots").absoluteFile

    // rawMessage is the '|'-substituted display string and wireRaw the SOH bytes — both always present,
    // exactly as the transport builds them. wireRaw is where the venue's field order lives, and a captured
    // expectation asserts that order, so a fixture without it is a capture that cannot happen.
    private fun msg(raw: String, dir: FixMessage.Direction, second: Int): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.of(2026, 7, 10, 9, 0, second),
            direction = dir,
            rawMessage = raw,
            quickfixMessage = Message(),
            wireRaw = raw.replace('|', '\u0001'),
        )

    // A stale first order (noise), then the real RFQ flow across two sessions.
    private val candidates = ScenarioCapture.candidates(
        listOf(
            ScenarioCapture.CapturedSession(
                "QUOTE",
                listOf(
                    msg("8=FIX.4.4|35=D|11=STALE-1|10=001|", FixMessage.Direction.OUTGOING, 0),
                    msg("8=FIX.4.4|35=R|131=QR-1|55=EUR/USD|10=002|", FixMessage.Direction.OUTGOING, 1),
                    msg("8=FIX.4.4|35=S|131=QR-1|117=Q-9|10=003|", FixMessage.Direction.INCOMING, 2),
                ),
            ),
            ScenarioCapture.CapturedSession(
                "TRADE",
                listOf(msg("8=FIX.4.4|35=8|131=QR-1|17=E-1|10=004|", FixMessage.Direction.INCOMING, 3)),
            ),
        ),
    )

    private fun render(onSave: (String, List<ScenarioCapture.Candidate>) -> Boolean) {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(1200.dp, 700.dp).background(AppTheme.Colors.background).padding(10.dp)) {
                StatefulCaptureReview(candidates = candidates, onSave = onSave)
            }
        }
    }

    /**
     * The harness **feeds `onStateChange` back**, the way the document host does — and that is not a detail.
     *
     * Capture review's curation lives in its document now (a tab, unlike the window it replaced, is disposed
     * the moment you look at something else). A harness that takes the state and drops the callback on the
     * floor renders a review whose checkboxes do nothing — and every assertion about *what got saved* would
     * then be an assertion about the default selection, passing over a surface that had stopped working.
     */
    @Composable
    private fun StatefulCaptureReview(
        candidates: List<ScenarioCapture.Candidate>,
        onSave: (String, List<ScenarioCapture.Candidate>) -> Boolean,
    ) {
        var state by remember { mutableStateOf(CaptureReviewState.of(candidates.size)) }
        ScenarioCaptureReview(
            candidates = candidates,
            dictionary = null,
            state = state,
            onStateChange = { state = it },
            onSave = onSave,
            onBack = {},
        )
    }

    @Test
    fun `unticking a row excludes it from the saved selection`() {
        var savedName: String? = null
        var savedSelection: List<ScenarioCapture.Candidate>? = null
        render { name, selection ->
            savedName = name
            savedSelection = selection
            true
        }
        composeTestRule.onNodeWithTag("capture-name").performTextInput("rfq")
        composeTestRule.onNodeWithTag("candidate-check-0").performClick() // untick the stale order
        composeTestRule.waitForIdle()
        snapshot("capture_review.png")
        composeTestRule.onNodeWithTag("capture-save").performClick()
        composeTestRule.waitForIdle()

        assertEquals("rfq", savedName)
        val selection = savedSelection
        assertTrue(selection != null, "save should fire")
        assertEquals(3, selection!!.size)
        assertTrue(selection.none { it.message.rawMessage.contains("STALE-1") }, "unticked noise is excluded")
    }

    @Test
    fun `the From range dropdown trims everything before the picked message`() {
        var savedSelection: List<ScenarioCapture.Candidate>? = null
        render { _, selection ->
            savedSelection = selection
            true
        }
        composeTestRule.onNodeWithTag("capture-from").performClick()
        composeTestRule.onNodeWithText("#2 ▶ R · QUOTE").performClick() // start the flow at the QuoteRequest
        composeTestRule.onNodeWithTag("capture-save").performClick()
        composeTestRule.waitForIdle()

        val selection = savedSelection
        assertTrue(selection != null)
        assertEquals(3, selection!!.size)
        assertEquals("R", selection.first().message.messageType, "flow starts at the picked row")
    }

    @Test
    fun `correlation badges follow the selection`() {
        render { _, _ -> true }
        // With the stale order excluded, QR-1 mints id0 (●id0 on the QuoteRequest) and its echoes on
        // the Quote and the cross-session ExecReport carry ○id0 — variables renumber with the selection.
        composeTestRule.onNodeWithTag("candidate-check-0").performClick()
        composeTestRule.waitForIdle()
        assertTrue(composeTestRule.onAllNodesWithText("○id0").fetchSemanticsNodes().size >= 2)
        // Exclude the QuoteRequest that mints id0 -> the echoes are no longer correlated; badges go.
        composeTestRule.onNodeWithTag("candidate-check-1").performClick()
        composeTestRule.waitForIdle()
        assertEquals(0, composeTestRule.onAllNodesWithText("○id0").fetchSemanticsNodes().size)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[ScenarioCaptureReviewTest] snapshot '$name' skipped: ${e.message}")
        }
    }

    /**
     * The preview shows every occurrence, each with the matcher that will actually check it.
     *
     * A venue sends the same firm twice under different roles — the case the sequence model exists for.
     * Capture seeds six assertions for the party block. This screen used to show three: it collapsed the
     * captured fields by tag with `distinctBy` (which keeps the FIRST occurrence) and the assertion rows by
     * tag with `associateBy` (which keeps the LAST). So the single PartyRole row it drew showed the
     * executing firm's captured value, `1`, beside the clearing firm's matcher, `= 4` — an assertion that
     * exists in no scenario, on the one screen whose whole job is to tell the author what they are about to
     * save.
     */
    @Test
    fun `the preview shows every occurrence of a repeated tag, each with its own matcher`() {
        val twoParties =
            ScenarioCapture.candidates(
                listOf(
                    ScenarioCapture.CapturedSession(
                        "TRADE",
                        listOf(
                            msg(
                                "8=FIX.4.4|35=8|39=2|453=2|448=FIRMA|447=D|452=1|448=FIRMA|447=D|452=4|10=004|",
                                FixMessage.Direction.INCOMING,
                                1,
                            ),
                        ),
                    ),
                ),
            )
        composeTestRule.setContent {
            Box(modifier = Modifier.size(1200.dp, 700.dp).background(AppTheme.Colors.background).padding(10.dp)) {
                StatefulCaptureReview(candidates = twoParties, onSave = { _, _ -> true })
            }
        }
        composeTestRule.onNodeWithTag("candidate-0").performClick()

        // The second party entry has three rows of its own — 448, 447, 452 — labelled #2. The old preview
        // could not show them at all: it kept one row per tag.
        composeTestRule.onAllNodesWithText("#2").assertCountEquals(3)

        // And each PartyRole row carries the matcher for *its own* entry. The old preview paired the first
        // entry's captured value with the second entry's matcher, so "= 1" was shown for no row at all.
        composeTestRule.onNodeWithText("= 1").assertExists()
        composeTestRule.onNodeWithText("= 4").assertExists()
    }
}
