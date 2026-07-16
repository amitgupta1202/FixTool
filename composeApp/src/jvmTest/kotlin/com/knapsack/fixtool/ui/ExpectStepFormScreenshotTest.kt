package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.TagValue
import com.knapsack.fixtool.model.scenario.TemporalKind
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * **The Expect step form, photographed** — the render the 2026-07 cleanup was verified against, kept in the
 * suite so the form's layout regressions are visible in a picture (`expect_step_form.png`). The structure it
 * pins: RECEIVES / BINDS TO / ASSERTS as bounded sections, session and timeout inside RECEIVES, the mode chip
 * on the title, and the constraint grid sharing its tag·name columns with the assertions preview.
 */
class ExpectStepFormScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the expect form renders its three sections from one left edge`() {
        val expectation = Expectation(
            fields = listOf(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(6, Matcher.Numeric(1.09244)),
                FieldExpectation(11, Matcher.Reference("\${id0}")),
                FieldExpectation(14, Matcher.Numeric(1000000.0)),
                FieldExpectation(15, Matcher.Exact("USD")),
                FieldExpectation(17, Matcher.Presence),
                FieldExpectation(31, Matcher.Numeric(1.09244)),
                FieldExpectation(37, Matcher.Presence),
                FieldExpectation(38, Matcher.Numeric(1000000.0)),
                FieldExpectation(39, Matcher.Exact("2")),
                FieldExpectation(54, Matcher.Exact("1")),
                FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)),
                FieldExpectation(150, Matcher.Exact("2")),
                FieldExpectation(151, Matcher.Numeric(0.0)),
            ),
            messageType = "8",
            mode = MatchMode.OPEN,
            golden = "8=FIX.4.4|35=8|11=ORD-1|150=2|10=000|",
        )
        val scenario = Scenario(
            id = "sc-1",
            name = "captured order flow",
            setup = listOf(ScenarioStep.ClearMessages("DEMO1")),
            steps = listOf(
                ScenarioStep.Send("35=D|11=\${id0 = UUID.randomUUID()}|38=1000000|40=1|54=1|55=EUR/USD|", "DEMO1"),
                ScenarioStep.Expect(
                    "DEMO1", "in",
                    MatchPredicate("8", null, listOf(TagValue(11, "\${id0}"), TagValue(150, "2"))),
                    10_000, expectation,
                ),
            ),
        )
        composeTestRule.setContent {
            Box(modifier = Modifier.size(1280.dp, 760.dp).background(AppTheme.Colors.background).padding(10.dp)) {
                ScenarioEditor(
                    initial = scenario,
                    dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4),
                    sessionOptions = listOf("DEMO1"),
                    onSave = {},
                    selectedStep = 1,
                    onOpenDiff = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // The three sections and their tenants — the door button included, so the render shows the whole form.
        composeTestRule.onNodeWithTag("session-dropdown").assertIsDisplayed()
        composeTestRule.onNodeWithTag("expect-timeout").assertIsDisplayed()
        composeTestRule.onNodeWithTag("match-type").assertIsDisplayed()
        composeTestRule.onNodeWithTag("open-diff").assertIsDisplayed()

        val out = File("build/scenario-screenshots").absoluteFile.apply { mkdirs() }
        ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(out, "expect_step_form.png"))
    }
}
