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
    /**
     * The load set the editor was last on, by name, so opening Load sets… comes back to what was being
     * worked on rather than to whatever sorts first.
     *
     * Here for the same reason as the rest of this file: it is not a setting anybody edits on a settings
     * page, and losing it costs one click. A name that no longer answers to a file is simply inert.
     */
    val lastLoadSet: String? = null,
    /**
     * The Load run dialog's last size in dp, or null until somebody resizes it.
     *
     * Here rather than in [AppSettings] for the same reason as the rest of this file: a window size is not
     * a setting anybody edits on a settings page, and losing it costs one drag of a corner. The dialog
     * opens at [LOAD_DIALOG_WIDTH] by [LOAD_DIALOG_HEIGHT] until then.
     */
    val loadDialogWidth: Float? = null,
    val loadDialogHeight: Float? = null,
    /**
     * The Load sets dialog's last size in dp, beside the run dialog's and for the same reason.
     *
     * Its own pair rather than the run dialog's: the editor is two panes wide and the run dialog is one
     * column, so a size dragged to fit the saved-set list is the wrong size for a single burst.
     */
    val loadSetsDialogWidth: Float? = null,
    val loadSetsDialogHeight: Float? = null,
)

/** What the Load run dialog opens at before anybody has resized it. Twenty percent up from 640 by 580. */
const val LOAD_DIALOG_WIDTH = 780f

/** See [LOAD_DIALOG_WIDTH]. Ten rows and two folds fit at this height without the footer being scrolled to. */
const val LOAD_DIALOG_HEIGHT = 700f

/** The saved-set list and the set beside it: wider than the run dialog because it holds two panes. */
const val LOAD_SETS_DIALOG_WIDTH = 820f

/** Four sections and three phase rows fit at this height, which 620 could not do without scrolling. */
const val LOAD_SETS_DIALOG_HEIGHT = 700f

/**
 * **Which of the Shape segment's three options a load run was last asked for.**
 *
 * Its own type rather than the plan's [com.knapsack.fixtool.model.load.LoadShape], because this says which
 * control was on and not what will be issued: a shape carries a count, a rate or a ceiling, and the whole
 * point of remembering the choice separately is that the other two options keep the numbers they were
 * given while they are not the one selected.
 */
@Serializable
enum class LoadShapeChoice {
    BURST,
    RATE,

    /** Fires as an earlier phase of a set is answered, so only a phase of a set can ever be this. */
    REACTIVE,
}

/**
 * The load dialog's last-used shape for one profile, so reopening it does not reset to 4,000 / 500 / 60s.
 *
 * Per profile, not global: the burst somebody fires at a five-lane RFQ client and the ten-minute rate run
 * they point at a venue are two different habits, and one of them was overwriting the other every time.
 */
@Serializable
data class LoadRunDefaults(
    /**
     * **False = a sustained rate**, which is every shape there was before a phase could react to another.
     *
     * Still written and still read, because it is the whole migration: a file saved by an older FixTool
     * carries this and no [shapeKind], and [shape] falls back to it so a saved rate opens as a rate rather
     * than being reset to a burst. A file this FixTool writes carries both, so a downgrade is owed the same
     * courtesy. Use [of] rather than setting the two by hand, or they can disagree.
     */
    val burst: Boolean = true,
    val count: String = "4000",
    val rate: String = "500",
    /** The rate's duration, as typed: `10m`, `1h 5m`. */
    val forText: String = "10m",
    val settle: String = "60s",
    /** Seed names and values in the order the rows were shown. A name with no value seeds nothing. */
    val seed: List<List<String>> = listOf(listOf("run", "")),
    /**
     * Null in a file written before there were three options, which is what [burst] then answers.
     *
     * Last rather than beside [burst], so that adding it broke nothing that names these six in order.
     */
    val shapeKind: LoadShapeChoice? = null,
) {
    /** The option the Shape segment opens on: what the file says, or what a file older than it meant. */
    val shape: LoadShapeChoice
        get() = shapeKind ?: if (burst) LoadShapeChoice.BURST else LoadShapeChoice.RATE

    companion object {
        /**
         * **One choice, both keys**, so [burst] and [shapeKind] can never say different things.
         *
         * A reactive choice writes [burst] as true rather than false, because an older FixTool reading it
         * offers a count with this file's own count already in the field, which is a closer answer than a
         * rate the phase never asked for. It is a courtesy and nothing more: only a phase of a set can be
         * reactive and only a single run saves defaults, so no file should ever carry the pair.
         */
        @Suppress("LongParameterList")
        fun of(
            shape: LoadShapeChoice,
            count: String = "4000",
            rate: String = "500",
            forText: String = "10m",
            settle: String = "60s",
            seed: List<List<String>> = listOf(listOf("run", "")),
        ): LoadRunDefaults =
            LoadRunDefaults(
                burst = shape != LoadShapeChoice.RATE,
                shapeKind = shape,
                count = count,
                rate = rate,
                forText = forText,
                settle = settle,
                seed = seed,
            )
    }
}
