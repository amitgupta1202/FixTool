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
import androidx.compose.ui.unit.width
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.LOAD_DIALOG_HEIGHT
import com.knapsack.fixtool.model.LOAD_DIALOG_WIDTH
import com.knapsack.fixtool.model.LoadRunDefaults
import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.service.SavedMessagesService
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
     * refusal that names one of them opens that fold and says why. Otherwise "the refusal sits next to
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
     * **A generator as a seed value is rendered once, exactly as a set renders its own seed.**
     *
     * `${'$'}{uuid:4}` typed into the value field used to go on the wire as those nine characters on every
     * one of four thousand messages.
     */
    @Test
    fun `a generator seed value is rendered once, and a name is refused`() {
        assertEquals(mapOf("run" to "b7f2"), renderedSeed(seedMap(listOf("run" to "b7f2"))))
        // A set saves what was typed, so the rows themselves keep the generator.
        assertEquals(mapOf("run" to "\${uuid:4}"), seedMap(listOf("run" to "\${uuid:4}")))

        val rendered = renderedSeed(seedMap(listOf("run" to "\${uuid:4}"))).getValue("run")

        assertEquals(4, rendered.length, "four hex characters, not the expression: $rendered")
        assertTrue(!rendered.contains("\$"), rendered)
        assertEquals(emptyList(), seedRefusals(listOf("run" to "\${uuid:4}")), "a generator is a value")
        assertEquals(
            listOf("run's value \${run} is not a value. Type one, or mint one."),
            seedRefusals(listOf("run" to "\${run}")),
        )
        assertEquals(1, seedRefusals(listOf("run" to "\${desk}")).size, "a name no generator answers to")
        assertEquals(emptyList(), seedRefusals(listOf("run" to "")), "an empty value is the empty-field refusal")
    }

    /** The refusal sits under Seed, opens the fold that holds Seed, and holds Run. */
    @Test
    fun `a self-referencing seed value is refused under its own row and Run refuses`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))
        viewModel.rememberLoadRunDefaults("lg", LoadRunDefaults(seed = listOf(listOf("run", "\${run}"))))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        assertTrue(refusals().any { it.contains("is not a value") }, refusals().toString())
        // The Seed field is on screen, because the fold that holds it opened itself.
        composeTestRule.onNodeWithTag("load-seed-value-0").assertExists()
        composeTestRule.onNodeWithTag("load-run").assertHasNoClickAction()
    }

    /**
     * **Also listen on offers only profiles a reply could land on.**
     *
     * Never an acceptor, which is FixTool answering rather than receiving, and never the far end these
     * lanes dial, which is the thing sending the replies. Ticking either listened on sessions no reply
     * could arrive on and then counted the misses as strays.
     */
    @Test
    fun `Also listen on excludes the issuing profile, the acceptor and the far end`() {
        val dropCopy = FixConnectionProfile(id = "dc", name = "DROPCOPY", config = FixConnectionConfig(senderCompID = "DC", targetCompID = "V"))
        val profiles = listOf(profile(resetOnLogon = true), venue(), dropCopy)

        val offered = listenCandidates(profiles, issuing = "lg", farEnd = "venue") { true }

        assertEquals(listOf("DROPCOPY"), offered.map { it.name })
        // With no far end named, the acceptor is still not offered: it answers, it does not receive.
        assertEquals(listOf("DROPCOPY"), listenCandidates(profiles, issuing = "lg", farEnd = null) { true }.map { it.name })
        // And a profile with nothing logged on cannot receive anything either.
        assertEquals(emptyList(), listenCandidates(profiles, issuing = "lg", farEnd = "venue") { false })
    }

    /**
     * **Two radios that mean the same thing are a decision nobody has to make.** When the profile already
     * runs a memory store with no log, the row says what will happen and stops.
     */
    @Test
    fun `the Store row is one sentence when the profile already runs a memory store with no log`() {
        val memoryProfile =
            profile(resetOnLogon = true).let {
                it.copy(
                    config =
                        it.config.copy(
                            messageStore = FixConnectionConfig.MessageStoreKind.MEMORY,
                            messageLog = FixConnectionConfig.MessageLogKind.NONE,
                        ),
                )
            }
        viewModel.saveConnectionProfile(memoryProfile)
        viewModel.rememberLoadRunDefaults("lg", LoadRunDefaults(seed = listOf(listOf("run", "b7f2"))))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-advanced").performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            listOf("Lanes run on a memory store with no message log, as the profile already does."),
            texts("load-store-same"),
        )
        composeTestRule.onNodeWithTag("load-store-memory").assertDoesNotExist()
        composeTestRule.onNodeWithTag("load-store-profile").assertDoesNotExist()
    }

    /**
     * **"view in editor" opens the editor.** It loaded the message into a panel that could be shut, behind
     * this dialog, which reads as a dead link. The window is raised too, which a test cannot see.
     */
    @Test
    fun `view in editor loads the message and opens the editor panel`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))
        SavedMessagesService(customPath = File(testDir, "saved_messages.json").absolutePath).saveMessage(
            "lg",
            SavedFixMessage(
                id = "nos",
                name = "NOS",
                userTags = setOf("lg"),
                fields = listOf(SavedFixField("35", "D"), SavedFixField("11", "ORD-\${messageIndex}")),
            ),
        )
        viewModel.loadSavedMessagesForActiveSession()
        assertTrue(!viewModel.showMessageEditor.value, "the editor starts shut")

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = null, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-view-template").assertTextContains("view in editor")
        composeTestRule.onNodeWithTag("load-view-template").performClick()
        composeTestRule.waitForIdle()

        assertTrue(viewModel.showMessageEditor.value, "the editor panel is open")
        assertEquals("D", viewModel.editorFields.first { it.tag == "35" }.value, "and the editor holds the message")
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

    /**
     * **The chrome, which is what "it looks nothing like the mockup" meant.** The words and the folding
     * landed without a single line between them, so four sections read as one continuous list however many
     * headings were in it. Every section but the first is separated by a rule, and the first has none
     * because the lead sentence above it is its top edge.
     */
    @Test
    fun `every section but the first is separated by a rule`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))
        viewModel.saveConnectionProfile(venue())
        viewModel.rememberLoadRunDefaults("lg", LoadRunDefaults(seed = listOf(listOf("run", "b7f2"))))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        val rules = listOf("load-rule-how-much", "load-rule-replies", "load-rule-identity", "load-rule-note")
        rules.forEach { composeTestRule.onNodeWithTag(it).assertIsDisplayed() }
        val firstHead = composeTestRule.onNodeWithText("What to send").getUnclippedBoundsInRoot()
        rules.forEach { tag ->
            val rule = composeTestRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(rule.top > firstHead.bottom, "$tag should sit below the first section's head, it was at ${rule.top}")
        }
    }

    /**
     * **One label column, ending on one edge.** At 92.dp left-aligned, the labels started together and
     * stopped at ten different places, and "Wait for replies" did not fit at all.
     */
    @Test
    fun `the label column is one width, wide enough for the longest label`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        val settle = composeTestRule.onNodeWithText("Wait for replies").getUnclippedBoundsInRoot()
        val template = composeTestRule.onNodeWithText("Template").getUnclippedBoundsInRoot()

        assertEquals(LABEL_COLUMN, settle.width, "the label column is 118.dp, it measured ${settle.width}")
        assertEquals(LABEL_COLUMN, template.width)
        assertEquals(settle.right, template.right, "right aligned, so every label ends on the same edge")
    }

    /** The link is the row's own edge, not wherever the summary happened to stop. */
    @Test
    fun `the change link on a fold is pinned to the row's right edge`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        val row = composeTestRule.onNodeWithTag("load-replies").getUnclippedBoundsInRoot()
        val link = composeTestRule.onNodeWithTag("load-replies-change", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val summary = composeTestRule.onNodeWithTag("load-replies-summary", useUnmergedTree = true).getUnclippedBoundsInRoot()

        // The section gutter is all that is between the link and the edge of the dialog.
        val gap = row.right - link.right
        assertTrue(gap < 18.dp, "the change link should sit on the row's right edge, it stopped $gap short")
        assertTrue(link.left > summary.left, "the summary takes the width and the link takes the edge")
    }

    /** Mint belongs beside the name it writes a value for, not beside the empty placeholder. */
    @Test
    fun `mint sits on the seed row that has a name, and the empty row carries only add`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))
        viewModel.rememberLoadRunDefaults("lg", LoadRunDefaults(seed = listOf(listOf("run", "b7f2"))))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-advanced").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-seed-mint-0").assertIsDisplayed()

        composeTestRule.onNodeWithTag("load-seed-add").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-seed-name-1").assertExists()
        composeTestRule.onNodeWithTag("load-seed-mint-1").assertDoesNotExist()
        composeTestRule.onNodeWithTag("load-seed-mint-0").assertExists()
    }

    /**
     * **A row that can be added can be taken away.** There was no way back from a "+ add" pressed by
     * mistake, so the row went on the wire with every message of the run.
     */
    @Test
    fun `a seed row can be taken away again, and the last one leaves an empty row behind`() {
        assertEquals(listOf("run" to "b7f2"), seedRowsWithout(listOf("run" to "b7f2", "desk" to "fx"), 1))
        assertEquals(listOf("" to ""), seedRowsWithout(listOf("run" to "b7f2"), 0), "the band never disappears")

        viewModel.saveConnectionProfile(profile(resetOnLogon = true))
        viewModel.rememberLoadRunDefaults("lg", LoadRunDefaults(seed = listOf(listOf("run", "b7f2"))))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-advanced").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-seed-add").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-seed-name-1").performTextInput("desk")
        composeTestRule.onNodeWithTag("load-seed-value-1").performTextInput("fx")
        composeTestRule.waitForIdle()
        assertTrue(
            texts("load-advanced-summary", unmerged = true).single().contains("desk = fx"),
            "the row is in the seed before it is removed",
        )

        composeTestRule.onNodeWithTag("load-seed-remove-1").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-seed-name-1").assertDoesNotExist()
        val summary = texts("load-advanced-summary", unmerged = true).single()
        assertTrue(!summary.contains("desk"), "the seed no longer carries the row that was removed: $summary")
    }
}
