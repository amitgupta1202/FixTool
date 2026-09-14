package com.knapsack.fixtool.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.unit.Dp
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
 * of every hop and the measured gap between them. The exception is a venue **FixTool is running**, whose
 * per-counterparty panes are one lane in the middle, requesters to its left and responders to its right;
 * an arrow that venue relayed says so under the line, from the reason it recorded when it decided.
 *
 * With nothing followed there is nothing to draw, and the panel says so rather than showing an empty
 * grid — the trace headers are listed and a click follows one, which is the gesture that fills the view.
 *
 * **Every lane is as wide as its reader makes it.** Drag a lane header's right edge, double-click it to fit
 * the lane's widest chip, double-click again for the declared width — the gesture every grid in the app
 * answers. A lane is keyed by its pane's title, so a width set while following one trace is still there
 * when the next trace crosses the same pane.
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
    /** What each lane has been dragged or fitted to. The dock holds it, so it outlives switching drawings. */
    laneWidths: GridColumnWidths = remember { laneColumnWidths() },
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
        val geometry = LaneGeometry(lanes.lanes.map { laneWidths.widthOf(laneKey(it), LANE_WIDTH) })

        Box(modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScrollState)) {
            Column(modifier = Modifier.fillMaxHeight()) {
                LaneHeaderRow(
                    lanes = lanes,
                    geometry = geometry,
                    onResize = { lane, delta -> laneWidths.resizeBy(laneKey(lane), delta, LANE_WIDTH) },
                    onFit = { lane ->
                        laneWidths.toggleFit(laneKey(lane)) { fittedLaneWidth(lane, lanes, dictionary) }
                    },
                )
                HorizontalDivider(color = AppTheme.Colors.border)
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    itemsIndexed(lanes.rows) { position, row ->
                        LaneRowView(
                            row = row,
                            position = position,
                            lanes = lanes,
                            geometry = geometry,
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

/**
 * Wide enough for the opening row's `HH:mm:ss.SSS` whole. At 84dp the monospace advance left it `08:39:22.4`, and
 * the milliseconds are the part a trace is read for.
 */
private val GUTTER_WIDTH = 100.dp
private val LANE_WIDTH = 200.dp
private val ROW_HEIGHT = 26.dp
private val HEADER_HEIGHT = 26.dp

/**
 * The width that shows this lane's widest chip, or its header, whole.
 *
 * Estimated from the text the way every grid fits, and for the same reason: measuring would lay out every row
 * of the trace to widen one lane. Only the chips a lane draws count — a hop's landing ◀ is a glyph wide. The
 * chip's own padding and the gaps between its parts are a few characters' worth, added as spaces.
 */
private fun fittedLaneWidth(
    lane: TraceLanes.Lane,
    lanes: TraceLanes.Lanes,
    dictionary: FixDictionary,
): Dp {
    val position = lanes.lanes.indexOf(lane)
    val header = "   ${lane.title} ${lane.party ?: roleWord(lane.role)}"
    val chips =
        lanes.rows
            .asSequence()
            .filter { lanes.laneOf(it.from.session) == position }
            .map { row ->
                val message = row.from.message
                "  ◀ ${message.messageType} ${chipName(message, dictionary)} ${correlationLabel(message, dictionary)}"
            }
    return fittedColumnWidth(sequenceOf(header) + chips, min = LANE_MIN_WIDTH, max = LANE_MAX_WIDTH)
}

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LaneHeaderRow(
    lanes: TraceLanes.Lanes,
    geometry: LaneGeometry,
    onResize: (TraceLanes.Lane, Dp) -> Unit,
    onFit: (TraceLanes.Lane) -> Unit,
) {
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
            // The rule itself is painted on the lane's leading edge, where it lines up with the same edge on every
            // row below. This marks where it falls without taking space from the title, and stands outside the
            // clickable cell so the cell's merged semantics do not swallow it.
            if (dividesHere) Box(modifier = Modifier.width(0.dp).testTag("trace-lane-divider"))
            Box(
                modifier =
                    Modifier
                        .width(geometry.widthOf(position))
                        .fillMaxHeight()
                        .acceptorRule(dividesHere)
                        .testTag("trace-lane-header")
                        .combinedClickable(onClick = {}, onDoubleClick = { onFit(lane) }),
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LaneDot(lane.session)
                    Text(
                        text = lane.title,
                        fontSize = 10.sp,
                        color = laneColor(lane.session),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp),
                    )
                    Text(
                        // A negotiation's own word when the venue declares one — requester, venue, responder — since
                        // on a relaying venue both edges are initiators and the wire side no longer tells them apart.
                        text = lane.party ?: roleWord(lane.role),
                        fontSize = 10.sp,
                        color = AppTheme.Colors.textDisabled,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
                ColumnResizeGrip(onResize = { onResize(lane, it) }, tag = "trace-lane-resize-$position")
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
    geometry: LaneGeometry,
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
    val relayed = relayedLabel(row.from.message)
    val height = if (relayed != null && row.to != null) RELAYED_ROW_HEIGHT else ROW_HEIGHT

    Row(modifier = Modifier.height(height).testTag("trace-lane-row")) {
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

        Box(modifier = Modifier.width(geometry.total).fillMaxHeight()) {
            // The lifelines first, so every chip and every arrow lands on top of them.
            Lifelines(lanes, geometry)

            val fromLane = lanes.laneOf(row.from.session)
            val toLane = row.to?.let { lanes.laneOf(it.session) } ?: -1
            val hops = row.to != null && fromLane >= 0 && toLane >= 0

            // The hop and the chip at a plain row's height, whatever this row's height is, so a relayed row's
            // reason gets a strip of its own underneath. Drawn in the same band as the chips, it sat where the
            // chips and the ◀ are — and between two neighbouring lanes, that is all the room there is.
            Box(modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
                if (hops) {
                    HopArrow(fromLane = fromLane, toLane = toLane, geometry = geometry)
                    Landing(fromLane = fromLane, toLane = toLane, geometry = geometry, elapsedMillis = row.hopMillis)
                }
                if (fromLane >= 0) {
                    Box(
                        modifier =
                            Modifier
                                .offset(x = geometry.startOf(fromLane))
                                .width(geometry.widthOf(fromLane))
                                .fillMaxHeight(),
                    ) {
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

            if (hops && relayed != null) {
                RelayReason(fromLane = fromLane, toLane = toLane, geometry = geometry, text = relayed)
            }
        }
    }
}

/** Every lane's thread for one row, each as wide as its lane, with the dashed rule on the lane it divides before. */
@Composable
private fun Lifelines(lanes: TraceLanes.Lanes, geometry: LaneGeometry) {
    Row(modifier = Modifier.fillMaxSize()) {
        lanes.lanes.forEachIndexed { position, _ ->
            Box(
                modifier =
                    Modifier
                        .width(geometry.widthOf(position))
                        .fillMaxHeight()
                        .lifeline()
                        .acceptorRule(position == lanes.acceptorDividerAt),
            )
        }
    }
}

/** Why a venue FixTool runs sent this message, when it relayed it — read from the recorded reason, never re-derived. */
private fun relayedLabel(message: FixMessage): String? {
    val reason = message.sendReason ?: return null
    return reason.relay?.let { relay -> "relayed · rule ${(reason.ruleIndex ?: 0) + 1} · to ${relay.address}" }
}

/**
 * The ◀ in the receiving lane, on the side facing the sender, so the direction of travel reads off the geometry as
 * well as off the glyph — and beside it, on the side away from the sender, **the hop's measured gap**.
 *
 * The gap is printed here because this is the one place on a paired row nothing else can be: a row draws one
 * chip, in the sending lane, so the receiving lane holds only this. It used to be printed halfway between the
 * two lane centres, which is inside whichever lane's chip sits on that side — right-aligned OUT chips longer
 * than half their lane covered it, which at 200dp was every chip carrying an id, and a lane fitted to its chips
 * made it every chip.
 *
 * The tooltip says what the arrow rests on and nothing more. `same bytes on both sessions` is the whole claim:
 * not that the venue forwarded it, not that the gap is the venue's fault — see [TraceLanes].
 */
@Composable
private fun Landing(fromLane: Int, toLane: Int, geometry: LaneGeometry, elapsedMillis: Long?) {
    val senderOnTheLeft = toLane > fromLane
    Box(
        modifier = Modifier.offset(x = geometry.startOf(toLane)).width(geometry.widthOf(toLane)).fillMaxHeight(),
        contentAlignment = if (senderOnTheLeft) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!senderOnTheLeft) HopGap(elapsedMillis)
            Text(
                text = "◀",
                fontSize = 10.sp,
                color = AppTheme.Colors.messageIncoming,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("trace-lane-landing").padding(horizontal = 5.dp),
            )
            if (senderOnTheLeft) HopGap(elapsedMillis)
        }
    }
}

@Composable
private fun HopGap(elapsedMillis: Long?) {
    if (elapsedMillis == null) return
    AppTooltip(text = "same bytes on both sessions") {
        Text(
            text = "+$elapsedMillis ms",
            fontSize = 9.sp,
            color = AppTheme.Colors.warning,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.background(AppTheme.Colors.background).padding(horizontal = 4.dp),
        )
    }
}

/** A relayed row's reason, in the strip under its hop, across both lanes the hop joins. */
@Composable
private fun RelayReason(fromLane: Int, toLane: Int, geometry: LaneGeometry, text: String) {
    val left = minOf(fromLane, toLane)
    val right = maxOf(fromLane, toLane)
    Box(
        modifier = Modifier.offset(x = geometry.startOf(left), y = ROW_HEIGHT).width(geometry.spanOf(left, right)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            color = AppTheme.Colors.textDisabled,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("trace-lane-reason"),
        )
    }
}

/**
 * **One hop, drawn once**: the line between the two lanes that logged the same bytes. Its measured gap is printed
 * where it lands — see [Landing] for why there and not on the middle of the line.
 *
 * The line runs lane centre to lane centre rather than edge to edge, because adjacent lanes have no
 * space between their edges — a rule that vanished whenever the two panes happened to be neighbours
 * would be a picture that stopped drawing its own subject.
 */
@Composable
private fun HopArrow(
    fromLane: Int,
    toLane: Int,
    geometry: LaneGeometry,
) {
    val left = minOf(fromLane, toLane)
    val right = maxOf(fromLane, toLane)

    Box(
        modifier =
            Modifier
                .offset(x = geometry.centreOf(left))
                .width(geometry.centreOf(right) - geometry.centreOf(left))
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
    )
}

private val HOP_COLOR = Color(0xFF3E4C5A)

/**
 * A relayed row carries its reason in a strip under the hop, so it is taller than a row that only states a gap.
 * The strip spans both lanes the hop joins, from edge to edge, because between neighbours the centre-to-centre
 * span the line is drawn over is one lane wide and the reason is longer than that.
 */
private val RELAYED_ROW_HEIGHT = 42.dp

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
 * evidence the relation never used. Two is what a lane holds at its declared width; the rest is on the row's message in
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
