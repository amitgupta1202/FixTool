package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.LegOutcome
import com.knapsack.fixtool.model.LegQuote
import com.knapsack.fixtool.model.PartyRole
import com.knapsack.fixtool.model.RfqBookView
import com.knapsack.fixtool.model.RfqEntry
import com.knapsack.fixtool.model.RfqLeg
import com.knapsack.fixtool.model.RfqLife
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * **Every negotiation a relaying venue is carrying, and how each side knows it.**
 *
 * On the venue's own pane because it is the one thing that spans counterparties: each client's pane holds that
 * client's messages, and an RFQ is a requester's message, two dealers' quotes and a lift, spread over three of them.
 *
 * Each leg prints its quote id **both ways** — the dealer's own 117 and the one the venue gave the requester for
 * it — because that pair is what `${to.117}` reads when a rule writes to one side or the other, and the first thing
 * anyone debugging a relay needs is to see which id went where.
 *
 * The clock is read when the book changes, not on a timer. A live RFQ past its expiry says "expired" the next time
 * anything happens on the venue; until the venue expires RFQs by itself (slice C), that is also when it would act.
 */
@Composable
fun RfqBookPanel(
    view: RfqBookView,
    onClear: (() -> Unit)?,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        RfqBookContent(view, onClear, now, wide = maxWidth >= WIDE_BOOK_MIN)
    }
}

/**
 * Below this a row is stacked over two or three lines instead of spread over one.
 *
 * A venue's pane shares the window with every client's, so it is often a third of it: at ~420dp, five columns
 * left an id four characters and a state three, and the book said nothing legible. Stacked, each line gets the
 * whole width and the facts a reader scans for — which RFQ, how it ended — lead their lines.
 */
internal val WIDE_BOOK_MIN = 640.dp

@Composable
private fun RfqBookContent(
    view: RfqBookView,
    onClear: (() -> Unit)?,
    now: Long,
    wide: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "RFQ book", color = AppTheme.Colors.textSecondary, fontSize = 10.sp)
            Text(
                text = rfqBookSummary(view, now),
                color = AppTheme.Colors.textDisabled,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.testTag("rfq-book-summary"),
            )
            Spacer(Modifier.weight(1f))
            if (onClear != null && view.rfqs.isNotEmpty()) {
                SlimButton(text = "Clear book", onClick = onClear, modifier = Modifier.testTag("rfq-book-clear"))
            }
        }

        if (view.rfqs.isEmpty()) {
            Text(
                text = "No RFQ yet. A requester's QuoteRequest opens one the moment a rule relays it.",
                color = AppTheme.Colors.textDisabled,
                fontSize = 10.sp,
            )
            return@Column
        }

        // Newest first: the negotiation being watched is the one that just started.
        view.rfqs.sortedByDescending { it.openedAt }.forEach { entry ->
            RfqRow(entry, now, wide)
            // The legs that reached somebody first, in the order they were asked; the ones owed a message last.
            entry.legs.sortedBy { it.outcome == LegOutcome.NOT_DELIVERED }.forEach { leg -> LegRow(entry, leg, now, wide) }
        }
    }
}

@Composable
private fun RfqRow(entry: RfqEntry, now: Long, wide: Boolean) {
    val life = entry.lifeAt(now)
    val base =
        Modifier
            .fillMaxWidth()
            .padding(top = 3.dp)
            .background(AppTheme.Colors.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag("rfq-row-${entry.rfqId}")
    val state: @Composable (Modifier) -> Unit = { mod ->
        BookCell(rfqStateLabel(entry, now), lifeColor(life), mod.testTag("rfq-state-${entry.rfqId}"))
    }
    val requester = "${entry.requesterCompId} ${entry.requesterQuoteReqId}"

    if (wide) {
        Row(modifier = base, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BookCell(entry.rfqId, AppTheme.Colors.text, Modifier.weight(1f), size = 11.sp)
            BookCell(instrumentLabel(entry), AppTheme.Colors.text, Modifier.weight(2f))
            BookCell(sideAndSize(entry), AppTheme.Colors.textSecondary, Modifier.weight(1.2f))
            BookCell(requester, AppTheme.Colors.textSecondary, Modifier.weight(2f))
            state(Modifier.weight(1.6f))
        }
    } else {
        Column(modifier = base) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BookCell(entry.rfqId, AppTheme.Colors.text, Modifier.weight(1f), size = 11.sp)
                state(Modifier.weight(2f))
            }
            BookCell("${instrumentLabel(entry)} · ${sideAndSize(entry)}", AppTheme.Colors.text)
            BookCell(requester, AppTheme.Colors.textSecondary)
        }
    }
}

