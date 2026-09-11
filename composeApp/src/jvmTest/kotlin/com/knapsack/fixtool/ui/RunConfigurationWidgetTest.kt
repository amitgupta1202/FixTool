package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The toolbar's run configuration widget: a name, a menu that changes the name, and a ▶ that runs it.**
 *
 * What it replaced was a `Run ▾` whose every row started something on the click, so the window could never
 * say what would run next and a mis-click on a list of five sets ran one. The claims worth a UI test are
 * therefore about the split: that a row **selects** and starts nothing, that the chip then names what was
 * selected, and that the ▶ beside it is the only thing that runs, stops or refuses.
 *
 * The rows keep the counts and the lane sentences they had in the old menu, because those answer the
 * question a name cannot: how big is this thing, and what will it connect. Those assertions moved here with
 * the rows rather than being rewritten.
 */
class RunConfigurationWidgetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-run-config", "").apply {
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
        composeTestRule.setContent { ToolbarRunConfiguration(viewModel) }
        composeTestRule.onNodeWithTag("run-config").performClick()
        composeTestRule.waitForIdle()
    }

    /** A load set that exists only so the chip has something to name, and so the dropdown exists at all. */
    private fun saveLoadSet(name: String, label: String, phases: Int = 1) {
        viewModel.saveLoadSet(
            LoadSet(
                name = name,
                label = label,
                phases =
                    (1..phases).map {
                        LoadPhaseSpec("Phase $it", "Quote request", "LoadGen", shape = LoadShape.Burst(10))
                    },
            ),
        )
    }

    /**
     * **A fresh workspace has no configuration, so the chip is the door to making one.**
     *
     * `Load run…` and not an empty name: the one thing a reader can do here is open the dialog, and a chip
     * that named nothing would be a control asking to be configured before it can be used. There is no
     * dropdown behind it, because a menu in front of a dialog on a workspace with one choice in it is a
     * step that exists only to be clicked through. And the ▶ says what it is short of rather than sitting
     * dark with no reason on it.
     */
    @Test
    fun `an empty workspace names the dialog rather than a configuration`() {
        composeTestRule.setContent { ToolbarRunConfiguration(viewModel) }

        composeTestRule.onNodeWithTag("run-config").assertIsDisplayed().assertTextContains("Load run…")
        composeTestRule.onNodeWithTag("run-config").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("run-config-menu").assertDoesNotExist()

        composeTestRule
            .onNodeWithTag("run-button")
            .assertIsNotEnabled()
            .assertContentDescriptionContains("Nothing is saved to run", substring = true)
    }

    /**
     * **A row aims the ▶, it does not press it.** The whole point of the widget: a menu of rows that each
     * ran something meant the window said nothing about what would run next, and a click on the wrong row
     * cost a run rather than a correction.
     */
    @Test
    fun `a row selects its configuration and starts nothing`() {
        saveLoadSet("rfq-round-trip", "RFQ round trip", phases = 2)
        viewModel.scenarioService.save(
            Scenario(id = "sc-1", name = "book-a-trade", steps = listOf(ScenarioStep.Send("35=D|", session = "s"))),
        )
        viewModel.runSetStore.save(SavedRunSet("nightly", listOf(SavedRunEntry("book-a-trade", repeat = 3))))
        viewModel.refreshScenarios()

        openTheMenu()
        composeTestRule.onNodeWithTag("run-config-nightly").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("run-config-menu").assertDoesNotExist()
        assertEquals(null, viewModel.activeLoadRun.value, "a row must not start a load run")
        assertTrue(viewModel.runningSetIds.value.isEmpty(), "and it must not claim the sessions either")
        composeTestRule.onNodeWithTag("run-config").assertTextContains("nightly", substring = true)
    }

    /**
     * **The chip says which kind of set it is holding**, because "nightly" alone does not say whether ▶ is
     * about to issue four thousand messages or run three scenarios, and those are very different clicks.
     */
    @Test
    fun `the chip names the configuration and its kind`() {
        saveLoadSet("rfq-round-trip", "RFQ round trip", phases = 2)
        viewModel.scenarioService.save(
            Scenario(id = "sc-1", name = "book-a-trade", steps = listOf(ScenarioStep.Send("35=D|", session = "s"))),
        )
        viewModel.runSetStore.save(SavedRunSet("nightly", listOf(SavedRunEntry("book-a-trade", repeat = 3))))
        viewModel.refreshScenarios()
        viewModel.selectRunConfiguration("LOADSET:rfq-round-trip")

        composeTestRule.setContent { ToolbarRunConfiguration(viewModel) }

        composeTestRule.onNodeWithTag("run-config").assertTextContains("RFQ round trip", substring = true)
        composeTestRule.onNodeWithTag("run-config").assertTextContains("· load set", substring = true)

        viewModel.selectRunConfiguration("RUNSET:nightly")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("run-config").assertTextContains("nightly", substring = true)
        composeTestRule.onNodeWithTag("run-config").assertTextContains("· scenario set", substring = true)
    }

    /**
     * **One row per saved configuration, each carrying its own size**, so aiming the ▶ at one is not a leap
     * of faith. A load set counts phases and a scenario set counts scenarios, because those are the two
     * different things the word "set" means in this app.
     */
    @Test
    fun `a saved load set and a saved run set each get a row with its own count`() {
        saveLoadSet("rfq-round-trip", "RFQ round trip", phases = 2)
        saveLoadSet("one-phase", "One phase", phases = 1)
        viewModel.scenarioService.save(
            Scenario(id = "sc-1", name = "book-a-trade", steps = listOf(ScenarioStep.Send("35=D|", session = "s"))),
        )
        viewModel.runSetStore.save(SavedRunSet("nightly", listOf(SavedRunEntry("book-a-trade", repeat = 3))))
        viewModel.refreshScenarios()

        openTheMenu()

        composeTestRule
            .onNodeWithTag("run-config-rfq-round-trip")
            .assertIsDisplayed()
            .assertTextContains("Load set ▸  RFQ round trip  2 phases")
        // Singular, because "1 phases" is the kind of detail that makes a reader distrust the rest.
        composeTestRule
            .onNodeWithTag("run-config-one-phase")
            .assertIsDisplayed()
            .assertTextContains("Load set ▸  One phase  1 phase")
        composeTestRule
            .onNodeWithTag("run-config-nightly")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertTextContains("Run set ▸  nightly  3 scenarios")
        composeTestRule.onNodeWithTag("rail-load-sets").assertTextContains("Load sets…  2 saved")
    }

    /**
     * **The row says which sessions the set runs on, and which of them running it will connect.**
     *
     * That is the one thing a set's name has never said. A set is two or three phases against two profiles,
     * and a week after writing it the only ways to find out which were to open the file or to run it, read
     * the refusal, connect that one, and run it again for the next.
     *
     * It stays **enabled** with its lanes down, because a run brings up what the set names, and a name no
     * saved profile answers to is listed as needed all the same, but never as something the run will
     * connect: the set's own refusal is what names that, and it opens the editor on the set.
     */
    @Test
    fun `a load set names the sessions it runs on, and which of them a run will connect`() {
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
            .onNodeWithTag("run-config-lanes-down")
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
        // line was two of its twelve dp, so the height of the line is the thing to ask about, and asking
        // it here fails on that shape and on the one before it.
        composeTestRule
            .onNodeWithText("on LoadGen, RFQVenue · Run connects LoadGen", useUnmergedTree = true)
            .assertHeightIsAtLeast(12.dp)
        // And the row keeps room to spare, which the line's own height cannot ask for. The shape that
        // reached a screen gave the two lines exactly the space they measure and no more, and an exact
        // fit clips at a real density, where the same figures round up rather than down, and the line came
        // out with its bottom half cut off while every logical measurement said it fitted.
        composeTestRule.onNodeWithTag("run-config-lanes-down").assertHeightIsAtLeast(40.dp)
    }

    /**
     * Recent is a titled group rather than "Recent ▸" repeated on every row.
     *
     * The fixture saves a load set as well as writing the record, because the dropdown only exists once
     * something is saved: with nothing saved the chip is a door straight to the load run dialog, and there
     * is no menu for Recent to be a group in.
     */
    @Test
    fun `a finished run puts Recent in the menu under its own title`() {
        saveLoadSet("rfq-round-trip", "RFQ round trip")
        val report = LoadFixtures.burstReport(unmatched = 0)
        viewModel.loadRecordStore.write(report)

        openTheMenu()

        composeTestRule.onNodeWithTag("toolbar-recent-group").assertIsDisplayed()
        composeTestRule
            .onNodeWithTag("rail-recent-${report.id}")
            .assertIsDisplayed()
            .assertTextContains("✓ ⚡ ${report.label}", substring = true)
    }

    /**
     * With nothing run there is no group to title, and an empty heading would be furniture. A saved set is
     * in the fixture for the same reason as above: without one there would be no dropdown to look in.
     */
    @Test
    fun `Recent is absent until something has run`() {
        saveLoadSet("rfq-round-trip", "RFQ round trip")

        openTheMenu()

        composeTestRule.onNodeWithTag("toolbar-recent-group").assertDoesNotExist()
    }

    /** The ▶ runs what the chip names, which is the one click in the widget that starts anything. */
    @Test
    fun `the run button runs the selection`() {
        var ran = false
        composeTestRule.setContent {
            RunConfigurationWidget(
                selected = RunConfiguration(RunConfiguration.Kind.LOAD_SET, "rfq-round-trip"),
                displayName = "RFQ round trip",
                running = false,
                refusal = null,
                fold = RunWidgetFold.FULL,
                onRun = { ran = true },
                onStop = {},
                onEmptyClick = {},
                emptyRefusal = null,
                menu = {},
            )
        }

        composeTestRule.onNodeWithTag("run-button").assertHasClickAction().performClick()
        composeTestRule.waitForIdle()

        assertTrue(ran, "the ▶ is what starts a run now that no row does")
    }

    /**
     * **And the widget wires that click to the same call the old row made.** A set whose template nothing
     * answers to is refused by its own file, which is a problem the editor can fix, so the ▶ opens the
     * editor *on that set* rather than half-running it or leaving a notification to be read and dismissed.
     */
    @Test
    fun `the run button opens the editor on a set that its own file refuses`() {
        viewModel.saveConnectionProfile(
            FixConnectionProfile(
                id = "lg",
                name = "LoadGen",
                config = FixConnectionConfig(senderCompID = "LG", targetCompID = "V"),
            ),
        )
        viewModel.saveLoadSet(
            LoadSet(
                name = "zulu-broken",
                label = "Zulu broken",
                phases = listOf(LoadPhaseSpec("Phase 1", "Also nothing", "LoadGen", shape = LoadShape.Burst(10))),
            ),
        )

        composeTestRule.setContent { ToolbarRunConfiguration(viewModel) }
        composeTestRule.onNodeWithTag("run-button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-sets-dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load-set-name").assertTextContains("Zulu broken")
    }

    /** While the selection is running the same button is the stop button, and says whose run it stops. */
    @Test
    fun `the run button becomes a stop button while its own configuration runs`() {
        var stopped = false
        composeTestRule.setContent {
            RunConfigurationWidget(
                selected = RunConfiguration(RunConfiguration.Kind.RUN_SET, "nightly"),
                displayName = "nightly",
                running = true,
                refusal = null,
                fold = RunWidgetFold.FULL,
                onRun = {},
                onStop = { stopped = true },
                onEmptyClick = {},
                emptyRefusal = null,
                menu = {},
            )
        }

        composeTestRule.onNodeWithTag("run-button").assertContentDescriptionContains("Stop nightly")
        composeTestRule.onNodeWithTag("run-button").performClick()
        composeTestRule.waitForIdle()

        assertTrue(stopped, "the ■ stops the run the chip names")
    }

    /**
     * **A refused ▶ carries its reason.** A dark button with nothing on it is the state this whole widget
     * exists to avoid: the reader is left to guess whether the app is busy, broken, or simply not pointed
     * at anything.
     */
    @Test
    fun `a refusal disables the run button and is its reason`() {
        composeTestRule.setContent {
            RunConfigurationWidget(
                selected = RunConfiguration(RunConfiguration.Kind.LOAD_SET, "rfq-round-trip"),
                displayName = "RFQ round trip",
                running = false,
                refusal = "Another run is in progress. Wait for it, or stop it first.",
                fold = RunWidgetFold.FULL,
                onRun = {},
                onStop = {},
                onEmptyClick = {},
                emptyRefusal = null,
                menu = {},
            )
        }

        composeTestRule
            .onNodeWithTag("run-button")
            .assertIsNotEnabled()
            .assertContentDescriptionContains("Another run is in progress", substring = true)
        composeTestRule.onNodeWithTag("run-button").assertHasNoClickAction()
    }
}
