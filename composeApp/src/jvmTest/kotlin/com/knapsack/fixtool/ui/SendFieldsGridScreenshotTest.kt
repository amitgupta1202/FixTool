package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * **The Send field grid's value inputs are one width** (`send_fields_grid.png`): the value column is a
 * fixed-width column like the Message Editor's, so a row whose value already mints (`${id0 = uuid:20}`,
 * with no mint button) does not stretch wider than a plain `EUR`, and an expression row is no wider than
 * an enum one. The flexible column is the help beside the value, never the value itself.
 */
class SendFieldsGridScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `send field value inputs share one width`() {
        val scenario = Scenario(
            id = "sc-grid",
            name = "grid",
            steps = listOf(
                ScenarioStep.Send(
                    "35=D|11=\${id0 = uuid:20}|15=EUR|38=1000|54=2|126=\${utcnow+5min}|131=\${id1 = uuid:20}|167=REPO|",
                    "DEMO1",
                ),
            ),
        )
        composeTestRule.setContent {
            Box(modifier = Modifier.size(1500.dp, 520.dp).background(AppTheme.Colors.background).padding(8.dp)) {
                ScenarioEditor(
                    initial = scenario,
                    dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4),
                    sessionOptions = listOf("DEMO1"),
                    onSave = {},
                    selectedStep = 0,
                    split = 0.28f,
                )
            }
        }
        composeTestRule.waitForIdle()

        val out = File("build/scenario-screenshots").absoluteFile.apply { mkdirs() }
        ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(out, "send_fields_grid.png"))

        // Every value input — plain, enum, and both flavours of expression — is the same width. A mint
        // button present on some rows and absent on others (already-minting values) must not change it.
        val widths = (0..7).map { i -> composeTestRule.onNodeWithTag("send-value-$i").fetchSemanticsNode().size.width }
        val first = widths.first()
        assertTrue(
            widths.all { abs(it - first) <= 1 },
            "Send value inputs must share one width; got $widths",
        )
    }
}
