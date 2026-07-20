package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals

/**
 * Excluding a send field from the editor: the eye toggles one row in and out of the message, and the
 * row itself — tag, value, and the value's text — is never touched.
 *
 * The point of the feature is the experiment an author runs repeatedly ("does the venue still accept
 * this without 18?"), so what matters is that the round trip is a toggle and not a retype: exclude,
 * save, include again, and the scenario is byte-identical to where it started.
 */
class SendFieldExclusionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val raw = "35=D|11=ORD-1|55=EUR/USD|18=A|38=1000|"

    private fun render(initial: Scenario, onSave: (Scenario) -> Unit) {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(1200.dp, 620.dp).background(AppTheme.Colors.background).padding(10.dp)) {
                ScenarioEditor(
                    initial = initial,
                    dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4),
                    sessionOptions = listOf("DEMO"),
                    onSave = onSave,
                    selectedStep = 0,
                    split = 0.28f,
                )
            }
        }
    }

    private fun scenarioOf(raw: String) =
        Scenario(id = "sc-exclude", name = "exclude", steps = listOf(ScenarioStep.Send(raw, "DEMO")))

    private fun sentRawOf(scenario: Scenario?) =
        (scenario?.steps?.single() as ScenarioStep.Send).raw

    @Test
    fun `the eye excludes a field without deleting it, and puts it back unchanged`() {
        var saved: Scenario? = null
        render(scenarioOf(raw)) { saved = it }

        // Tag 18 (ExecInst) is the 4th row — exclude it.
        composeTestRule.onNodeWithTag("send-exclude-3").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("editor-save").performClick()
        composeTestRule.waitForIdle()

        // Marked, not removed: the row keeps its tag and its value, and every other field is untouched.
        assertEquals("35=D|11=ORD-1|55=EUR/USD|#18=A|38=1000|", sentRawOf(saved))

        // And the toggle is a toggle: putting it back restores the original byte for byte.
        composeTestRule.onNodeWithTag("send-exclude-3").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("editor-save").performClick()
        composeTestRule.waitForIdle()

        assertEquals(raw, sentRawOf(saved))
    }

    /**
     * An already-excluded field opens as an editable row rather than as nothing. This is the failure the
     * editor's parse → re-join normalization would otherwise cause on *open*, before any edit and with
     * the dirty flag none the wiser — the author's parked field silently gone.
     */
    @Test
    fun `an excluded field loaded from disk is still there, and still editable`() {
        var saved: Scenario? = null
        render(scenarioOf("35=D|11=ORD-1|55=EUR/USD|#18=A|38=1000|")) { saved = it }

        composeTestRule.onNodeWithTag("send-value-3").assertExists()
        // The excluded row as an author sees it on open: present, dimmed, still editable.
        ImageIO.write(
            composeTestRule.onRoot().captureToImage().toAwtImage(),
            "png",
            File("build/scenario-screenshots").absoluteFile.apply { mkdirs() }.resolve("send_field_excluded.png"),
        )
        composeTestRule.onNodeWithTag("editor-save").performClick()
        composeTestRule.waitForIdle()

        assertEquals("35=D|11=ORD-1|55=EUR/USD|#18=A|38=1000|", sentRawOf(saved))
    }

    /**
     * Insert-below is the affordance that makes a captured message editable. `+ field` appends, and on a
     * message with a repeating group appending puts the new field in the LAST entry — so without a
     * positional insert there is no way to add a field to the first party of a two-party block. The
     * position is not cosmetic: repeating-group entries are built by walking these rows in order
     * (FixMessageHelper.toQuickFixMessageManual), so where a row sits decides which entry it joins.
     */
    @Test
    fun `insert-below puts a field inside the first group entry, where append cannot reach`() {
        var saved: Scenario? = null
        // Two parties: rows 1-3 are entry one, rows 4-6 are entry two.
        render(scenarioOf("35=D|453=2|448=FIRMA|447=D|452=1|448=FIRMB|447=D|452=2|")) { saved = it }

        ImageIO.write(
            composeTestRule.onRoot().captureToImage().toAwtImage(),
            "png",
            File("build/scenario-screenshots").absoluteFile.apply { mkdirs() }.resolve("send_field_rowtools.png"),
        )
        // Insert directly below 452=1 — the last row of the FIRST entry.
        composeTestRule.onNodeWithTag("send-insert-4").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("editor-save").performClick()
        composeTestRule.waitForIdle()

        assertEquals("35=D|453=2|448=FIRMA|447=D|452=1|0=|448=FIRMB|447=D|452=2|", sentRawOf(saved))
    }

    @Test
    fun `the arrows move a row, and the ends are not draggable past themselves`() {
        var saved: Scenario? = null
        render(scenarioOf(raw)) { saved = it }

        // 55=EUR/USD (row 2) up one, swapping it above 11=ORD-1.
        composeTestRule.onNodeWithTag("send-up-2").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("editor-save").performClick()
        composeTestRule.waitForIdle()
        assertEquals("35=D|55=EUR/USD|11=ORD-1|18=A|38=1000|", sentRawOf(saved))

        // The first row cannot go up and the last cannot go down — clicking is inert, not an exception.
        composeTestRule.onNodeWithTag("send-up-0").performClick()
        composeTestRule.onNodeWithTag("send-down-4").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("editor-save").performClick()
        composeTestRule.waitForIdle()
        assertEquals("35=D|55=EUR/USD|11=ORD-1|18=A|38=1000|", sentRawOf(saved))
    }

    /** Moving a row must not launder its excluded state — the flag travels with the field. */
    @Test
    fun `an excluded field keeps its exclusion when moved`() {
        var saved: Scenario? = null
        render(scenarioOf("35=D|11=ORD-1|#18=A|38=1000|")) { saved = it }

        composeTestRule.onNodeWithTag("send-up-2").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("editor-save").performClick()
        composeTestRule.waitForIdle()

        assertEquals("35=D|#18=A|11=ORD-1|38=1000|", sentRawOf(saved))
    }
}
