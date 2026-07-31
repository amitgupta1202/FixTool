package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.BookedOrder
import com.knapsack.fixtool.model.OrderSnapshot
import com.knapsack.fixtool.model.OrderState
import com.knapsack.fixtool.service.BookView
import java.time.format.DateTimeFormatter

/**
 * **What the venue is holding for this counterparty, and how it came to think so.**
 *
 * A row is a claim; the trail under it is the evidence. Expanding an order shows the messages that
 * put it in the state it is in, each with what the order looked like *after* it — so `CumQty 2500` is
 * not something the panel asks to be believed about, it is two fills a reader can count. That is the
 * whole reason the book is a fold over a log rather than a live table (decision 6b), and the reason
 * this is in slice A rather than a polish pass: a QA tool that asks to be trusted has picked the
 * wrong side of the argument it exists to settle.
 *
 * The footer is the other half of that stance. Three numbers there say how the book may be **wrong** —
 * messages it could not attribute, orders it dropped to stay inside its cap, and whether it was
 * cleared rather than never filled — because a book that quietly omits what it does not understand
 * looks exactly like one that is working.
 */
@Composable
fun OrderBookPanel(
    book: BookView,
    title: String,
    onClear: () -> Unit,
    onClose: () -> Unit,
    /** Opens the message an event came from, by its uid — the trail's link back to the grid. */
    onOpenMessage: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf<String?>(null) }
    var showUnattributed by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(AppTheme.Colors.background)) {
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.Colors.surfaceHeader)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Orders", color = AppTheme.Colors.text, fontSize = 11.sp)
            Text(
                text = "$title  ·  ${book.orders.size} order${if (book.orders.size == 1) "" else "s"} · ${book.working} working",
                color = AppTheme.Colors.textSecondary,
                fontSize = 9.sp,
                modifier = Modifier.testTag("order-book-summary"),
            )
            Spacer(modifier = Modifier.weight(1f))
            TooltipIconButton(
                tooltip = "Clear this book — the panel will say it was cleared, not that nothing happened",
                onClick = onClear,
                modifier = Modifier.size(20.dp).testTag("order-book-clear"),
            ) {
                Icon(Icons.Default.DeleteSweep, "Clear book", tint = AppTheme.Colors.textSecondary, modifier = Modifier.size(14.dp))
            }
            TooltipIconButton(tooltip = "Hide the order book", onClick = onClose, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Default.Close, "Close", tint = AppTheme.Colors.textSecondary, modifier = Modifier.size(14.dp))
            }
        }
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

        if (book.orders.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    // Never just "no orders": a cleared book and a book nothing has happened to look
                    // identical, and they send a reader in opposite directions.
                    text =
                        book.clearedAt?.let { "Cleared ${TIME.format(it)}${book.clearedBy?.let { by -> " — $by" }.orEmpty()}" }
                            ?: "No orders yet — this venue has not been sent one",
                    color = AppTheme.Colors.textDisabled,
                    fontSize = 11.sp,
                    modifier = Modifier.testTag("order-book-empty"),
                )
            }
        } else {
            Box(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    HeaderRow()
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(book.orders.size) { index ->
                            val order = book.orders[index]
                            OrderRow(
                                order = order,
                                expanded = expanded == order.key,
                                onToggle = { expanded = if (expanded == order.key) null else order.key },
                            )
                            if (expanded == order.key) Trail(order, onOpenMessage)
                        }
                    }
                }
            }
        }

        Footer(book, showUnattributed) { showUnattributed = !showUnattributed }
        if (showUnattributed && book.unattributed.isNotEmpty()) UnattributedList(book, onOpenMessage)
    }
}

@Composable
private fun HeaderRow() {
    Row(
        modifier =
            Modifier
                .background(AppTheme.Colors.surfaceVariant)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Head("ClOrdID", CL_ORD_ID)
        Head("OrderID", ORDER_ID)
        Head("Symbol", SYMBOL)
        Head("Side", SIDE)
        Head("OrderQty", QTY, end = true)
        Head("CumQty", QTY, end = true)
        Head("LeavesQty", QTY, end = true)
        Head("AvgPx", QTY, end = true)
        Head("Status", STATUS)
        Head("Last", TIME_COL)
    }
}

@Composable
private fun Head(text: String, width: Dp, end: Boolean = false) {
    Text(
        text = text.uppercase(),
        color = AppTheme.Colors.textDisabled,
        fontSize = 8.sp,
        maxLines = 1,
        modifier = Modifier.width(width).padding(end = 6.dp),
        textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
    )
}

@Composable
private fun OrderRow(order: BookedOrder, expanded: Boolean, onToggle: () -> Unit) {
    val current = order.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .background(if (expanded) AppTheme.Colors.surfaceVariant else Color.Transparent)
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag("order-row-${order.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell("${if (expanded) "▾" else "▸"} ${order.key}", CL_ORD_ID, color = AppTheme.Colors.text)
        Cell(current.orderId ?: "—", ORDER_ID)
        Cell(current.symbol ?: "—", SYMBOL)
        Cell(sideOf(current.side), SIDE)
        Cell(current.orderQty ?: "—", QTY, end = true)
        Cell(current.cumQty ?: "—", QTY, end = true)
        Cell(current.leavesQty ?: "—", QTY, end = true)
        Cell(current.avgPx ?: "—", QTY, end = true)
        Box(modifier = Modifier.width(STATUS)) { StatusPill(order) }
        Cell(order.lastAt?.let { TIME.format(it) } ?: "", TIME_COL)
    }
}

