package com.knapsack.fixtool.service

/**
 * **The relation itself, computed once and shared by everything that draws it.**
 *
 * [Conversations] groups one session's log; [Traces] groups every session's at once. They are the same
 * question asked over a different list, and the answer must not depend on which one asked: a pane's
 * conversation *is* its trace's slice, and a reader who sees them disagree has no way to tell which one
 * is lying. So the union-find and the pass that feeds it live here, and both callers get their answer
 * from this function — rather than two derivations kept equal by review.
 *
 * **What an edge is.** Two messages are joined when they share a correlation-id *value*. Every value on
 * one message is joined to every other value on it, and that single rule is what makes the chain
 * transitive: a Quote carrying both `QuoteReqID` and its own `QuoteID` is what links the RFQ to the
 * order later placed against the quote, with no `131 → 117 → 11` rule written anywhere.
 *
 * **What is not an edge.** Anything this function is not handed. It takes ids, not messages, precisely
 * so the decision about *which tags are correlation ids* stays in one place ([Minting.isCorrelationId])
 * and cannot be re-litigated here. Joining on any equal value would put every `54=1` message in one
 * blob — the false-correlation defect capture already carries a scar from.
 *
 * **Nothing is hidden.** A message whose id list is empty is not dropped and not forced into a
 * component; it comes back in [Components.ungrouped], which every caller must show and count.
 */
internal object CorrelationComponents {
    /**
     * One pass's answer, in positions into the list of id-lists that was handed in.
     *
     * Positions rather than messages because both callers address something different — one a flat
     * session log, the other a merged cross-session order — and because the caller already holds the
     * messages. Handing them back would be a copy of what it just passed down.
     */
    class Components(
        /**
         * Each component's member positions, ascending within a component, components in the order
         * they opened.
         *
         * "The order they opened" falls out rather than being sorted for: the map below preserves
         * first-seen insertion order, and the first message of a component is the one that created its
         * entry. A sort by timestamp would be worse, not better — it would reorder same-millisecond
         * arrivals whose real order the caller knows and a clock does not.
         */
        val components: List<List<Int>>,
        /** Positions carrying no correlation id at all, in the order given. */
        val ungrouped: List<Int>,
    )

    fun of(idsPerMessage: List<List<Pair<Int, String>>>): Components {
        val union = Union()
        for (ids in idsPerMessage) {
            val first = ids.firstOrNull()?.second ?: continue
            ids.forEach { (_, value) -> union.join(first, value) }
        }

        val ungrouped = mutableListOf<Int>()
        val byRoot = linkedMapOf<String, MutableList<Int>>()
        idsPerMessage.forEachIndexed { index, ids ->
            val root = ids.firstOrNull()?.second?.let(union::rootOf)
            if (root == null) {
                ungrouped += index
            } else {
                byRoot.getOrPut(root) { mutableListOf() } += index
            }
        }
        return Components(byRoot.values.toList(), ungrouped)
    }

    /** Union-find over id values, with path compression. Small, private, and the whole algorithm. */
    private class Union {
        private val parent = mutableMapOf<String, String>()

        fun rootOf(value: String): String {
            var root = parent.getOrPut(value) { value }
            while (root != parent.getValue(root)) root = parent.getValue(root)
            var walk = value
            while (walk != root) {
                val next = parent.getValue(walk)
                parent[walk] = root
                walk = next
            }
            return root
        }

        fun join(a: String, b: String) {
            val ra = rootOf(a)
            val rb = rootOf(b)
            if (ra != rb) parent[rb] = ra
        }
    }
}
