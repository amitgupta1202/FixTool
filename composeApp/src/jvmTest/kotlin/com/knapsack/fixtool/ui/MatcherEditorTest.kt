package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/** Evidence for 3.1 (matcher chips): the editor switches matcher type and emits the new matcher. */
class MatcherEditorTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private val outDir = File("build/scenario-screenshots").absoluteFile

    @Composable
    private fun editor(onChange: (Matcher) -> Unit) {
        var matcher by remember { mutableStateOf<Matcher>(Matcher.Exact("8")) }
        Box(modifier = Modifier.size(620.dp, 90.dp).background(AppTheme.Colors.surface).padding(8.dp)) {
            MatcherEditor(matcher = matcher, capturedValue = "8", onChange = {
                matcher = it
                onChange(it)
            })
        }
    }

    @Test
    fun `switching type emits a matcher of the chosen type`() {
        var last: Matcher = Matcher.Exact("8")
        composeTestRule.setContent { editor { last = it } }
        snapshot("matcher_editor_exact.png")
        // Open the type dropdown (button shows the current type) and pick oneOf.
        composeTestRule.onNodeWithText("exact").performClick()
        composeTestRule.onNodeWithText("oneOf").performClick()
        composeTestRule.waitForIdle()
        assertTrue(last is Matcher.OneOf, "expected OneOf, got $last")
        snapshot("matcher_editor_oneof.png")
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[MatcherEditorTest] snapshot '$name' skipped: ${e.message}")
        }
    }
}