@Composable
private fun LegRow(entry: RfqEntry, leg: RfqLeg, now: Long, wide: Boolean) {
    val level = leg.quotes.lastOrNull()?.let { levelLabel(it, entry.securityType) }
    val base =
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, top = 1.dp, bottom = 1.dp)
            .testTag("rfq-leg-${entry.rfqId}-${leg.compId}")
    val state: @Composable (Modifier) -> Unit = { mod ->
        BookCell(legStateLabel(leg, now), legColor(leg, now), mod.testTag("rfq-leg-state-${entry.rfqId}-${leg.compId}"))
    }

    if (wide) {
        Row(modifier = base, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BookCell(leg.compId, AppTheme.Colors.text, Modifier.weight(1f))
            BookCell(legIds(leg), AppTheme.Colors.textSecondary, Modifier.weight(2.2f))
            BookCell(level.orEmpty(), AppTheme.Colors.text, Modifier.weight(2f))
            state(Modifier.weight(1.6f))
        }
    } else {
        Column(modifier = base) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BookCell(leg.compId, AppTheme.Colors.text, Modifier.weight(1f))
                state(Modifier.weight(2f))
            }
            BookCell(legIds(leg), AppTheme.Colors.textSecondary)
            level?.let { BookCell(it, AppTheme.Colors.text) }
        }
    }
}

