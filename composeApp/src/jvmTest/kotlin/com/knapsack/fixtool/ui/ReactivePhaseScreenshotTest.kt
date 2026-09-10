package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.LOAD_SETS_DIALOG_HEIGHT
import com.knapsack.fixtool.model.LOAD_SETS_DIALOG_WIDTH
import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPhaseSpec
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.NBSP
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.service.SavedMessagesService
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * **The gate for the reactive phase's doors, and it is not the assertions below.**
 *
 * The engine was verified live through the control surface, but nobody had ever seen the dialog: the
 * three-way shape control, the Reacts to picker and the ceiling tick shipped covered only by a semantics
 * tree, which knows what a node says and nothing about where it sits or how wide it is. A UI phase is
 * gated by its screenshots. The tests only pin what somebody already thought to ask.
 *
 * So this drives the real sets dialog and the real phase editor, clicking the way a tester clicks, and
 * writes one picture per state of the shape control: a burst, a rate, reactive with no trigger picked and
 * the sentence that refuses it, the picker open, reactive uncapped, reactive capped, and the set band
 * afterwards with the chain drawn on the phase rows.
 *
 * The lanes are down throughout, which is the state a set is authored in: a set is written before it is
 * run. So the ceiling's unit reads "/s" here and grows its ", across all N lanes" tail only once a
 * profile is logged on.
 */
class ReactivePhaseScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val outDir = File("build/reactive-screenshots").absoluteFile
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-reactive-shots", "").apply {
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
                        sessionCount = 5,
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
        // Reads a name no seed covers, so phase 3's sub-line says which earlier phase keeps it.
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

    /**
     * Three phases, because two would give the Reacts to picker one option and a picker with one row in it
     * cannot show whether the rows are named or numbered.
     */
    private val chain =
        LoadSet(
            name = "rfq-chain",
            label = "RFQ chain",
            seed = mapOf("run" to "\${uuid:4}"),
            storeAndLog = StoreAndLogOverride.FOR_LOAD,
            phases =
                listOf(
                    LoadPhaseSpec(
                        "Ask for a quote",
                        "Quotes",
                        "LOADGEN",
                        match = LoadMatch(131, 131, "S"),
                        shape = LoadShape.Burst(4_000),
                        capture = mapOf("quoteId" to 117),
                    ),
                    LoadPhaseSpec("Hit the quotes", "Quotes", "LOADGEN", match = LoadMatch(11, 11, "8"), shape = LoadShape.Burst(2_000)),
                    LoadPhaseSpec("Pass the rest", "Passes", "LOADGEN", match = LoadMatch(117, 117, "AI"), shape = LoadShape.Burst(500)),
                ),
        )

    /** The dialog at the size it opens at, so a picture measures the same widths a user sees. */
    private fun show() {
        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier
                        .size(LOAD_SETS_DIALOG_WIDTH.dp, LOAD_SETS_DIALOG_HEIGHT.dp)
                        .background(AppTheme.Colors.background),
            ) {
                LoadSetsDialogContent(viewModel, onDismiss = {}, onRun = {})
            }
        }
        composeTestRule.waitForIdle()
    }

    /**
     * The dialog's own root, not `onRoot`, because an open dropdown is a second root and `onRoot` refuses
     * to choose between two. The capture is a crop of the whole rendered scene, so the popup's pixels are
     * in the picture whichever root names the rectangle: the first one is the content's, which keeps every
     * shot the same 820 by 700 frame.
     */
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            val root = composeTestRule.onAllNodes(isRoot()).onFirst()
            ImageIO.write(root.captureToImage().toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[ReactivePhaseScreenshotTest] snapshot '$name' skipped: ${e.message}")
        }
    }

    /**
     * **One phase, walked through all three shapes and back out to the band.**
     *
     * The order is the order somebody meets the control in: the burst it already is, the rate it could be,
     * the reactive shape with nothing picked yet and the sentence that says so, the picker that answers
     * that sentence, the trigger picked, the ceiling ticked, and finally Done, where the phase row has to
     * say what the phase now does.
     */
    @Test
    fun `a phase walked from a burst to a rate to a reactive one, and the row that reads it back`() {
        viewModel.saveLoadSet(chain)
        show()

        composeTestRule.onNodeWithTag("load-set-phase-edit-3").performClick()
        composeTestRule.waitForIdle()

        // 1. The burst it is: a count beside the segment, and the index it counts from beside that.
        composeTestRule.onNodeWithTag("load-count").assertTextContains("500")
        snapshot("01-shape-burst.png")

        // 2. The rate, whose two fields take the count's place.
        composeTestRule.onNodeWithTag("load-shape-rate").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-rate").performTextClearance()
        composeTestRule.onNodeWithTag("load-rate").performTextInput("750")
        composeTestRule.onNodeWithTag("load-for").performTextClearance()
        composeTestRule.onNodeWithTag("load-for").performTextInput("30s")
        composeTestRule.waitForIdle()
        snapshot("02-shape-rate.png")

        // 3. Reactive, with nothing picked. The refusal is in the Shape band, one row above the picker
        // that answers it, and it holds Done.
        composeTestRule.onNodeWithTag("load-shape-reactive").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-refusal").assertTextContains("Pick the phase this one reacts to", substring = true)
        // A reactive phase takes no count and authors no index: both are its trigger's.
        composeTestRule.onNodeWithTag("load-count").assertDoesNotExist()
        composeTestRule.onNodeWithTag("phase-index-from").assertDoesNotExist()
        snapshot("06-refusal-phase1-reactive.png")

        // 4. The picker open. Every earlier phase, by number and label, the way every sentence about a
        // trigger names one.
        composeTestRule.onNodeWithTag("phase-after").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("phase-after-1 · Ask for a quote").assertIsDisplayed()
        composeTestRule.onNodeWithTag("phase-after-2 · Hit the quotes").assertIsDisplayed()
        snapshot("04-reacts-to-picker-open.png")

        // 5. The trigger picked, and the ceiling left off, which is the decision to run uncapped.
        composeTestRule.onNodeWithTag("phase-after-1 · Ask for a quote").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("phase-after").assertTextContains("1 · Ask for a quote", substring = true)
        snapshot("03-shape-reactive-uncapped.png")

        // 6. A ceiling put in and then untaken. The field keeps the number and is drawn off, so the tick,
        // the field, the hint under it and the footer all say the same thing: the tick decides, and 999 is
        // kept rather than in force.
        composeTestRule.onNodeWithTag("load-cap-on").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-cap").performTextInput("999")
        composeTestRule.onNodeWithTag("load-cap-on").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-cap").assertTextContains("999")
        composeTestRule.onNodeWithTag("load-cap").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("phase-why").assertTextContains("reactive after phase 1", substring = true)
        snapshot("11-cap-typed-while-unticked.png")

        // 7. The ceiling ticked, with a number in it.
        composeTestRule.onNodeWithTag("load-cap-on").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-cap").performTextClearance()
        composeTestRule.onNodeWithTag("load-cap").performTextInput("200")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-cap").assertTextContains("200")
        snapshot("05-shape-reactive-capped.png")

        // 8. Done, and the band. The phase row is the only place a reader sees the chain without opening
        // anything, so it has to carry the shape and the trigger in the words the file was written in.
        composeTestRule.onNodeWithTag("phase-done").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-set-phase-plan-3").assertTextContains("reactive", substring = true)
        composeTestRule.onNodeWithTag("load-set-phase-plan-3").assertTextContains("after${NBSP}phase${NBSP}1", substring = true)
        snapshot("07-set-rows-chain.png")

        // 9. The same row with the ceiling taken off, which is the shortest a reactive row gets. The two
        // pictures together say whether the row has width to spare or whether the cap is what overruns it.
        composeTestRule.onNodeWithTag("load-set-phase-edit-3").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-cap-on").performClick()
        composeTestRule.onNodeWithTag("phase-done").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-set-phase-plan-3").assertTextContains("reactive · after${NBSP}phase${NBSP}1", substring = true)
        snapshot("09-set-rows-uncapped.png")
    }

    /**
     * **Phase 1's segment has two options, and that is why picture 6 is the no-trigger refusal.**
     *
     * Nothing runs before the first phase, so there is no reactive option there to select and no refusal
     * to reach that way. The picture is here so the two-option segment can be compared against the
     * three-option one beside it.
     */
    @Test
    fun `the first phase is offered two shapes, so a refusal there is unreachable by clicking`() {
        viewModel.saveLoadSet(chain)
        show()

        composeTestRule.onNodeWithTag("load-set-phase-edit-1").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-shape-burst").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load-shape-rate").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load-shape-reactive").assertDoesNotExist()
        snapshot("08-phase-1-two-options.png")

        // The Template picker is the same composable the Reacts to picker is, so its open menu says
        // whether anything about how that menu looks belongs to the trigger or to every picker here.
        composeTestRule.onNodeWithTag("load-template").performClick()
        composeTestRule.waitForIdle()
        snapshot("10-template-picker-open.png")
    }
}
