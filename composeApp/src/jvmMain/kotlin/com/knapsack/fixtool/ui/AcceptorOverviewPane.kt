package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessageSession
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
 */
@Composable
fun AcceptorOverviewPane(
    venue: FixMessageSession,
    clients: List<FixMessageSession>,
    onFocusClient: (FixMessageSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by venue.connectionState.collectAsState()
    val refused by venue.refusedLogons.collectAsState()
    val config = venue.currentConfig
    val status = venue.acceptorStatus()

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
            text =
                buildString {
                    append(config?.senderCompID.orEmpty().ifBlank { "?" })
                    append("  ·  ")
                    append(
                        if (state == FixConnectionState.CONNECTED) {
                            "listening on ${config?.socketAcceptPort?.ifBlank { config.port }.orEmpty().ifBlank { "?" }}"
                        } else {
                            "not listening"
                        },
                    )
                    append("  ·  ")
                    append(clientCountLabel(clients))
                },
            color = if (state == FixConnectionState.CONNECTED) AppTheme.Colors.text else AppTheme.Colors.warning,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )

        if (clients.isEmpty()) {
            Text(
                text = "No client has logged on yet. Any counterparty addressing ${config?.senderCompID.orEmpty()} will open its own tab.",
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
                            "${event.sessionId.senderCompID} — this venue is ${config?.senderCompID.orEmpty()}",
                    color = AppTheme.Colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }

        if (status != null) {
            HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
            Text(
                text =
                    "rules live ${status.rulesLive}  ·  latency ${if (status.latencyActive) "on" else "off"}  ·  " +
                        "triggered ${status.triggersMatched}  ·  sent ${status.responsesSent}  ·  " +
                        "pending ${status.pendingResponses}",
                color = AppTheme.Colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            Text(
                text = "Response rules apply to every client on this venue.",
                color = AppTheme.Colors.textDisabled,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun ClientRow(client: FixMessageSession, onFocus: (FixMessageSession) -> Unit) {
    val state by client.connectionState.collectAsState()
    val messages by client.messages.collectAsState()
    val since = messages.firstOrNull()?.timestamp

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onFocus(client) }
                .background(AppTheme.Colors.surfaceVariant)
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
        val book = client.orderBook()
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

/**
 * "3 clients", counting only those actually on the venue right now.
 *
 * Panes outlive their sessions on purpose — a client's history is most wanted just after it drops —
 * so the number of tabs is not the number of connections, and a header that said "5 clients" over
 * two live ones would be the more misleading of the two numbers.
 */
private fun clientCountLabel(clients: List<FixMessageSession>): String {
    val live = clients.count { it.connectionState.value == FixConnectionState.LOGGED_ON }
    val gone = clients.size - live
    return when {
        clients.isEmpty() -> "no clients"
        gone == 0 -> "$live connected"
        else -> "$live connected, $gone gone"
    }
}

private fun stateColor(state: FixConnectionState) =
    when (state) {
        FixConnectionState.LOGGED_ON -> AppTheme.Colors.success
        FixConnectionState.CONNECTED, FixConnectionState.CONNECTING -> AppTheme.Colors.info
        FixConnectionState.ERROR -> AppTheme.Colors.error
        FixConnectionState.DISCONNECTED -> AppTheme.Colors.textDisabled
    }

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
