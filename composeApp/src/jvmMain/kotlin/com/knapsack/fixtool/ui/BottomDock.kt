// Compose UI: dense composable calls read best on one line; multi-arg composables are idiomatic.
@file:Suppress("MaxLineLength")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.service.TraceRows
import com.knapsack.fixtool.ui.terminal.TerminalPanel
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import com.knapsack.fixtool.viewmodel.TraceRendering
import java.io.File

/**
 * **What the one bottom dock is showing.**
 *
 * Everything that opens across the foot of the window is a tab in here: the terminal, the Ledger, pinned
 * search results and every open scenario document. One height, one tab strip, one frame, so a load-run
 * report and a scenario editor land where the terminal lands, at the size the terminal was left at.
 *
 * Persisted as a string ([key]) rather than an ordinal, so a reordering of these types never silently
 * reopens the wrong tab out of `layout.json`.
 */
sealed interface BottomTab {
    /** How the tab is written into `layout.json`. */
    val key: String

    data object Terminal : BottomTab {
        override val key: String get() = "TERMINAL"
    }

    data object Trace : BottomTab {
        override val key: String get() = "TRACE"
    }

    data object SearchResults : BottomTab {
        override val key: String get() = "SEARCH_RESULTS"
    }

    /** One open document, by [ScenarioDoc.id]. */
    data class Document(
        val id: String,
    ) : BottomTab {
        override val key: String get() = "$DOC_PREFIX$id"
    }

    companion object {
        private const val DOC_PREFIX = "DOC:"

        /** The stored key back to a tab, or null for a key this build does not know. */
        fun parse(key: String?): BottomTab? =
            when {
                key == null -> null
                key == Terminal.key -> Terminal
                key == Trace.key -> Trace
                key == SearchResults.key -> SearchResults
                key.startsWith(DOC_PREFIX) -> key.removePrefix(DOC_PREFIX).takeIf { it.isNotBlank() }?.let(::Document)
                else -> null
            }
    }
}

/**
 * **The one dock at the foot of the window.**
 *
 * It replaces two docks that each had a frame, a title row and a minimise chevron of their own, and did not
 * know about each other: the terminal was as wide as the centre column, the scenario editor was as wide as
 * the window, the Ledger and pinned search results were squeezed into the centre column above the terminal.
 * They are four tabs of one thing now, full width between the stripes, which is IntelliJ's default and is
 * what finally gives the Ledger and a load report the whole window to draw in.
 *
 *  - **The tab strip is the header.** No title text and no chevron: a stripe tab already names the dock and
 *    already hides it, and a second way to do one thing is what the amendment set out to remove.
 *  - **Hiding is the minimise.** ⌘7 on a showing terminal hides the dock and leaves the PTY composed at zero
 *    height, so a running `claude` session survives exactly as the old minimise chevron made it survive.
 *  - **One call site.** The dock sits below the three session layouts rather than inside them, so switching
 *    between TABS and SPLIT no longer moves it, which is what the terminal needed `movableContentOf` for.
 */
