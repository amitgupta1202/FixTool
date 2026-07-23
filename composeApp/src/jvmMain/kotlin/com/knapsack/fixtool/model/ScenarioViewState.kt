package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable

/** How the rail orders scenarios within each section. Persisted by name in `scenario_view.json`. */
@Serializable
enum class ScenarioSort {
    /** A→Z by name — the default, and the calmest reading of a curated suite. */
    NAME,

    /** Newest touch first — "the one I just edited floats up". Uses the file's mtime. */
    RECENTLY_MODIFIED,

    /** Oldest first — the order the suite was built. Uses `Scenario.createdAt`, mtime as the fallback. */
    CREATED,
}

/**
 * The rail's local, per-user view chrome — how *this* machine is looking at the scenario list right now.
 *
 * Deliberately **not** in [AppSettings]: none of it is a setting an author edits on a settings page (the
 * `no setting is invisible` test would demand a control for each), and none of it may travel with a shared
 * scenario file. Lost or corrupt, it costs nothing — every field is regenerable and defaulted, and a
 * favourite that names a scenario since deleted is simply inert (it matches nothing).
 */
@Serializable
data class ScenarioViewState(
    val sortMode: ScenarioSort = ScenarioSort.NAME,
    /**
     * Ids of the scenarios the author has starred. Kept here rather than on the [scenario][AppSettings]
     * file so a star is weightless: toggling one never rewrites — or reformats, or re-stamps ids into — a
     * scenario file, and never shows up as a diff in a shared scenarios folder.
     */
    val favouriteIds: Set<String> = emptySet(),
    /** Which rail sections are folded shut — a subset of `{"favourites", "all"}`. */
    val collapsedSections: Set<String> = emptySet(),
)
