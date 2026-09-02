package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.ScenarioSort
import com.knapsack.fixtool.model.scenario.Scenario

/** The rail's list, split into the pinned-to-top ★ favourites and everything else, each already ordered. */
data class RailSections(
    val favourites: List<Scenario>,
    val others: List<Scenario>,
)

/**
 * Computes what the rail draws: the given (already text-filtered) scenarios split into ★ favourites and the
 * rest, each sorted by [sort]. Pure on purpose — the ordering rules, and the mtime fallback for a scenario
 * with no `createdAt`, are unit-testable here without a Compose tree.
 *
 * Every comparator is *total* (ties broken by name, then id) so the list order is stable frame to frame —
 * a partial order would let two same-named scenarios swap places on any recomposition.
 *
 * @param modifiedAt the file's mtime for a scenario id, or null when unknown.
 */
fun railSections(
    scenarios: List<Scenario>,
    favouriteIds: Set<String>,
    sort: ScenarioSort,
    modifiedAt: (String) -> Long?,
): RailSections {
    val comparator: Comparator<Scenario> =
        when (sort) {
            ScenarioSort.NAME ->
                compareBy({ it.name.lowercase() }, { it.id })
            ScenarioSort.RECENTLY_MODIFIED ->
                // Newest touch first; a scenario whose file has no mtime sorts last, then by name.
                compareByDescending<Scenario> { modifiedAt(it.id) ?: Long.MIN_VALUE }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.id }
            ScenarioSort.CREATED ->
                // Oldest first — the order the suite was built. No birth time falls back to the file's mtime;
                // a scenario with neither sorts last, since it cannot be honestly placed in time.
                compareBy<Scenario>({ it.createdAt ?: modifiedAt(it.id) ?: Long.MAX_VALUE }, { it.name.lowercase() }, { it.id })
        }
    val (favourites, others) = scenarios.partition { it.id in favouriteIds }
    return RailSections(favourites.sortedWith(comparator), others.sortedWith(comparator))
}

/**
 * **The ids the rail is drawing, top to bottom** — the one order a shift-range and a select-all are allowed
 * to reason about.
 *
 * It is not the model order and it is not [RailSections] read end to end: the scenario that just ran is
 * lifted to the top ([pinned], and it is already absent from both sections), the ★ Favourites section only
 * exists once something is starred, and either section may be folded shut in [collapsedSections] — a folded
 * section's rows are off screen, so they are not in the order, so no range or select-all can reach them.
 */
fun railOrder(pinned: String?, sections: RailSections, collapsedSections: Set<String>): List<String> =
    buildList {
        pinned?.let { add(it) }
        if (sections.favourites.isNotEmpty()) {
            if ("favourites" !in collapsedSections) sections.favourites.forEach { add(it.id) }
            if ("all" !in collapsedSections) sections.others.forEach { add(it.id) }
        } else {
            sections.others.forEach { add(it.id) }
        }
    }
