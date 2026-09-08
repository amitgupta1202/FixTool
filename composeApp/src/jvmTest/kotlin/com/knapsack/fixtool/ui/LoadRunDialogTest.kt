package com.knapsack.fixtool.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.LOAD_DIALOG_HEIGHT
import com.knapsack.fixtool.model.LOAD_DIALOG_WIDTH
import com.knapsack.fixtool.model.LoadRunDefaults
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Every refusal is a sentence on screen, under the row that caused it, and Run is what refuses.** The
 * dialog is driven without its window, over a view model whose one profile has lanes configured and none
 * logged on.
 */
class LoadRunDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    private val nos = LoadTemplate("NOS", listOf(35 to "D", 11 to "ORD-\${run}-\${messageIndex}", 55 to "EUR/USD"))

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-load-dialog", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    /** The refusal sentences on screen, as text. */
    private fun refusals(): List<String> = texts("load-refusal")

    private fun texts(tag: String, unmerged: Boolean = false): List<String> =
        composeTestRule
            .onAllNodesWithTag(tag, useUnmergedTree = unmerged)
            .fetchSemanticsNodes()
            .map { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "" }

    private fun profile(resetOnLogon: Boolean) =
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
                    resetOnLogon = resetOnLogon,
                ),
        )

    /**
     * The acceptor the client's own port belongs to, which is the whole condition for the far-end note:
     * a loopback connect host plus a FixTool acceptor listening on the port the lanes dial.
     */
    private fun venue() =
        FixConnectionProfile(
            id = "venue",
            name = "VENUE",
            config =
                FixConnectionConfig(
                    connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                    senderCompID = "VENUE",
                    targetCompID = "LG{n}",
                    socketAcceptPort = "9",
                ),
        )

    private fun seedTheRun() {
        composeTestRule.onNodeWithTag("load-seed-value-0").performTextClearance()
        composeTestRule.onNodeWithTag("load-seed-value-0").performTextInput("b7f2")
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the match is prefilled, the template is described, and no lanes means Run refuses with the fan-out sentence`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        // The match tags live in the Replies fold now. Its summary is what says so without opening it.
        composeTestRule
            .onNodeWithTag("load-replies-summary", useUnmergedTree = true)
            .assertTextContains("matching 11", substring = true)
        composeTestRule.onNodeWithTag("load-replies").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-request-tag").assertTextContains("11")
        composeTestRule.onNodeWithTag("load-reply-tag").assertTextContains("11")
        composeTestRule.onNodeWithTag("load-run").assertHasNoClickAction()
        val refusals = composeTestRule.onAllNodesWithTag("load-refusal").fetchSemanticsNodes()
        assertEquals(2, refusals.size, "a missing seed and no lane logged on")
    }

    /**
     * **The Template row says where the message came from, not what it is called.**
     *
     * Opened from the editor's Load button the row read "message editor", which looks like the name of a
     * saved template and sent an author looking for one. It says what it is instead, and the MsgType and
     * its name stay on the sub-line beside it.
     */
    @Test
    fun `opened from the editor, the Template row names the editor rather than a template`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-template").assertTextContains("the message in the editor")
        // The MsgType and its name stay beside the row, so nothing was lost by renaming it.
        assertTrue(
            composeTestRule.onAllNodesWithText("35=D", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "the sub-line under Template should still describe the message",
        )
    }

    @Test
    fun `a memory store on a profile without Reset on Logon is refused until the store choice goes back to the profile's`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = false))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }
        seedTheRun()

        val before = refusals()
        assertTrue(before.any { it.contains("Reset on Logon") }, before.toString())

        composeTestRule.onNodeWithTag("load-store-profile").performClick()
        composeTestRule.waitForIdle()

        val after = refusals()
        assertTrue(after.none { it.contains("Reset on Logon") }, after.toString())
    }

    /**
     * The store radios live inside the Identity fold, which is shut when everything in it is fine. A
     * refusal that names one of them opens that fold and says why — otherwise "the refusal sits next to
     * its cause" would be a claim the dialog breaks the moment the cause is hidden.
     */
    @Test
    fun `Advanced opens itself when a refusal names something inside it, and says why`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = false))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-store-profile").assertExists()
        seedTheRun()
        composeTestRule.onNodeWithTag("load-advanced-summary", useUnmergedTree = true).assertTextContains("the store", substring = true)
    }

    @Test
    fun `the footer says why Run is off rather than leaving a dead button to explain itself`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-run").assertHasNoClickAction()
        assertTrue(texts("load-why").single().isNotBlank(), "a disabled Run always has a reason on screen")
    }

    @Test
    fun `a preset chip sets the shape it names`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-preset-500").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-rate").assertTextContains("500")
        composeTestRule.onNodeWithTag("load-for").assertTextContains("10m")
        composeTestRule.onNodeWithTag("load-count").assertDoesNotExist()
    }

    @Test
    fun `switching the shape to a rate shows the rate fields`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-count").assertExists()
        composeTestRule.onNodeWithTag("load-rate").assertDoesNotExist()
        composeTestRule.onNodeWithTag("load-shape-rate").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-rate").assertExists()
        composeTestRule.onNodeWithTag("load-for").assertTextContains("10m")
        composeTestRule.onNodeWithTag("load-count").assertDoesNotExist()
    }

    /** The count, the rate and the settle window were `remember` locals, so every open reset them. */
    @Test
    fun `the dialog opens on what this profile was last asked for`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))
        viewModel.rememberLoadRunDefaults("lg", LoadRunDefaults(burst = true, count = "250", settle = "5s", seed = listOf(listOf("run", "b7f2"))))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-count").assertTextContains("250")
        composeTestRule.onNodeWithTag("load-settle").assertTextContains("5s")
        // The seed is inside Advanced, which stays shut while everything in it is fine.
        composeTestRule.onNodeWithTag("load-advanced").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-seed-value-0").assertTextContains("b7f2")
    }

    @Test
    fun `a seed name with no value seeds nothing, which is what leaves the refusal standing`() {
        assertEquals(mapOf("run" to "b7f2", "desk" to "fx"), seedMap(listOf("run" to "b7f2", "desk" to "fx")))
        assertEquals(emptyMap(), seedMap(listOf("run" to "")))
        assertEquals(mapOf("a" to "1"), seedMap(listOf("a" to "1", "" to "2")))
    }

    /** The CLI already takes every field the dialog collects, so this is a transcription, not a grammar. */
    @Test
    fun `Copy as fixtool load writes the line that runs the same plan`() {
        val plan =
            LoadPlan(
                id = "x",
                label = "l",
                template = nos,
                profileId = "lg",
                profileName = "RFQ Load Client",
                shape = LoadShape.Rate(500, 600_000),
                match = LoadMatch(131, 131, "S"),
                settleMs = 60_000,
                seed = mapOf("run" to "b7f2"),
                storeAndLog = StoreAndLogOverride.FOR_LOAD,
            )

        assertEquals(
            "fixtool load NOS --profile \"RFQ Load Client\" --rate 500/s --for 10m --settle 1m " +
                "--match 131=131 --reply-type S --seed run=b7f2 --store memory --log none",
            cliLine(plan),
        )
    }

    /** A SlimButton that is not enabled has no click action at all, which is what "refuses" means here. */
    @Test
    fun `the Run button is a real button that a test can read the state of`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))
        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }
        composeTestRule.onNodeWithTag("load-run").assertHasNoClickAction()
        composeTestRule.onNodeWithTag("load-shape-burst").assertHasClickAction()
    }

    /**
     * **The far-end note is prose the tool speaks, so it obeys the same style rule as the rest.** It named
     * the venue and then broke to an em dash before "FixTool's own acceptor", where a comma belongs. Read
     * off the dialog rather than off the view model, because the dialog is where anybody meets it. The
     * dialog's own sentence is the shorter one and ends with what to do about it, and the view model's
     * longer one still goes to the fan-out dialog and the control surface.
     */
    @Test
    fun `the far-end note is on screen and carries no dash`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))
        viewModel.saveConnectionProfile(venue())

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-far-end").assertIsDisplayed()
        val note = texts("load-far-end").single()
        assertTrue(note.contains("FixTool's own demo venue"), note)
        assertTrue(note.contains("connect the profile to it"), "the note ends with what to do about it: $note")
        assertTrue(!note.contains("\u2014"), "the far-end sentence should not carry an em dash: $note")
    }

    /**
     * **The marker is one character, or the stripe is twice as tall as every other one.** The marker column
     * was 22.dp, which fits "fix" and not "note", so the warning's marker broke into "not" over "e". It is
     * a circled "i" now: one character in a 14.dp circle cannot split.
     */
    @Test
    fun `the note marker is an icon on one line, never the word that split`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))
        viewModel.saveConnectionProfile(venue())

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        assertEquals(listOf("i"), texts("load-far-end-marker"), "the marker is the icon, whole")
        assertTrue(
            composeTestRule.onAllNodesWithText("note").fetchSemanticsNodes().isEmpty(),
            "the word that wrapped is gone from the dialog",
        )
        // One line of AppTheme.Type.meta is about 13.dp tall, so two of them clear 24.dp.
        val marker = composeTestRule.onNodeWithTag("load-far-end-marker").getUnclippedBoundsInRoot().height
        assertTrue(marker < 16.dp, "the note marker should be one line tall, it measured $marker")
    }

    /**
     * **Every row's explainer is a sentence about this run, not a field list.**
     *
     * The row read "35=D NewOrderSingle· per message 11 · fixed 35, 55", which is the same information in
     * the shape of debug output. What a reader needs is which tags move, and how many do not.
     */
    @Test
    fun `the Template row says what changes on every message, in a sentence`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithText("changes on every message", substring = true).assertExists()
        composeTestRule.onNodeWithText("the other two tags are fixed", substring = true).assertExists()
        // Opened from the editor there is no saved message behind it, and the hint says so.
        composeTestRule.onNodeWithText("unsaved", substring = true).assertExists()
    }

    /** "Settle" is the engine's word. The row is named by what the person filling it in is waiting for. */
    @Test
    fun `the settle row is called Wait for replies in the dialog only`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithText("Wait for replies").assertExists()
        assertTrue(
            composeTestRule.onAllNodesWithText("Settle").fetchSemanticsNodes().isEmpty(),
            "the dialog does not say Settle anywhere",
        )
        // The field, and everything downstream of it, keeps the engine's word.
        composeTestRule.onNodeWithTag("load-settle").assertExists()
    }

    /** The facts inside a hint are drawn a shade stronger than the sentence around them. */
    @Test
    fun `a hint marks its facts, and drops the markers`() {
        val hint = hintText("**35=D NewOrderSingle**, unsaved.", Color.Gray, Color.White)

        assertEquals("35=D NewOrderSingle, unsaved.", hint.text)
        val facts = hint.spanStyles.filter { it.item.color == Color.White }
        assertEquals(1, facts.size, "one fact, and the markers themselves are gone")
        assertEquals(0, facts.single().start, "the fact is the front of the sentence")
        assertTrue(hint.spanStyles.any { it.item.color == Color.Gray }, "the sentence around the fact stays dim")
    }

    /**
     * **A fold says what it holds, in the order the rows inside it come in.**
     *
     * Folding is only acceptable if nothing is hidden by it: the reader who never opens the fold still
     * has to know what the run matches on, which reply types count, and who is listening.
     */
    @Test
    fun `each fold carries the state of what it holds`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))
        viewModel.rememberLoadRunDefaults("lg", LoadRunDefaults(seed = listOf(listOf("run", "b7f2"))))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        assertEquals(
            "matching 11 ClOrdID to 11 · any reply type counts · listening on LOADGEN only",
            texts("load-replies-summary", unmerged = true).single(),
        )
        assertEquals("run = b7f2 · memory store, no log", texts("load-advanced-summary", unmerged = true).single())
    }

    /**
     * **A refusal opens its own fold, and only its own.** Both folds opening on any refusal would be the
     * ten-rows-at-one-weight dialog again with two extra clicks in it.
     */
    @Test
    fun `a store refusal opens the Identity fold and leaves the Replies fold shut`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = false))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        // Open, because the store cannot work and the radios that fix it are in here.
        composeTestRule.onNodeWithTag("load-store-profile").assertExists()
        composeTestRule.onNodeWithTag("load-advanced-fixes", useUnmergedTree = true).assertTextContains("fix", substring = true)
        // Shut, because nothing in it is refused. Its controls are not on screen at all.
        composeTestRule.onNodeWithTag("load-request-tag").assertDoesNotExist()
        composeTestRule.onNodeWithTag("load-replies-fixes").assertDoesNotExist()
    }

    /** "change" is the way in for a reader who is not being refused anything. */
    @Test
    fun `the change link on a fold opens it`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-request-tag").assertDoesNotExist()
        composeTestRule.onNodeWithTag("load-replies-change", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-request-tag").assertExists()
    }

    /** Four sections, each with what it is for beside its name. */
    @Test
    fun `the sections say what they are for`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithText("What to send").assertExists()
        composeTestRule.onNodeWithText("the message, and the lanes that carry it").assertExists()
        composeTestRule.onNodeWithText("How much").assertExists()
        composeTestRule.onNodeWithText("how many, how fast, and how long to wait").assertExists()
        composeTestRule.onNodeWithText("How replies are counted").assertExists()
        composeTestRule.onNodeWithText("Identity and store").assertExists()
    }

    /**
     * **780 by 700, and the user's last size after that.** In the view-state store, never in AppSettings:
     * a window size is not a setting anybody edits on a settings page.
     */
    @Test
    fun `the dialog opens at its own size until one is remembered`() {
        assertEquals(LOAD_DIALOG_WIDTH to LOAD_DIALOG_HEIGHT, viewModel.loadDialogSize())

        viewModel.rememberLoadDialogSize(900f, 820f)

        assertEquals(900f to 820f, viewModel.loadDialogSize())
    }
}
