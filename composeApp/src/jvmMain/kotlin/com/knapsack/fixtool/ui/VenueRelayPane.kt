package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.LegOutcome
import com.knapsack.fixtool.model.PartyRole
import com.knapsack.fixtool.model.RfqBookView
import com.knapsack.fixtool.model.RfqLeg

/**
 * **The parties on a venue that relays, and what is being negotiated between them** — the section of a venue's
 * pane that only a relaying venue has.
 *
 * Drawn on a venue that declares who plays what, or whose book holds anything, and on no other. The book is
 * collected rather than read, because a pane that reads a book once draws the book as it was when the pane opened;
 * and its flow is remembered per engine, since a Stop and Start builds a new one.
 */
@Composable
internal fun VenueRelaySection(venue: FixMessageSession, clients: List<FixMessageSession>) {
    val counterparties = venue.currentConfig?.counterparties.orEmpty()
    val service = venue.venueService()
    val bookFlow = remember(service) { service?.rfqBook?.views() }
    val book = bookFlow?.collectAsState()?.value ?: RfqBookView()
    if (counterparties.isEmpty() && book.rfqs.isEmpty()) return

    HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
    if (counterparties.isNotEmpty()) CounterpartyTable(counterparties, clients, book)
    RfqBookPanel(view = book, onClear = service?.let { engine -> { engine.rfqBook.clear() } })
}

/**
 * The declared counterparties as the running venue sees them: here or not, and what each has done in the book.
 *
 * A declared CompID that is not logged on is the first thing to see when a relay reports "not delivered", so it is
 * a row here whether or not it has ever connected — the client list above only has rows for those that have.
 */
@Composable
private fun CounterpartyTable(
    counterparties: List<Counterparty>,
    clients: List<FixMessageSession>,
    book: RfqBookView,
) {
    val sessions =
        clients.map { client ->
            val state by client.connectionState.collectAsState()
            client.clientSessionId?.targetCompID.orEmpty() to (state == FixConnectionState.LOGGED_ON)
        }
    val unlisted = unlistedLogons(counterparties, sessions)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val wide = maxWidth >= WIDE_BOOK_MIN
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(text = "Counterparties", color = AppTheme.Colors.textSecondary, fontSize = 10.sp)
            counterpartyActivity(counterparties, sessions, book).forEach { row -> CounterpartyRow(row, wide) }
            if (unlisted.isNotEmpty()) {
                Text(
                    text =
                        "Unlisted logons: ${unlisted.joinToString(", ")} — no counterparty covers them, " +
                            "so no relay reaches them",
                    color = AppTheme.Colors.warning,
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("venue-unlisted"),
                )
            }
        }
    }
}

/**
 * One counterparty. Stacked on a narrow pane, for the reason the RFQ book stacks: five columns at a third of the
 * window left "reque…" and "1 asked · 1 q…", which is a table that has stopped saying anything.
 */
@Composable
private fun CounterpartyRow(row: CounterpartyActivity, wide: Boolean) {
    val base =
        Modifier
            .fillMaxWidth()
            .background(AppTheme.Colors.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("venue-counterparty-${row.compId}")
    val sessionColor = if (row.loggedOn) AppTheme.Colors.success else AppTheme.Colors.textDisabled
    val owed = if (row.notDelivered > 0) "${row.notDelivered} not delivered" else ""
    val spacing = Arrangement.spacedBy(12.dp)

    if (wide) {
        Row(modifier = base, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = spacing) {
            BookCell(row.compId, AppTheme.Colors.text, Modifier.weight(1.6f), size = 11.sp)
            BookCell(row.role, AppTheme.Colors.textSecondary, Modifier.weight(1f))
            BookCell(row.session, sessionColor, Modifier.weight(1.4f))
            BookCell(row.activity, AppTheme.Colors.textSecondary, Modifier.weight(2.2f))
            BookCell(owed, AppTheme.Colors.warning, Modifier.weight(1.2f))
        }
    } else {
        Column(modifier = base) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = spacing) {
                BookCell(row.compId, AppTheme.Colors.text, Modifier.weight(1f), size = 11.sp)
                BookCell(row.session, sessionColor, Modifier.weight(1f))
            }
            Row(horizontalArrangement = spacing) {
                BookCell("${row.role} · ${row.activity}", AppTheme.Colors.textSecondary, Modifier.weight(1f))
                if (owed.isNotEmpty()) BookCell(owed, AppTheme.Colors.warning)
            }
        }
    }
}

