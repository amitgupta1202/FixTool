package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionary
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals

/**
 * Visual + behavioural regression test for the context-preserving search in [MessageDetailPanel].
 *
 * It renders the real panel with a multi-party NewOrderSingle and a real FIX44 dictionary (so field
 * names and enum translations resolve), drives a live search on the nested tag PartyRole, and for
 * each match-context mode:
 *  - asserts the rendered field rows match the mode's contract (e.g. Bare hides PartyID, Identity
 *    reveals it once per matching party), and
 *  - writes a PNG snapshot to [outDir] for eyeballing (best-effort — a capture failure never fails
 *    the test, so it is CI-safe on headless agents).
 *
 * "PartyID" is used as the discriminator because its field name only renders in the parsed fields
 * list, never in the raw-message section (which uses tag numbers), and is an exact-match string
 * distinct from "PartyIDSource".
 */
class MatchContextScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val dictionary: FixDictionary = MatchContextFixtures.dictionary()
    private val outDir = File("build/match-context-screenshots").absoluteFile

    @Composable
    private fun panel() {
        Box(modifier = Modifier.size(760.dp, 760.dp).background(Color(0xFF12141A))) {
            MessageDetailPanel(
                message = MatchContextFixtures.multiPartyOrder(),
                dictionary = dictionary,
                onClose = {},
            )
        }
    }

    private fun search(query: String) {
        composeTestRule.onNode(hasSetTextAction()).performTextInput(query)
        composeTestRule.waitForIdle()
    }

    private fun selectMode(label: String) {
        composeTestRule.onNodeWithText(label).performClick()
        composeTestRule.waitForIdle()
    }

    private fun countFieldRows(name: String): Int =
        composeTestRule.onAllNodesWithText(name).fetchSemanticsNodes().size

    /** Best-effort PNG snapshot; never fails the test if image capture is unavailable. */
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            val image = composeTestRule.onRoot().captureToImage().toAwtImage()
            val file = File(outDir, name)
            ImageIO.write(image, "png", file)
            println("[MatchContextScreenshotTest] wrote ${file.absolutePath}")
        } catch (e: Exception) {
            println("[MatchContextScreenshotTest] snapshot '$name' skipped: ${e.message}")
        }
    }

    @Test
    fun `bare mode shows matched rows only, no party identity`() {
        composeTestRule.setContent { panel() }
        // Reproduce the legacy "before": expand the group, then search the nested tag.
        composeTestRule.onNodeWithContentDescription("Expand All").performClick()
        composeTestRule.waitForIdle()
        search("PartyRole")
        selectMode("Bare")

        // Bare = legacy behaviour: only the matched PartyRole rows; no PartyID context.
        assertEquals(0, countFieldRows("PartyID"), "Bare mode should not reveal any PartyID rows")
        snapshot("matchcontext_bare.png")
    }

    @Test
    fun `identity mode reveals the identity field for each matching party`() {
        composeTestRule.setContent { panel() }
        search("PartyRole")
        selectMode("Identity")

        // Each of the 3 matching party instances shows its PartyID identity; PartyIDSource stays hidden.
        assertEquals(3, countFieldRows("PartyID"), "Identity mode should reveal PartyID for each of the 3 parties")
        assertEquals(0, countFieldRows("PartyIDSource"), "Identity mode should not reveal non-matching, non-identity fields")
        snapshot("matchcontext_identity.png")
    }

    @Test
    fun `full mode reveals the whole matching entry`() {
        composeTestRule.setContent { panel() }
        search("PartyRole")
        selectMode("Full")

        // Full = the entire matching instance: PartyID + PartyIDSource + PartyRole for all 3 parties.
        assertEquals(3, countFieldRows("PartyID"), "Full mode should reveal PartyID for each party")
        assertEquals(3, countFieldRows("PartyIDSource"), "Full mode should reveal PartyIDSource for each party")
        snapshot("matchcontext_full.png")
    }
}
