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
)
