package com.knapsack.fixtool.service

/**
 * **What "search" means in a field grid** — the predicate, in one place, for the three surfaces that own one.
 *
 * The message editor invented this: a query matches a field by its **tag**, its **dictionary name**, or its
 * **value**, case-insensitively, because those are the three things an author knows a field by and they never
 * know in advance which one is on the tip of their tongue. Typing `44` and typing `price` find the same row.
 *
 * It lives here rather than beside any one grid because the chrome is the cheap half. Three copies of a search
 * *box* is a tidiness problem; three copies of the *rule* is a correctness one — the day someone teaches one
 * grid to match a field's enum description ("BUY" finding `54=1`), the other two quietly disagree about what
 * the same query means, and the author learns not to trust the feature anywhere.
 */
object FieldSearch {
    /**
     * Does the field described by [tag]/[name]/[value] answer [query]?
     *
     * A blank query matches **nothing**, not everything. Search here highlights rather than filters, so
     * "matches everything" would paint every row the moment the box was focused and emptied.
     */
    fun matches(query: String, tag: Int?, name: String?, value: String?): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return false
        return tag?.toString()?.contains(needle) == true ||
            name?.lowercase()?.contains(needle) == true ||
            value?.lowercase()?.contains(needle) == true
    }

    /** The same question asked of an already-stringified tag, for grids that hold one (the message editor). */
    fun matches(query: String, tag: String, name: String?, value: String?): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return false
        return tag.lowercase().contains(needle) ||
            name?.lowercase()?.contains(needle) == true ||
            value?.lowercase()?.contains(needle) == true
    }
}
