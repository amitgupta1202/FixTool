package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
        // A listen-only side, so a phase has something to also match on.
        viewModel.saveConnectionProfile(
            FixConnectionProfile(
                id = "dc",
                name = "DROPCOPY",
                config =
                    FixConnectionConfig(
                        senderCompID = "DC",
                        targetCompID = "VENUE",
                        host = "localhost",
                        port = "9",
                        resetOnLogon = true,
                    ),
            ),
        )
        val messages = SavedMessagesService(customPath = File(testDir, "saved_messages.json").absolutePath)
        messages.saveMessage(
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
        // Reads a name no seed can cover: an earlier phase has to have kept it.
        messages.saveMessage(
            "lg",
            SavedFixMessage(
                id = "passes",
                name = "Passes",
                userTags = setOf("lg"),
                fields =
                    listOf(
                        SavedFixField("35", "AJ"),
                        SavedFixField("117", "\${quoteId}"),
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
        composeTestRule.onNodeWithTag("load-seed-name-0").assertDoesNotExist()

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
        composeTestRule.onNodeWithTag("load-set-phase-row-2").assertDoesNotExist()
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

    /**
     * The bug this pins: `listen` is saved as profile **names** and the checkbox row speaks profile **ids**,
     * so seeding the editor from the spec unmapped left every box unticked and Done wrote back an empty
     * list. Opening a phase and pressing Done must be a no-op.
     */
    @Test
    fun `Done keeps the phase's listen profiles, which are saved by name and ticked by id`() {
        viewModel.saveLoadSet(
            roundTrip.copy(
                phases = roundTrip.phases.mapIndexed { i, p -> if (i == 0) p.copy(listen = listOf("DROPCOPY")) else p },
            ),
        )
        show()

        composeTestRule.onNodeWithTag("load-set-phase-edit-1").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("phase-done").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-set-save").performClick()
        composeTestRule.waitForIdle()

        val saved = assertNotNull(viewModel.loadSet("round-trip"))
        assertEquals(listOf("DROPCOPY"), saved.phases[0].listen, "Done dropped the phase's listeners")
    }

    /** "${quoteId} from phase 1", because "from the seed" sends its reader to the wrong band to change it. */
    @Test
    fun `a captured name is attributed to the phase that keeps it, not to the seed`() {
        viewModel.saveLoadSet(
            LoadSet(
                name = "captures",
                label = "Captures",
                seed = mapOf("run" to "b7f2"),
                phases =
                    listOf(
                        LoadPhaseSpec(
                            "Ask for a quote",
                            "Quotes",
                            "LOADGEN",
                            match = LoadMatch(131, 131, "S"),
                            shape = LoadShape.Burst(10),
                            capture = mapOf("quoteId" to 117),
                        ),
                        LoadPhaseSpec("Pass them", "Passes", "LOADGEN", match = LoadMatch(117, 117, "AI"), shape = LoadShape.Burst(10)),
                    ),
            ),
        )
        show()

        composeTestRule.onNodeWithTag("load-set-phase-edit-2").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("\${quoteId} from phase 1", substring = true).assertExists()
        composeTestRule.onNodeWithText("from the seed", substring = true).assertDoesNotExist()
    }

    /**
     * A `Where.SEED` refusal has no field on this screen, so it must not hold Done: Esc was the only way
     * out, and Esc discards the edit. The set band's phase row carries the sentence instead.
     */
    @Test
    fun `a phase reading a name nothing seeds can still be edited and Done`() {
        viewModel.saveLoadSet(roundTrip.copy(name = "unseeded", label = "Unseeded", seed = emptyMap()))
        show()

        composeTestRule.onNodeWithTag("load-set-phase-edit-1").performClick()
        composeTestRule.waitForIdle()

        // The footer says what the phase will do, not a refusal it cannot act on.
        composeTestRule.onNodeWithTag("phase-why").assertTextContains("settle", substring = true)
        composeTestRule.onNodeWithTag("phase-label").performTextClearance()
        composeTestRule.onNodeWithTag("phase-label").performTextInput("Ask again")
        composeTestRule.onNodeWithTag("phase-done").assertHasClickAction().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-set-phase-label-1").assertTextContains("Ask again")
        // And the fix is on the phase's own row, one click from the Seed field that clears it.
        composeTestRule.onNodeWithTag("load-set-phase-fixes-1").assertTextContains("1 fix", substring = true)
    }

    /** `${messageIndex}` restarts at 1 in every phase whatever its shape, so a rate phase needs the field too. */
    @Test
    fun `a rate phase can say where its message index starts`() {
        viewModel.saveLoadSet(
            roundTrip.copy(
                phases = listOf(roundTrip.phases[0].copy(shape = LoadShape.Rate(500, 10_000), indexFrom = 2_001)),
            ),
        )
        show()

        composeTestRule.onNodeWithTag("load-set-phase-edit-1").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-rate").assertTextContains("500")
        composeTestRule.onNodeWithTag("phase-index-from").assertTextContains("2001")
    }

    /** It copies the saved name, so a renamed or brand-new set must be on disk before the name goes out. */
    @Test
    fun `Copy as fixtool load --set saves first, so the name it copies answers to a file`() {
        show()

        composeTestRule.onNodeWithTag("load-set-name").performTextClearance()
        composeTestRule.onNodeWithTag("load-set-name").performTextInput("Nightly soak")
        composeTestRule.onNodeWithTag("load-set-copy-cli").performClick()
        composeTestRule.waitForIdle()

        assertNotNull(viewModel.loadSet("nightly-soak"), "the copied name has to be one a file answers to")
    }

    /** The refused set opens on **itself**, not on whichever set the editor would have selected. */
    @Test
    fun `initialName opens the editor on the saved set that was refused`() {
        viewModel.saveLoadSet(roundTrip.copy(name = "aaa-first", label = "Aaa first"))
        viewModel.saveLoadSet(roundTrip.copy(name = "zulu-broken", label = "Zulu broken", seed = emptyMap()))

        composeTestRule.setContent {
            LoadSetsDialogContent(viewModel, onDismiss = {}, onRun = {}, initialName = "zulu-broken")
        }

        composeTestRule.onNodeWithTag("load-set-name").assertTextContains("Zulu broken")
        // Opened by name, so it is not a draft: nothing has been changed yet.
        composeTestRule.onNodeWithTag("load-set-why").assertTextContains("Phase 1", substring = true)
        composeTestRule.onNodeWithTag("load-set-phase-fixes-1").assertTextContains("1 fix", substring = true)
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
