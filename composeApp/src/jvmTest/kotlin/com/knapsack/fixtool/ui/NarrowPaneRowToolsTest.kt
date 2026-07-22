package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.TagValue
import com.knapsack.fixtool.service.SendFields
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A narrow detail pane costs the author width, never a button.**
 *
 * The scenario editor's two dense grids — the Send field row and the Expect step's bind constraint row — were
 * built from fixed-width columns summing to about 500dp. The detail pane defaults to 40% of the editor, so on
 * an ordinary window it is nearer 400dp, and `Row` pays that overflow by measuring whatever stands last
 * against `maxWidth = 0`. A `Modifier.width(...)` column under a zero constraint is not clipped — it is 0dp
 * wide, present in the tree and invisible on screen. What stood last in those rows was **Remove field** and
 * **Remove constraint**, so deleting a tag from a send message, or a constraint from an expectation, was
 * impossible at any window size an author actually uses, with nothing on screen to say why.
 *
 * These tests hold the row structure that fixes it (see `RowTool`'s KDoc): the buttons are unweighted
 * siblings of a `weight(1f)` block, so they keep their width and the describing columns give theirs up.
 * They assert *size*, not existence — existence never broke.
 */
class NarrowPaneRowToolsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** Two constraints and a five-field send, so both grids have rows to starve. */
    private fun scenario() =
        Scenario(
            id = "sc-narrow",
            name = "narrow",
            steps =
                listOf(
                    ScenarioStep.Send("35=D|11=ORD-1|38=1000|54=1|55=EUR/USD|", "DEMO1"),
                    ScenarioStep.Expect(
                        "DEMO1",
                        "in",
                        MatchPredicate("8", null, listOf(TagValue(11, "ORD-1"), TagValue(150, "2"))),
                        10_000,
                        Expectation(emptyList()),
                    ),
                ),
        )

    /**
     * The editor at [width], on the step at [step]. 1024dp is the point of the test — it is a perfectly
     * ordinary window, and it is where the buttons used to disappear. The pane also mirrors the document
     * host's [LocalMinimumInteractiveComponentSize], since that is what sets the buttons' real width.
     */
    private fun editorAt(width: Int, step: Int, onChange: (Scenario) -> Unit = {}) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 24.dp) {
                Box(modifier = Modifier.size(width.dp, 700.dp).background(AppTheme.Colors.background)) {
                    ScenarioEditor(
                        initial = scenario(),
                        dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4),
                        sessionOptions = listOf("DEMO1"),
                        onSave = {},
                        onChange = onChange,
                        selectedStep = step,
                        onOpenDiff = { _, _ -> },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun ComposeContentTestRule.widthOf(tag: String): Int = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().size.width

    /** The render, kept beside the other editor screenshots so the fixed layout is visible in a picture. */
    private fun shoot(name: String) {
        val out = File("build/scenario-screenshots").absoluteFile.apply { mkdirs() }
        ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(out, name))
    }

    @Test
    fun `a send field row keeps its buttons when the detail pane is narrow`() {
        editorAt(width = 1024, step = 0)
        shoot("narrow_send_row.png")

        // Every trailing control, not just the delete: the row starved from the right, so Remove went first
        // and Insert and Move down went with it.
        listOf("send-up-0", "send-down-0", "send-insert-0", "send-remove-0").forEach { tag ->
            assertTrue(composeTestRule.widthOf(tag) > 0, "$tag must have width in a narrow pane; it is the row's only way to act")
        }
        // And the column that yields instead is the value.
        assertTrue(composeTestRule.widthOf("send-value-0") > 0, "the value column shrinks, but never to nothing")
    }

    @Test
    fun `remove field deletes the row it names in a narrow pane`() {
        var latest: Scenario? = null
        editorAt(width = 1024, step = 0, onChange = { latest = it })

        composeTestRule.onNodeWithTag("send-remove-1").performClick()
        composeTestRule.waitForIdle()

        val send = latest?.steps?.first() as ScenarioStep.Send
        assertEquals(
            listOf(35, 38, 54, 55),
            SendFields.parse(send.raw).map { it.tag },
            "clicking Remove on row 1 (tag 11) must delete that field",
        )
    }

    @Test
    fun `a bind constraint row keeps its remove button when the detail pane is narrow`() {
        editorAt(width = 1024, step = 1)
        shoot("narrow_bind_row.png")

        listOf("match-remove-0", "match-remove-1").forEach { tag ->
            assertTrue(composeTestRule.widthOf(tag) > 0, "$tag must have width; a constraint that cannot be removed is a trap")
        }
        // The pickers the author reads to know *which* constraint they are removing survive too — the
        // position picker was the first casualty of the BINDS TO header row.
        assertTrue(composeTestRule.widthOf("match-occurrence") > 0, "the position picker wraps rather than collapsing")
    }

    @Test
    fun `remove constraint deletes the constraint it names in a narrow pane`() {
        var latest: Scenario? = null
        editorAt(width = 1024, step = 1, onChange = { latest = it })

        composeTestRule.onNodeWithTag("match-remove-0").performClick()
        composeTestRule.waitForIdle()

        val expect = latest?.steps?.get(1) as ScenarioStep.Expect
        assertEquals(listOf(150), expect.match?.fields?.map { it.tag }, "clicking Remove on constraint 0 (tag 11) must delete it")
    }
}
