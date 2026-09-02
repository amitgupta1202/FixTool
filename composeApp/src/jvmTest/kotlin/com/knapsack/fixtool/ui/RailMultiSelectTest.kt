package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * **Picking more than one scenario, and being able to see that you have.**
 *
 * `RailSelectionTest` owns which rows a gesture reaches; this owns whether a person can perform it. The
 * three claims a UI test is the only honest place for:
 *
 *  - an idle rail is unchanged — one master tick, and not a single standing checkbox;
 *  - once anything is picked, **every** row shows its box, because hunting for the next one by hovering is
 *    what made picking four scenarios not worth doing;
 *  - a pick is announced with its count, which is what lets it outlive the run it starts.
 */
class RailMultiSelectTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-rail-multi-select", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        listOf("alpha", "beta", "gamma").forEach { viewModel.scenarioService.save(scenario(it)) }
        viewModel.refreshScenarios()
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private fun railOnScreen() {
        composeTestRule.setContent { ScenariosRail(viewModel, modifier = Modifier.fillMaxSize()) }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `an idle rail carries one selection control, and no standing checkboxes`() {
        railOnScreen()

        composeTestRule.onNodeWithTag("rail-select-all").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rail-selection-bar").assertDoesNotExist()
        // The rail's hover rule, intact: ninety-six idle boxes are exactly what it exists to prevent.
        composeTestRule.onNodeWithTag("pick-sc-alpha").assertDoesNotExist()
    }

    @Test
    fun `the master tick takes the whole list, and says how much of it that was`() {
        railOnScreen()

        composeTestRule.onNodeWithTag("rail-select-all").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rail-selection-bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rail-selection-count").assertTextEquals("☑ 3 of 3 selected")
        // Every box now stands, on every row, with the pointer nowhere near them — the point of the change.
        listOf("alpha", "beta", "gamma").forEach { composeTestRule.onNodeWithTag("pick-sc-$it").assertIsDisplayed() }
    }

    /** "Select all" has to mean all of what is on screen, or a filter is a lie about what you just picked. */
    @Test
    fun `select-all reaches what the filter is drawing, and nothing behind it`() {
        railOnScreen()

        composeTestRule.onNodeWithTag("rail-filter").performTextReplacement("beta")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("rail-select-all").performClick()
        composeTestRule.waitForIdle()

        // One picked, out of the three that exist — the denominator is what says a filter is hiding the rest.
        composeTestRule.onNodeWithTag("rail-selection-count").assertTextEquals("☑ 1 of 3 selected")
    }

    @Test
    fun `hover still reveals the way in, and one click is enough to start`() {
        railOnScreen()

        composeTestRule.onNodeWithTag("scenario-row-sc-beta").performMouseInput { moveTo(center) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pick-sc-beta").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rail-selection-count").assertTextEquals("☑ 1 of 3 selected")
        // And with one picked, the other rows' boxes are up too, though nothing is hovering them.
        composeTestRule.onNodeWithTag("pick-sc-gamma").assertIsDisplayed()
    }

    @Test
    fun `clearing puts the rail back exactly as it was`() {
        railOnScreen()
        composeTestRule.onNodeWithTag("rail-select-all").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rail-selection-clear").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rail-selection-bar").assertDoesNotExist()
        // A row away from the pointer: the bar leaving shifts the list up under the cursor, so whichever row
        // lands beneath it is hovered and shows its box — which is the rail's ordinary hover rule, restored.
        composeTestRule.onNodeWithTag("pick-sc-gamma").assertDoesNotExist()
    }

    /**
     * The reversal this feature is built on: a pick used to be spent the moment the set was made, because a
     * tick standing in a hover-only column was invisible. The bar makes it visible, so it stays — and
     * re-running the ones you just fixed costs one click, not the whole gesture again.
     */
    @Test
    fun `a pick outlives the run it starts`() {
        railOnScreen()
        composeTestRule.onNodeWithTag("rail-select-all").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rail-selection-run").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rail-selection-bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rail-selection-count").assertTextEquals("☑ 3 of 3 selected")
    }

    /**
     * Promoting a pick to ★ is how "these, now" becomes a lasting list. It stars what is not starred rather
     * than toggling each: toggling would have starred half the pick and unstarred the other half.
     */
    @Test
    fun `the bar promotes a whole pick to favourites in one click`() {
        viewModel.toggleScenarioFavourite("sc-beta")
        railOnScreen()
        composeTestRule.onNodeWithTag("rail-select-all").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rail-selection-star").performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            setOf("sc-alpha", "sc-beta", "sc-gamma"),
            viewModel.scenarioViewState.value.favouriteIds,
            "the one already starred stays starred",
        )
    }

    private fun scenario(name: String) =
        Scenario(id = "sc-$name", name = name, steps = listOf(ScenarioStep.Send("35=D|", session = "s")))
}
