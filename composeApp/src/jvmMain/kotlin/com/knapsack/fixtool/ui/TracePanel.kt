package com.knapsack.fixtool.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.knapsack.fixtool.service.Located
import com.knapsack.fixtool.service.TraceKey
import com.knapsack.fixtool.service.TraceLanes
import com.knapsack.fixtool.service.TraceRows
import com.knapsack.fixtool.viewmodel.TraceRendering
import java.time.format.DateTimeFormatter

/**
 * **The Ledger** — every exchange the app is holding, across every session, in one grid.
 *
 * It lives in the bottom slot the pinned search results live in, and it is drawn like them on purpose:
 * the same flat grid, the same per-session colour badges, the same click that raises a pane and selects
 * a message. A reader switching between the two panels should be switching *questions*, not learning a
 * second grid.
 *
 * The two questions are different, though, and the columns say which is which. `SearchResultsPane`
 * answers *which messages matched what I typed* — a query the reader must already know how to write.
 * This answers *what exchanges are running, and which of them crossed more than one session*, which
 * nobody has to know anything to ask. That is why the headers carry the session count and the fold is
 * shut by default: the headers are the answer, and the messages beneath them are the evidence.
 *
 * **Elapsed is a measurement, not a diagnosis** — see [TraceRows] for what the number is and what it
 * refuses to claim. It is deliberately the only time column here; the per-session latency column, whose
 * request/response pairing is defined only inside one pane, stays in the panes.
 *
 * Closing the panel does not stop following. The chip in the toolbar still names what every pane is
 * narrowed to, and a reader who shut a panel did not ask to change what they are looking at.
 */
@Composable
fun TracePanel(
    rows: List<TraceRows.Row>,
    /** Positional, parallel to `Located.session` — the titles the badges and the status line read. */
    sessionTitles: List<String>,
    selectedMessage: FixMessage?,
    dictionary: FixDictionary,
    appSettings: AppSettings,
    /**
     * What the toolbar chip says is followed, or null.
     *
     * Passed rather than derived from [rows] because an anchor can be followed before it exists — an id
     * a venue mints three hops in is followed the moment it is clicked — and a panel that said
     * "12 traces" while the chip said "Following V-8813" would be two answers to one question.
     */
    followingLabel: String? = null,
    /** Which drawing is on screen. One panel, two renderings of the same rows — see [TraceLanes]. */
    rendering: TraceRendering = TraceRendering.LEDGER,
    /**
     * The followed trace laid out in lanes, or null when nothing is followed.
     *
     * Handed in already built rather than derived here, exactly as [rows] is, and for the same reason:
     * both are memoised against the one index generation they address, and a panel that rebuilt one of
     * them from a fresher index than the other would draw two answers to one question.
     */
    lanes: TraceLanes.Lanes? = null,
    /**
     * What the Ledger's columns have been dragged to. The dock holds it, so a width set on the Ledger survives
     * switching to the search results and back.
     */
    columnWidths: GridColumnWidths = remember { GridColumnWidths() },
    onSetRendering: (TraceRendering) -> Unit = {},
    onToggleTrace: (TraceKey) -> Unit = {},
    onToggleUngrouped: () -> Unit = {},
    onExpandAll: () -> Unit = {},
    onCollapseAll: () -> Unit = {},
    onFollow: (String) -> Unit = {},
    onUnfollow: () -> Unit = {},
    onSelectMember: (Located, FixMessage) -> Unit = { _, _ -> },
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val headers = remember(rows) { rows.filterIsInstance<TraceRows.Row.Header>() }
    val ungroupedCount = remember(rows) { rows.filterIsInstance<TraceRows.Row.UngroupedHeader>().sumOf { it.count } }
    val followed = remember(headers) { headers.firstOrNull { it.isFollowed } }
    // Sessions a trace actually touches, not the app's session count: "5 sessions" beside "1 trace" reads as a
    // claim the trace spans five, and the acceptor's parent pane holds no messages at all.
    val spannedSessions = remember(headers) { headers.flatMap { it.sessions }.distinct().size }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.background)
                .testTag("trace-panel"),
    ) {
        TracePanelHeaderBar(
            status = statusLine(followed, followingLabel, headers.size, spannedSessions, ungroupedCount),
            rendering = rendering,
            onSetRendering = onSetRendering,
            onExpandAll = onExpandAll,
            onCollapseAll = onCollapseAll,
            onClose = onClose,
        )

        if (rendering == TraceRendering.LANES) {
            TraceLanesView(
                lanes = lanes,
                headers = headers,
                selectedMessage = selectedMessage,
                dictionary = dictionary,
                appSettings = appSettings,
                onFollow = onFollow,
                onSelectMember = onSelectMember,
            )
            return@Column
        }

        val widths = TraceColumns(columnWidths, appSettings.gridViewColumns)
        Box(modifier = Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            val horizontalScrollState = rememberScrollState()

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .horizontalScroll(horizontalScrollState),
            ) {
                Column(modifier = Modifier.fillMaxHeight()) {
                    TraceGridHeader(
                        widths = widths,
                        dictionary = dictionary,
                        onFit = { key ->
                            columnWidths.toggleFit(key) { fittedTraceColumn(key, rows, sessionTitles, dictionary) }
                        },
                    )

                    HorizontalDivider(color = AppTheme.Colors.border)

                    LazyColumn(
                        state = listState,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                    ) {
                        items(rows) { row ->
                            when (row) {
                                is TraceRows.Row.Header ->
                                    TraceHeaderRow(
                                        header = row,
                                        widths = widths,
                                        onToggle = { onToggleTrace(row.key) },
                                        onFollow = onFollow,
                                        onUnfollow = onUnfollow,
                                    )

                                is TraceRows.Row.UngroupedHeader ->
                                    UngroupedHeaderRow(header = row, widths = widths, onToggle = onToggleUngrouped)

                                is TraceRows.Row.Member ->
                                    TraceMemberRow(
                                        member = row,
                                        sessionTitles = sessionTitles,
                                        isSelected = selectedMessage == row.message,
                                        widths = widths,
                                        dictionary = dictionary,
                                        appSettings = appSettings,
                                        onClick = { onSelectMember(row.located, row.message) },
                                    )
                            }
                        }
                    }
                }
            }

            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(end = 4.dp),
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
}

