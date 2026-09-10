package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.load.LoadPhaseSpec
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.service.SavedRunEntry
import com.knapsack.fixtool.service.SavedRunSet
import com.knapsack.fixtool.service.load.LoadFixtures
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * **The toolbar's Run ▾, which is the run-configurations chooser.**
 *
 * Every row here is a *named* door: it runs a saved configuration by name, or opens the chooser that
 * edits them. The claim worth a UI test is that each one is reachable and says what is behind it — a set
 * that can only be started over HTTP is a feature the person looking at the traffic cannot use, and a
 * disabled row with no count on it cannot tell "nothing is saved" from "this does not exist".
 *
 * The doors that *do* something (a refused set opening the editor, a Recent row opening its record) are
 * covered where the thing behind them is: `LoadRunRailTest` and `RunSetRailTest`.
 */
class ToolbarRunMenuTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-toolbar-run", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private fun openTheMenu() {
        composeTestRule.setContent { ToolbarRunControls(viewModel) }
        composeTestRule.onNodeWithTag("toolbar-run-menu").performClick()
        composeTestRule.waitForIdle()
    }

    /**
     * A fresh workspace: nothing saved, nothing logged on. Every row stays **visible and disabled with
     * its count showing**, because withholding it answers only one of the two questions an author has.
     *
     * "nothing up yet" and not "0", because a load run now dials what it is pointed at: what holds this
     * row is having no saved profile to point it at, and a bare zero reads as a number of lanes.
     */
    @Test
    fun `an empty workspace still shows every door, disabled and counted`() {
        openTheMenu()

        composeTestRule
            .onNodeWithTag("rail-run-load")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertTextContains("Load run…  nothing up yet")
        composeTestRule.onNodeWithTag("rail-run-set-none").assertIsDisplayed().assertIsNotEnabled()
        composeTestRule
            .onNodeWithTag("rail-load-sets")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertTextContains("Load sets…  0 saved")
    }

    /**
     * **One row per saved configuration, each carrying its own size**, so "run this" is not a leap of
     * faith. A load set counts phases and a scenario set counts scenarios, because those are the two
     * different things the word "set" means in this app.
     */
    @Test
    fun `a saved load set and a saved run set each get a runnable row with its own count`() {
        viewModel.saveLoadSet(
            LoadSet(
                name = "rfq-round-trip",
                label = "RFQ round trip",
                phases =
                    listOf(
                        LoadPhaseSpec("Ask", "Quote request", "LoadGen", shape = LoadShape.Burst(10)),
                        LoadPhaseSpec("Hit", "Hit the quote", "LoadGen", shape = LoadShape.Burst(10)),
                    ),
            ),
        )
        viewModel.saveLoadSet(
            LoadSet(
                name = "one-phase",
                label = "One phase",
                phases = listOf(LoadPhaseSpec("Only", "Quote request", "LoadGen", shape = LoadShape.Burst(10))),
            ),
        )
        viewModel.scenarioService.save(Scenario(id = "sc-1", name = "book-a-trade", steps = listOf(ScenarioStep.Send("35=D|", session = "s"))))
        viewModel.runSetStore.save(SavedRunSet("nightly", listOf(SavedRunEntry("book-a-trade", repeat = 3))))
        viewModel.refreshScenarios()

        openTheMenu()

        composeTestRule
            .onNodeWithTag("rail-run-load-set-rfq-round-trip")
            .assertIsDisplayed()
            .assertTextContains("Load set ▸  RFQ round trip  2 phases")
        // Singular, because "1 phases" is the kind of detail that makes a reader distrust the rest.
        composeTestRule
            .onNodeWithTag("rail-run-load-set-one-phase")
            .assertIsDisplayed()
            .assertTextContains("Load set ▸  One phase  1 phase")
        composeTestRule
            .onNodeWithTag("rail-run-set-nightly")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertTextContains("Run set ▸  nightly  3 scenarios")
        composeTestRule.onNodeWithTag("rail-load-sets").assertTextContains("Load sets…  2 saved")
    }

    /**
     * **The row says which sessions the set runs on, and which of them pressing it will connect.**
     *
     * That is the one thing a set's name has never said. A set is two or three phases against two
     * profiles, and a week after writing it the only ways to find out which were to open the file or to
     * press Run, read the refusal, connect that one, and press Run again for the next.
     *
     * It stays **enabled** with its lanes down, because Run brings up what the set names — and a name no
     * saved profile answers to is listed as needed all the same, but never as something Run will connect:
     * the set's own refusal is what names that, and it opens the editor on the set.
     */
    @Test
    fun `a load set names the sessions it runs on, and which of them Run will connect`() {
        viewModel.saveConnectionProfile(
            FixConnectionProfile(id = "lg", name = "LoadGen", config = FixConnectionConfig(senderCompID = "LG", targetCompID = "V")),
        )
        viewModel.saveLoadSet(
            LoadSet(
                name = "lanes-down",
                label = "Lanes down",
                phases =
                    listOf(
                        LoadPhaseSpec("Phase 1", "Nothing", "LoadGen", listen = listOf("RFQVenue"), shape = LoadShape.Burst(10)),
                    ),
            ),
        )

        openTheMenu()

        composeTestRule
            .onNodeWithTag("rail-run-load-set-lanes-down")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertTextContains("on LoadGen, RFQVenue · Run connects LoadGen")
        composeTestRule.onNodeWithTag("rail-load-sets").assertIsEnabled()

        // **And the second line is drawn at its full height, not squeezed into what the row had left.**
        //
        // A fixed row height cut it through the middle and let the row below draw over what was left, and
        // no assertion about *bounds* can see that: a Text under a fixed-height parent has its own size
        // clamped to the space remaining, so the node reports the squeezed height as its whole self and
        // sits obediently inside its row. Measured against the shape that shipped as far as a screen, the
        // line was two of its twelve dp — so the height of the line is the thing to ask about, and asking
        // it here fails on that shape and on the one before it.
        composeTestRule
            .onNodeWithText("on LoadGen, RFQVenue · Run connects LoadGen", useUnmergedTree = true)
            .assertHeightIsAtLeast(12.dp)
        // And the row keeps room to spare, which the line's own height cannot ask for. The shape that
        // reached a screen gave the two lines exactly the space they measure and no more, and an exact
        // fit clips at a real density, where the same figures round up rather than down — the line came
        // out with its bottom half cut off while every logical measurement said it fitted.
        composeTestRule.onNodeWithTag("rail-run-load-set-lanes-down").assertHeightIsAtLeast(40.dp)
    }

    /** Recent is a titled group rather than "Recent ▸" repeated on every row. */
    @Test
    fun `a finished run puts Recent in the menu under its own title`() {
        val report = LoadFixtures.burstReport(unmatched = 0)
        viewModel.loadRecordStore.write(report)

        openTheMenu()

        composeTestRule.onNodeWithTag("toolbar-recent-group").assertIsDisplayed()
        composeTestRule
            .onNodeWithTag("rail-recent-${report.id}")
            .assertIsDisplayed()
            .assertTextContains("✓ ⚡ ${report.label}", substring = true)
    }

    /** With nothing run there is no group to title, and an empty heading would be furniture. */
    @Test
    fun `Recent is absent until something has run`() {
        openTheMenu()

        composeTestRule.onNodeWithTag("toolbar-recent-group").assertDoesNotExist()
    }
}
