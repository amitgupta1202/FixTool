package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixMessageSession

// ---------------------------------------------------------------------------------------------------
// The pane vocabulary: one word per action, shared by both layouts and by the folded menu.
// ---------------------------------------------------------------------------------------------------

/**
 * **One wording per action, and the noun alone.**
 *
 * The two layouts had already drifted: the same button was "Add Separator" in tabs and "Add Blank Line" in
 * split. And none of the four toggles explains its own mechanism any more — the pressed look says which
 * state it is in, which is what frees the word to name the thing. "Wrap: On (click to unwrap)" was a
 * control describing how it works to somebody who only wanted to know what it does.
 *
 * A pane action and its all-panes twin on the toolbar share the verb and differ only in the object: Add
 * blank line and Add blank line to all panes, Clear messages and Clear all panes, Search in pane and Search
 * all sessions.
 */
internal const val WRAP_LABEL = "Wrap lines"

internal const val SEARCH_LABEL = "Search in pane"

internal const val FILTER_LABEL = "Filter pane"

internal const val GROUP_LABEL = "Group by conversation"

internal const val BLANK_LINE_LABEL = "Add blank line"

internal const val CLEAR_LABEL = "Clear messages"

internal const val SCROLL_TO_BOTTOM_LABEL = "Scroll to bottom"

internal const val MINIMIZE_LABEL = "Minimize"

internal const val MOVE_LEFT_LABEL = "Move left"

internal const val MOVE_RIGHT_LABEL = "Move right"

internal const val CLOSE_SESSION_LABEL = "Close session"

/**
 * **One header for a session pane, in both layouts.**
 *
 * The split layout drew thirteen controls with its own fold rule; the tabs layout drew nine of the same
 * controls in its tab strip with no fold rule at all, no overflow, and no title. They had already drifted —
 * the same button was "Add Separator" in one and "Add Blank Line" in the other — and nothing stopped them
 * drifting further, because there was no one place where a pane's actions were written down.
 *
 * Here they are written down once, as [BarAction]s, and [FoldingActions] decides what fits. Which means
 * the fold order is declared rather than positional: Move left goes first and **Close never goes at all**,
 * where folding by position would have taken Close off before Move left and made the pane unclosable at
 * exactly the width where closing it is what you want.
 *
 * @param showTitle the split layout names its pane here. In tabs the tab already does, so the header draws
 *   its actions alone rather than printing the name a reader is looking straight at.
 */