// ---------------------------------------------------------------- the panel's own header bar

@Composable
private fun TracePanelHeaderBar(
    status: String,
    rendering: TraceRendering,
    onSetRendering: (TraceRendering) -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onClose: () -> Unit,
) {
    DockHeader(
        window = ToolWindow.TRACE,
        onHide = onClose,
        // What it is following, which is the one thing this dock has to say in its own words.
        status = status,
        statusTag = "trace-panel-status",
        // Ledger and Lanes stay a segmented control rather than becoming two actions: they are one view of
        // one thing, and a reader moving between them is changing the question they are asking of the rows
        // already on screen.
        leading = { RenderingToggle(rendering, onSetRendering) },
        leadingWidth = RENDERING_TOGGLE_WIDTH,
        // Folding is the Ledger's own gesture and Lanes has nothing to fold, so the two actions go when it
        // does rather than sitting there doing nothing. Icon buttons now, with the tooltips two bare text
        // actions never had.
        actions =
            if (rendering != TraceRendering.LEDGER) {
                emptyList()
            } else {
                listOf(
                    BarAction("Expand all", Icons.Default.UnfoldMore, onExpandAll, "trace-expand-all"),
                    BarAction("Collapse all", Icons.Default.UnfoldLess, onCollapseAll, "trace-collapse-all"),
                )
            },
        // Hides the dock and nothing else: the panes stay narrowed and the chip goes on saying so.
        hideTooltip = "Hide Trace (keeps following)",
        hideTag = "trace-close",
    )
}

/** Ledger and Lanes side by side, as the fold has to budget for them. */
private val RENDERING_TOGGLE_WIDTH = 92.dp

/**
 * **Ledger | Lanes** — the same trace rows, two drawings, one segmented control.
 *
 * A toggle rather than a second panel because they are one view of one thing: the Ledger browses every
 * exchange, Lanes reads one of them closely, and a reader moving between the two is changing the
 * question they are asking of the rows already on screen. Two panels would make it a choice about
 * screen real estate instead.
 */