@Composable
fun BottomDock(viewModel: FixMessageViewModel, modifier: Modifier = Modifier) {
    val tab by viewModel.bottomTab.collectAsState()
    val documents by viewModel.openDocuments.collectAsState()
    val workspace by viewModel.openScenarios.collectAsState()
    val confirmingCloseId by viewModel.confirmingCloseId.collectAsState()
    val pinnedSearchResults by viewModel.pinnedSearchResults.collectAsState()
    val searchResultsPinned by viewModel.showSearchResultsPane.collectAsState()

    val density = LocalDensity.current
    var heightDp by remember { mutableStateOf(viewModel.layoutState.value.resolvedBottomHeightDp.dp) }

    // The terminal is built on first use and then never torn down: a PTY is expensive and, more to the
    // point, a `claude` session running in it must survive the tab being switched away from or the dock
    // being hidden. Latched rather than derived, because "is showing" is exactly what it must not follow.
    var terminalStarted by remember { mutableStateOf(false) }
    LaunchedEffect(tab) { if (tab is BottomTab.Terminal) terminalStarted = true }

    // The widths a reader dragged the Ledger's, Lanes' and the search results' columns to. Held here, where they
    // outlive the grids: each grid leaves composition whenever the other tab is chosen or the dock is hidden.
    val traceColumnWidths = remember { GridColumnWidths() }
    val traceLaneWidths = remember { laneColumnWidths() }
    val searchColumnWidths = remember { GridColumnWidths() }

    val visible = tab != null
    Column(modifier.fillMaxWidth().testTag("bottom-dock")) {
        if (visible) {
            HeightResizeHandle(
                onDeltaPx = { dy -> heightDp = (heightDp - with(density) { dy.toDp() }).coerceIn(120.dp, 760.dp) },
                onDragEnd = { viewModel.updateLayout { it.copy(bottomHeightDp = heightDp.value) } },
            )
            BottomDockTabs(
                tab = tab,
                documents = documentTabsOf(documents, workspace),
                // Pinned, not showing: the tab stays in the strip until the results are unpinned, or
                // switching to another tab would be the only way to lose them.
                searchResultCount = pinnedSearchResults.size.takeIf { searchResultsPinned },
                confirmingCloseId = confirmingCloseId,
                onSelect = { viewModel.showBottomTab(it) },
                onFocusDocument = { viewModel.focusDocument(it) },
                onCloseSearchResults = { viewModel.closeSearchResultsPane() },
                onRequestCloseDocument = { viewModel.requestCloseDocument(it) },
                onConfirmCloseDocument = { viewModel.closeDocument(it) },
                onCancelCloseDocument = { viewModel.cancelCloseDocument() },
            )
        }
        // The terminal, laid out only while it is the selected tab. Zero height keeps it composed, which is
        // what keeps the shell alive. See [terminalStarted].
        if (terminalStarted) {
            val port = System.getenv("FIXTOOL_CONTROL_PORT")?.toIntOrNull() ?: viewModel.appSettings.automationControlPort
            val workingDir =
                remember {
                    System.getenv("FIXTOOL_TERMINAL_CWD")?.ifBlank { null }?.let(::File)
                        ?: File(System.getProperty("user.home"))
                }
            TerminalPanel(
                workingDir = workingDir,
                controlUrl = "http://127.0.0.1:$port",
                controlToken = System.getenv("FIXTOOL_CONTROL_TOKEN")?.ifBlank { null },
                modifier = Modifier.fillMaxWidth().height(if (tab is BottomTab.Terminal) heightDp else 0.dp),
            )
        }
        val selected = tab
        if (selected != null && selected !is BottomTab.Terminal) {
            Box(modifier = Modifier.fillMaxWidth().height(heightDp)) {
                when (selected) {
                    is BottomTab.Trace ->
                        DockTracePanel(viewModel, traceColumnWidths, traceLaneWidths, Modifier.fillMaxSize())
                    is BottomTab.SearchResults ->
                        SearchResultsPane(
                            searchResults = pinnedSearchResults,
                            selectedMessage = viewModel.selectedMessage.collectAsState().value,
                            dictionary = viewModel.dictionary,
                            appSettings = viewModel.appSettings,
                            onSelectResult = { result -> viewModel.navigateToSearchResult(result) },
                            onClose = { viewModel.closeSearchResultsPane() },
                            columnWidths = searchColumnWidths,
                            modifier = Modifier.fillMaxSize(),
                        )
                    is BottomTab.Document -> {
                        // The dock falls back to the last document opened: there is no session tab in here to
                        // deselect to, so a strip with nothing active would be one you could not get out of.
                        val doc = documents.firstOrNull { it.id == selected.id } ?: documents.lastOrNull()
                        if (doc != null) ScenarioDocumentPane(viewModel, doc, modifier = Modifier.fillMaxSize())
                    }
                    is BottomTab.Terminal -> Unit
                }
            }
        }
    }
}

/**
 * The dock's header: the fixed tabs, then the pinned-results tab while there are any, then one tab per open
 * document, exactly as the document strip drew them.
 */
@Composable
private fun BottomDockTabs(
    tab: BottomTab?,
    documents: List<DocumentTab>,
    searchResultCount: Int?,
    confirmingCloseId: String?,
    onSelect: (BottomTab) -> Unit,
    onFocusDocument: (String) -> Unit,
    onCloseSearchResults: () -> Unit,
    onRequestCloseDocument: (String) -> Unit,
    onConfirmCloseDocument: (String) -> Unit,
    onCancelCloseDocument: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surface)
                .padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
                .testTag("bottom-dock-tabs"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DockTab(
            title = ToolWindow.TERMINAL.title,
            icon = ToolWindow.TERMINAL.icon,
            active = tab is BottomTab.Terminal,
            testTag = "bottom-dock-tab-terminal",
            onClick = { onSelect(BottomTab.Terminal) },
        )
        DockTab(
            title = ToolWindow.TRACE.title,
            icon = ToolWindow.TRACE.icon,
            active = tab is BottomTab.Trace,
            testTag = "bottom-dock-tab-trace",
            onClick = { onSelect(BottomTab.Trace) },
        )
        if (searchResultCount != null) {
            DockTab(
                title = "Search results · $searchResultCount",
                icon = null,
                active = tab is BottomTab.SearchResults,
                testTag = "bottom-dock-tab-search",
                onClick = { onSelect(BottomTab.SearchResults) },
                // The × unpins the results, which is the only thing that makes this tab go away.
                onClose = onCloseSearchResults,
                closeTestTag = "bottom-dock-close-search",
                closeTooltip = "Unpin search results",
            )
        }
        DocumentTabs(
            tabs = documents,
            activeId = (tab as? BottomTab.Document)?.id,
            confirmingCloseId = confirmingCloseId,
            onFocus = onFocusDocument,
            onRequestClose = onRequestCloseDocument,
            onConfirmClose = onConfirmCloseDocument,
            onCancelClose = onCancelCloseDocument,
        )
    }
}

