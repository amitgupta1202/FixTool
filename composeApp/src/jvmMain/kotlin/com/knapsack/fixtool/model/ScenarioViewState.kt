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
    /**
     * What the load run dialog was last asked for, per profile id.
     *
     * Here rather than in [AppSettings] for the same reason as everything else in this file: it is not a
     * setting anybody edits on a settings page, and the `no setting is invisible` test would demand a
     * control for each of these five fields. Losing it costs one retyped count.
     */
    val loadRuns: Map<String, LoadRunDefaults> = emptyMap(),
)

/**
 * The load dialog's last-used shape for one profile, so reopening it does not reset to 4,000 / 500 / 60s.
 *
 * Per profile, not global: the burst somebody fires at a five-lane RFQ client and the ten-minute rate run
 * they point at a venue are two different habits, and one of them was overwriting the other every time.
 */
@Serializable
data class LoadRunDefaults(
    /** False = a sustained rate. Stored as a flag rather than a shape so a rate's count survives a burst. */
    val burst: Boolean = true,
    val count: String = "4000",
    val rate: String = "500",
    /** The rate's duration, as typed: `10m`, `1h 5m`. */
    val forText: String = "10m",
    val settle: String = "60s",
    /** Seed names and values in the order the rows were shown. A name with no value seeds nothing. */
    val seed: List<List<String>> = listOf(listOf("run", "")),
)
