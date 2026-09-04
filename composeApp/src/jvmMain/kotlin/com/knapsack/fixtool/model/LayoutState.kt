package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable

/**
 * The workbench layout, persisted so the app reopens the way the user left it — panel sizes, which panels are
 * open, and the bottom-dock heights. This is *view state*, not settings: it is machine-written as the user
 * drags and toggles, so it lives in its own `layout.json` (via `LayoutStateService`), the sibling of the
 * rail's `scenario_view.json`, and never in `app_settings.json` where every field must be a user-editable
 * setting on a settings page.
 *
 * Sizes are the fraction of the window a side panel takes, or a dp height for the bottom docks. Two things are
 * deliberately absent: the session view mode lives in [AppSettings.defaultLayout] (a real setting, which
 * already seeded the initial layout), and the search-results pane — it is tied to having pinned results, so
 * reopening an empty one on launch would be noise.
 */
@Serializable
data class LayoutState(
    val railRatio: Float = 0.18f,
    val editorRatio: Float = 0.28f,
    val detailRatio: Float = 0.2f,
    val connectionRatio: Float = 0.2f,
    val latencyRatio: Float = 0.25f,
    val orderBookRatio: Float = 0.34f,
    val searchHeightDp: Float = 200f,
    val terminalHeightDp: Float = 320f,
    val scenarioDockHeightDp: Float = 340f,
    val showScenariosRail: Boolean = false,
    val showDetailPanel: Boolean = false,
    val showMessageEditor: Boolean = false,
    val showConnectionPanel: Boolean = false,
    val showLatencyPanel: Boolean = false,
    val showOrderBookPanel: Boolean = false,
    val terminalVisible: Boolean = false,
    val terminalMinimized: Boolean = false,
    val scenarioDockMinimized: Boolean = false,
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
)