@Composable
@Suppress("LongParameterList")
internal fun PaneHeader(
    session: FixMessageSession,
    viewMode: FixMessageSession.ViewMode,
    /** Unfiltered, the same number this pane's minimized chip shows. */
    messageCount: Int,
    isAtBottom: Boolean,
    onScrollToBottom: () -> Unit,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
    onConnect: (() -> Unit)? = null,
    onDisconnect: (() -> Unit)? = null,
    onMoveLeft: (() -> Unit)? = null,
    onMoveRight: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    showTitle: Boolean = true,
) {
    val connectionState by session.connectionState.collectAsState()
    val discarded by session.discarded.collectAsState()

    val canPowerOn = connectionState.canConnect() && onConnect != null
    val canPowerOff = connectionState.canDisconnect() && onDisconnect != null
    // Not for a venue: on one, this unbinds a port that every client on its list is sitting on, and an
    // unlabelled power icon says none of that. The overview draws it as a named Stop or Start instead.
    val showsPower = !session.isVenue && (canPowerOn || canPowerOff)
    val showsCount = !session.isVenue && showTitle

    val actions =
        paneActions(
            session = session,
            viewMode = viewMode,
            isAtBottom = isAtBottom,
            onScrollToBottom = onScrollToBottom,
            onMinimize = onMinimize,
            onMoveLeft = onMoveLeft,
            onMoveRight = onMoveRight,
            onClose = onClose,
        )

    BoxWithConstraints(modifier = modifier.fillMaxWidth().background(AppTheme.Colors.surface)) {
        val available = maxWidth
        // What the actions may not spend: the row's padding, the power button, the count, and the floor the
        // title keeps for itself. Reserving it is what makes the fold give up an action rather than draw
        // over the name.
        val reserved =
            PANE_H_PAD +
                (if (showsPower) BAR_BUTTON + 4.dp else 0.dp) +
                (if (showsCount) COUNT_WIDTH else 0.dp) +
                (if (showTitle) MIN_PANE_TITLE else 0.dp)

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(PANE_HEADER_HEIGHT)
                    .padding(horizontal = PANE_H_PAD / 2)
                    .testTag("pane-header"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showsPower) {
                PanePower(canPowerOn, onConnect, onDisconnect)
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (showTitle) {
                PaneTitle(
                    title = session.title,
                    discarded = discarded,
                    messageCount = messageCount.takeIf { showsCount },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            FoldingActions(
                actions = actions,
                available = available,
                reserved = reserved,
                overflowTag = "pane-overflow",
            )
        }
    }
}

/**
 * **Every action a pane offers, in one list, in fold order.**
 *
 * The ranks are the note's fold table, as code: Move left goes first, then Move right, Minimize, Scroll to
 * bottom, Clear messages, Add blank line, and the four toggles last. Close carries `neverFolds`.
 *
 * A venue gets the moves, Minimize and Close and nothing else. Its pane has no grid — every message on a
 * venue belongs to one of its clients — so filtering, grouping, blank lines, clearing and scroll-to-bottom
 * were controls that did nothing, and the filter was the worst of them, drawing a working-looking regex box
 * above a list it could not touch.
 */
@Composable
@Suppress("LongParameterList")
private fun paneActions(
    session: FixMessageSession,
    viewMode: FixMessageSession.ViewMode,
    isAtBottom: Boolean,
    onScrollToBottom: () -> Unit,
    onMinimize: () -> Unit,
    onMoveLeft: (() -> Unit)?,
    onMoveRight: (() -> Unit)?,
    onClose: (() -> Unit)?,
): List<BarAction> {
    val grid = if (session.isVenue) emptyList() else gridActions(session, viewMode, isAtBottom, onScrollToBottom)
    return grid + paneChrome(session, onMinimize, onMoveLeft, onMoveRight, onClose)
}

/**
 * The controls that act on the pane's message grid.
 *
 * A venue gets none of them: its pane has no grid, because every message on a venue belongs to one of its
 * clients (see `QuickFixService.deliver`, whose every venue branch routes to a client channel). Filtering,
 * grouping, blank lines, clearing and scroll-to-bottom were controls that did nothing there, and the filter
 * was the worst of them — a working-looking regex box above a list it could not touch.
 */
@Composable
private fun gridActions(
    session: FixMessageSession,
    viewMode: FixMessageSession.ViewMode,
    isAtBottom: Boolean,
    onScrollToBottom: () -> Unit,
): List<BarAction> {
    val wrapText by session.wrapText.collectAsState()
    val searchVisible by session.searchVisible.collectAsState()
    val filterVisible by session.filterVisible.collectAsState()
    val grouped by session.groupByConversation.collectAsState()

    return listOfNotNull(
        // Wrap is genuinely RAW-only: a grid of parsed fields has no lines to wrap. Search is not,
        // and used to be drawn as though it were — the bar it opens works in both views, so the
        // button that opens it was the only thing that did not.
        BarAction(
            WRAP_LABEL,
            Icons.Default.WrapText,
            { session.toggleWrapText() },
            "pane-wrap",
            pressed = wrapText,
            foldRank = 7,
        ).takeIf { viewMode == FixMessageSession.ViewMode.RAW },
        BarAction(
            SEARCH_LABEL,
            Icons.Default.Search,
            { session.toggleSearch() },
            "pane-search",
            shortcut = Shortcuts.SEARCH_IN_PANE.label,
            pressed = searchVisible,
            foldRank = 8,
        ),
        BarAction(
            FILTER_LABEL,
            Icons.Default.FilterAlt,
            { session.toggleFilter() },
            "pane-filter",
            pressed = filterVisible,
            foldRank = 9,
        ),
        BarAction(
            GROUP_LABEL,
            Icons.Default.AccountTree,
            { session.toggleGroupByConversation() },
            "pane-group",
            pressed = grouped,
            foldRank = 10,
        ),
        BarAction(
            BLANK_LINE_LABEL,
            Icons.Default.Add,
            { session.addSeparator() },
            "pane-blank-line",
            shortcut = Shortcuts.BLANK_LINE.label,
            foldRank = 6,
        ),
        BarAction(CLEAR_LABEL, Icons.Default.Delete, { session.clearMessages() }, "pane-clear", foldRank = 5),
        BarAction(
            SCROLL_TO_BOTTOM_LABEL,
            Icons.Default.ArrowDownward,
            onScrollToBottom,
            "pane-scroll-bottom",
            enabled = !isAtBottom,
            disabledReason = "$SCROLL_TO_BOTTOM_LABEL — already there",
            foldRank = 4,
        ),
    )
}

/**
 * The controls that act on the pane itself, which every pane has — a venue's included.
 *
 * Close is the one with `neverFolds`, and the one that arms.
 */
@Composable
private fun paneChrome(
    session: FixMessageSession,
    onMinimize: () -> Unit,
    onMoveLeft: (() -> Unit)?,
    onMoveRight: (() -> Unit)?,
    onClose: (() -> Unit)?,
): List<BarAction> {
    // **A Close takes the log with it and there is no Recent for a closed session**, so it asks — in the
    // button, the way Close all does, rather than in a dialog over the window. One click arms it, the
    // second closes, and it gives up on its own. The question is the window's rather than this button's,
    // because the tab and the Session menu close the same session: see [WindowArming].
    val arming = windowArming()
    val closing = ClosingSession(session.id)
    val armed = arming.isArmed(closing)

    return listOfNotNull(
        // Leaves the layout for a chip in the strip above. Not a close: the session keeps running and
        // keeps its log, which closing does not.
        BarAction(MINIMIZE_LABEL, Icons.Default.Remove, onMinimize, "pane-minimize", foldRank = 3),
        onMoveLeft?.let {
            BarAction(MOVE_LEFT_LABEL, Icons.Default.ChevronLeft, it, "pane-move-left", foldRank = 1)
        },
        onMoveRight?.let {
            BarAction(MOVE_RIGHT_LABEL, Icons.Default.ChevronRight, it, "pane-move-right", foldRank = 2)
        },
        onClose?.let { close ->
            BarAction(
                label = if (armed) "Close ${session.title}?" else CLOSE_SESSION_LABEL,
                icon = Icons.Default.Close,
                onClick = { if (arming.confirm(closing)) close() },
                tag = "pane-close",
                hint = if (armed) "click again to confirm" else "click again to confirm — the log goes with it",
                tint = if (armed) AppTheme.Colors.warning else null,
                neverFolds = true,
            )
        },
    )
}

/** Connect or disconnect, as the one control whose colour is its state. */
@Composable
private fun PanePower(canPowerOn: Boolean, onConnect: (() -> Unit)?, onDisconnect: (() -> Unit)?) {
    val connect = canPowerOn && onConnect != null
    TooltipIconButton(
        tooltip = if (connect) "Connect" else "Disconnect",
        onClick = { if (connect) onConnect?.invoke() else onDisconnect?.invoke() },
        modifier = Modifier.size(BAR_BUTTON).testTag("pane-power"),
    ) {
        Icon(
            imageVector = Icons.Default.PowerSettingsNew,
            contentDescription = if (connect) "Connect" else "Disconnect",
            tint = if (connect) AppTheme.Colors.textSecondary else AppTheme.Colors.success,
            modifier = Modifier.size(BAR_ICON),
        )
    }
}

/**
 * The name, what the session threw away, and the count.
 *
 * The title loses its middle rather than its tail: the tail of "FX Demo Venue ← DEMO_CLIENT1" is the half
 * that says which client this is, so an end ellipsis would leave ten tiles reading alike.
 */
@Composable
private fun PaneTitle(title: String, discarded: Long, messageCount: Int?, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        AppTooltip(text = title, modifier = Modifier.weight(1f, fill = false)) {
            MiddleEllipsisText(title, AppTheme.Colors.text, 12.sp, Modifier.testTag("pane-title"))
        }
        // **Messages this session threw away.** In the header, next to the name, because it is a fact about
        // the session's whole log: everything below it is missing this many messages, and a reader who does
        // not know that will reasonably conclude the venue never sent them. Absent while the count is zero —
        // a badge that is always there is furniture, not a warning.
        if (discarded > 0) {
            Spacer(modifier = Modifier.width(6.dp))
            TooltipIconButton(
                tooltip =
                    "$discarded message(s) received and discarded — FixTool could not ingest them fast " +
                        "enough, so they are missing from this log entirely. Not a venue problem, and not " +
                        "recoverable: they were never stored. Raising the session buffer deepens the burst " +
                        "it can absorb but does not raise the rate.",
                onClick = {},
                modifier = Modifier.size(BAR_BUTTON).testTag("pane-discarded"),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "$discarded messages discarded",
                    tint = AppTheme.Colors.warning,
                    modifier = Modifier.size(BAR_ICON),
                )
            }
            Text(
                text = "$discarded lost",
                color = AppTheme.Colors.warning,
                fontSize = 10.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
        // The count its minimized chip also shows, so a pane reduces to something already seen rather than
        // to a new readout. Held to one line: squeezed, it used to stack its digits.
        if (messageCount != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$messageCount",
                color = AppTheme.Colors.textDisabled,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag("pane-message-count"),
            )
        }
    }
}

/** Fixed, so the strip does not change height with whichever button the active pane happens to draw. */
internal val PANE_HEADER_HEIGHT: Dp = 28.dp

/** 8dp each side, from the header row's own padding. */
private val PANE_H_PAD = 16.dp

/** Room for four monospace digits at 10sp, which is as wide as a session's count gets in practice. */
private val COUNT_WIDTH = 24.dp

/** The narrowest title still worth reading: the room the fold rule keeps clear for the name. */
private val MIN_PANE_TITLE = 72.dp
