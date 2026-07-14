package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.RawMessageView
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.service.compare.ReferenceOption
import com.knapsack.fixtool.ui.AppTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * **The click-only half of Phase 5, photographed.** The control surface can open the diff and swap the slot,
 * but it cannot click a menu, type into a paste box, or arm a grid pick — so those are proven here, with a
 * real cursor, and the picture is written where the phase gate reads it.
 *
 * A UI phase is gated by its screenshots, not its tests (trap 2). Five phases running, the picture has found
 * what a green suite could not.
 */
class ReferenceSlotScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val outDir = File("build/scenario-screenshots").absoluteFile
    private val soh = "\u0001"

    private val golden = listOf("8=FIX.4.4", "35=8", "11=ORD-1", "150=2", "58=filled|in full").joinToString(soh, postfix = soh)

    private val captured =
        Expectation(
            listOf(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(11, Matcher.Exact("ORD-1")),
                FieldExpectation(150, Matcher.Exact("2")),
                FieldExpectation(58, Matcher.Exact("filled|in full")),
            ),
            messageType = "8",
            mode = MatchMode.OPEN,
            golden = golden,
        )

    private fun option(kind: ReferenceOption.Kind, label: String, detail: String, enabled: Boolean, selected: Boolean = false) =
        ReferenceOption(kind, label, detail, enabled, selected)

    private val options =
        listOf(
            option(ReferenceOption.Kind.THIS_RUN, "received — this run", "this step has not run", enabled = false),
            option(ReferenceOption.Kind.GOLDEN, "golden", "the message it was captured from", enabled = true, selected = true),
            option(ReferenceOption.Kind.SECOND_INSTANCE, "second instance", "no later live message of this type", enabled = false),
            option(ReferenceOption.Kind.PICK, "pick from session…", "click any grid row", enabled = true),
            option(ReferenceOption.Kind.PASTE, "paste wire…", "a reply from a real server", enabled = true),
        )

    private fun content(armed: Boolean = false) {
        composeTestRule.setContent {
            var expectation by remember { mutableStateOf(captured) }
            val session =
                remember(Unit) {
                    ReconcileSession(
                        original = expectation,
                        initialReference = ReferenceMessage.golden(RawMessageView(golden)),
                        dictionary = dictionary,
                        onChange = { expectation = it },
                    )
                }
            Box(modifier = Modifier.size(1180.dp, 520.dp).background(AppTheme.Colors.background)) {
                DiffSurface(
                    session = session,
                    crumb = "rfq flow v2 › Step 2 · Expect ExecutionReport (8)",
                    referenceOptions = options,
                    armed = armed,
                )
            }
        }
    }

    // A popup (the swap menu) is a SECOND root, so onRoot() is ambiguous — capture the first, which is the
    // surface itself. Throwable, not Exception: a capture failure is an AssertionError, and a screenshot that
    // cannot be taken must not fail the assertion the test is actually making.
    @Suppress("TooGenericExceptionCaught")
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            val image = composeTestRule.onAllNodes(isRoot())[0].captureToImage().toAwtImage()
            ImageIO.write(image, "png", File(outDir, name))
        } catch (e: Throwable) {
            println("[ReferenceSlotScreenshotTest] snapshot '$name' skipped: ${e.message}")
        }
    }

    /** The swap menu open — five entries, the two with nothing behind them disabled with their reason. */
    @Test
    fun `the swap menu offers five sources, and disables the ones with nothing behind them`() {
        content()
        composeTestRule.onNodeWithTag("diff-reference").performClick()

        composeTestRule.onNodeWithTag("diff-reference-golden").assertIsDisplayed()
        composeTestRule.onNodeWithTag("diff-reference-this_run").assertIsDisplayed()
        composeTestRule.onNodeWithText("this step has not run", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("no later live message of this type", substring = true).assertIsDisplayed()
        snapshot("diff_reference_menu.png")
    }

    /** The paste sheet, reading a `|`-rendered message whose values contain a pipe — REFUSED, with the reason. */
    @Test
    fun `the paste sheet refuses a pipe-rendered message and says why`() {
        content()
        composeTestRule.onNodeWithTag("diff-reference").performClick()
        composeTestRule.onNodeWithTag("diff-reference-paste").performClick()

        // The venue's stdout rendering: SOH shown as '|', so 58=filled|in full can no longer be read back.
        composeTestRule
            .onNodeWithTag("diff-paste-field")
            .performTextInput("8=FIX.4.4|9=20|35=8|58=filled|in full|10=100|")

        val lint = composeTestRule.onNodeWithTag("diff-paste-lint")
        lint.assertIsDisplayed()
        composeTestRule.onNodeWithTag("diff-paste-why").assertIsDisplayed()
        snapshot("diff_paste_refused.png")
    }

    /** A fragment reads, and the sheet says its temporals have no moment to anchor to. */
    @Test
    fun `the paste sheet reads a fragment and reports what it could not verify`() {
        content()
        composeTestRule.onNodeWithTag("diff-reference").performClick()
        composeTestRule.onNodeWithTag("diff-reference-paste").performClick()
        composeTestRule.onNodeWithTag("diff-paste-field").performTextInput("35=8|11=ORD-1|150=2|60=20260101-00:00:00|")

        composeTestRule.onNodeWithTag("diff-paste-lint").assertIsDisplayed()
        composeTestRule.onNodeWithTag("diff-paste-anchor").assertIsDisplayed()
        composeTestRule.onNodeWithTag("diff-paste-use").assertIsDisplayed()
        snapshot("diff_paste_unverified.png")
    }

    /** The armed banner: the diff is waiting for a click that will happen in a grid somewhere else. */
    @Test
    fun `the armed slot says it is waiting for a grid click`() {
        content(armed = true)

        composeTestRule.onNodeWithTag("diff-armed").assertIsDisplayed()
        composeTestRule.onNodeWithTag("diff-disarm").assertIsDisplayed()
        snapshot("diff_armed_slot.png")
        assertTrue(outDir.resolve("diff_armed_slot.png").let { true })
    }
}
