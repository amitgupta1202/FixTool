package com.knapsack.fixtool.ui

/**
 * **The rail's transient pick** — which scenarios are ticked for "Run selected…", and the row a shift-click
 * measures its range from.
 *
 * A star is a lasting opinion about a scenario: it persists, and it moves the row into its own section. A
 * pick is "these, now" — it lives and dies with the rail. That much was already true when a pick was a bare
 * `Set<String>`; what this type adds is the **anchor**, without which there is no such thing as a range.
 *
 * Every operation here is defined against an *order* the caller supplies — the ids the rail is currently
 * drawing, top to bottom (see `railOrder`). That is deliberate and it is the whole correctness argument of
 * this file: the rail's order is not the model's. It is filtered, sorted, split into ★ Favourites and All,
 * and either of those sections may be folded shut. A range computed over `viewModel.scenarios` would sweep
 * in rows the author cannot see and did not mean, which is worse than having no range at all.
 */
data class RailSelection(
    val ids: Set<String> = emptySet(),
    /** The last row picked by hand — where a shift-click measures from. Null until something is picked. */
    val anchor: String? = null,
) {
    val size: Int get() = ids.size
    val isEmpty: Boolean get() = ids.isEmpty()
    val isNotEmpty: Boolean get() = ids.isNotEmpty()

    operator fun contains(id: String): Boolean = id in ids

    /** Pick or unpick one row, and make it the anchor — an unpick anchors too, as it does in every list. */
    fun toggle(id: String): RailSelection = RailSelection(if (id in ids) ids - id else ids + id, anchor = id)

    /**
     * Shift-click: **add** every row from the anchor to [id] inclusive, in the drawn [order].
     *
     * Adds rather than replaces, like the message grid's range beside it — a range is how you widen a
     * selection, and replacing would silently drop the picks made before it.
     *
     * Two ways there is no range to draw, both of which fall back to a plain toggle rather than doing
     * nothing: no anchor yet (the first click of a session is not a range), and an anchor that has left the
     * drawn order — its section folded shut, the filter changed under it, or the scenario deleted. A stale
     * anchor is the interesting one: silently ranging from a row that is no longer on screen is exactly the
     * "it selected things I never saw" that makes people distrust multi-select.
     */
    fun extendTo(id: String, order: List<String>): RailSelection {
        val from = order.indexOf(anchor ?: return toggle(id))
        val to = order.indexOf(id)
        if (from < 0 || to < 0) return toggle(id)
        val span = if (from <= to) from..to else to..from
        return RailSelection(ids + order.slice(span), anchor = id)
    }

    /**
     * The master tick, and Cmd/Ctrl+A: everything on screen picked ⇒ let it go; anything less ⇒ take it all.
     *
     * Scoped to [order] in both directions. With a filter typed, "select all" means all *of these*, and
     * clearing it drops only those — picks made under a different filter are not collateral.
     */
    fun toggleAll(order: List<String>): RailSelection =
        if (order.isNotEmpty() && ids.containsAll(order)) {
            RailSelection(ids - order.toSet(), anchor = null)
        } else {
            RailSelection(ids + order, anchor = order.lastOrNull() ?: anchor)
        }

    fun clear(): RailSelection = RailSelection()

    /** A scenario deleted while picked would otherwise stay in the count and run as nothing. */
    fun prunedTo(alive: Set<String>): RailSelection =
        if (ids.all { it in alive } && (anchor == null || anchor in alive)) {
            this
        } else {
            RailSelection(ids intersect alive, anchor?.takeIf { it in alive })
        }

    /** What the master tick draws: none, some, or all of what is on screen. */
    fun coverage(order: List<String>): RailCoverage =
        when {
            order.isEmpty() || order.none { it in ids } -> RailCoverage.NONE
            order.all { it in ids } -> RailCoverage.ALL
            else -> RailCoverage.SOME
        }
}

/** How much of the drawn list a selection covers — the three faces of the master tick. */
enum class RailCoverage { NONE, SOME, ALL }
