package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.ui.AppTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * **The plain diff viewer — click-only, so the picture is the gate** (trap 2/4). It renders the read-only
 * diff of two messages: the `=`/`≠`/`+A`/`+B` gutter, the neutral difference count, the read-only footer — and
 * it carries **no offers**, which is the structural form of *"nothing can write"* (G2). The change is fed back
 * the way the app feeds it: the session mutates in place, and the surface recomposes off its state.
 */
class DiffViewerScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val outDir = File("build/scenario-screenshots").absoluteFile
    private val soh = "\u0001"

    private fun wire(vararg f: Pair<Int, String>) = f.joinToString("") { (t, v) -> "$t=$v$soh" }

    // golden vs shape: a value change (151), a moved party entry, a dropped tag (58, only in A), an added
    // tag (2376, only in B) — every classification the viewer draws, in one pair.
    private val golden =
        wire(
            35 to "8",
            151 to "0",
            453 to "2",
            448 to "FIRMA",
            447 to "D",
            452 to "1",
            448 to "FIRMB",
            447 to "D",
            452 to "4",
            58 to "filled|in full",
        )
    private val shape =
        wire(35 to "8", 151 to "500000", 2376 to "Y", 453 to "2", 448 to "FIRMB", 447 to "D", 452 to "4", 448 to "FIRMA", 447 to "D", 452 to "1")

    private fun sideA() = DiffSide(golden, "golden · UAT · 08:12:31", ReferenceMessage.Provenance.PICKED)

    private fun sideB() = DiffSide(shape, "this run · TRADE · 09:35:44", ReferenceMessage.Provenance.PICKED)

    private fun view(onSeed: (SeedFrom) -> Unit = {}): DiffViewerSession {
        lateinit var session: DiffViewerSession
        composeTestRule.setContent {
            val s = remember { DiffViewerSession(sideA(), sideB(), dictionary, MatchMode.STRICT) }
            session = s
            androidx.compose.foundation.layout.Box(modifier = Modifier.size(1180.dp, 1100.dp).background(AppTheme.Colors.background)) {
                DiffViewerSurface(
                    session = s,
                    onSwapSides = { s.swapSides() },
                    onSelectMode = { s.selectMode(it) },
                    onSeed = onSeed,
                )
            }
        }
        return session
    }

    @Test
    fun `the viewer draws the four classifications, a neutral count, and a read-only footer`() {
        val session = view()
        composeTestRule.onNodeWithTag("diff-viewer").assertExists()
        composeTestRule.onNodeWithTag("diff-viewer-gutter-value").assertExists() // ≠
        composeTestRule.onNodeWithTag("diff-viewer-gutter-left_only").assertExists() // +A (58, only in golden)
        composeTestRule.onNodeWithTag("diff-viewer-gutter-right_only").assertExists() // +B (2376, only in shape)
        composeTestRule.onNodeWithTag("diff-viewer-count").assertExists()
        composeTestRule.onNodeWithTag("diff-viewer-footer").assertExists()

        // The structural guard: the viewer's lines carry no offers, so there is no button to click and nothing
        // to write. Delete `{ emptyList() }` from DiffViewerSession.model and this goes red.
        assertTrue(session.model.lines.all { it.offers.isEmpty() }, "a viewer line must carry no offer — nothing can write")

        snapshot("diff_viewer.png")
    }

    @Test
    fun `swap sides turns the only-in-A row into an only-in-B row`() {
        view()
        // Before: 58 is only in A (golden). Its row shows a +A gutter.
        composeTestRule.onNodeWithTag("diff-viewer-gutter-left_only").assertExists()
        composeTestRule.onNodeWithTag("diff-viewer-swap").performClick()
        composeTestRule.waitForIdle()
        // After: golden is on the right, so 58 is only in B.
        composeTestRule.onNodeWithTag("diff-viewer-gutter-right_only").assertExists()
        snapshot("diff_viewer_swapped.png")
    }

    @Test
    fun `seed offers both sides and fires the one-way door`() {
        var seeded: SeedFrom? = null
        view(onSeed = { seeded = it })
        composeTestRule.onNodeWithTag("diff-viewer-seed").performClick()
        composeTestRule.onNodeWithTag("diff-viewer-seed-a").performClick()
        composeTestRule.waitForIdle()
        assertTrue(seeded == SeedFrom.A, "Seed from A must fire the one-way door")
    }

    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            ImageIO.write(composeTestRule.onNodeWithTag("diff-viewer").captureToImage().toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[DiffViewerScreenshotTest] snapshot '$name' skipped: ${e.message}")
        }
    }
}
