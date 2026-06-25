package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.MatchContextMode
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * UI integration tests for the context-preserving detail search, driving the real
 * [MessageDetailPanel] with a multi-party order ([MatchContextFixtures]) and a real FIX44 dictionary.
 *
 * Covers two things the snapshot test does not: switching the context mode at runtime via the
 * on-screen toggle, and the hoisted external-state path — the same wiring the app host uses so the
 * control surface (/detail, fixtool_detail_search) can drive the search and the panel reflects it.
 */
class MatchContextDetailSearchUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val dictionary = MatchContextFixtures.dictionary()

    // Hoisted state, simulating the ViewModel the control surface writes to.
    private var externalQuery by mutableStateOf("")
    private var externalMode by mutableStateOf(MatchContextMode.IDENTITY)

    @Composable
    private fun internalPanel() {
        Box(modifier = Modifier.size(760.dp, 760.dp).background(Color(0xFF12141A))) {
            MessageDetailPanel(
                message = MatchContextFixtures.multiPartyOrder(),
                dictionary = dictionary,
                onClose = {},
            )
        }
    }

    @Composable
    private fun externallyDrivenPanel() {
        Box(modifier = Modifier.size(760.dp, 760.dp).background(Color(0xFF12141A))) {
            MessageDetailPanel(
                message = MatchContextFixtures.multiPartyOrder(),
                dictionary = dictionary,
                onClose = {},
                externalSearchQuery = externalQuery,
                onSearchQueryChange = { externalQuery = it },
                externalMatchContextMode = externalMode,
                onMatchContextModeChange = { externalMode = it },
            )
        }
    }

    private fun type(query: String) {
        composeTestRule.onNode(hasSetTextAction()).performTextInput(query)
        composeTestRule.waitForIdle()
    }

    private fun clickMode(label: String) {
        composeTestRule.onNodeWithText(label).performClick()
        composeTestRule.waitForIdle()
    }

    private fun count(name: String): Int =
        composeTestRule.onAllNodesWithText(name).fetchSemanticsNodes().size

    @Test
    fun `toggling context mode reveals or hides party context at runtime`() {
        composeTestRule.setContent { internalPanel() }
        type("PartyRole")

        // Default Identity: each of the 3 parties shows its PartyID identity.
        assertEquals(3, count("PartyID"))
        assertEquals(0, count("PartyIDSource"))

        clickMode("Bare")
        assertEquals(0, count("PartyID"), "Bare hides the identity field")

        clickMode("Full")
        assertEquals(3, count("PartyID"), "Full shows every party's PartyID")
        assertEquals(3, count("PartyIDSource"), "Full shows the whole entry")

        clickMode("Identity")
        assertEquals(3, count("PartyID"))
        assertEquals(0, count("PartyIDSource"), "Identity drops non-matching, non-identity fields again")
    }

    @Test
    fun `identity search keeps the repeating-group ancestor header visible`() {
        composeTestRule.setContent { internalPanel() }
        type("PartyRole")
        // The NoPartyIDs group header stays as the ancestor anchor so the match keeps its path.
        composeTestRule.onNodeWithText("NoPartyIDs", substring = true).assertExists()
    }

    @Test
    fun `detail panel reflects externally driven search state (control-surface path)`() {
        composeTestRule.setContent { externallyDrivenPanel() }

        // Nothing typed — simulate the control surface / ViewModel setting the search externally.
        composeTestRule.runOnIdle { externalQuery = "PartyRole" }
        composeTestRule.waitForIdle()
        assertEquals(3, count("PartyID"), "externally set query should drive the identity view")

        // Switch the mode externally too.
        composeTestRule.runOnIdle { externalMode = MatchContextMode.FULL }
        composeTestRule.waitForIdle()
        assertEquals(3, count("PartyIDSource"), "externally set mode should switch to Full")

        // Clearing the query externally returns to the full (unfiltered) message — a top-level
        // non-party field becomes visible again.
        composeTestRule.runOnIdle { externalQuery = "" }
        composeTestRule.waitForIdle()
        assertEquals(1, count("ClOrdID"), "clearing the search restores top-level fields")
    }
}
