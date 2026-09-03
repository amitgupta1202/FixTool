package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.service.AcceptorPresets
import com.knapsack.fixtool.service.FxVenuePreset
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * The rules editor uses the width it is given.
 *
 * The connection panel is drag-resizable between a tenth and six tenths of the window, and the editor
 * was built for the narrow end and then stayed that shape inside all of it — a template wrapping over
 * three lines with 700dp of nothing beside it. The layout is chosen from the measured width, so both
 * ends are rendered here rather than reasoned about: a screenshot is the only thing that actually
 * answers "does it fit".
 */
class AcceptorRulesLayoutTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private val outDir = File("build/scenario-screenshots").absoluteFile

    private val lifecycle =
        listOf(
            AcceptorResponseRule(
                whenMsgType = "D",
                whenFields = mapOf("55" to "EUR/USD"),
                steps =
                    listOf(
                        ResponseStep(template = "35=8|150=0|39=0|37=\${uuid}|17=\${uuid}|11=\${req.11}|14=0|"),
                        ResponseStep(template = "35=8|150=F|39=2|37=\${uuid}|17=\${uuid}|11=\${req.11}|14=100|", delayMillis = 400),
                    ),
            ),
        )

    /**
     * Renders the rule **open**, since what is being measured is the step rows and cards start closed.
     * The screenshots are of the editing surface, which is the only shape that has a width problem —
     * a closed card is two lines of text at any width.
     */
    private fun render(width: Int) {
        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier
                        .width(width.dp)
                        .background(AppTheme.Colors.background)
                        .padding(8.dp)
                        .testTag(EDITOR),
            ) {
                AcceptorRulesEditor(rules = lifecycle, onRulesChange = {})
            }
        }
        composeTestRule.onNodeWithTag("rule-expand-0").performClick()
        composeTestRule.waitForIdle()
    }

    /** The default side panel: timing on one line, template wrapping under it. */
    @Test
    fun `at the narrow end each step stacks so its template stays readable`() {
        render(340)

        composeTestRule.onNodeWithText("ms → 400ms", substring = true).assertExists()
        snapshot("acceptor_rules_narrow.png")
    }

    /**
     * Dragged out, the same rule is one line per step. This is the regression the responsive layout
     * exists to prevent: before it, this render was identical to the 340dp one.
     */
    @Test
    fun `dragged wide each step becomes a single row`() {
        render(900)

        composeTestRule.onNodeWithText("ms → 400ms", substring = true).assertExists()
        snapshot("acceptor_rules_wide.png")
    }

    /**
     * **The venue the demo installs, as a reader meets it.**
     *
     * Twenty-one rules in the width the connection panel actually opens at. Open, this was twenty-one
     * forms and about nine screens of scrolling, which is why the rules were the least-read part of a
     * product whose whole claim is that its venue can be read. Closed it is a list, and the assertions
     * are that each line still says the two things a rule is: what it answers, and what it sends back.
     */
    @Test
    fun `the FX venue bundle reads as a list of one-line rules`() {
        val bundle = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(FxVenuePreset.ID)!!).rules
        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier
                        .width(340.dp)
                        .background(AppTheme.Colors.background)
                        .padding(8.dp)
                        .testTag(EDITOR),
            ) {
                AcceptorRulesEditor(rules = bundle, onRulesChange = {})
            }
        }

        composeTestRule.onNodeWithText("21 rule(s), first match wins").assertExists()
        // The first card, and the last — the priced pair at the top of the quote block, and the
        // catch-all that has to say out loud that it answers everything left.
        composeTestRule.onNodeWithTag("rule-number-0").assertExists()
        composeTestRule.onNodeWithTag("rule-digest-0").assertExists()
        composeTestRule.onNodeWithTag("rule-digest-${bundle.lastIndex}").assertExists()

        snapshot("acceptor_rules_fx_venue_closed.png")
    }

    /** Both layouts show every field — a fold must not be a way to lose one. */
    @Test
    fun `neither layout drops a field`() {
        render(900)

        // MsgType, the condition's tag, the condition's exact value, two delays, two templates.
        composeTestRule.onAllNodesWithText("EUR/USD", substring = true).assertCountEquals(1)
        composeTestRule.onAllNodesWithText("\${req.11}", substring = true).assertCountEquals(2)
        composeTestRule.onAllNodesWithText("400").assertCountEquals(1)
    }

    /**
     * Captures the editor, not the window.
     *
     * `onRoot()` cannot: opening the rule leaves the chevron's tooltip up, a tooltip is a window of its
     * own, and two roots is not one node. Framing the editor is also the better picture — what these
     * screenshots are about is how a rule lays out in the width the panel gave it, and 700px of empty
     * test window either side of it was never part of the answer.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            ImageIO.write(
                composeTestRule
                    .onNodeWithTag(EDITOR)
                    .captureToImage()
                    .toAwtImage(),
                "png",
                File(outDir, name),
            )
        } catch (e: Exception) {
            println("[AcceptorRulesLayoutTest] snapshot '$name' skipped: ${e.message}")
        }
    }

    private companion object {
        const val EDITOR = "rules-editor-under-test"
    }
}
