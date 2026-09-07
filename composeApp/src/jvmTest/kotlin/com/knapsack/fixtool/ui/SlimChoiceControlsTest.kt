package com.knapsack.fixtool.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * **What the glyph rows did not have.** The `"◉ "` prefix plus a bare `selectable` was already focusable
 * and already toggled on Space, so these are the three things that were actually missing: a [Role] a
 * screen reader can read, checkbox semantics on the row that multi-selects, and arrow keys inside a group.
 */
@OptIn(ExperimentalTestApi::class)
class SlimChoiceControlsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun role(tag: String): Role? =
        composeTestRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Role)

    @Test
    fun `a radio says it is a radio, and a checkbox says it is a checkbox`() {
        composeTestRule.setContent {
            var picked by remember { mutableStateOf("a") }
            var ticked by remember { mutableStateOf(false) }
            SlimRadio(selected = picked == "a", onSelect = { picked = "a" }, testTag = "radio") { Text("A") }
            SlimCheckbox(checked = ticked, onCheckedChange = { ticked = it }, testTag = "check") { Text("B") }
        }

        assertEquals(Role.RadioButton, role("radio"))
        assertEquals(Role.Checkbox, role("check"))
    }

    @Test
    fun `a checkbox reports its state as toggled, which a radio group cannot say for a multi-select`() {
        composeTestRule.setContent {
            var ticked by remember { mutableStateOf(false) }
            SlimCheckbox(checked = ticked, onCheckedChange = { ticked = it }, testTag = "check") { Text("DROPCOPY") }
        }

        val state = {
            composeTestRule
                .onNodeWithTag("check")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ToggleableState)
        }
        assertEquals(ToggleableState.Off, state())
        composeTestRule.onNodeWithTag("check").performClick()
        composeTestRule.waitForIdle()
        assertEquals(ToggleableState.On, state())
    }

    @Test
    fun `arrow keys move the selection within a radio group, which is what makes it a group`() {
        var picked = "file"
        val first = FocusRequester()
        composeTestRule.setContent {
            var selected by remember { mutableStateOf("file") }
            SlimRadioGroup(
                options = listOf("file", "memory"),
                selected = selected,
                onSelect = {
                    selected = it
                    picked = it
                },
                optionTestTag = { "store-$it" },
                modifier = Modifier.focusRequester(first),
            ) { Text(it) }
        }
        composeTestRule.runOnIdle { first.requestFocus() }

        composeTestRule.onNodeWithTag("store-file").performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.waitForIdle()
        assertEquals("memory", picked)

        composeTestRule.onNodeWithTag("store-memory").performKeyInput { pressKey(Key.DirectionUp) }
        composeTestRule.waitForIdle()
        assertEquals("file", picked)
    }

    @Test
    fun `left and right walk a segmented control`() {
        var shape = "Burst"
        composeTestRule.setContent {
            var selected by remember { mutableStateOf("Burst") }
            SlimSegmented(
                options = listOf("Burst", "Rate"),
                selected = selected,
                onSelect = {
                    selected = it
                    shape = it
                },
                label = { it },
                optionTestTag = { "seg-$it" },
            )
        }

        composeTestRule.onNodeWithTag("seg-Burst").performClick()
        composeTestRule.onNodeWithTag("seg-Burst").performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.waitForIdle()
        assertEquals("Rate", shape)

        composeTestRule.onNodeWithTag("seg-Rate").performKeyInput { pressKey(Key.DirectionLeft) }
        composeTestRule.waitForIdle()
        assertEquals("Burst", shape)
    }
}
