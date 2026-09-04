package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessageSession
import java.awt.Cursor
import java.time.format.DateTimeFormatter

/**
 * **What a venue shows instead of a message grid: who is on it.**
 *
 * An acceptor open to any client has no traffic of its own — every message belongs to one
 * counterparty, and each of those has a pane. What is left is the thing no session can say: who is
 * connected, who has gone, and *who was turned away*. That last one is the reason this pane exists
 * rather than an empty grid. A refused logon produces no FIX message anywhere, on either side, so
 * without somewhere to put it the tester sees an acceptor that is running perfectly and a client
 * that cannot connect, with nothing in between to link them.
 *
 * Everything drawn here comes from [VenueSummary], which the minimized chip also reads. That is what
 * keeps the two from drifting: this pane is the full density and the chip is a strict subset, and
 * neither can show a fact or offer an action the other does not have. See [MinimizedStrip].
 */
@Composable
fun AcceptorOverviewPane(
    venue: FixMessageSession,
    clients: List<FixMessageSession>,
    onFocusClient: (FixMessageSession) -> Unit,
    /** Opens this venue's rules in the connection panel. The venue's main control, at either size. */
    onEditRules: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val summary = rememberVenueSummary(venue, clients)
    val refused by venue.refusedLogons.collectAsState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(AppTheme.Colors.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "${summary.senderCompID.ifBlank { "?" }}  ·  ${summary.portLabel()}  ·  ${summary.clientsLabel()}",
            color = if (summary.listening) AppTheme.Colors.text else AppTheme.Colors.warning,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )

        if (clients.isEmpty()) {
            Text(
                text = "No client has logged on yet. Any counterparty addressing ${summary.senderCompID} will open its own tab.",
                color = AppTheme.Colors.textDisabled,
                fontSize = 11.sp,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                clients.forEach { client -> ClientRow(client, onFocusClient) }
            }
        }

        if (refused.isNotEmpty()) {
            HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
            Text(
                text = "Refused logons",
                color = AppTheme.Colors.warning,
                fontSize = 10.sp,
            )
            // Newest first: the one that just failed is the one being investigated.
            refused.reversed().forEach { event ->
                Text(
                    text =
                        "${TIME.format(event.at)}  ${event.sessionId.targetCompID} addressed " +
                            "${event.sessionId.senderCompID} — this venue is ${summary.senderCompID}",
                    color = AppTheme.Colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }

        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

        // The full counters. The chip promotes only the ones that have deviated (see [VenueSummary]);
        // this is the density that shows them all, because it is the surface you open to read numbers.
        Text(
            text = if (summary.noRules) "no rules loaded — this venue will answer nothing" else summary.rulesLabel(),
            color = if (summary.noRules) AppTheme.Colors.warning else AppTheme.Colors.textSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Text(
            text = "Response rules apply to every client on this venue.",
            color = AppTheme.Colors.textDisabled,
            fontSize = 10.sp,
        )

        // The venue's actions, named. A bare power icon used to be the only one, and on a venue it
        // unbinds a port that every client on the list is sitting on without ever saying so.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (onEditRules != null) SlimButton(text = "Rules", onClick = onEditRules)
            SlimButton(
                text = if (summary.listening) "Stop" else "Start",
                onClick = { if (summary.listening) venue.disconnect() else venue.reconnect() },
                color = if (summary.listening) AppTheme.Colors.warning else AppTheme.Colors.success,
            )
            if (summary.showRefused) {
                SlimButton(text = "Clear refused", onClick = { venue.clearRefusedLogons() })
            }
        }
    }
}

/**
 * Reads a venue into the shape both its surfaces draw from.
 *
 * Every client's state is collected here rather than inside the row, so the rollup on the chip and the
 * rows in the pane are counted from the same reading at the same moment.
 */
@Composable
fun rememberVenueSummary(venue: FixMessageSession, clients: List<FixMessageSession>): VenueSummary {
    val state by venue.connectionState.collectAsState()
    val refused by venue.refusedLogons.collectAsState()
    val clientStates = clients.map { it.connectionState.collectAsState().value }
    val config = venue.currentConfig
    val up = clientStates.count { it == FixConnectionState.LOGGED_ON }
    return VenueSummary.of(
        senderCompID = config?.senderCompID.orEmpty(),
        port = config?.socketAcceptPort?.ifBlank { config.port }.orEmpty(),
        listening = state == FixConnectionState.CONNECTED || state == FixConnectionState.LOGGED_ON,
        clientsConnected = up,
        clientsGone = clientStates.size - up,
        refused = refused.size,
        status = venue.acceptorStatus(),
    )
}

@Composable
private fun ClientRow(client: FixMessageSession, onFocus: (FixMessageSession) -> Unit) {
    val state by client.connectionState.collectAsState()
    val messages by client.messages.collectAsState()
    val since = messages.firstOrNull()?.timestamp

    // The row has always been a way into that client's pane and has never looked like one, which makes
    // it an action nobody finds. A hand cursor, a lit edge and a chevron are the whole fix.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                .clickable { onFocus(client) }
                .background(if (hovered) AppTheme.Colors.surface else AppTheme.Colors.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Each cell truncates rather than wraps: a pane narrow enough to fold "17:23:17" onto two
        // lines turns a timestamp into two numbers that are not one, and the row stops being a row.
        Cell(
            text = client.clientSessionId?.targetCompID.orEmpty(),
            color = AppTheme.Colors.text,
            size = 11.sp,
            modifier = Modifier.weight(2f),
        )
        Cell(text = state.name, color = stateColor(state), modifier = Modifier.weight(1.5f))
        Cell(
            text = since?.let { TIME.format(it) }.orEmpty(),
            color = AppTheme.Colors.textDisabled,
            modifier = Modifier.weight(1.2f),
        )
        Cell(text = "${messages.size} msgs", color = AppTheme.Colors.textSecondary, modifier = Modifier.weight(1f))
        // What the venue is holding *for this client*. The roll-up rather than the book itself: this
        // pane answers "who is on my venue", and the orders belong to the pane that holds that
        // client's messages. Absent rather than zeroed when there is nothing booked — a column of
        // "0 orders" on a venue nobody has traded with is furniture, the same reason a refused-logon
        // count is only drawn for a venue.
        val book = client.orderBookFlow()?.collectAsState()?.value
        Cell(
            text =
                when {
                    book == null || book.orders.isEmpty() -> ""
                    book.working > 0 -> "${book.orders.size} orders · ${book.working} working"
                    else -> "${book.orders.size} orders"
                },
            color = if ((book?.working ?: 0) > 0) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
            modifier = Modifier.weight(1.6f),
        )
        Text(
            text = "›",
            color = if (hovered) AppTheme.Colors.primary else AppTheme.Colors.textDisabled,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun Cell(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.TextUnit = 10.sp,
) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = size,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

internal fun stateColor(state: FixConnectionState) =
    when (state) {
        FixConnectionState.LOGGED_ON -> AppTheme.Colors.success
        FixConnectionState.CONNECTED, FixConnectionState.CONNECTING -> AppTheme.Colors.info
        FixConnectionState.ERROR -> AppTheme.Colors.error
        FixConnectionState.DISCONNECTED -> AppTheme.Colors.textDisabled
    }

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
