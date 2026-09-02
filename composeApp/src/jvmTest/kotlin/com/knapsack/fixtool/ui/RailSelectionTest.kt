package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.ScenarioSort
import com.knapsack.fixtool.model.scenario.Scenario
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The rail's multi-select, tested where it is pure** — no Compose tree, because the claims worth pinning
 * are all about *which rows a gesture reaches*, and every one of them is decided before a pixel is drawn.
 *
 * The recurring trap this file exists for: the rail's drawn order is not the model's. It is filtered,
 * sorted, split into ★ Favourites and All, and either section may be folded shut. A range or a select-all
 * that reasons about `viewModel.scenarios` sweeps in rows the author cannot see — the exact failure that
 * makes people stop trusting a checkbox.
 */
class RailSelectionTest {
    private val order = listOf("a", "b", "c", "d", "e")

    @Test
    fun `a pick anchors, so the next shift-click has somewhere to measure from`() {
        val one = RailSelection().toggle("b")
        assertEquals(setOf("b"), one.ids)
        assertEquals("b", one.anchor)
        // An unpick anchors too, as it does in every list of this shape.
        val none = one.toggle("b")
        assertTrue(none.isEmpty)
        assertEquals("b", none.anchor)
    }

    @Test
    fun `shift-click takes the span between the anchor and the row, inclusive`() {
        val out = RailSelection().toggle("b").extendTo("d", order)
        assertEquals(setOf("b", "c", "d"), out.ids)
        assertEquals("d", out.anchor, "the range's far end becomes the anchor, so ranges chain")
    }

    @Test
    fun `a shift-click upwards is the same span read the other way`() {
        val out = RailSelection().toggle("d").extendTo("b", order)
        assertEquals(setOf("b", "c", "d"), out.ids)
    }

    @Test
    fun `a range widens a selection rather than replacing it`() {
        // Pick 'a', then range b..c: 'a' survives. Replacing would silently drop the picks made before it.
        val out = RailSelection().toggle("a").toggle("b").extendTo("c", order)
        assertEquals(setOf("a", "b", "c"), out.ids)
    }

    @Test
    fun `the very first click is not a range`() {
        val out = RailSelection().extendTo("c", order)
        assertEquals(setOf("c"), out.ids, "with no anchor a shift-click is an ordinary pick")
    }

    /**
     * The one that matters: the anchor can leave the screen between the two clicks — its section folded, the
     * filter retyped, the scenario deleted. Ranging from a row that is no longer drawn is how a selection
     * quietly acquires rows the author never saw.
     */
    @Test
    fun `an anchor that has left the drawn order falls back to a plain pick`() {
        val stale = RailSelection().toggle("z")
        val out = stale.extendTo("c", order)
        assertEquals(setOf("z", "c"), out.ids, "'c' alone is added — nothing between an off-screen row and it")
        assertEquals("c", out.anchor)
    }

    @Test
    fun `the master tick takes everything drawn, then lets it go`() {
        val all = RailSelection().toggleAll(order)
        assertEquals(order.toSet(), all.ids)
        assertTrue(all.toggleAll(order).isEmpty)
    }

    @Test
    fun `a half-picked list fills up rather than emptying`() {
        val out = RailSelection().toggle("b").toggleAll(order)
        assertEquals(order.toSet(), out.ids)
    }

    /**
     * Select-all is scoped to what is drawn in *both* directions. With a filter typed, "all" means all of
     * these — and clearing it drops only these. Picks made under a different filter are not collateral.
     */
    @Test
    fun `select-all and its undo reach only the rows on screen`() {
        val filtered = listOf("b", "c")
        val out = RailSelection(ids = setOf("e")).toggleAll(filtered)
        assertEquals(setOf("b", "c", "e"), out.ids)
        assertEquals(setOf("e"), out.toggleAll(filtered).ids, "'e' was never on screen, so it is not dropped")
    }

    @Test
    fun `coverage is the master tick's three faces, over the drawn rows only`() {
        assertEquals(RailCoverage.NONE, RailSelection().coverage(order))
        assertEquals(RailCoverage.SOME, RailSelection(setOf("b")).coverage(order))
        assertEquals(RailCoverage.ALL, RailSelection(order.toSet()).coverage(order))
        // Off-screen picks do not make the visible rows look picked.
        assertEquals(RailCoverage.NONE, RailSelection(setOf("z")).coverage(order))
        assertEquals(RailCoverage.ALL, RailSelection(setOf("b", "c")).coverage(listOf("b", "c")))
        assertEquals(RailCoverage.NONE, RailSelection(setOf("b")).coverage(emptyList()), "nothing drawn, nothing to untick")
    }

    @Test
    fun `a deleted scenario leaves the selection, and takes the anchor with it`() {
        val pruned = RailSelection(setOf("a", "b"), anchor = "b").prunedTo(setOf("a"))
        assertEquals(setOf("a"), pruned.ids)
        assertNull(pruned.anchor, "an anchor pointing at a deleted row would range from nowhere")
        val untouched = RailSelection(setOf("a"), anchor = "a")
        assertTrue(untouched === untouched.prunedTo(setOf("a", "b")), "nothing to prune, nothing recomposed")
    }

    // --- railOrder: what the rail is actually drawing, which is what every gesture above is scoped to ---

    private fun sc(id: String) = Scenario(id = id, name = id)

    private fun sections(favourites: List<String>, others: List<String>) =
        railSections((favourites + others).map { sc(it) }, favourites.toSet(), ScenarioSort.NAME) { null }

    @Test
    fun `the drawn order is the pinned run, then favourites, then the rest`() {
        val out = railOrder("pin", sections(listOf("f1", "f2"), listOf("o1", "o2")), emptySet())
        assertEquals(listOf("pin", "f1", "f2", "o1", "o2"), out)
    }

    @Test
    fun `a folded section is off screen, so no range or select-all can reach it`() {
        val s = sections(listOf("f1"), listOf("o1", "o2"))
        assertEquals(listOf("o1", "o2"), railOrder(null, s, setOf("favourites")))
        assertEquals(listOf("f1"), railOrder(null, s, setOf("all")))
        assertTrue(railOrder(null, s, setOf("favourites", "all")).isEmpty())
    }

    @Test
    fun `with nothing starred there are no sections, and folding one means nothing`() {
        // The rail draws a single flat list in this case, under no header there is no header to fold.
        val s = sections(emptyList(), listOf("o1", "o2"))
        assertEquals(listOf("o1", "o2"), railOrder(null, s, setOf("all", "favourites")))
    }
}
