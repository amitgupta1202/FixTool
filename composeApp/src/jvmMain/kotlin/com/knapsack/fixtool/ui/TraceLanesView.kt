package com.knapsack.fixtool.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.Conversations
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.LaneRole
import com.knapsack.fixtool.service.Located
import com.knapsack.fixtool.service.TraceLanes
import com.knapsack.fixtool.service.TraceRows
import java.time.format.DateTimeFormatter

/**
 * **Lanes** — the Trace panel's second drawing: one column per session, time running down, and every
 * message a chip in the lane of the pane that logged it.
 *
 * The Ledger answers *what is running* across every exchange at once. This answers *what happened to
 * this one*, in the shape a QA already draws it in on a whiteboard — which is why it draws one followed
 * trace and refuses to be a browser for many. Both renderings read the same rows from the same trace,
 * so they cannot disagree; see [TraceLanes] for the one place they deliberately differ, which is that a
 * pair of identical byte strings on two panes is drawn here as a single hop and in the Ledger as the two
 * rows two panes logged.
 *
 * **The venue is the space between the lanes.** Nothing is drawn there and nothing may be: FixTool holds
 * no session with itself, so the thing in the middle has no column. What the picture shows is both ends
 * of every hop and the measured gap between them.
 *
 * With nothing followed there is nothing to draw, and the panel says so rather than showing an empty
 * grid — the trace headers are listed and a click follows one, which is the gesture that fills the view.
 */
@Composable
fun TraceLanesView(
    /** The followed trace, laid out. Null when nothing is followed or the anchor has not arrived. */
    lanes: TraceLanes.Lanes?,
    /** The Ledger's headers, for the nothing-followed state — a click on one is a Follow. */
    headers: List<TraceRows.Row.Header>,
    selectedMessage: FixMessage?,
    dictionary: FixDictionary,
    appSettings: AppSettings,
    onFollow: (String) -> Unit = {},
    onSelectMember: (Located, FixMessage) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(AppTheme.Colors.background).testTag("trace-lanes")) {
        if (lanes == null || lanes.lanes.isEmpty()) {
            NothingFollowed(headers = headers, onFollow = onFollow)
            return@Box
        }

        val listState = rememberLazyListState()
        val horizontalScrollState = rememberScrollState()

        Box(modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScrollState)) {
            Column(modifier = Modifier.fillMaxHeight()) {
                LaneHeaderRow(lanes)
                HorizontalDivider(color = AppTheme.Colors.border)
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    itemsIndexed(lanes.rows) { position, row ->
                        LaneRowView(
                            row = row,
                            position = position,
                            lanes = lanes,
                            selectedMessage = selectedMessage,
                            dictionary = dictionary,
                            appSettings = appSettings,
                            onSelectMember = onSelectMember,
                        )
                    }
                }
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp),
        )
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(horizontalScrollState),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 20.dp, bottom = 4.dp)
                    .height(8.dp),
        )
    }
}

// ---------------------------------------------------------------- nothing followed

/**
 * Lanes is for one exchange, so with none chosen the honest thing to show is the choice.
 *
 * Not an empty grid and not the Ledger in disguise: the headers are listed exactly as the reason they
 * are listed — one click and the picture fills — and the line above them says why the view is empty, so
 * nobody reads a blank panel as "there are no traces".
 */
