package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.width
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.LOAD_DIALOG_HEIGHT
import com.knapsack.fixtool.model.LOAD_DIALOG_WIDTH
import com.knapsack.fixtool.model.LOAD_SETS_DIALOG_HEIGHT
import com.knapsack.fixtool.model.LOAD_SETS_DIALOG_WIDTH
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

    /**
     * **A seed row that can be added can be taken away.** The band had "+ add" and no way back, so a name
     * typed by mistake stayed in the set and in every phase's scope.
     */
    @Test
    fun `a set's seed row can be taken away again`() {
        viewModel.saveLoadSet(roundTrip)
        show()

        composeTestRule.onNodeWithTag("load-set-seed-add").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-set-seed-name-1").performTextInput("desk")
        composeTestRule.onNodeWithTag("load-set-seed-value-1").performTextInput("fx")
        composeTestRule.onNodeWithTag("load-set-save").performClick()
        composeTestRule.waitForIdle()
        assertEquals(setOf("run", "desk"), assertNotNull(viewModel.loadSet("round-trip")).seed.keys)

        composeTestRule.onNodeWithTag("load-set-seed-remove-1").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-set-seed-name-1").assertDoesNotExist()
        composeTestRule.onNodeWithTag("load-set-seed-name-0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load-set-save").performClick()
        composeTestRule.waitForIdle()
        assertEquals(setOf("run"), assertNotNull(viewModel.loadSet("round-trip")).seed.keys)
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

    /**
     * **The chrome, which is what "the two halves of one window disagree" meant.** The editor was built
     * before the run dialog was given sections, so it drew nine-point grey group heads with nothing between
     * them, and then a phase opened over it in the other idiom. Every section but the name row is separated
     * by a rule, and the name row has none because the top of the pane is its own top edge.
     */
    @Test
    fun `the set editor's sections are separated by rules, and the name row carries none`() {
        viewModel.saveLoadSet(roundTrip)

        show()

        val rules = listOf("set-rule-identity", "set-rule-phases", "set-rule-policy")
        rules.forEach { composeTestRule.onNodeWithTag(it).assertIsDisplayed() }
        val name = composeTestRule.onNodeWithText("Name").getUnclippedBoundsInRoot()
        rules.forEach { tag ->
            val rule = composeTestRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(rule.top > name.bottom, "$tag should sit below the name row, it was at ${rule.top}")
        }
        composeTestRule.onNodeWithText("the seed and the store every phase shares").assertExists()
        composeTestRule.onNodeWithText("one saved message per phase, in the order they run").assertExists()
        composeTestRule.onNodeWithText("what happens when a phase does not pass").assertExists()
    }

    /**
     * **One label column across both halves of the window.** The editor's own was 104.dp and left aligned,
     * so a label here and a label in the phase editor started and stopped in different places.
     */
    @Test
    fun `the set editor's label column is the run dialog's`() {
        viewModel.saveLoadSet(roundTrip)

        show()

        val name = composeTestRule.onNodeWithText("Name").getUnclippedBoundsInRoot()
        val fails = composeTestRule.onNodeWithText("If a phase fails").getUnclippedBoundsInRoot()

        assertEquals(LABEL_COLUMN, name.width, "the label column is 118.dp, it measured ${name.width}")
        assertEquals(LABEL_COLUMN, fails.width)
        assertEquals(name.right, fails.right, "right aligned, so every label ends on the same edge")
    }

    /** The footer says what Run set will do, or the first reason it cannot, and Run set is a real button. */
    @Test
    fun `the footer carries Run set and the sentence beside it`() {
        viewModel.saveLoadSet(roundTrip)

        show()

        composeTestRule.onNodeWithTag("load-set-run").assertHasClickAction()
        composeTestRule.onNodeWithText("Run set", substring = true).assertExists()
        // Nothing is wrong and nothing is unsaved, so it says what the set is rather than staying blank.
        composeTestRule.onNodeWithTag("load-set-why").assertTextContains("2 phases", substring = true)
        composeTestRule.onNodeWithTag("load-set-why").assertTextContains("memory store", substring = true)
    }

    /**
     * **820 by 700, and the user's last size after that**, in the view-state store beside the run dialog's
     * pair and never in AppSettings: a window size is not a setting anybody edits on a settings page.
     */
    @Test
    fun `the sets dialog opens at its own size until one is remembered`() {
        assertEquals(LOAD_SETS_DIALOG_WIDTH to LOAD_SETS_DIALOG_HEIGHT, viewModel.loadSetsDialogSize())

        viewModel.rememberLoadSetsDialogSize(960f, 840f)

        assertEquals(960f to 840f, viewModel.loadSetsDialogSize())
        assertEquals(
            LOAD_DIALOG_WIDTH to LOAD_DIALOG_HEIGHT,
            viewModel.loadDialogSize(),
            "the two dialogs keep their own size, so one drag does not resize the other",
        )
    }

    /** The chip parks the phase, the footer counts it, and Save puts the key in the file. */
    @Test
    fun `the mute chip parks a phase, the footer says so, and Save writes the key`() {
        viewModel.saveLoadSet(roundTrip)
        show()

        composeTestRule.onNodeWithTag("load-set-phase-mute-2").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-set-phase-muted-2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load-set-phase-mute-2").assertTextContains("muted")
        composeTestRule.onNodeWithTag("load-set-save").performClick()
        composeTestRule.waitForIdle()

        val saved = assertNotNull(viewModel.loadSet("round-trip"))
        assertEquals(listOf(false, true), saved.phases.map { it.muted })
        // Only the parked phase grows the key, so a set that never muted anything is byte for byte itself.
        val written = viewModel.loadSetStore.fileFor("round-trip").readText()
        assertEquals(1, written.split("\"muted\"").size - 1, written)
        // Saved, so the footer says what Run set will do rather than "unsaved".
        composeTestRule.onNodeWithTag("load-set-why").assertTextContains("2 phases, 1 muted", substring = true)
    }

    /**
     * **A phase keeps what the editor has no field for.**
     *
     * The phase editor writes the whole spec back over the one in the set, so anything it does not rebuild
     * is dropped. Two things are not on that screen: `muted`, which the set band owns, and `strictRate`,
     * which the command line sets. Opening a parked phase to read it therefore un-parked it, and a set run
     * with `--strict-rate` lost the flag the moment anybody touched a phase.
     */
    @Test
    fun `a phase edited through the breadcrumb stays muted, and keeps its strict rate`() {
        viewModel.saveLoadSet(
            roundTrip.copy(phases = roundTrip.phases.mapIndexed { i, p -> if (i == 1) p.copy(muted = true, strictRate = true) else p }),
        )
        show()

        composeTestRule.onNodeWithTag("load-set-phase-edit-2").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("phase-label").performTextClearance()
        composeTestRule.onNodeWithTag("phase-label").performTextInput("Hit them again")
        composeTestRule.onNodeWithTag("phase-done").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-set-phase-muted-2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load-set-save").performClick()
        composeTestRule.waitForIdle()

        val saved = assertNotNull(viewModel.loadSet("round-trip"))
        assertEquals("Hit them again", saved.phases[1].label, "the edit itself lands")
        assertTrue(saved.phases[1].muted, "and the phase is still parked")
        assertTrue(saved.phases[1].strictRate, "and still judges its own rate")
    }

    /**
     * The refusal sits under the phase that **reads**, because that is the phase whose edit button is one
     * line above the sentence, and it names the muted phase so nobody has to go looking for it. The parked
     * phase itself shows no fix count: it is not judged.
     */
    @Test
    fun `a phase reading a muted phase's captures shows the refusal under the reading phase`() {
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
                            muted = true,
                        ),
                        LoadPhaseSpec("Pass them", "Passes", "LOADGEN", match = LoadMatch(117, 117, "AI"), shape = LoadShape.Burst(10)),
                    ),
            ),
        )
        show()

        // On the refusal notice inside the reading phase's own row, not only in the footer.
        composeTestRule
            .onNodeWithTag("load-set-refusal")
            .assertTextContains(
                "Phase 2 · Pass them: the template reads \${quoteId}, and the phase that keeps it, " +
                    "phase 1 · Ask for a quote, is muted. Unmute it, or add it under Seed.",
            )
        composeTestRule.onNodeWithTag("load-set-phase-fixes-2").assertTextContains("1 fix", substring = true)
        composeTestRule.onNodeWithTag("load-set-phase-fixes-1").assertDoesNotExist()
        composeTestRule.onNodeWithTag("load-set-run").assertHasNoClickAction()
        composeTestRule.onNodeWithTag("load-set-why").assertTextContains("Phase 2", substring = true)
    }

    /**
     * **The whole feature, from the dialog.** Pick Reactive, pick the phase it reacts to, Done, Save, and
     * the file on disk carries the shape and the trigger a set file could only be hand-written to carry.
     *
     * The picker names the phase the way every refusal about a trigger names it, number and label, so the
     * sentence and the control cannot send their reader looking in two places.
     */
    @Test
    fun `a phase is made reactive in the dialog, and the file carries the trigger`() {
        viewModel.saveLoadSet(roundTrip)
        show()

        composeTestRule.onNodeWithTag("load-set-phase-edit-2").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-shape-reactive").performClick()
        composeTestRule.waitForIdle()
        // Reactive takes no count of its own, so the burst's field is gone and so is the index it counts
        // from: both are the trigger's.
        composeTestRule.onNodeWithTag("load-count").assertDoesNotExist()
        composeTestRule.onNodeWithTag("phase-index-from").assertDoesNotExist()
        composeTestRule.onNodeWithTag("phase-after").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("phase-after-1 · Ask for a quote").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("phase-done").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-set-save").performClick()
        composeTestRule.waitForIdle()

        val saved = assertNotNull(viewModel.loadSet("round-trip"))
        assertEquals(LoadShape.Triggered(), saved.phases[1].shape)
        assertEquals(1, saved.phases[1].after)
        assertEquals(1, saved.phases[1].indexFrom, "its indices are its trigger's, so it authors none")
        // And the row says so, in the words the file was written in.
        composeTestRule.onNodeWithTag("load-set-phase-plan-2").assertTextContains("reactive", substring = true)
        composeTestRule.onNodeWithTag("load-set-phase-plan-2").assertTextContains("after phase 1", substring = true)
    }

    /** A ceiling is a number a second, and an unticked box is the decision to run uncapped. */
    @Test
    fun `the never above tick writes the cap, and unticking it takes it away again`() {
        viewModel.saveLoadSet(
            roundTrip.copy(
                phases = roundTrip.phases.mapIndexed { i, p -> if (i == 1) p.copy(shape = LoadShape.Triggered(), after = 1) else p },
            ),
        )
        show()

        composeTestRule.onNodeWithTag("load-set-phase-edit-2").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-cap-on").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-cap").performTextInput("200")
        composeTestRule.onNodeWithTag("phase-done").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-set-save").performClick()
        composeTestRule.waitForIdle()

        assertEquals(LoadShape.Triggered(200), assertNotNull(viewModel.loadSet("round-trip")).phases[1].shape)

        composeTestRule.onNodeWithTag("load-set-phase-edit-2").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-cap").assertTextContains("200")
        composeTestRule.onNodeWithTag("load-cap-on").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("phase-done").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-set-save").performClick()
        composeTestRule.waitForIdle()

        assertEquals(LoadShape.Triggered(), assertNotNull(viewModel.loadSet("round-trip")).phases[1].shape)
    }

    /**
     * **Phase 1 is not offered a shape nothing could ever fire.** Nothing runs before the first phase, so
     * the segment has two options there and three from phase 2 on.
     */
    @Test
    fun `the first phase is offered burst and rate only, and the second is offered all three`() {
        viewModel.saveLoadSet(roundTrip)
        show()

        composeTestRule.onNodeWithTag("load-set-phase-edit-1").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-shape-reactive").assertDoesNotExist()
        composeTestRule.onNodeWithTag("phase-back").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-set-phase-edit-2").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-shape-reactive").assertIsDisplayed()
    }

    /**
     * **Every option keeps what it was given while another one is selected.**
     *
     * The reason the shape is a choice with three sets of fields rather than one field that changes
     * meaning: a phase tried as a rate and put back as a burst is the burst it was, and a trigger picked
     * before somebody looked at what a rate would do is still picked when they come back.
     */
    @Test
    fun `switching between all three shapes loses nothing that was typed`() {
        viewModel.saveLoadSet(roundTrip)
        show()

        composeTestRule.onNodeWithTag("load-set-phase-edit-2").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-count").assertTextContains("2000")

        composeTestRule.onNodeWithTag("load-shape-rate").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-rate").performTextClearance()
        composeTestRule.onNodeWithTag("load-rate").performTextInput("750")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-shape-reactive").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("phase-after").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("phase-after-1 · Ask for a quote").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-shape-burst").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-count").assertTextContains("2000", substring = true)

        composeTestRule.onNodeWithTag("load-shape-rate").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-rate").assertTextContains("750")

        composeTestRule.onNodeWithTag("load-shape-reactive").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("phase-after").assertTextContains("1 · Ask for a quote", substring = true)
    }

    /**
     * A reactive phase with no trigger has nothing to take its count or its indices from, so it is not a
     * phase yet. The sentence is in the Shape band, one row above the picker that answers it.
     */
    @Test
    fun `reactive with no phase picked refuses in the shape band and holds Done`() {
        viewModel.saveLoadSet(roundTrip)
        show()

        composeTestRule.onNodeWithTag("load-set-phase-edit-2").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-shape-reactive").performClick()
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithTag("load-refusal")
            .assertTextContains("Pick the phase this one reacts to", substring = true)
        composeTestRule.onNodeWithTag("phase-done").assertHasNoClickAction()
    }

    /**
     * A shape changed away from reactive drops the trigger with it, because `after` belongs to a reactive
     * phase and nothing else. That is one of the two remedies the set's own refusal offers, and the other
     * is one click away on the segment.
     */
    @Test
    fun `a reactive phase put back to a burst keeps no trigger`() {
        viewModel.saveLoadSet(
            roundTrip.copy(
                phases = roundTrip.phases.mapIndexed { i, p -> if (i == 1) p.copy(shape = LoadShape.Triggered(), after = 1) else p },
            ),
        )
        show()

        composeTestRule.onNodeWithTag("load-set-phase-edit-2").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-shape-burst").performClick()
        composeTestRule.waitForIdle()
        // The count opened empty, because a reactive phase asked for no number and offering four thousand
        // behind one click would be a volume nobody chose.
        composeTestRule.onNodeWithTag("load-count").performTextInput("500")
        composeTestRule.onNodeWithTag("phase-done").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-set-save").performClick()
        composeTestRule.waitForIdle()

        val saved = assertNotNull(viewModel.loadSet("round-trip"))
        assertEquals(LoadShape.Burst(500), saved.phases[1].shape)
        assertNull(saved.phases[1].after, "a burst that named a trigger would be refused for holding one")
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
