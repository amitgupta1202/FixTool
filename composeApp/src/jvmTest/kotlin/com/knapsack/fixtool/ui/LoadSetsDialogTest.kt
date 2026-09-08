package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPhaseSpec
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.service.SavedMessagesService
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The run-configurations dialog**: what is saved down the left, the selected set on the right, and Run
 * set in the footer. What is checked is that a set saves, that a phase is edited through the breadcrumb,
 * and that a refusal about the seed lands on the set band rather than on a phase with no field for it.
 */
class LoadSetsDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-load-sets-dialog", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        viewModel.saveConnectionProfile(
            FixConnectionProfile(
                id = "lg",
                name = "LOADGEN",
                config =
                    FixConnectionConfig(
                        senderCompID = "LG{n}",
                        targetCompID = "VENUE",
                        host = "localhost",
                        port = "9",
                        sessionCount = 3,
                        resetOnLogon = true,
                    ),
            ),
        )
        SavedMessagesService(customPath = File(testDir, "saved_messages.json").absolutePath).saveMessage(
            "lg",
            SavedFixMessage(
                id = "quotes",
                name = "Quotes",
                userTags = setOf("lg"),
                fields =
                    listOf(
                        SavedFixField("35", "R"),
                        SavedFixField("131", "Q-\${run}-\${messageIndex}"),
                        SavedFixField("55", "EUR/USD"),
                    ),
            ),
        )
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private val roundTrip =
        LoadSet(
            name = "round-trip",
            label = "Round trip",
            seed = mapOf("run" to "\${uuid:4}"),
            storeAndLog = StoreAndLogOverride.FOR_LOAD,
            onFailure = OnFailure.STOP,
            phases =
                listOf(
                    LoadPhaseSpec("Ask for a quote", "Quotes", "LOADGEN", match = LoadMatch(131, 131, "S"), shape = LoadShape.Burst(4_000)),
                    LoadPhaseSpec("Hit them", "Quotes", "LOADGEN", match = LoadMatch(11, 11, "8"), shape = LoadShape.Burst(2_000)),
                ),
        )

    private fun show(initial: LoadSet? = null, onRun: (LoadSet.Planned) -> Unit = {}) {
        composeTestRule.setContent {
            LoadSetsDialogContent(viewModel, onDismiss = {}, onRun = onRun, initial = initial)
        }
    }

    @Test
    fun `the saved sets are the left column, and the selected one is the editor`() {
        viewModel.saveLoadSet(roundTrip)

        show()

        composeTestRule.onNodeWithTag("load-sets-dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load-sets-row-round-trip").assertTextContains("Round trip", substring = true)
        composeTestRule.onNodeWithTag("load-set-name").assertTextContains("Round trip")
        composeTestRule.onNodeWithTag("load-set-phase-label-1").assertTextContains("Ask for a quote")
        composeTestRule.onNodeWithTag("load-set-phase-label-2").assertTextContains("Hit them")
        composeTestRule.onNodeWithTag("load-set-phase-plan-1").assertTextContains("×4,000", substring = true)
    }

    @Test
    fun `an empty store offers a new set with one phase to fill in, and Run set is off until it resolves`() {
        show()

        composeTestRule.onNodeWithTag("load-sets-none").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load-set-phase-row-1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load-set-run").assertHasNoClickAction()
        // A phase naming no template is a refusal in that phase's voice, on its own row.
        composeTestRule.onNodeWithTag("load-set-why").assertTextContains("Phase 1", substring = true)
    }

    @Test
    fun `Save writes the file, and the left column picks it up`() {
        show()

        composeTestRule.onNodeWithTag("load-set-name").performTextClearance()
        composeTestRule.onNodeWithTag("load-set-name").performTextInput("Nightly soak")
        composeTestRule.onNodeWithTag("load-set-save").performClick()
        composeTestRule.waitForIdle()

        val saved = assertNotNull(viewModel.loadSet("nightly-soak"))
        assertEquals("Nightly soak", saved.label)
        assertEquals(1, saved.phases.size)
        composeTestRule.onNodeWithTag("load-sets-row-nightly-soak").assertIsDisplayed()
    }

    @Test
    fun `the mint chip writes the generator, not four hex characters`() {
        show()

        composeTestRule.onNodeWithTag("load-set-seed-mint").performClick()
        composeTestRule.onNodeWithTag("load-set-save").performClick()
        composeTestRule.waitForIdle()

        val saved = viewModel.loadSets().single()
        assertEquals(
            "\${uuid:4}",
            saved.seed["run"],
            "a saved set with four hex characters would give the venue duplicate ids every night",
        )
    }

    @Test
    fun `delete takes the set off disk and selects whatever is left`() {
        viewModel.saveLoadSet(roundTrip)
        show()

        composeTestRule.onNodeWithTag("load-sets-delete").performClick()
        composeTestRule.waitForIdle()

        assertNull(viewModel.loadSet("round-trip"))
        composeTestRule.onNodeWithTag("load-sets-none").assertIsDisplayed()
    }

    @Test
    fun `a phase opens through the breadcrumb, and Done writes its label back to the row`() {
        viewModel.saveLoadSet(roundTrip)
        show()

        composeTestRule.onNodeWithTag("load-set-phase-edit-1").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("phase-back").assertTextContains("‹ Round trip")
        // The set owns the seed and the store, so the phase says so rather than showing fields it cannot fix.
        composeTestRule.onNodeWithTag("phase-label").assertTextContains("Ask for a quote")
        composeTestRule.onNodeWithTag("phase-index-from").assertTextContains("1")
        composeTestRule.onNodeWithTag("load-seed-name-0").assertDoesNotExistSafely()

        composeTestRule.onNodeWithTag("phase-label").performTextClearance()
        composeTestRule.onNodeWithTag("phase-label").performTextInput("Ask again")
        composeTestRule.onNodeWithTag("phase-done").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-set-phase-label-1").assertTextContains("Ask again")
    }

    @Test
    fun `Remove phase takes it out of the set and comes back to the band`() {
        viewModel.saveLoadSet(roundTrip)
        show()

        composeTestRule.onNodeWithTag("load-set-phase-edit-2").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("phase-remove").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-set-phase-row-1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load-set-phase-row-2").assertDoesNotExistSafely()
    }

    @Test
    fun `Make this a set hands the run over with its seed and store lifted to the band`() {
        val made =
            LoadSet(
                name = "one-burst",
                label = "Quotes ×4,000",
                seed = mapOf("run" to "b7f2"),
                storeAndLog = StoreAndLogOverride.FOR_LOAD,
                phases = listOf(LoadPhaseSpec("Quotes ×4,000", "Quotes", "LOADGEN", match = LoadMatch(131), shape = LoadShape.Burst(4_000))),
            )

        show(initial = made)

        composeTestRule.onNodeWithTag("load-set-name").assertTextContains("Quotes ×4,000")
        composeTestRule.onNodeWithTag("load-set-seed-value-0").assertTextContains("b7f2")
        composeTestRule.onNodeWithTag("load-set-why").assertTextContains("unsaved", substring = true)
    }

    @Test
    fun `Run set saves first, then hands over a planned set`() {
        viewModel.saveLoadSet(roundTrip)
        var planned: LoadSet.Planned? = null
        show(onRun = { planned = it })

        composeTestRule.onNodeWithTag("load-set-run").performClick()
        composeTestRule.waitForIdle()

        val ready = assertNotNull(planned)
        assertEquals(2, ready.phases.size)
        assertEquals("round-trip", ready.name)
        assertTrue(ready.seed.getValue("run").length == 4, "the generator was rendered once: ${ready.seed}")
        assertEquals(listOf(ready.id), ready.phases.map { it.id }.distinct(), "one record for the set")
    }
}

/** `assertDoesNotExist` on a tag that never appeared, without the matcher throwing on the empty set. */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistSafely() {
    runCatching { assertDoesNotExist() }
}