@Composable
private fun NothingFollowed(
    headers: List<TraceRows.Row.Header>,
    onFollow: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text(
            text =
                if (headers.isEmpty()) {
                    "Lanes draws one exchange at a time. Nothing has been traced yet."
                } else {
                    "Lanes draws one exchange at a time — pick one to follow, or use the Ledger to browse them all."
                },
            fontSize = 11.sp,
            color = AppTheme.Colors.textSecondary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 6.dp).testTag("trace-lanes-empty"),
        )
        val listState = rememberLazyListState()
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(headers) { _, header ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .testTag("trace-lanes-pick")
                            .clickable { onFollow(header.label) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    header.sessions.take(MAX_PICK_DOTS).forEach { LaneDot(it) }
                    Text(
                        text = header.label,
                        fontSize = 11.sp,
                        color = AppTheme.Colors.text,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                    )
                    Text(
                        text =
                            buildString {
                                append(header.sessionCount)
                                append(if (header.sessionCount == 1) " session · " else " sessions · ")
                                append(header.memberCount)
                                append(" messages")
                            },
                        fontSize = 10.sp,
                        color = AppTheme.Colors.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private const val MAX_PICK_DOTS = 5

// ---------------------------------------------------------------- the picture

private val GUTTER_WIDTH = 84.dp
private val LANE_WIDTH = 200.dp
private val ROW_HEIGHT = 26.dp
private val HEADER_HEIGHT = 26.dp

/** The colour a session is badged with — the Ledger's mapping, so one pane is one colour everywhere. */
private fun laneColor(session: Int): Color =
    AppTheme.Colors.usernameColors[
        (session % AppTheme.Colors.usernameColors.size).coerceAtLeast(0),
    ]

@Composable
private fun LaneDot(session: Int) {
    Box(
        modifier =
            Modifier
                .padding(end = 3.dp)
                .size(6.dp)
                .background(laneColor(session), CircleShape),
    )
}

/** The dashed rule the mockup draws between the side that dials out and the side that is dialled. */
private fun Modifier.acceptorRule(draw: Boolean): Modifier =
    if (!draw) {
        this
    } else {
        this.drawBehind {
            drawLine(
                color = DIVIDER_COLOR,
                start = Offset(0f, 0f),
                end = Offset(0f, size.height),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
            )
        }
    }

private val DIVIDER_COLOR = Color(0xFF4A4A4A)

/** A lane's lifeline: the faint thread the chips hang off, so an empty cell still reads as a column. */
private fun Modifier.lifeline(): Modifier =
    this.drawBehind {
        drawLine(
            color = LIFELINE_COLOR,
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height),
            strokeWidth = 1f,
        )
    }

private val LIFELINE_COLOR = Color(0xFF303030)

@Composable
private fun LaneHeaderRow(lanes: TraceLanes.Lanes) {
    val background = Color(0xFF2D2D2D)
    Row(modifier = Modifier.background(background).height(HEADER_HEIGHT)) {
        Box(modifier = Modifier.width(GUTTER_WIDTH).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = "elapsed",
                fontSize = 10.sp,
                color = AppTheme.Colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        lanes.lanes.forEachIndexed { position, lane ->
            val dividesHere = position == lanes.acceptorDividerAt
            Row(
                modifier =
                    Modifier
                        .width(LANE_WIDTH)
                        .fillMaxHeight()
                        .acceptorRule(dividesHere)
                        .testTag("trace-lane-header")
                        .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The rule itself is painted on the lane's leading edge, where it lines up with the same
                // edge on every row below. This marks where it falls without taking space from the title.
                if (dividesHere) Box(modifier = Modifier.width(0.dp).testTag("trace-lane-divider"))
                LaneDot(lane.session)
                Text(
                    text = lane.title,
                    fontSize = 10.sp,
                    color = laneColor(lane.session),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = roleWord(lane.role),
                    fontSize = 10.sp,
                    color = AppTheme.Colors.textDisabled,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The profile's own word for this side, lower-cased as the mockup writes it. */
private fun roleWord(role: LaneRole): String =
    when (role) {
        LaneRole.INITIATOR -> "initiator"
        LaneRole.ACCEPTOR -> "acceptor"
        LaneRole.UNKNOWN -> "unconfigured"
    }

@Composable
private fun LaneRowView(
    row: TraceLanes.LaneRow,
    position: Int,
    lanes: TraceLanes.Lanes,
    selectedMessage: FixMessage?,
    dictionary: FixDictionary,
    appSettings: AppSettings,
    onSelectMember: (Located, FixMessage) -> Unit,
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss.SSS") }
    // The first row is the only one with no gap to state, so it states where on the clock the exchange
    // started instead. Everything below it is a measured gap — see TraceLanes.LaneRow for which gap.
    val opens = position == 0 || row.elapsedMillis == null
    val started = row.from.message.timestamp
    val gutter = if (opens) started.format(timeFormatter) else "+${row.elapsedMillis} ms"

    Row(modifier = Modifier.height(ROW_HEIGHT).testTag("trace-lane-row")) {
        Box(modifier = Modifier.width(GUTTER_WIDTH).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = gutter,
                fontSize = 10.sp,
                color = if (opens) AppTheme.Colors.textSecondary else AppTheme.Colors.warning,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }

        Box(modifier = Modifier.width(LANE_WIDTH * lanes.lanes.size).fillMaxHeight()) {
            // The lifelines first, so every chip and every arrow lands on top of them.
            Row(modifier = Modifier.fillMaxSize()) {
                lanes.lanes.forEachIndexed { lanePosition, _ ->
                    Box(
                        modifier =
                            Modifier
                                .width(LANE_WIDTH)
                                .fillMaxHeight()
                                .lifeline()
                                .acceptorRule(lanePosition == lanes.acceptorDividerAt),
                    )
                }
            }

            val fromLane = lanes.laneOf(row.from.session)
            val toLane = row.to?.let { lanes.laneOf(it.session) } ?: -1
            if (row.to != null && fromLane >= 0 && toLane >= 0) {
                HopArrow(fromLane = fromLane, toLane = toLane, elapsedMillis = row.hopMillis)
                // The ◀ lands in the receiving lane, on the side facing the sender, so the direction of
                // travel reads off the geometry as well as off the glyph.
                Box(
                    modifier = Modifier.offset(x = LANE_WIDTH * toLane).width(LANE_WIDTH).fillMaxHeight(),
                    contentAlignment = if (toLane > fromLane) Alignment.CenterStart else Alignment.CenterEnd,
                ) {
                    Text(
                        text = "◀",
                        fontSize = 10.sp,
                        color = AppTheme.Colors.messageIncoming,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("trace-lane-landing").padding(horizontal = 5.dp),
                    )
                }
            }
            if (fromLane >= 0) {
                Box(modifier = Modifier.offset(x = LANE_WIDTH * fromLane).width(LANE_WIDTH).fillMaxHeight()) {
                    MessageChip(
                        entry = row.from,
                        selected = selectedMessage == row.from.message,
                        dictionary = dictionary,
                        appSettings = appSettings,
                        onClick = { onSelectMember(row.from.located, row.from.message) },
                    )
                }
            }
        }
    }
}

/**
 * **One hop, drawn once**: the line between the two lanes that logged the same bytes, with the measured
 * gap printed on it.
 *
 * The line runs lane centre to lane centre rather than edge to edge, because adjacent lanes have no
 * space between their edges — a rule that vanished whenever the two panes happened to be neighbours
 * would be a picture that stopped drawing its own subject.
 *
 * The tooltip says what the arrow rests on and nothing more. `same bytes on both sessions` is the whole
 * claim: not that the venue forwarded it, not that the gap is the venue's fault — see [TraceLanes].
 */
@Composable
private fun HopArrow(
    fromLane: Int,
    toLane: Int,
    elapsedMillis: Long?,
) {
    val left = minOf(fromLane, toLane)
    val right = maxOf(fromLane, toLane)

    Box(
        modifier =
            Modifier
                .offset(x = LANE_WIDTH * left + LANE_WIDTH / 2)
                .width(LANE_WIDTH * (right - left))
                .fillMaxHeight()
                .testTag("trace-lane-pair")
                .drawBehind {
                    drawLine(
                        color = HOP_COLOR,
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 1f,
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        AppTooltip(text = "same bytes on both sessions") {
            Text(
                text = elapsedMillis?.let { "+$it ms" }.orEmpty(),
                fontSize = 9.sp,
                color = AppTheme.Colors.warning,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier =
                    Modifier
                        .background(AppTheme.Colors.background)
                        .padding(horizontal = 4.dp),
            )
        }
    }
}

private val HOP_COLOR = Color(0xFF3E4C5A)

/**
 * One message in its lane: what type it is, what the dictionary calls it, and which ids carried it here.
 *
 * OUT sits right, IN sits left, and the glyph points the same way in both — out of FixTool, or into it.
 * The direction colours are the grid's own (`MessageColorScheme`), so a reader who has learned blue-out
 * and teal-in in the panes has already learned this.
 */
@Composable
private fun MessageChip(
    entry: TraceLanes.Entry,
    selected: Boolean,
    dictionary: FixDictionary,
    appSettings: AppSettings,
    onClick: () -> Unit,
) {
    val message = entry.message
    val outgoing = message.direction == FixMessage.Direction.OUTGOING
    val color =
        appSettings.messageColorScheme.getMessageColor(message.direction, message.isRejectionOrLogout(), true)
    val ids = remember(message, dictionary) { correlationLabel(message, dictionary) }
    val name = remember(message, dictionary) { chipName(message, dictionary) }
    val chipBackground = if (selected) AppTheme.Colors.selectionPrimary else AppTheme.Colors.surfaceVariant

    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(
            modifier =
                Modifier
                    .testTag("trace-lane-chip")
                    .clickable(onClick = onClick)
                    .background(chipBackground, RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!outgoing) {
                Text(text = "◀", fontSize = 9.sp, color = color, fontFamily = FontFamily.Monospace)
            }
            Text(
                text = message.messageType,
                fontSize = 10.sp,
                color = color,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = name,
                fontSize = 10.sp,
                color = AppTheme.Colors.text,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (ids.isNotEmpty()) {
                Text(
                    text = ids,
                    fontSize = 10.sp,
                    color = AppTheme.Colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (outgoing) {
                Text(text = "▶", fontSize = 9.sp, color = color, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/**
 * What the chip calls this message.
 *
 * An ExecutionReport says the status it *stated* rather than its own name, because on a lane the useful
 * word is `Filled`, not `ExecutionReport` said six times down one column — and it is a quotation either
 * way: `Filled` appears because a reply carried `39=2` and the dictionary calls that FILLED, exactly the
 * rule `Conversations.Summary` keeps. With no status stated, or no dictionary word for it, the message
 * type's own name stands.
 */
private fun chipName(
    message: FixMessage,
    dictionary: FixDictionary,
): String {
    val typeName = dictionary.getFieldValueDescription(35, message.messageType) ?: message.messageType
    if (message.messageType != EXECUTION_REPORT) return typeName
    val status =
        FixMessageHelper
            .fieldsForDisplay(message)
            .firstOrNull { it.first == ORD_STATUS }
            ?.second
            ?.takeIf { it.isNotBlank() }
            ?: return typeName
    return dictionary.getFieldValueDescription(ORD_STATUS, status) ?: typeName
}

private const val EXECUTION_REPORT = "8"
private const val ORD_STATUS = 39

/**
 * The ids this message carried, at most two.
 *
 * [Conversations.idsOf] is the one decider about which tags are correlation ids, here as everywhere —
 * the chip must cite the same values the grouping joined on, or the picture would explain itself with
 * evidence the relation never used. Two is what a 200dp lane holds; the rest is on the row's message in
 * the pane a click raises.
 */
private fun correlationLabel(
    message: FixMessage,
    dictionary: FixDictionary,
): String {
    val ids = Conversations.idsOf(message, dictionary)
    if (ids.isEmpty()) return ""
    return ids.take(MAX_CHIP_IDS).joinToString(" ") { it.second } + if (ids.size > MAX_CHIP_IDS) " …" else ""
}

private const val MAX_CHIP_IDS = 2