@Composable
private fun BookCell(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: TextUnit = 10.sp,
) {
    // Truncated rather than wrapped, like the client rows above it: an id folded over two lines reads as two ids.
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

private fun lifeColor(life: RfqLife) =
    when (life) {
        RfqLife.REQUESTED, RfqLife.OPEN -> AppTheme.Colors.primary
        RfqLife.DONE -> AppTheme.Colors.success
        RfqLife.EXPIRED, RfqLife.REFUSED -> AppTheme.Colors.warning
        RfqLife.PASSED -> AppTheme.Colors.textSecondary
    }

private fun legColor(leg: RfqLeg, now: Long) =
    when (leg.outcome) {
        LegOutcome.NOT_DELIVERED -> AppTheme.Colors.warning
        LegOutcome.LIFTED -> AppTheme.Colors.success
        null -> if (leg.currentQuote(now) != null) AppTheme.Colors.primary else AppTheme.Colors.textSecondary
        else -> AppTheme.Colors.textSecondary
    }

// ------------------------------------------------------------------ what the rows say

/** "3 RFQs · 1 open" — plus the evictions, which are RFQs the book no longer holds and must not look like none. */
internal fun rfqBookSummary(view: RfqBookView, now: Long): String {
    val live = view.rfqs.count { it.lifeAt(now).live }
    return listOfNotNull(
        "${view.rfqs.size} RFQ${if (view.rfqs.size == 1) "" else "s"}",
        live.takeIf { it > 0 }?.let { "$it open" },
        view.evicted.takeIf { it > 0 }?.let { "$it evicted" },
    ).joinToString(" · ")
}

internal fun instrumentLabel(entry: RfqEntry): String =
    listOfNotNull(entry.symbol, entry.securityId?.takeIf { it != entry.symbol }).joinToString(" ").ifEmpty { "—" }

/** "Buy · 10mm", "Two-way · 25mm". An RFQ with no side asks for both, which is what a two-way request is. */
internal fun sideAndSize(entry: RfqEntry): String {
    val side =
        when (entry.side) {
            "1" -> "Buy"
            "2" -> "Sell"
            null -> "Two-way"
            else -> "54=${entry.side}"
        }
    return entry.qty?.let { "$side · ${sizeLabel(it)}" } ?: side
}

/** Whole millions the way a rates desk says them — `10mm` — and anything else as it came. */
internal fun sizeLabel(qty: String): String {
    val value = qty.toBigDecimalOrNull() ?: return qty
    val millions = value.divide(MILLION)
    return if (value.signum() > 0 && millions.stripTrailingZeros().scale() <= 0) "${millions.toBigInteger()}mm" else qty
}

internal fun rfqStateLabel(entry: RfqEntry, now: Long): String {
    val life = entry.lifeAt(now)
    return when {
        life.live && entry.expireAt != null -> "${life.word} · expires ${clock(entry.expireAt)}"
        life == RfqLife.DONE -> entry.legs.firstOrNull { it.outcome == LegOutcome.LIFTED }?.let { "done · ${it.compId} lifted" } ?: "done"
        else -> life.word
    }
}

/**
 * The ids one leg carries, as each side knows them: `D1-Q-88 ↔ V-Q-1042-1` once quoted — the dealer's, then the
 * requester's — and the QuoteReqID the dealer was sent until then.
 */
internal fun legIds(leg: RfqLeg): String {
    val quote = leg.quotes.lastOrNull()
    return when {
        quote != null -> quote.dealerQuoteId + (quote.venueQuoteId?.let { " ↔ $it" } ?: " ↔ not shown yet")
        leg.venueQuoteReqId != null -> "asked as ${leg.venueQuoteReqId}"
        else -> "—"
    }
}

internal fun legStateLabel(leg: RfqLeg, now: Long): String {
    leg.outcome?.let { return it.word }
    val quote = leg.quotes.lastOrNull() ?: return if (leg.venueQuoteReqId != null) "asked" else "—"
    return when {
        leg.currentQuote(now) == null -> "lapsed"
        quote.validUntil != null -> "quoted · valid to ${clock(quote.validUntil)}"
        else -> "quoted"
    }
}

/**
 * A quote's level: `98-16+ (98.515625) offer`. Thirty-seconds first for a Treasury, because that is how one is
 * quoted and read aloud, with the decimal the wire carries beside it so the two can be checked against each other.
 */
internal fun levelLabel(quote: LegQuote, securityType: String?): String {
    val treasury = securityType?.uppercase() in QUOTED_IN_32NDS
    fun price(decimal: String) = (if (treasury) thirtySeconds(decimal) else null)?.let { "$it ($decimal)" } ?: decimal
    return listOfNotNull(
        quote.bid?.let { "${price(it)} bid" },
        quote.offer?.let { "${price(it)} offer" },
    ).joinToString(" / ")
}

/**
 * A decimal price in Treasury thirty-seconds: `98.515625` is `98-16+`.
 *
 * The third place is eighths of a thirty-second, written the way the market writes it — `+` for a half, a digit
 * for the rest (`98-162` is 16¼) — and a price that is not a whole number of 256ths is not a 32nds price at all,
 * so it is null rather than rounded into one it never was.
 */
internal fun thirtySeconds(decimal: String): String? {
    val value = decimal.toBigDecimalOrNull() ?: return null
    if (value.signum() < 0) return null
    val scaled = value.multiply(EIGHTHS_OF_A_32ND)
    if (scaled.stripTrailingZeros().scale() > 0) return null
    val units = scaled.toBigInteger().toLong()
    val handle = units / EIGHTHS_OF_A_32ND_INT
    val ticks = (units % EIGHTHS_OF_A_32ND_INT) / EIGHTHS_PER_TICK
    val eighths = (units % EIGHTHS_OF_A_32ND_INT) % EIGHTHS_PER_TICK
    val suffix =
        when (eighths) {
            0L -> ""
            HALF_TICK -> "+"
            else -> eighths.toString()
        }
    return "$handle-${ticks.toString().padStart(2, '0')}$suffix"
}

// ------------------------------------------------------------------ counterparties, as the venue sees them

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
        val members = clients.filter { (compId, _) -> decides(compId) === counterparty }
        val online = members.count { it.second }
        val session =
            when {
                counterparty.isPrefix -> "$online of ${members.size} logged on"
                online > 0 -> "logged on"
                members.isNotEmpty() -> "logged out"
                else -> "not logged on"
            }
        val covered: (String) -> Boolean = { compId -> decides(compId) === counterparty }
        val legs = view.rfqs.flatMap { it.legs }.filter { covered(it.compId) }
        val activity =
            when (PartyRole.byWord(counterparty.role)) {
                PartyRole.REQUESTER -> view.rfqs.count { covered(it.requesterCompId) }.let { if (it > 0) "$it opened" else "—" }
                PartyRole.RESPONDER ->
                    listOfNotNull(
                        legs.count { it.venueQuoteReqId != null }.takeIf { it > 0 }?.let { "$it asked" },
                        legs.count { it.quotes.isNotEmpty() }.takeIf { it > 0 }?.let { "$it quoted" },
                        legs.count { it.outcome == LegOutcome.PASSED }.takeIf { it > 0 }?.let { "$it passed" },
                        legs.count { it.outcome == LegOutcome.LIFTED }.takeIf { it > 0 }?.let { "$it lifted" },
                    ).joinToString(" · ").ifEmpty { "—" }
                null -> "not a role"
            }
        CounterpartyActivity(
            compId = counterparty.compId,
            role = counterparty.role,
            session = session,
            loggedOn = online > 0,
            activity = activity,
            notDelivered = legs.count { it.outcome == LegOutcome.NOT_DELIVERED },
        )
    }
}

/** The logged-on CompIDs no declared counterparty covers: on the venue, and nobody in any rule's terms. */
internal fun unlistedLogons(counterparties: List<Counterparty>, clients: List<Pair<String, Boolean>>): List<String> =
    clients.filter { (compId, up) -> up && counterparties.none { it.covers(compId) } }.map { it.first }

private fun clock(epochMillis: Long): String = CLOCK.format(Instant.ofEpochMilli(epochMillis))

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private val MILLION = BigDecimal(1_000_000)

/** The Treasury coupon securities the cash market quotes in 32nds. Bills trade on discount yield and are left out. */
private val QUOTED_IN_32NDS = setOf("TNOTE", "TBOND", "UST", "TIPS")

private const val EIGHTHS_OF_A_32ND_INT = 256L
private val EIGHTHS_OF_A_32ND = BigDecimal(EIGHTHS_OF_A_32ND_INT)
private const val EIGHTHS_PER_TICK = 8L
private const val HALF_TICK = 4L
