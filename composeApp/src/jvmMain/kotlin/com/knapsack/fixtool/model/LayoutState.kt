package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable

/**
 * The workbench layout, persisted so the app reopens the way the user left it: panel sizes, which window is
 * showing in each stripe group, and the bottom dock's height. This is *view state*, not settings: it is
 * machine-written as the user drags and toggles, so it lives in its own `layout.json` (via
 * `LayoutStateService`), the sibling of the rail's `scenario_view.json`, and never in `app_settings.json`
 * where every field must be a user-editable setting on a settings page.
 *
 * Sizes are the fraction of the window a side panel takes, or a dp height for the bottom dock. One thing is
 * deliberately absent: the session view mode lives in [AppSettings.defaultLayout] (a real setting, which
 * already seeded the initial layout).
 *
 * **Three selections, not eight flags.** A stripe group shows one window at a time (see `StripeGroup`), so
 * the layout records which one rather than a boolean per window, which is also what stops a restore
 * reopening four right-hand panels onto a grid that then has no room left. The selections are the
 * `ToolWindow` name as a string, and the bottom one a `BottomTab` key, so neither survives as an ordinal
 * that a reordering could silently repoint.
 */
@Serializable
data class LayoutState(
    val railRatio: Float = 0.18f,
    val editorRatio: Float = 0.28f,
    val detailRatio: Float = 0.2f,
    val connectionRatio: Float = 0.2f,
    val latencyRatio: Float = 0.25f,
    val orderBookRatio: Float = 0.34f,
    /** The window showing in the left stripe's top group, as a `ToolWindow` name, or null for none. */
    val leftWindow: String? = null,
    /** The window showing in the right stripe, as a `ToolWindow` name, or null for none. */
    val rightWindow: String? = null,
    /** The tab the bottom dock is showing, as a `BottomTab` key, or null when the dock is hidden. */
    val bottomTab: String? = null,
    /** How tall the dock was left. Null means "never resized", which is the dock's own default. */
    val bottomHeightDp: Float? = null,
    /**
     * **What panels are open**, in the shape this file had before the stripe groups.
     *
     * Read once, on the first launch after the upgrade, and written back as null, see [migrated]. Nullable
     * so "absent" and "false" are different facts: a file already in the new shape must not be re-migrated
     * into reopening a panel its owner closed.
     */
    @Deprecated("Migrated to leftWindow on first read. See migrated().")
    val showScenariosRail: Boolean? = null,
    @Deprecated("Migrated to rightWindow on first read. See migrated().")
    val showDetailPanel: Boolean? = null,
    @Deprecated("Migrated to leftWindow on first read. See migrated().")
    val showMessageEditor: Boolean? = null,
    @Deprecated("Migrated to rightWindow on first read. See migrated().")
    val showConnectionPanel: Boolean? = null,
    @Deprecated("Migrated to rightWindow on first read. See migrated().")
    val showLatencyPanel: Boolean? = null,
    @Deprecated("Migrated to rightWindow on first read. See migrated().")
    val showOrderBookPanel: Boolean? = null,
    @Deprecated("Migrated to bottomTab on first read. See migrated().")
    val showTracePanel: Boolean? = null,
    @Deprecated("Migrated to bottomTab on first read. See migrated().")
    val terminalVisible: Boolean? = null,
    @Deprecated("Retired with the minimise chevron: hiding the dock is the minimise now.")
    val terminalMinimized: Boolean? = null,
    @Deprecated("Retired with the minimise chevron: hiding the dock is the minimise now.")
    val scenarioDockMinimized: Boolean? = null,
    @Deprecated("Migrated to bottomHeightDp on first read. See migrated().")
    val terminalHeightDp: Float? = null,
    @Deprecated("Migrated to bottomHeightDp on first read. See migrated().")
    val scenarioDockHeightDp: Float? = null,
    @Deprecated("Retired: the search results are a tab in the one bottom dock, at the dock's height.")
    val searchHeightDp: Float? = null,
    /**
     * **Which panes the user has minimized**, keyed `"<profileId>#<slot>"`.
     *
     * A map of decisions rather than a list of names, so "restored" is recordable. A venue's pane starts
     * minimized, so its absence from a list would be indistinguishable from a user who restored it and
     * wants it to stay restored — an explicit `false` says the difference.
     *
     * Keyed by profile and slot and not by [FixMessageSession.id], which is a fresh UUID every run.
     * Venue *client* panes are absent by design: they are created by a counterparty logging on, and do
     * not outlive the process for a saved key to name.
     */
    val paneMinimized: Map<String, Boolean> = emptyMap(),
    /**
     * The run configuration the ▶ runs, as `LOADSET:<name>` or `RUNSET:<name>`.
     *
     * View state and not a setting, for the reason this file's header gives: it is machine-written as the
     * user picks from a menu, not edited on a settings page. Null means nothing has been picked yet, and
     * the widget falls back to the most recent run's configuration. A name that no longer answers to a
     * saved set falls back the same way rather than being pruned, because a set can come back with the
     * branch that defined it.
     */
    val selectedRunConfiguration: String? = null,
    /**
     * The project workspace open at last exit, so the app comes back where it was left. Empty means
     * the installation's own directory, which is where a fresh install keeps everything.
     *
     * Here and not in [AppSettings] for the reason this file's header gives: every field of
     * app_settings.json must be a setting somebody can edit on a settings page, and which workspace
     * was open is not edited, it is remembered.
     */
    val openWorkspace: String = "",
    /**
     * Workspaces opened before, newest first.
     *
     * A path that no longer exists is filtered on read rather than pruned on write: a folder on a
     * volume that is not mounted has not been deleted, and should come back when the volume does.
     */
    val recentWorkspaces: List<String> = emptyList(),
) {
    /** The dock's height, with the default applied for a layout that has never been resized. */
    val resolvedBottomHeightDp: Float get() = bottomHeightDp ?: DEFAULT_BOTTOM_HEIGHT_DP

    /**
     * **This layout in the stripe-group shape, migrating a file written before the groups existed.**
     *
     * One-time and one-way: the old booleans are read, the first true one in stripe order per group becomes
     * that group's window, and every old field is cleared so the next launch has nothing left to migrate.
     * "First true in stripe order" is the only honest answer to a file that says four right-hand panels were
     * open, which is precisely the state the groups exist to make unreachable.
     *
     * The window names are spelled here rather than read off `ToolWindow`: that enum is a UI type, and a
     * model that imported it to name a string would be the wrong direction for a dependency to run in.
     */
    @Suppress("DEPRECATION")
    fun migrated(): LayoutState =
        copy(
            leftWindow = leftWindow ?: firstOpen("EDITOR" to showMessageEditor, "SCENARIOS" to showScenariosRail),
            rightWindow =
                rightWindow ?: firstOpen(
                    "DETAIL" to showDetailPanel,
                    "CONNECTION" to showConnectionPanel,
                    "ORDER_BOOK" to showOrderBookPanel,
                    "LATENCY" to showLatencyPanel,
                ),
            bottomTab = bottomTab ?: firstOpen("TERMINAL" to terminalVisible, "TRACE" to showTracePanel),
            // The taller of the two docks that became one, because the dock now holds what both held and
            // the larger of the two sizes is the one that fits everything its owner chose to see.
            bottomHeightDp = bottomHeightDp ?: listOfNotNull(terminalHeightDp, scenarioDockHeightDp).maxOrNull(),
            showScenariosRail = null,
            showDetailPanel = null,
            showMessageEditor = null,
            showConnectionPanel = null,
            showLatencyPanel = null,
            showOrderBookPanel = null,
            showTracePanel = null,
            terminalVisible = null,
            terminalMinimized = null,
            scenarioDockMinimized = null,
            terminalHeightDp = null,
            scenarioDockHeightDp = null,
            searchHeightDp = null,
        )

    private fun firstOpen(vararg candidates: Pair<String, Boolean?>): String? =
        candidates.firstOrNull { it.second == true }?.first

    companion object {
        /** How tall the dock opens when nothing has ever resized it. Mirrored by `BOTTOM_DOCK_DEFAULT_HEIGHT_DP`. */
        const val DEFAULT_BOTTOM_HEIGHT_DP = 340f
    }
}
