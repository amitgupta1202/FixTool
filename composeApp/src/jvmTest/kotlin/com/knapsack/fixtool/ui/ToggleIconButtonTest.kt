package com.knapsack.fixtool.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **One pressed look, and the three readers it has to reach.**
 *
 * The audit found five state languages among the app's toggles — glyph swap, tint, a changing label, a
 * filled background, and one control that was not a toggle at all while looking like one. Two of those say
 * nothing to an eye that has not seen the other state, and none of them said anything to a test or to a
 * screen reader. So the state is asserted here three ways, because that is how it is published.
 */
class ToggleIconButtonTest {
    @get:Rule
    val rule = createComposeRule()

    private fun render(
        on: Boolean,
        enabled: Boolean = true,
        disabledReason: String? = null,
        onClick: () -> Unit = {},
    ) {
        rule.setContent {
            ToggleIconButton(
                on = on,
                tooltip = "Filter pane",
                icon = Icons.Default.FilterAlt,
                onClick = onClick,
                enabled = enabled,
                disabledReason = disabledReason,
                tag = "filter",
            )
        }
    }

    /** The same control, holding its own state, so one test can watch it change rather than two draw it. */
    private fun renderToggling(initiallyOn: Boolean) {
        rule.setContent {
            var on by remember { mutableStateOf(initiallyOn) }
            ToggleIconButton(
                on = on,
                tooltip = "Filter pane",
                icon = Icons.Default.FilterAlt,
                onClick = { on = !on },
                tag = "filter",
            )
        }
    }

    /**
     * **A pressed toggle says so to a test and to a screen reader, not only to an eye.**
     *
     * `selected` and `stateDescription` are the two a tinted glyph never published, which is why four of
     * the app's toggles could not be asserted on at all: the only difference between their states was a
     * colour inside an Icon.
     */
    @Test
    fun `a toggle publishes its state as selected and as a state description`() {
        render(on = true)

        rule
            .onNodeWithTag("filter")
            .assertIsOn()
            .assertHasClickAction()
        assertEquals("on", rule.onNodeWithTag("filter").stateDescription())
    }

    @Test
    fun `an off toggle says off, and is still the same control`() {
        render(on = false)

        rule
            .onNodeWithTag("filter")
            .assertIsOff()
            .assertIsDisplayed()
            .assertHasClickAction()
        assertEquals("off", rule.onNodeWithTag("filter").stateDescription())
    }

    /**
     * **The tooltip is the noun alone.** "Wrap: On (click to unwrap)" was a control explaining its own
     * mechanism to a reader who wanted to know what it does; the pressed look carries the state now, so the
     * word is free to name the thing. Asserted in both states, because the point is that it does not change.
     */
    @Test
    fun `the tooltip names the thing and says the same in both states`() {
        renderToggling(initiallyOn = true)

        rule.onNodeWithTag("filter").assertContentDescriptionContains("Filter pane")
        rule.onNodeWithTag("filter").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("filter").assertIsOff()
        rule.onNodeWithTag("filter").assertContentDescriptionContains("Filter pane")
    }

    /**
     * **A refused toggle carries its reason and does nothing when pressed**, rather than being a button that
     * looks live and answers with silence. The reason replaces the noun, because a reader hovering a dark
     * control is asking why, not what.
     */
    @Test
    fun `a disabled toggle says why, and a click does not fire`() {
        var clicks = 0
        render(on = false, enabled = false, disabledReason = "Requires a FIX data dictionary", onClick = { clicks++ })

        rule.onNodeWithTag("filter").assertContentDescriptionContains("Requires a FIX data dictionary")
        rule.onNodeWithTag("filter").performClick()
        rule.waitForIdle()

        assertEquals(0, clicks, "a refused toggle is not clickable, so there is nothing to press past")
    }

    /** And an enabled one fires exactly once per click. */
    @Test
    fun `a click toggles`() {
        var clicks = 0
        render(on = false, onClick = { clicks++ })

        rule.onNodeWithTag("filter").performClick()
        rule.waitForIdle()

        assertEquals(1, clicks)
    }

    /**
     * **A pressed toggle is drawn, not merely described.**
     *
     * The look is a ground, a hairline and a 2dp underline, none of which a semantics tree can see. What it
     * can see is that the node is the size it was asked for at both states — the pressed decoration is drawn
     * behind the glyph rather than laid out beside it, so a toggle turning on never moves its neighbours.
     */
    @Test
    fun `turning a toggle on does not change its size`() {
        renderToggling(initiallyOn = false)
        val off = rule.onNodeWithTag("filter").fetchSemanticsNode().size

        rule.onNodeWithTag("filter").performClick()
        rule.waitForIdle()
        val on = rule.onNodeWithTag("filter").fetchSemanticsNode().size

        assertTrue(
            off == on,
            "a pressed toggle measured $on where the same control measured $off off, so its ground is laid " +
                "out rather than drawn and the row beside it shifts",
        )
    }

    /** The state description as a screen reader would read it, which no tinted glyph ever published. */
    private fun SemanticsNodeInteraction.stateDescription(): String? =
        fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }
}
