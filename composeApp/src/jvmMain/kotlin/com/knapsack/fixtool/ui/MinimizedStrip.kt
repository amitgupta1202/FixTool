package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessageSession
import java.awt.Cursor

/**
 * **The panes that have left the layout**, as one row of chips above the ones that have not.
 *
 * Minimizing takes a pane out of the grid rather than collapsing it in place, and that is the whole
 * reason it reclaims anything: a cell is sized from the row and column weights and never from its
 * content, so a pane collapsed to its header would be a header floating in a full-size cell. Removed
 * from the list instead, the existing column arithmetic reflows on its own — five panes become four,
 * and the grid that rounded 5 up to 6 cells lands on a 2x2 with nothing wasted.
 *
 * It is *not* a way of closing a pane. Closing calls [FixMessageSession.destroy], which disconnects the
 * session and throws its log away, and until now that was the only way to quiet a busy split view.
 * A minimized session keeps running, keeps its log, and keeps being a valid send target — which is why
 * a chip that is the editor's target says so, loudly. See [MinimizedChip].
 *
 * One strip, shared by minimized conversations and by venues, which start minimized. Two bars stacked
 * here would hand back the space the feature just saved.
 */
@Composable
fun MinimizedStrip(
    minimized: List<FixMessageSession>,
    /** Every session, so a minimized venue can count the clients that belong to it. */
    allSessions: List<FixMessageSession>,
    /** Where the editor's Send will go. Its chip has to say so — it is not visible to say it itself. */
    targetSession: FixMessageSession?,
    onRestore: (FixMessageSession) -> Unit,
    onRestoreAll: () -> Unit,
    /** Opens a venue's rules in the connection panel. The same action, and label, as the pane's. */
    onEditRules: ((FixMessageSession) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Absent rather than empty: a permanently present bar over a layout with nothing minimized is the
    // furniture this whole change is about removing.
    if (minimized.isEmpty()) return

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surfaceHeader)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("minimized-strip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            minimized.forEach { session ->
                MinimizedChip(
                    session = session,
                    clients = allSessions.filter { it.isClientOf(session) },
                    isTarget = session === targetSession,
                    onRestore = { onRestore(session) },
                    onEditRules = onEditRules?.let { edit -> { edit(session) } },
                )
            }
        }
        if (minimized.size > 1) {
            Text(
                text = "restore all",
                color = AppTheme.Colors.textDisabled,
                fontSize = 10.sp,
                modifier =
                    Modifier
                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                        .clickable { onRestoreAll() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * One minimized pane, at the density a strip can afford.
 *
 * A venue's chip and a conversation's chip are not the same object, and neither is a shrunken copy of
 * the other: a conversation chip is a placeholder for a pane you will bring back, while a venue chip
 * carries the venue's whole readout because the venue is minimized by default and the chip is what
 * most testers will actually look at. Both are strict subsets of their pane — see [VenueSummary] for
 * the contract, and for why the badges here are all conditional.
 */
@Composable
private fun MinimizedChip(
    session: FixMessageSession,
    clients: List<FixMessageSession>,
    isTarget: Boolean,
    onRestore: () -> Unit,
    onEditRules: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val state by session.connectionState.collectAsState()
    val isVenue = session.isVenue
    val summary = if (isVenue) rememberVenueSummary(session, clients) else null
    val live = if (isVenue) summary?.listening == true else state == FixConnectionState.LOGGED_ON

    val edge =
        when {
            isTarget -> AppTheme.Colors.primary
            hovered -> AppTheme.Colors.borderDark
            else -> AppTheme.Colors.border
        }

    Row(
        modifier =
            Modifier
                .hoverable(interaction)
                .background(
                    if (isTarget) AppTheme.Colors.surfaceVariant else AppTheme.Colors.surface,
                    chipShape,
                )
                .border(1.dp, edge, chipShape)
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .testTag("chip:${session.title}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        // Restore is the chip itself, not a button on it: the chevron marks where the pane goes back to
        // (down, into the grid) and the whole label is the target, because a 12px hit area is not one.
        Row(
            modifier =
                Modifier
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                    .clickable { onRestore() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(text = "▾", color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
            StateDot(live = live)
            Text(
                text = session.title,
                color = chipTitleColor(isTarget, isVenue, listening = summary?.listening == true),
                fontFamily = if (isVenue) FontFamily.Monospace else FontFamily.Default,
                fontSize = 11.sp,
            )

            if (summary != null) {
                Figure(":${summary.port.ifBlank { "?" }}  ·  ${summary.clientsLabel()}")
                VenueBadges(summary)
            } else {
                // A conversation's chip carries the count its pane header now also shows, so minimizing
                // reduces to something already seen rather than to a different readout.
                val messages by session.messages.collectAsState()
                Figure("${messages.size}")
            }

            if (isTarget) {
                Text(
                    text = "→ editor",
                    color = AppTheme.Colors.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }

        if (summary != null) VenueActions(session, summary, onEditRules)
    }
}

/**
 * The badges, every one of them conditional.
 *
 * Which is the whole reason a healthy venue's chip is the short one: it widens in proportion to how
 * much has gone wrong, rather than carrying five counters that read the same on every venue in the
 * world. The conditions live on [VenueSummary] so the pane agrees with this about what counts as
 * worth saying.
 */
@Composable
private fun VenueBadges(summary: VenueSummary) {
    if (summary.showRefused) Badge("⚠ ${summary.refused} refused", AppTheme.Colors.warning)
    if (summary.noRules) Badge("⚠ no rules", AppTheme.Colors.warning)
    if (summary.showPending) Badge("${summary.pending} pending", AppTheme.Colors.info)
    if (summary.sendsDiverge) Badge("⚠ ${summary.divergenceLabel()}", AppTheme.Colors.error)
    if (summary.showLatency) Badge("latency on", AppTheme.Colors.info)
}

/**
 * A venue's actions, in the same words as its pane's — see [AcceptorOverviewPane].
 *
 * Close is deliberately absent: on a venue it recurses through `isClientOf` and takes every client
 * pane with it, which is too much to hang on one click in a strip. Restore the pane and close it there.
 */
@Composable
private fun VenueActions(
    session: FixMessageSession,
    summary: VenueSummary,
    onEditRules: (() -> Unit)?,
) {
    if (onEditRules != null) SlimButton(text = "Rules", onClick = onEditRules)
    SlimButton(
        text = if (summary.listening) "Stop" else "Start",
        onClick = { if (summary.listening) session.disconnect() else session.reconnect() },
        color = if (summary.listening) AppTheme.Colors.warning else AppTheme.Colors.success,
    )
    if (summary.showRefused) {
        SlimButton(text = "Clear refused", onClick = { session.clearRefusedLogons() })
    }
}

/**
 * A chip's name in the colour of what it is.
 *
 * The send target reads as the accent whatever else it is, because "Send goes here" outranks every
 * other thing a chip has to say about itself.
 */
private fun chipTitleColor(isTarget: Boolean, isVenue: Boolean, listening: Boolean) =
    when {
        isTarget -> AppTheme.Colors.primary
        isVenue && !listening -> AppTheme.Colors.textDisabled
        isVenue -> AppTheme.Colors.warning
        else -> AppTheme.Colors.text
    }

/** Live is filled, down is hollow. A stopped venue has to look stopped at this size too. */
@Composable
private fun StateDot(live: Boolean) {
    Box(
        modifier =
            Modifier
                .size(7.dp)
                .let {
                    if (live) {
                        it.background(AppTheme.Colors.success, CircleShape)
                    } else {
                        it.border(1.dp, AppTheme.Colors.textDisabled, CircleShape)
                    }
                },
    )
}

@Composable
private fun Figure(text: String) {
    Text(text = text, color = AppTheme.Colors.textDisabled, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        modifier = Modifier.border(1.dp, color, badgeShape).padding(horizontal = 6.dp),
    )
}

private val chipShape = RoundedCornerShape(3.dp)
private val badgeShape = RoundedCornerShape(8.dp)
