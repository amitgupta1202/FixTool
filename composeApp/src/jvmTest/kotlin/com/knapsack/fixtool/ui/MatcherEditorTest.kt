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
import com.knapsack.fixtool.model.scenario.ScenarioVariable
import com.knapsack.fixtool.model.scenario.validationError
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        // Open the type dropdown (chip shows the current type) and pick oneOf (menu items carry help text).
        composeTestRule.onNodeWithText("exact").performClick()
        composeTestRule.onNodeWithText("oneOf", substring = true).performClick()
        composeTestRule.waitForIdle()
        assertTrue(last is Matcher.OneOf, "expected OneOf, got $last")
        snapshot("matcher_editor_oneof.png")
    }

    /**
     * Switching a row to "regex" seeds the pattern from the captured value — and seeded raw, that value
     * is a pattern, not a literal. A Price captured as `1.5` became `1.5`, where `.` matches any
     * character, so an actual price of `125` satisfied the assertion and the step passed. The author
     * asked to loosen the row; the tool loosened it in the direction of passing, and said nothing.
     */
    @Test
    fun `a regex seeded from a captured value matches only that value`() {
        val seeded = defaultMatcherForType("regex", "1.5") as Matcher.Regex

        assertTrue(Regex(seeded.pattern).matches("1.5"), "the captured value must still match: ${seeded.pattern}")
        assertFalse(Regex(seeded.pattern).matches("125"), "'.' must be a literal dot, not any-character: ${seeded.pattern}")
    }

    @Test
    fun `a seeded regex survives the metacharacters a FIX value really carries`() {
        // Text(58) values are free-form and routinely carry brackets, parens and pluses.
        val value = "Rejected (band +1.5) [MKT]"
        val seeded = defaultMatcherForType("regex", value) as Matcher.Regex

        assertNull(seeded.validationError(), "a seeded pattern must always compile: ${seeded.pattern}")
        assertTrue(Regex(seeded.pattern).matches(value))
        assertFalse(Regex(seeded.pattern).matches("Rejected (band +1X5) [MKT]"))
    }

    @Test
    fun `an empty captured value still seeds a usable pattern`() {
        assertEquals(Matcher.Regex(".*"), defaultMatcherForType("regex", ""))
    }

    // ----- reference, with a scope in hand ---------------------------------------------------------------

    /**
     * With the run's variables supplied, switching a row to `reference` seeds the variable whose value the
     * row actually carries — never the invented `${out.D.11}` — and the expression edits as a **picker**
     * over the scope's names, so the name cannot be mistyped and each menu row shows what it held.
     */
    @Test
    fun `with a scope, reference seeds the matching variable and edits as a picker`() {
        val scope =
            listOf(
                ScenarioVariable("id0", "OTHER"),
                ScenarioVariable("id1", "8"), // the captured value — the correlation the author means
            )
        var last: Matcher = Matcher.Exact("8")
        composeTestRule.setContent {
            var matcher by remember { mutableStateOf<Matcher>(Matcher.Exact("8")) }
            Box(modifier = Modifier.size(620.dp, 90.dp).background(AppTheme.Colors.surface).padding(8.dp)) {
                MatcherEditor(
                    matcher = matcher,
                    capturedValue = "8",
                    scopeVariables = scope,
                    onChange = {
                        matcher = it
                        last = it
                    },
                )
            }
        }

        composeTestRule.onNodeWithText("exact").performClick()
        composeTestRule.onNodeWithText("reference", substring = true).performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            Matcher.Reference("\${id1}"),
            last,
            "the seed is the variable whose value this row carries, not a hardcoded history expression",
        )
        snapshot("matcher_editor_reference_picker.png")

        // The expression is now a picker: open it and choose the other variable — by name, values shown.
        composeTestRule.onNodeWithText("\${id1}").performClick()
        composeTestRule.onNodeWithText("\${id0} = OTHER", substring = true).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Matcher.Reference("\${id0}"), last)
    }

    /** An expression the scope cannot name keeps the free-text field — the picker must not eat it. */
    @Test
    fun `a history expression stays free text even when a scope is present`() {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(620.dp, 90.dp).background(AppTheme.Colors.surface).padding(8.dp)) {
                MatcherEditor(
                    matcher = Matcher.Reference("\${out.D.11}"),
                    capturedValue = "8",
                    scopeVariables = listOf(ScenarioVariable("id0", "A1")),
                    onChange = {},
                )
            }
        }
        composeTestRule.onNode(hasSetTextAction()).assertTextContains("\${out.D.11}")
    }

    // ----- named enum values --------------------------------------------------------------------------

    /** OrdStatus, as the dictionary names it. */
    private val ordStatus = listOf("0" to "NEW", "1" to "PARTIALLY_FILLED", "2" to "FILLED", "8" to "REJECTED")

    @Composable
    private fun enumEditor(initial: Matcher, values: List<Pair<String, String>>, onChange: (Matcher) -> Unit) {
        var matcher by remember { mutableStateOf(initial) }
        Box(modifier = Modifier.size(620.dp, 90.dp).background(AppTheme.Colors.surface).padding(8.dp)) {
            MatcherEditor(
                matcher = matcher,
                capturedValue = "8",
                enumValues = values,
                onChange = {
                    matcher = it
                    onChange(it)
                },
            )
        }
    }

    /**
     * The BA-enabling half: nobody should have to remember that `39=8` is Rejected. The row shows the
     * dictionary's word for the value it carries, and picking a different one emits that value.
     */
    @Test
    fun `an exact matcher on an enum tag reads and edits by name`() {
        var last: Matcher = Matcher.Exact("8")
        composeTestRule.setContent { enumEditor(Matcher.Exact("8"), ordStatus) { last = it } }

        composeTestRule.onNodeWithText("8 (REJECTED)").assertExists()
        snapshot("matcher_editor_enum_named.png")

        composeTestRule.onNodeWithText("8 (REJECTED)").performClick()
        composeTestRule.onNodeWithText("2 — FILLED", substring = true).performClick()
        composeTestRule.waitForIdle()

        assertEquals(Matcher.Exact("2"), last, "picking a name must emit its value, not its label")
    }

    /**
     * A value the dictionary has never heard of is exactly what a tester chasing an undocumented
     * venue code is asserting on. The field must still carry it, and the menu must not mislabel it
     * as one of the codes it does know.
     */
    @Test
    fun `a value the dictionary does not define is kept, and not mislabelled`() {
        composeTestRule.setContent { enumEditor(Matcher.Exact("Z9"), ordStatus) {} }

        // The field keeps it, and the menu says "pick…" — not "Z9", which would read as though the
        // dictionary had listed it. That is the whole of the `takeIf` guarding the picker's selection.
        composeTestRule.onNode(hasSetTextAction()).assertTextContains("Z9")
        composeTestRule.onNodeWithText("pick…").assertExists()
    }

    /** Without a dictionary entry the row is what it always was — a plain field, no menu. */
    @Test
    fun `a tag with no enum values keeps the plain field`() {
        composeTestRule.setContent { enumEditor(Matcher.Exact("ACME"), emptyList()) {} }

        composeTestRule.onNode(hasSetTextAction()).assertTextContains("ACME")
        composeTestRule.onNodeWithText("pick…").assertDoesNotExist()
    }

    /** A oneOf is built up, so its picker ADDS — a dropdown that replaced the set could never name the second value. */
    @Test
    fun `the oneOf picker adds to the set rather than replacing it`() {
        var last: Matcher = Matcher.OneOf(listOf("1"))
        composeTestRule.setContent { enumEditor(Matcher.OneOf(listOf("1")), ordStatus) { last = it } }

        composeTestRule.onNodeWithText("add…").performClick()
        composeTestRule.onNodeWithText("2 — FILLED", substring = true).performClick()
        composeTestRule.waitForIdle()

        assertEquals(Matcher.OneOf(listOf("1", "2")), last, "the value already there must survive: $last")
    }

    /**
     * The acceptor rules editor lives in a side panel a third of the workbench's width and passes a
     * narrowed params slot. Rendered here so the value and its name are known to still fit, rather
     * than assumed from the arithmetic.
     */
    @Test
    fun `the narrow params slot still shows both the value and its name`() {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(340.dp, 90.dp).background(AppTheme.Colors.surface).padding(8.dp)) {
                MatcherEditor(
                    matcher = Matcher.Exact("8"),
                    capturedValue = "8",
                    enumValues = ordStatus,
                    paramsWidth = 190.dp,
                    onChange = {},
                )
            }
        }
        composeTestRule.onNodeWithText("8 (REJECTED)").assertExists()
        snapshot("matcher_editor_enum_narrow.png")
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
