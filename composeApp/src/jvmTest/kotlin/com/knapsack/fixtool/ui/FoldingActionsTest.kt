package com.knapsack.fixtool.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.UnfoldMore
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The fold rule, without a renderer.**
 *
 * [foldBar] is arithmetic on purpose — a bar has to know what it can afford before it composes the
 * controls, and a rule that could only be checked by screenshotting a pane at six widths would not be
 * checked. So the three stages are asserted here at exact widths, and the promises that matter are the ones
 * a reader notices: nothing wraps, nothing is half drawn, and nothing is reachable at one width and gone at
 * another.
 */
class FoldingActionsTest {
    /** 20dp buttons, 2dp gaps, and a chip that costs 60px, at density 1 — near enough to the real bar. */
    private val button = 20
    private val gap = 2
    private val chip = 60

    private fun action(
        label: String,
        labelled: Boolean = false,
        rank: Int = 0,
        neverFolds: Boolean = false,
        glyph: Boolean = true,
    ) = BarAction(
        label = label,
        icon = if (glyph) Icons.Default.UnfoldMore else null,
        onClick = {},
        tag = label.lowercase().replace(' ', '-'),
        labelled = labelled,
        foldRank = rank,
        neverFolds = neverFolds,
    )

    private fun fold(actions: List<BarAction>, available: Int, reserved: Int = 0) =
        foldBar(
            actions = actions,
            availablePx = available,
            reservedPx = reserved,
            metrics = BarMetrics(buttonPx = button, gapPx = gap, overflowPx = button, labelPx = { chip }),
        )

    /** Stage one, and the only state a wide bar is ever in: every word is on screen. */
    @Test
    fun `a bar with room keeps its words`() {
        val actions = listOf(action("Send", labelled = true), action("Clear", labelled = true))
        val fold = fold(actions, available = 400)

        assertTrue(fold.labelled, "a bar with room for both chips keeps them labelled")
        assertEquals(2, fold.shown.size)
        assertTrue(fold.folded.isEmpty(), "and folds nothing")
    }

    /**
     * **Stage two: the words go before any action does.**
     *
     * The order matters more than either stage. A bar that folded an action while its neighbours still had
     * their labels would be spending room on a word it could have spent on a control.
     */
    @Test
    fun `the labels go first, and every action is still on the bar`() {
        val actions = listOf(action("Send", labelled = true), action("Clear", labelled = true))
        // Two chips need 122, two glyphs need 42. 80 is between them.
        val fold = fold(actions, available = 80)

        assertFalse(fold.labelled, "no room for the words")
        assertEquals(2, fold.shown.size, "but every action is still a control")
        assertTrue(fold.folded.isEmpty())
    }

    /**
     * **Stage three: the lowest-ranked action folds, not the rightmost.**
     *
     * Declared rather than positional, which is the whole reason [BarAction.foldRank] exists. Folding by
     * position would take a pane header's Close off before it took Move left, and the fold would have made
     * the pane unclosable at the width where closing it is what you want.
     */
    @Test
    fun `the lowest rank folds first, whatever its position`() {
        val actions =
            listOf(
                action("Move left", rank = 1),
                action("Clear", rank = 5),
                action("Scroll to bottom", rank = 3),
            )
        // **The first fold is free and the loop knows it.** Replacing one glyph with the ⋯ costs exactly
        // what the glyph cost, so a bar that has run out of room folds at least two actions or none —
        // which is why this leaves room for one glyph and the ⋯ (42px) rather than for two controls.
        val fold = fold(actions, available = 62, reserved = 20)

        assertEquals(
            listOf("Move left", "Scroll to bottom"),
            fold.folded.map { it.label },
            "rank 1 folds first and rank 3 next, so the rank 5 action is the one left on the bar",
        )
        assertEquals(listOf("Clear"), fold.shown.map { it.label })
    }

    /** Equal ranks fold right to left, so a bar that declares nothing still reads the way it is written. */
    @Test
    fun `equal ranks fold from the right`() {
        val actions = listOf(action("First"), action("Second"), action("Third"))
        val fold = fold(actions, available = 62, reserved = 20)

        assertEquals(listOf("Second", "Third"), fold.folded.map { it.label })
        assertEquals(listOf("First"), fold.shown.map { it.label }, "the leftmost is the last to go")
    }