/** One declared counterparty on a running venue: who it is, whether it is here, and what it has done. */
internal data class CounterpartyActivity(
    val compId: String,
    val role: String,
    val session: String,
    val loggedOn: Boolean,
    val activity: String,
    val notDelivered: Int,
)

/**
 * **Each declared counterparty against the sessions and the book**, one row each.
 *
 * Every logged-on CompID is counted against the one entry that decides its role — an exact entry, else the longest
 * family — so a CompID carved out of a family is not counted in both. [clients] is each client pane's CompID and
 * whether it is logged on now.
 */
internal fun counterpartyActivity(
    counterparties: List<Counterparty>,
    clients: List<Pair<String, Boolean>>,
    view: RfqBookView,
): List<CounterpartyActivity> {
    fun decides(compId: String): Counterparty? =
        counterparties
            .filter { it.covers(compId) }
            .sortedWith(compareBy<Counterparty> { it.isPrefix }.thenByDescending { it.compId.length })
            .firstOrNull()

    return counterparties.map { counterparty ->
        val covered: (String) -> Boolean = { compId -> decides(compId) === counterparty }
        val members = clients.filter { (compId, _) -> covered(compId) }
        val legs = view.rfqs.flatMap { it.legs }.filter { covered(it.compId) }
        val opened = view.rfqs.count { covered(it.requesterCompId) }
        CounterpartyActivity(
            compId = counterparty.compId,
            role = counterparty.role,
            session = sessionLabel(counterparty, members),
            loggedOn = members.any { it.second },
            activity = activityLabel(PartyRole.byWord(counterparty.role), opened, legs),
            notDelivered = legs.count { it.outcome == LegOutcome.NOT_DELIVERED },
        )
    }
}

/** "logged on", "logged out", "not logged on" — or, for a family, how many of its members are on. */
private fun sessionLabel(counterparty: Counterparty, members: List<Pair<String, Boolean>>): String {
    val online = members.count { it.second }
    return when {
        counterparty.isPrefix -> "$online of ${members.size} logged on"
        online > 0 -> "logged on"
        members.isNotEmpty() -> "logged out"
        else -> "not logged on"
    }
}

/** What a counterparty has done: the RFQs a requester opened, or how far a responder got on each it was asked. */
private fun activityLabel(role: PartyRole?, opened: Int, legs: List<RfqLeg>): String {
    fun counted(count: Int, word: String) = count.takeIf { it > 0 }?.let { "$it $word" }
    return when (role) {
        PartyRole.REQUESTER -> counted(opened, "opened")
        PartyRole.RESPONDER ->
            listOfNotNull(
                counted(legs.count { it.venueQuoteReqId != null }, "asked"),
                counted(legs.count { it.quotes.isNotEmpty() }, "quoted"),
                counted(legs.count { it.outcome == LegOutcome.PASSED }, "passed"),
                counted(legs.count { it.outcome == LegOutcome.LIFTED }, "lifted"),
                counted(legs.count { it.outcome == LegOutcome.HIT }, "hit"),
            ).joinToString(" · ").ifEmpty { null }
        null -> "not a role"
    } ?: "—"
}

/** The logged-on CompIDs no declared counterparty covers: on the venue, and nobody in any rule's terms. */
internal fun unlistedLogons(counterparties: List<Counterparty>, clients: List<Pair<String, Boolean>>): List<String> =
    clients.filter { (compId, up) -> up && counterparties.none { it.covers(compId) } }.map { it.first }