@Composable
private fun RenderingToggle(
    rendering: TraceRendering,
    onSetRendering: (TraceRendering) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
        RenderingSegment("Ledger", "trace-render-ledger", rendering == TraceRendering.LEDGER) {
            onSetRendering(TraceRendering.LEDGER)
        }
        RenderingSegment("Lanes", "trace-render-lanes", rendering == TraceRendering.LANES) {
            onSetRendering(TraceRendering.LANES)
        }
    }
}

@Composable
private fun RenderingSegment(
    label: String,
    tag: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        fontSize = 10.sp,
        color = if (selected) AppTheme.Colors.text else AppTheme.Colors.textSecondary,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier =
            Modifier
                .testTag(tag)
                .background(if (selected) AppTheme.Colors.selectionPrimary else Color.Transparent)
                .clickable { onClick() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun TextAction(
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        fontSize = 10.sp,
        color = AppTheme.Colors.textSecondary,
        fontFamily = FontFamily.Monospace,
        modifier =
            Modifier
                .testTag(tag)
                .clickable { onClick() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * What the panel is showing, in one line.
 *
 * Following wins over counting because it is the narrower, more urgent fact: a reader who followed
 * something wants to know it took. `not yet arrived` rather than a silent absence — an id followed
 * before the venue echoed it is a state this app deliberately holds, and reporting it as zero traces
 * would read as the exchange never having happened.
 */
private fun statusLine(
    followed: TraceRows.Row.Header?,
    followingLabel: String?,
    traceCount: Int,
    sessionCount: Int,
    ungrouped: Int,
): String =
    when {
        followed != null ->
            "Following ${followed.label} · ${plural(followed.sessionCount, "session")} · " +
                "${plural(followed.memberCount, "message")} · ${followed.summary.exchange.elapsedMillis} ms"

        followingLabel != null -> "Following $followingLabel · not yet arrived"
        traceCount == 0 -> "No traces yet · $ungrouped ungrouped"
        else -> "${plural(traceCount, "trace")} across ${plural(sessionCount, "session")} · $ungrouped ungrouped"
    }

private fun plural(
    count: Int,
    noun: String,
): String = "$count $noun${if (count == 1) "" else "s"}"

// ---------------------------------------------------------------- the grid

private val FOLD_WIDTH = 24.dp
private val SESSION_WIDTH = 120.dp
private val TIME_WIDTH = 120.dp
private val DIR_WIDTH = 50.dp
private val SEQ_WIDTH = 70.dp
private val MSGTYPE_WIDTH = 100.dp
private val SUMMARY_WIDTH = 200.dp
private val ELAPSED_WIDTH = 90.dp
private val TAG_WIDTH = 120.dp

private const val SESSION = "Session"
private const val TIME = "Time"
private const val DIR = "Dir"
private const val SEQ = "SeqNum"
private const val MSGTYPE = "MsgType"
private const val SUMMARY = "Summary"
private const val ELAPSED = "Elapsed"

private fun tagKey(tag: Int): String = "Tag_$tag"

/**
 * **The Ledger's columns at the widths they are now** — one place every row reads them from.
 *
 * The header, the trace rows, the ungrouped row and the member rows each used to add up the same constants for
 * themselves, which held only while nothing could change them. A width read from here is the dragged one
 * wherever it is read, and a span is the sum of the columns it covers, so a group row's edges fall on the
 * header's rules at every width.
 *
 * The getters read [widths] where the cell draws, not where this is built, so a drag recomposes the cells it
 * moved.
 */
private class TraceColumns(
    private val widths: GridColumnWidths,
    val tags: List<Int>,
) {
    val session get() = widths.widthOf(SESSION, SESSION_WIDTH)
    val time get() = widths.widthOf(TIME, TIME_WIDTH)
    val dir get() = widths.widthOf(DIR, DIR_WIDTH)
    val seq get() = widths.widthOf(SEQ, SEQ_WIDTH)
    val msgType get() = widths.widthOf(MSGTYPE, MSGTYPE_WIDTH)
    val summary get() = widths.widthOf(SUMMARY, SUMMARY_WIDTH)
    val elapsed get() = widths.widthOf(ELAPSED, ELAPSED_WIDTH)

    fun tag(tag: Int): Dp = widths.widthOf(tagKey(tag), TAG_WIDTH)

    /** Time+Dir+SeqNum+MsgType, which a header row has no per-message values for. */
    val identity get() = time + dir + seq + msgType

    /** Every tag column, which a trace header spends on the things only a header says. */
    val tagSpan get() = tags.fold(0.dp) { sum, tag -> sum + tag(tag) }

    fun resize(
        key: String,
        default: Dp,
    ): (Dp) -> Unit = { delta -> widths.resizeBy(key, delta, default) }
}

@Composable
private fun cell(
    width: Dp,
    background: Color,
    alignment: Alignment = Alignment.CenterStart,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .width(width)
                .fillMaxHeight()
                .background(background)
                .border(0.5.dp, AppTheme.Colors.border),
        contentAlignment = alignment,
    ) { content() }
}

@Composable
private fun TraceGridHeader(
    widths: TraceColumns,
    dictionary: FixDictionary,
    onFit: (String) -> Unit,
) {
    fun resizable(
        key: String,
        label: String,
        width: Dp,
        default: Dp,
        align: Alignment = Alignment.Center,
    ) = GridColumn(
        label = label,
        width = width,
        align = align,
        tag = "trace-column-$key",
        onResize = widths.resize(key, default),
        onDoubleClick = { onFit(key) },
    )

    GridHeader(
        listOf(
            GridColumn("", FOLD_WIDTH),
            resizable(SESSION, SESSION, widths.session, SESSION_WIDTH),
            resizable(TIME, TIME, widths.time, TIME_WIDTH),
            resizable(DIR, DIR, widths.dir, DIR_WIDTH),
            resizable(SEQ, SEQ, widths.seq, SEQ_WIDTH),
            resizable(MSGTYPE, MSGTYPE, widths.msgType, MSGTYPE_WIDTH),
            resizable(SUMMARY, SUMMARY, widths.summary, SUMMARY_WIDTH, Alignment.CenterStart),
            resizable(ELAPSED, ELAPSED, widths.elapsed, ELAPSED_WIDTH, Alignment.CenterEnd),
        ) +
            widths.tags.map { tag ->
                resizable(tagKey(tag), dictionary.getFieldName(tag) ?: tag.toString(), widths.tag(tag), TAG_WIDTH)
            },
    )
}

/**
 * The width a double-click fits a Ledger column to: its header word, and every value the rows on screen put in it.
 *
 * Only the rows the fold has open — a column fitted to messages nobody can see would be wider than anything
 * on screen needs. A trace header's own text counts for the columns it draws in, so fitting Summary does not
 * cut the composition line short.
 */
private fun fittedTraceColumn(
    key: String,
    rows: List<TraceRows.Row>,
    sessionTitles: List<String>,
    dictionary: FixDictionary,
): Dp {
    val members = rows.asSequence().filterIsInstance<TraceRows.Row.Member>()
    val headers = rows.asSequence().filterIsInstance<TraceRows.Row.Header>()
    val tag = key.removePrefix("Tag_").toIntOrNull()
    val values: Sequence<String> =
        when {
            key == SESSION ->
                members.map { sessionTitles.getOrNull(it.located.session) ?: "session ${it.located.session}" } +
                    // The dots and the follow button beside the count take about as much room as six characters.
                    headers.map { plural(it.sessionCount, "session") + "      " }
            key == TIME -> sequenceOf("HH:mm:ss.SSS")
            key == DIR -> sequenceOf("OUT")
            key == SEQ -> members.map { extractTopLevelFieldValue(it.message, 34) }
            key == MSGTYPE -> members.map { it.message.messageType }
            key == SUMMARY ->
                members.map { m ->
                    dictionary.getFieldValueDescription(35, m.message.messageType) ?: m.message.messageType
                } +
                    headers.map { summaryText(it) }
            key == ELAPSED ->
                members.map { m -> m.elapsedMillis?.let { "+$it ms" }.orEmpty() } +
                    headers.map { "${it.summary.exchange.elapsedMillis} ms" }
            tag != null ->
                members.map { extractTopLevelFieldValue(it.message, tag) } +
                    sequenceOf(dictionary.getFieldName(tag) ?: key)
            else -> emptySequence()
        }
    return fittedColumnWidth(sequenceOf(key) + values)
}

/** The colour a session's rows are badged with — the search-results treatment, keyed by position. */
private fun sessionColor(session: Int): Color =
    AppTheme.Colors.usernameColors[
        // Negative cannot happen from a Located, but a modulo that could go negative is a crash waiting
        // for the one day it does.
        (session % AppTheme.Colors.usernameColors.size).coerceAtLeast(0),
    ]

@Composable
private fun SessionDot(session: Int) {
    Box(
        modifier =
            Modifier
                .padding(end = 3.dp)
                .size(6.dp)
                .background(sessionColor(session), CircleShape),
    )
}

/** What a trace did — its composition and its status — as the header's Summary cell says it. */
private fun summaryText(header: TraceRows.Row.Header): String {
    val summary = header.summary.exchange
    return buildString {
        append(summary.composition.joinToString(" · ") { "${it.name ?: it.messageType} ×${it.count}" })
        summary.status?.let { append(" · ").append(it.valueName ?: it.value) }
    }
}

/**
 * One trace's summary line: what it is, where it ran, what it did, how long it took.
 *
 * Everything on it is quoted from the messages — see `Conversations.summarize`, which decides what a
 * header may claim and is deliberately the only thing that decides it.
 *
 * Every cell stands under a header column or a run of them, and the row ends on the grid's last rule. It used
 * to end past it: a follow button in a 28dp cell no header stood over, and notes held to 360dp however little
 * the tag columns under them added up to.
 */
@Composable
private fun TraceHeaderRow(
    header: TraceRows.Row.Header,
    widths: TraceColumns,
    onToggle: () -> Unit,
    onFollow: (String) -> Unit,
    onUnfollow: () -> Unit,
) {
    val background = AppTheme.Colors.surfaceVariant
    val summary = header.summary.exchange
    // The two things a trace can only say about itself, and neither may be left unsaid: that it is missing
    // history at the front, and that nothing carried it off this one session.
    val notes =
        listOfNotNull(
            header.truncatedSessionTitles
                .takeIf { it.isNotEmpty() }
                ?.let { "history lost on ${it.joinToString(", ")}" },
            header.hint,
        ).joinToString(" · ")
    val notesColor =
        if (header.truncatedSessionTitles.isEmpty()) AppTheme.Colors.textDisabled else AppTheme.Colors.warning

    Row(
        modifier =
            Modifier
                .height(24.dp)
                .testTag("trace-header-${header.label}")
                .clickable { onToggle() },
    ) {
        cell(FOLD_WIDTH, background, Alignment.Center) {
            Text(
                text = if (header.expanded) "▼" else "▶",
                color = AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        // Where it ran: one dot per session, then the count, then the button that follows it across them. The
        // dots are what makes "this one crossed four panes" readable without counting words.
        cell(widths.session, background) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            ) {
                header.sessions.take(MAX_HEADER_DOTS).forEach { SessionDot(it) }
                if (header.sessions.size > MAX_HEADER_DOTS) {
                    Text(
                        text = "+ ",
                        color = AppTheme.Colors.textSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    text = plural(header.sessionCount, "session"),
                    color = if (header.sessionCount > 1) AppTheme.Colors.text else AppTheme.Colors.textSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                FollowTraceButton(
                    following = header.isFollowed,
                    onClick = { if (header.isFollowed) onUnfollow() else onFollow(header.label) },
                )
            }
        }
        cell(widths.identity, background) {
            Text(
                text =
                    buildString {
                        append(header.label)
                        summary.instrument?.let { append(" · ").append(it) }
                        summary.quantity?.let { append(" ").append(it) }
                    },
                color = AppTheme.Colors.text,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        cell(widths.summary, background) {
            Text(
                text = summaryText(header),
                color = AppTheme.Colors.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        cell(widths.elapsed, background, Alignment.CenterEnd) {
            Text(
                text = "${summary.elapsedMillis} ms",
                color = AppTheme.Colors.warning,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (widths.tags.isNotEmpty()) {
            // Across the tag columns, exactly as wide as they are — widen them to read more of it, or hover it
            // for all of it.
            cell(widths.tagSpan, background) {
                if (notes.isNotEmpty()) {
                    AppTooltip(text = notes) {
                        TraceNotes(notes, notesColor, Modifier.fillMaxWidth())
                    }
                }
            }
        } else if (notes.isNotEmpty()) {
            // No tag columns to stand under. Written after the grid's last rule, unboxed, so it reads as a remark
            // on the row rather than as a column the header does not have.
            TraceNotes(notes, notesColor, Modifier.align(Alignment.CenterVertically))
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TraceNotes(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 8.dp),
    )
}

private const val MAX_HEADER_DOTS = 5

@Composable
private fun UngroupedHeaderRow(
    header: TraceRows.Row.UngroupedHeader,
    widths: TraceColumns,
    onToggle: () -> Unit,
) {
    val background = AppTheme.Colors.surfaceVariant
    Row(
        modifier =
            Modifier
                .height(24.dp)
                .testTag("trace-ungrouped-header")
                .clickable { onToggle() },
    ) {
        cell(FOLD_WIDTH, background, Alignment.Center) {
            Text(
                text = if (header.expanded) "▼" else "▶",
                color = AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        cell(widths.session + widths.identity + widths.summary + widths.elapsed, background) {
            Text(
                text = "Ungrouped · ${plural(header.count, "message")} · no correlation id",
                color = AppTheme.Colors.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        // The tag columns too, empty: the row stopped at Elapsed while every row around it ran on.
        widths.tags.forEach { tag -> cell(widths.tag(tag), background) {} }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TraceMemberRow(
    member: TraceRows.Row.Member,
    sessionTitles: List<String>,
    isSelected: Boolean,
    widths: TraceColumns,
    dictionary: FixDictionary,
    appSettings: AppSettings,
    onClick: () -> Unit,
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss.SSS") }
    val message = member.message
    val session = member.located.session
    val background = if (isSelected) AppTheme.Colors.selectionPrimary else AppTheme.Colors.background
    val directionColor =
        appSettings.messageColorScheme.getMessageColor(message.direction, message.isRejectionOrLogout(), true)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .testTag("trace-member")
                .clickable(onClick = onClick),
    ) {
        cell(FOLD_WIDTH, background) {}
        cell(widths.session, background) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                SessionDot(session)
                Text(
                    text = sessionTitles.getOrNull(session) ?: "session $session",
                    fontSize = 10.sp,
                    color = sessionColor(session),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        cell(widths.time, background, Alignment.Center) {
            Text(
                text = message.timestamp.format(timeFormatter),
                fontSize = 10.sp,
                color = AppTheme.Colors.text,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        cell(widths.dir, background, Alignment.Center) {
            Text(
                text = if (message.direction == FixMessage.Direction.INCOMING) "IN" else "OUT",
                fontSize = 10.sp,
                color = directionColor,
                fontFamily = FontFamily.Monospace,
            )
        }
        cell(widths.seq, background, Alignment.Center) {
            Text(
                text = extractTopLevelFieldValue(message, 34),
                fontSize = 10.sp,
                color = AppTheme.Colors.tagNumber,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        cell(widths.msgType, background, Alignment.Center) {
            Text(
                text = message.messageType,
                fontSize = 10.sp,
                color = AppTheme.Colors.text,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        cell(widths.summary, background) {
            Text(
                text = dictionary.getFieldValueDescription(35, message.messageType) ?: message.messageType,
                fontSize = 10.sp,
                color = AppTheme.Colors.text,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        // The gap since the previous message OF THIS TRACE, on whichever pane it landed. Blank on the
        // first, and on the ungrouped bucket, where there is no sequence for a gap to be a gap in.
        cell(widths.elapsed, background, Alignment.CenterEnd) {
            Text(
                text = member.elapsedMillis?.let { "+$it ms" }.orEmpty(),
                fontSize = 10.sp,
                color = AppTheme.Colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        widths.tags.forEach { tag ->
            cell(widths.tag(tag), background, Alignment.Center) {
                Text(
                    text = extractTopLevelFieldValue(message, tag),
                    fontSize = 10.sp,
                    color = AppTheme.Colors.fieldValue,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