@Composable
private fun Cell(text: String, width: Dp, end: Boolean = false, color: Color = AppTheme.Colors.textSecondary) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier.width(width).padding(end = 6.dp),
        textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
    )
}

/**
 * State in a shape as well as a word, so a book of forty orders reads at a glance — and a superseded
 * order says what superseded it, since "done" alone would leave a reader hunting for the successor.
 */
@Composable
private fun StatusPill(order: BookedOrder) {
    val current = order.current
    val label =
        when {
            order.supersededBy != null -> "replaced → ${order.supersededBy}"
            current.state == OrderState.DONE -> doneWord(current)
            else -> current.state.name.lowercase()
        }
    val colour =
        when {
            current.state == OrderState.PENDING -> AppTheme.Colors.warning
            current.state == OrderState.WORKING -> AppTheme.Colors.primary
            current.ordStatus == "2" -> AppTheme.Colors.success
            current.ordStatus == "8" -> AppTheme.Colors.error
            else -> AppTheme.Colors.textDisabled
        }
    Text(
        text = label,
        color = colour,
        fontSize = 9.sp,
        maxLines = 1,
        modifier =
            Modifier
                .padding(end = 6.dp)
                .background(colour.copy(alpha = 0.10f), RoundedCornerShape(2.dp))
                .padding(horizontal = 4.dp),
    )
}

private fun doneWord(current: OrderSnapshot): String =
    when (current.ordStatus) {
        "2" -> "filled"
        "4" -> "canceled"
        "5" -> "replaced"
        "8" -> "rejected"
        "C" -> "expired"
        else -> "done"
    }

/**
 * The evidence, one line per message: when, what it did, and what the order looked like afterwards.
 *
 * Clicking a line selects that message in the grid, because the question a trail raises next is
 * always *what exactly did we send them?*
 */
@Composable
private fun Trail(order: BookedOrder, onOpenMessage: (Long) -> Unit) {
    val snapshots = order.snapshots()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surface)
                .padding(start = 18.dp, end = 6.dp, top = 2.dp, bottom = 4.dp),
    ) {
        Text(
            "every number above is this, folded",
            color = AppTheme.Colors.textDisabled,
            fontSize = 8.sp,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        order.events.forEachIndexed { index, event ->
            val after = snapshots.getOrNull(index)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenMessage(event.messageUid) }
                        .padding(vertical = 1.dp)
                        .testTag("order-trail-${order.key}-$index"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cell(TIME.format(event.at), TIME_COL, color = AppTheme.Colors.textDisabled)
                Cell(if (event.sent) "↑ sent" else "↓ recv", DIRECTION, color = AppTheme.Colors.textDisabled)
                Cell(event.label, LABEL, color = AppTheme.Colors.text)
                Cell(
                    after?.let { "cum ${it.cumQty ?: "—"} · leaves ${it.leavesQty ?: "—"}" }.orEmpty(),
                    AFTER,
                    color = AppTheme.Colors.success,
                )
            }
        }
    }
}

@Composable
private fun Footer(book: BookView, showingUnattributed: Boolean, onToggleUnattributed: () -> Unit) {
    HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
    // 20dp buttons at 6dp spacing is a 26dp pitch, which is where a dense row stops eating its own
    // clicks — but the local is declared anyway, because the next person to add a button here should
    // not have to know that. See TooltipButtonHitAreaTest.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 20.dp) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.Colors.surfaceHeader)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            book.clearedAt?.let {
                Text(
                    "cleared ${TIME.format(it)}${book.clearedBy?.let { by -> " · $by" }.orEmpty()}",
                    color = AppTheme.Colors.textDisabled,
                    fontSize = 9.sp,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (book.evicted > 0) {
                Text(
                    "${book.evicted} evicted of ${book.cap}",
                    color = AppTheme.Colors.warning,
                    fontSize = 9.sp,
                    modifier = Modifier.testTag("order-book-evicted"),
                )
            }
            if (book.unattributedCount > 0) {
                Text(
                    text = "${book.unattributedCount} unattributed ${if (showingUnattributed) "▾" else "→"}",
                    color = AppTheme.Colors.warning,
                    fontSize = 9.sp,
                    modifier =
                        Modifier
                            .clickable(onClick = onToggleUnattributed)
                            .testTag("order-book-unattributed"),
                )
            }
        }
    }
}

/** The count opens: "2 unattributed" says something is wrong and nothing whatever about what. */
@Composable
private fun UnattributedList(book: BookView, onOpenMessage: (Long) -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        book.unattributed.takeLast(8).forEach { entry ->
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenMessage(entry.messageUid) }
                        .padding(vertical = 2.dp),
            ) {
                Text(
                    "${TIME.format(entry.at)}  35=${entry.msgType} — ${entry.why}",
                    color = AppTheme.Colors.warning,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
                Text(
                    entry.raw,
                    color = AppTheme.Colors.textDisabled,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun sideOf(side: String?): String =
    when (side) {
        "1" -> "Buy"
        "2" -> "Sell"
        "5" -> "SellShort"
        null -> "—"
        else -> side
    }

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private val CL_ORD_ID = 120.dp
private val ORDER_ID = 100.dp
private val SYMBOL = 70.dp
private val SIDE = 46.dp
private val QTY = 74.dp
private val STATUS = 120.dp
private val TIME_COL = 84.dp
private val DIRECTION = 52.dp
private val LABEL = 130.dp
private val AFTER = 190.dp
