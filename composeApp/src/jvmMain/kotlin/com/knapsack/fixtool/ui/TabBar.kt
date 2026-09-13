package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessageSession

@Composable
fun TabBar(
    /** Every pane. Minimized ones get a chip above rather than a tab. */
    sessions: List<FixMessageSession>,
    activeSession: FixMessageSession?,
    viewMode: FixMessageSession.ViewMode,
    onTabClick: (FixMessageSession) -> Unit,
    onCloseTab: (FixMessageSession) -> Unit,
    onToggleWrapText: (FixMessageSession) -> Unit,
    onConnect: (FixMessageSession) -> Unit,
    onDisconnect: (FixMessageSession) -> Unit,
    onMinimize: (FixMessageSession, Boolean) -> Unit = { session, on -> session.setMinimized(on) },
    onEditVenueRules: ((FixMessageSession) -> Unit)? = null,
    isAtBottom: Boolean = true,
    onScrollToBottom: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Same reading as the split grid takes, for the same reason: one combined collector keyed on the
    // pane identities, never a composable called inside a loop over a mutable list. See [SplitView].
    val identities = sessions.joinToString(",") { it.id }
    val minimizedFlags by remember(identities) {
        val flows = sessions.map { it.minimized }
        if (flows.isEmpty()) {
            kotlinx.coroutines.flow.flowOf(emptyList<Boolean>())
        } else {
            kotlinx.coroutines.flow.combine(flows) { flags -> flags.toList() }
        }
    }.collectAsState(initial = sessions.map { it.minimized.value })
    val minimized = sessions.filterIndexed { i, _ -> minimizedFlags.getOrElse(i) { false } }
    val visible = sessions.filterIndexed { i, _ -> !minimizedFlags.getOrElse(i) { false } }

    // The pane the row's buttons act on, or null when there is none on screen to act on. A minimized
    // pane keeps its session and its log, but it has no grid for these buttons to reach.
    val activeGridSession = activeSession?.takeIf { it in visible }

    // Read once, here, rather than inside the block below: the connect/disconnect button draws from it
    // and the fold count is computed from it, and two collectors could have them disagree about the same
    // pane. A plain if, not a `?.let` around a composable call, so the collector is one branch of one
    // conditional rather than a slot that appears and disappears with the active pane.
    val connectionState =
        if (activeGridSession != null) {
            activeGridSession.connectionState.collectAsState().value
        } else {
            FixConnectionState.DISCONNECTED
        }

    Column(modifier = modifier) {
        // Top border
        androidx.compose.material3.HorizontalDivider(
            color = AppTheme.Separators.color,
            thickness = AppTheme.Separators.dividerThickness,
        )

        // One strip, in both layouts. A venue starts minimized whichever layout is showing, so its live
        // state has to be readable here too, and putting it anywhere else would be a second answer to
        // the same question.
        MinimizedStrip(
            minimized = minimized,
            allSessions = sessions,
            targetSession = activeSession,
            onRestore = { onMinimize(it, false) },
            onRestoreAll = { minimized.forEach { session -> onMinimize(session, false) } },
            onEditRules = onEditVenueRules,
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // A fixed height, not padding around whatever is tallest, so the strip does not
                    // change height with whichever button the active pane happens to draw.
                    .height(TAB_BAR_HEIGHT)
                    .background(AppTheme.Colors.surface)
                    .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tab items. Scenario documents no longer share this strip — they live in the scenario dock
            // (see [BottomDock]), so a session tab is active on identity alone.
            //
            // The tabs are the flexible part of this bar, which is why the strip is the weighted child and
            // the per-session actions are not. A Row measures its unweighted children first and hands the
            // weighted one what is left, so those buttons keep their natural width at any pane count and
            // the strip absorbs the shortfall, scrolling when its share runs short. A tab that has
            // scrolled out of view is one scroll away. A button pushed off the right edge by fifty lanes'
            // worth of tabs is not reachable at all.
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                visible.forEach { session ->
                    Tab(
                        session = session,
                        isActive = session === activeSession,
                        onClick = { onTabClick(session) },
                        onClose = { onCloseTab(session) },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }

            // **The same header the split layout draws**, with its title suppressed: the tab a reader is
            // looking straight at already names the pane, and a second copy of the name in the same strip
            // would be the one thing a shared header must not add. Everything else is shared — the
            // vocabulary, the pressed look, the fold order, the overflow and the arming Close — so the two
            // layouts cannot drift again the way "Add Separator" and "Add Blank Line" did.
            //
            // Unless the pane is in the strip above: a minimized pane keeps its session and its log, but it
            // has no grid on screen for these to act on.
            if (activeGridSession != null) {
                val paneMessageCount by activeGridSession.messages.collectAsState()
                PaneHeader(
                    session = activeGridSession,
                    viewMode = viewMode,
                    messageCount = paneMessageCount.size,
                    isAtBottom = isAtBottom,
                    onScrollToBottom = onScrollToBottom,
                    onMinimize = { onMinimize(activeGridSession, true) },
                    onConnect = { onConnect(activeGridSession) },
                    onDisconnect = { onDisconnect(activeGridSession) },
                    showTitle = false,
                    // The tabs are the flexible part of this bar; the header keeps its natural width, so a
                    // box with fifty tabs scrolls its strip rather than pushing the actions off the edge.
                    modifier = Modifier.width(PANE_ACTIONS_WIDTH),
                )
            }
        }
    }
}

/**
 * What the tab strip leaves for the pane header beside it.
 *
 * The strip is the weighted child and the header is not, so this is the header's whole share: a Row
 * measures its unweighted children first and hands the weighted one what is left. Wide enough for the nine
 * controls a client pane draws, so nothing folds at a normal window width — and when the window is narrow
 * enough that it does, the fold is the shared one and everything stays reachable through its ⋯.
 */
private val PANE_ACTIONS_WIDTH = 232.dp

/** The RAW-view-only buttons (wrap, search), lifted out so the tab bar itself stays readable. */
@Composable
private fun RawViewActions(session: FixMessageSession, onToggleWrapText: (FixMessageSession) -> Unit) {
    val wrapText by session.wrapText.collectAsState()
    val searchVisible by session.searchVisible.collectAsState()

    ToggleIconButton(
        on = wrapText,
        tooltip = WRAP_LABEL,
        icon = Icons.Default.WrapText,
        onClick = { onToggleWrapText(session) },
        tag = "tab-wrap",
    )

    ToggleIconButton(
        on = searchVisible,
        tooltip = SEARCH_LABEL,
        icon = Icons.Default.Search,
        onClick = { session.toggleSearch() },
        tag = "tab-search",
    )
}

@Composable
private fun Tab(
    session: FixMessageSession,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (isActive) AppTheme.Colors.background else AppTheme.Colors.surface
    val textColor = if (isActive) AppTheme.Colors.text else AppTheme.Colors.textSecondary

    Row(
        modifier =
            modifier
                .clip(tabShape)
                .background(backgroundColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = session.title,
            color = textColor,
            fontSize = 11.sp,
        )

        if (onClose != null) {
            // **The tab's × is this layout's Close session, so it asks the same question the split header's
            // Close asks** — the same question, not a copy of it: arming it here arms the header and the
            // Session menu too, and a second click on any of them is the answer. It closed on one click
            // before, which made the tabs layout the one place a session's log went with no second look.
            val arming = windowArming()
            val closing = ClosingSession(session.id)
            val armed = arming.isArmed(closing)
            val hover =
                if (armed) {
                    "Close ${session.title}? — click again to confirm"
                } else {
                    "$CLOSE_SESSION_LABEL — click again to confirm — the log goes with it"
                }
            Spacer(modifier = Modifier.width(8.dp))
            TooltipIconButton(
                tooltip = hover,
                onClick = { if (arming.confirm(closing)) onClose() },
                modifier = tabCloseButtonSize.testTag("tab-close-${session.title}"),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = hover,
                    tint = if (armed) AppTheme.Colors.warning else textColor,
                    modifier = tabCloseIconSize,
                )
            }
        }
    }
}

// No local color constants needed - all colors now use AppTheme.Colors

/** One bar height, which is what the row already measured with its 28dp buttons. */
private val TAB_BAR_HEIGHT = 32.dp

// Modifier constants
private val toolbarButtonSize = Modifier.size(28.dp)
private val toolbarIconSize = Modifier.size(18.dp)
private val tabCloseButtonSize = Modifier.size(16.dp)
private val tabCloseIconSize = Modifier.size(14.dp)
private val tabShape = RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp)