    /**
     * **What a bar may never lose.** A dock's Hide and a pane's Close are the controls that put the thing
     * away, and a width at which they are gone is a width at which the window cannot be tidied.
     */
    @Test
    fun `a control that never folds survives a bar with no room at all`() {
        val actions =
            listOf(
                action("Clear", rank = 1),
                action("Expand all", rank = 2),
                BarAction("Hide", Icons.Default.Close, {}, "hide", neverFolds = true),
            )
        val fold = fold(actions, available = 44)

        assertEquals(listOf("Hide"), fold.shown.map { it.label }, "only the one that never folds is drawn")
        assertEquals(listOf("Clear", "Expand all"), fold.folded.map { it.label })
    }

    /** An action with no glyph cannot become one, so it is a menu row from the first fold rather than a gap. */
    @Test
    fun `an action with no glyph goes straight to the menu`() {
        val actions = listOf(action("Pin results", glyph = false), action("Clear"))
        val fold = fold(actions, available = 60)

        assertEquals(listOf("Clear"), fold.shown.map { it.label })
        assertEquals(listOf("Pin results"), fold.folded.map { it.label })
    }

    /**
     * **An unmeasured bar draws everything rather than folding to nothing.**
     *
     * The first frame of a `BoxWithConstraints` has no width. A bar that believed it would fold every
     * action into a ⋯ and then spring open on the second frame, which is a flicker on every pane that
     * opens.
     */
    @Test
    fun `a bar that has not been measured yet draws everything`() {
        val actions = listOf(action("Send", labelled = true), action("Clear", labelled = true))
        val fold = fold(actions, available = 0)

        assertTrue(fold.labelled)
        assertEquals(2, fold.shown.size)
        assertTrue(fold.folded.isEmpty())
    }

    /**
     * **The reserved room is genuinely reserved**, which is what makes "never draws half a control" true.
     *
     * The title's floor and the Hide are not the actions' to spend. Two bars of the same width with
     * different reservations fold differently, and the one with more reserved folds more.
     */
    @Test
    fun `reserving room for the title folds more of the bar, not less of the title`() {
        val actions = List(6) { action("Action $it", rank = it) }
        val loose = fold(actions, available = 200, reserved = 0)
        val tight = fold(actions, available = 200, reserved = 120)

        assertTrue(loose.folded.isEmpty(), "six glyphs fit in 200px with nothing reserved")
        assertTrue(tight.folded.isNotEmpty(), "and do not once the title and the Hide have their room")
        assertTrue(
            tight.shown.size < loose.shown.size,
            "the fold gives up actions for the reservation rather than drawing over it",
        )
    }

    /** Nothing is reachable at one width and gone at another: what leaves the bar is in the menu. */
    @Test
    fun `every action is either drawn or in the menu, at every width`() {
        val actions =
            listOf(
                action("Clear", labelled = true, rank = 1),
                action("Expand all", rank = 2),
                action("Collapse all", rank = 3),
                BarAction("Hide", Icons.Default.DeleteSweep, {}, "hide", neverFolds = true),
            )
        for (width in listOf(0, 20, 44, 64, 100, 160, 400)) {
            val fold = fold(actions, available = width, reserved = 20)
            assertEquals(
                actions.map { it.label }.toSet(),
                (fold.shown + fold.folded).map { it.label }.toSet(),
                "at ${width}px the bar lost an action altogether",
            )
            assertEquals(
                actions.size,
                fold.shown.size + fold.folded.size,
                "at ${width}px an action was drawn and listed at once",
            )
        }
    }

    /**
     * **A chip says nothing extra on hover; a glyph says the word it lost.**
     *
     * The tooltip rule, as the two strings a [BarAction] answers with. A tooltip repeating a visible label
     * is noise, and a glyph with no tooltip is learned by pressing it and watching.
     */
    @Test
    fun `the hover text is the word, the shortcut and whatever the word cannot carry`() {
        val plain = action("Clear messages")
        assertEquals("Clear messages", plain.hover)
        assertEquals(null, plain.chipHover, "a chip showing its word has nothing to add")

        val withHint = plain.copy(hint = "the panel will say it was cleared")
        assertEquals("Clear messages — the panel will say it was cleared", withHint.hover)
        assertEquals(withHint.hover, withHint.chipHover, "a chip with a consequence says it at every width")

        val withShortcut = plain.copy(shortcut = "⌘F")
        assertEquals("Clear messages · ⌘F", withShortcut.hover)

        val refused = plain.copy(enabled = false, disabledReason = "nothing to clear yet")
        assertEquals("Clear messages — nothing to clear yet", refused.hover, "a dark control carries its reason")
    }
}