/** One fixed tab in the dock's strip, drawn the way a document tab is so the strip reads as one strip. */
@Composable
private fun DockTab(
    title: String,
    icon: ImageVector?,
    active: Boolean,
    testTag: String,
    onClick: () -> Unit,
    onClose: (() -> Unit)? = null,
    closeTestTag: String = "",
    closeTooltip: String = "Close",
) {
    val background = if (active) AppTheme.Colors.background else AppTheme.Colors.surface
    val textColor = if (active) AppTheme.Colors.text else AppTheme.Colors.textSecondary
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 16.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp))
                    .background(background)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
                    .testTag(testTag),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(11.dp))
            val gap = if (icon != null) 4.dp else 0.dp
            Text(title, color = textColor, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = gap))
            if (onClose != null) {
                val closeModifier =
                    Modifier
                        .padding(start = 6.dp)
                        .size(16.dp)
                        .testTag(closeTestTag)
                TooltipIconButton(tooltip = closeTooltip, onClick = onClose, modifier = closeModifier) {
                    Icon(Icons.Default.Close, contentDescription = closeTooltip, tint = textColor, modifier = Modifier.size(11.dp))
                }
            }
        }
    }
}

/**
 * The Ledger with everything it needs wired to the one app-level follow state.
 *
 * Here rather than in App because this is now its only call site: the trace left the centre column's bottom
 * slot when the dock took over the foot of the window.
 */
@Composable
private fun DockTracePanel(
    viewModel: FixMessageViewModel,
    columnWidths: GridColumnWidths,
    laneWidths: GridColumnWidths,
    modifier: Modifier = Modifier,
) {
    val followedTrace by viewModel.followedTrace.collectAsState()
    val index by viewModel.traceIndex.collectAsState()
    val expandedTraces by viewModel.expandedTraces.collectAsState()
    val ungroupedTracesExpanded by viewModel.ungroupedTracesExpanded.collectAsState()
    val selectedMessage by viewModel.selectedMessage.collectAsState()
    val rendering by viewModel.traceRendering.collectAsState()
    val anchor = followedTrace?.anchorId

    /**
     * The Ledger's rows, rebuilt only when something it draws from changed.
     *
     * The index is republished on the trace ticker and is a new object only when some pane's snapshot
     * actually changed (`TraceFollow` memoises on snapshot identity), so this memo is quiet while the app
     * is, and does no work at all while the dock is elsewhere, because this is not composed then.
     */
    val rows =
        remember(index, expandedTraces, ungroupedTracesExpanded, anchor) {
            val current = index
            if (current == null) {
                emptyList()
            } else {
                TraceRows.build(
                    snapshots = current.snapshots,
                    sessionTitles = current.sessionTitles,
                    grouping = current.grouping,
                    dictionary = viewModel.getDictionaryAdapter(),
                    expanded = expandedTraces,
                    ungroupedExpanded = ungroupedTracesExpanded,
                    followedAnchor = anchor,
                )
            }
        }

    /**
     * The followed trace laid out in lanes, built only while Lanes is the drawing on screen.
     *
     * Keyed on the same index generation `rows` is, so the two renderings can never be one tick apart,
     * and gated on the rendering so a reader on the Ledger pays nothing for the picture they are not
     * looking at.
     */
    val lanes =
        remember(index, anchor, rendering) {
            if (rendering != TraceRendering.LANES) {
                null
            } else {
                val current = index
                current
                    ?.grouping
                    ?.traces
                    ?.firstOrNull { anchor != null && anchor in it.ids }
                    ?.let { current.lanes(it) }
            }
        }

    TracePanel(
        rows = rows,
        sessionTitles = index?.sessionTitles.orEmpty(),
        selectedMessage = selectedMessage,
        dictionary = viewModel.dictionary,
        appSettings = viewModel.appSettings,
        followingLabel = followedTrace?.label,
        rendering = rendering,
        lanes = lanes,
        columnWidths = columnWidths,
        laneWidths = laneWidths,
        onSetRendering = { viewModel.setTraceRendering(it) },
        onToggleTrace = { key -> viewModel.toggleTrace(key) },
        onToggleUngrouped = { viewModel.toggleUngroupedTraces() },
        // The keys of what is on screen right now, not of some index the panel is not drawing. See
        // TraceFollow.expandAll.
        onExpandAll = { viewModel.expandAllTraces(rows.filterIsInstance<TraceRows.Row.Header>().map { it.key }) },
        onCollapseAll = { viewModel.collapseAllTraces() },
        onFollow = { id -> viewModel.follow(id) },
        onUnfollow = { viewModel.unfollow() },
        onSelectMember = { located, message -> viewModel.navigateToTraceMember(located.session, message) },
        // The panel's own × hides the dock and leaves the follow alone, the same asymmetry the chip's × has.
        onClose = { viewModel.closeTracePanel() },
        modifier = modifier,
    )
}
