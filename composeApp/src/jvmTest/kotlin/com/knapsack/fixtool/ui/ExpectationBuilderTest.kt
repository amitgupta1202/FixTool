package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.service.MessageView
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Evidence for 3.1/3.2/3.3: the expectation builder renders dictionary-seeded matcher chips with a
 * live green/red preview, flags an over-specified field via the two-instance "verify generalizes"
 * check, and emits the authored [Expectation] on save.
 */
class ExpectationBuilderTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private val outDir = File("build/scenario-screenshots").absoluteFile

    // Tag 37 is deliberately authored as Exact (over-specified) so verify-generalizes flags it.
    private val drafts = listOf(
        FieldDraft(35, "MsgType", "8", included = true, matcher = Matcher.Exact("8")),
        FieldDraft(60, "TransactTime", "20260630-09:15:02.000", included = true, matcher = Matcher.Temporal(TemporalKind.TODAY)),
        FieldDraft(31, "LastPx", "1.2345", included = true, matcher = Matcher.Numeric(1.2345, 0.0)),
        FieldDraft(37, "OrderID", "OID-1", included = true, matcher = Matcher.Exact("OID-1")),
        FieldDraft(11, "ClOrdID", "ORD-1", included = true, matcher = Matcher.Exact("ORD-1")),
    )
    private val golden = MapView(mapOf(35 to "8", 60 to "20260630-09:15:02.000", 31 to "1.2345", 37 to "OID-1", 11 to "ORD-1"))
    // Second instance: fresh OrderID and timestamp, same business keys.
    private val second = MapView(mapOf(35 to "8", 60 to "20260630-10:30:00.000", 31 to "1.2345", 37 to "OID-2", 11 to "ORD-1"))

    @Composable
    private fun builder(onSave: (Expectation) -> Unit) {
        // Reproduce embedding inside a scrollable parent (the scenario builder / dialog).
        Box(modifier = Modifier.size(900.dp, 420.dp).background(AppTheme.Colors.surface).padding(10.dp)) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ExpectationBuilder("8", drafts, goldenView = golden, secondView = second, onSave = onSave)
            }
        }
    }

    @Test
    fun `renders seeded chips with live preview and saves the expectation`() {
        var saved: Expectation? = null
        composeTestRule.setContent { builder { saved = it } }
        // Matcher type chips are shown (capture/auto-seed result).
        assertTrue(composeTestRule.onAllNodesWithText("exact").fetchSemanticsNodes().isNotEmpty())
        composeTestRule.onNodeWithText("numeric").assertExists()
        snapshot("expectation_builder.png")

        composeTestRule.onNodeWithText("Save expectation").performClick()
        composeTestRule.waitForIdle()
        assertEquals(5, saved?.fields?.size, "all five included fields should be saved")
    }

    @Test
    fun `verify generalizes flags the over-specified field`() {
        composeTestRule.setContent { builder {} }
        composeTestRule.onNodeWithText("Verify generalizes").performClick()
        composeTestRule.waitForIdle()
        // Tag 37 was Exact on a value that changes between instances -> over-specified.
        composeTestRule.onNodeWithText("over-specified", substring = true).assertExists()
        snapshot("expectation_builder_verify.png")
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[ExpectationBuilderTest] snapshot '$name' skipped: ${e.message}")
        }
    }

    private class MapView(private val tags: Map<Int, String>) : MessageView {
        // A LinkedHashMap keeps insertion order, which is what the callers below rely on: under the
        // sequence model the order of the fields *is* the addressing, so a view that reordered them
        // would be testing against a message no venue ever sent.
        override fun fields(): List<Pair<Int, String>> = tags.toList()
    }
}
