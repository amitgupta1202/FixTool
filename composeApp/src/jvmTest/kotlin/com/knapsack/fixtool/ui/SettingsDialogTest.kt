package com.knapsack.fixtool.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixDictionary
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The dialog's own behaviour: what it refuses, and what it asks before doing.
 *
 * The page contents themselves are covered by `SettingsDraftTest` and `SettingsPagesTest`, which can
 * reach every field without a composition. What only this test can show is the wiring — that an
 * out-of-range number actually stops the Save button, and that the way out of a dirty form asks first.
 */
class SettingsDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var saved: AppSettings? = null
    private var dismissed = false

    private fun showDialog(settings: AppSettings = AppSettings.default()) {
        saved = null
        dismissed = false
        composeTestRule.setContent {
            SettingsDialog(
                currentSettings = settings,
                dictionary = FixDictionary.createDefault(),
                onSave = { saved = it },
                onDismiss = { dismissed = true },
            )
        }
    }

    @Test
    fun `every page is offered, and protocol is the one already open`() {
        showDialog()

        listOf("protocol", "tags", "appearance", "sessions", "storage", "latency", "developer").forEach { id ->
            composeTestRule.onNodeWithTag("settings-page-$id").assertExists()
        }
        composeTestRule.onNodeWithText("What the wire means here — owned by the venue, not by this machine.").assertExists()
    }

    @Test
    fun `choosing a page in the sidebar shows it`() {
        showDialog()

        composeTestRule.onNodeWithTag("settings-page-developer").performClick()

        composeTestRule.onNodeWithText("A loopback door for driving FixTool from a script or an agent.").assertExists()
    }

    /** Every page composes — with an empty dictionary, which is the state the tag pickers must survive. */
    @Test
    fun `each page renders its own contents`() {
        showDialog()

        mapOf(
            "tags" to "Grid view columns",
            "appearance" to "Message colours",
            "sessions" to "Message buffer",
            "storage" to "Connection profiles",
            "developer" to "Automation control",
        ).forEach { (id, heading) ->
            composeTestRule.onNodeWithTag("settings-page-$id").performClick()
            composeTestRule.onNodeWithText(heading).assertExists()
        }

        // Latency hides everything behind its switch, so both states are worth walking.
        composeTestRule.onNodeWithTag("settings-page-latency").performClick()
        composeTestRule.onNodeWithText("Correlation tags").assertDoesNotExist()
        composeTestRule.onNodeWithText("Enable latency tracking").performClick()
        composeTestRule.onNodeWithText("Correlation tags").assertExists()
    }

    @Test
    fun `searching narrows the sidebar to the pages that answer`() {
        showDialog()

        composeTestRule.onNodeWithTag("settings-search").performTextReplacement("MCP")

        composeTestRule.onNodeWithTag("settings-page-developer").assertExists()
        composeTestRule.onNodeWithTag("settings-page-appearance").assertDoesNotExist()
        composeTestRule.onNodeWithTag("settings-page-latency").assertDoesNotExist()
    }

    @Test
    fun `a search with no answer says so instead of showing a blank page`() {
        showDialog()

        composeTestRule.onNodeWithTag("settings-search").performTextReplacement("kerning")

        composeTestRule.onNodeWithText("No settings match \"kerning\".").assertExists()
    }

    @Test
    fun `an out-of-range number is reported and Save refuses it`() {
        showDialog()
        composeTestRule.onNodeWithTag("settings-page-sessions").performClick()

        composeTestRule.onNodeWithTag("settings-number-SESSION_BUFFER").performTextReplacement("50")

        composeTestRule.onNodeWithTag("settings-status").assertTextContains("must be at least 100", substring = true)
        composeTestRule.onNodeWithTag("settings-save").performClick()
        assertNull(saved, "Save must not store a corrected value behind the user's back")
        assertFalse(dismissed, "and must not close over an unsaved problem")
    }

    @Test
    fun `correcting the number lets Save through, and it stores what was asked for`() {
        showDialog()
        composeTestRule.onNodeWithTag("settings-page-sessions").performClick()
        composeTestRule.onNodeWithTag("settings-number-SESSION_BUFFER").performTextReplacement("50")
        composeTestRule.onNodeWithTag("settings-number-SESSION_BUFFER").performTextReplacement("2500")

        composeTestRule.onNodeWithTag("settings-save").performClick()

        assertEquals(2500, saved?.sessionBufferSize)
    }

    @Test
    fun `leaving with an edit asks first`() {
        showDialog()
        composeTestRule.onNodeWithTag("settings-page-sessions").performClick()
        composeTestRule.onNodeWithTag("settings-number-SESSION_BUFFER").performTextReplacement("2500")

        composeTestRule.onNodeWithTag("settings-cancel").performClick()

        composeTestRule.onNodeWithText("Discard changes?").assertExists()
        assertFalse(dismissed, "the edits are still there until the question is answered")

        composeTestRule.onNodeWithTag("settings-discard").performClick()
        assert(dismissed)
        assertNull(saved)
    }

    @Test
    fun `keeping editing returns to the form with the edit intact`() {
        showDialog()
        composeTestRule.onNodeWithTag("settings-page-sessions").performClick()
        composeTestRule.onNodeWithTag("settings-number-SESSION_BUFFER").performTextReplacement("2500")
        composeTestRule.onNodeWithTag("settings-cancel").performClick()

        composeTestRule.onNodeWithText("Keep editing").performClick()

        composeTestRule.onNodeWithText("Discard changes?").assertDoesNotExist()
        composeTestRule.onNodeWithTag("settings-number-SESSION_BUFFER").assertTextContains("2500")
        assertFalse(dismissed)
    }

    @Test
    fun `leaving an untouched form does not ask`() {
        showDialog()

        composeTestRule.onNodeWithTag("settings-cancel").performClick()

        assert(dismissed)
        composeTestRule.onNodeWithText("Discard changes?").assertDoesNotExist()
    }

    @Test
    fun `the sidebar marks the page an edit was made on`() {
        showDialog()
        composeTestRule.onNodeWithTag("settings-status").assertTextEquals("")

        composeTestRule.onNodeWithTag("settings-page-sessions").performClick()
        composeTestRule.onNodeWithTag("settings-number-SESSION_BUFFER").performTextReplacement("2500")

        composeTestRule.onNodeWithTag("settings-status").assertTextEquals("Unsaved changes")
    }
}
