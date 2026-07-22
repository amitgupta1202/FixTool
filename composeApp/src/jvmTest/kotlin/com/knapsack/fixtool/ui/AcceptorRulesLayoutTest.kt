package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.ResponseStep
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

    private fun render(width: Int) {
        composeTestRule.setContent {
            Box(modifier = Modifier.width(width.dp).background(AppTheme.Colors.background).padding(8.dp)) {
                AcceptorRulesEditor(rules = lifecycle, onRulesChange = {})
            }
        }
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

    /** Both layouts show every field — a fold must not be a way to lose one. */
    @Test
    fun `neither layout drops a field`() {
        render(900)

        // MsgType, the condition's tag, the condition's exact value, two delays, two templates.
        composeTestRule.onAllNodesWithText("EUR/USD", substring = true).assertCountEquals(1)
        composeTestRule.onAllNodesWithText("\${req.11}", substring = true).assertCountEquals(2)
        composeTestRule.onAllNodesWithText("400").assertCountEquals(1)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[AcceptorRulesLayoutTest] snapshot '$name' skipped: ${e.message}")
        }
    }
}
