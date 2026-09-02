package com.knapsack.fixtool.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.knapsack.fixtool.service.TraceRows
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
            onExpandAll = onExpandAll,
            onCollapseAll = onCollapseAll,
            onClose = onClose,
        )

        HorizontalDivider(color = AppTheme.Colors.border)

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
                    TraceGridHeader(appSettings.gridViewColumns, dictionary)

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
                                        gridViewColumns = appSettings.gridViewColumns,
                                        onToggle = { onToggleTrace(row.key) },
                                        onFollow = onFollow,
                                        onUnfollow = onUnfollow,
                                    )

                                is TraceRows.Row.UngroupedHeader ->
                                    UngroupedHeaderRow(header = row, onToggle = onToggleUngrouped)

                                is TraceRows.Row.Member ->
                                    TraceMemberRow(
                                        member = row,
                                        sessionTitles = sessionTitles,
                                        isSelected = selectedMessage == row.message,
                                        gridViewColumns = appSettings.gridViewColumns,
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
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF2D2D2D))
                .height(26.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Trace",
                fontSize = 10.sp,
                color = AppTheme.Colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
            Text(
                text = status,
                fontSize = 10.sp,
                color = AppTheme.Colors.text,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp).testTag("trace-panel-status"),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction("Expand all", "trace-expand-all", onExpandAll)
            TextAction("Collapse all", "trace-collapse-all", onCollapseAll)
            // Closes the panel and nothing else: the panes stay narrowed and the chip goes on saying so.
            TooltipIconButton(
                tooltip = "Close (keeps following)",
                onClick = onClose,
                modifier = Modifier.size(24.dp).testTag("trace-close"),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
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
private val FOLLOW_WIDTH = 28.dp

/** Time+Dir+SeqNum+MsgType, which a header row has no per-message values for. */
private val IDENTITY_WIDTH = TIME_WIDTH + DIR_WIDTH + SEQ_WIDTH + MSGTYPE_WIDTH

/**
 * Room for the things only a header says — the truncation notice and the sidecar hint.
 *
 * It absorbs the tag columns the way `ConversationGroupRow`'s identity span absorbs Time..MsgType, and
 * keeps a floor of its own so the hint is still readable with no tag columns configured, which is the
 * default.
 */
private fun notesWidth(gridViewColumns: List<Int>): Dp =
    maxOf(TAG_WIDTH * gridViewColumns.size, 360.dp)

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
private fun headerLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = AppTheme.Colors.textSecondary,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun TraceGridHeader(
    gridViewColumns: List<Int>,
    dictionary: FixDictionary,
) {
    val background = Color(0xFF2D2D2D)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background)
                .height(24.dp),
    ) {
        cell(FOLD_WIDTH, background) {}
        cell(SESSION_WIDTH, background, Alignment.Center) { headerLabel("Session") }
        cell(TIME_WIDTH, background, Alignment.Center) { headerLabel("Time") }
        cell(DIR_WIDTH, background, Alignment.Center) { headerLabel("Dir") }
        cell(SEQ_WIDTH, background, Alignment.Center) { headerLabel("SeqNum") }
        cell(MSGTYPE_WIDTH, background, Alignment.Center) { headerLabel("MsgType") }
        cell(SUMMARY_WIDTH, background) { headerLabel("Summary") }
        cell(ELAPSED_WIDTH, background, Alignment.CenterEnd) { headerLabel("Elapsed") }
        gridViewColumns.forEach { tag ->
            cell(TAG_WIDTH, background, Alignment.Center) { headerLabel(dictionary.getFieldName(tag) ?: tag.toString()) }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
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

/**
 * One trace's summary line: what it is, where it ran, what it did, how long it took.
 *
 * Everything on it is quoted from the messages — see `Conversations.summarize`, which decides what a
 * header may claim and is deliberately the only thing that decides it.
 */
@Composable
private fun TraceHeaderRow(
    header: TraceRows.Row.Header,
    gridViewColumns: List<Int>,
    onToggle: () -> Unit,
    onFollow: (String) -> Unit,
    onUnfollow: () -> Unit,
) {
    val background = AppTheme.Colors.surfaceVariant
    val summary = header.summary.exchange
    val rowWidth =
        FOLD_WIDTH + SESSION_WIDTH + IDENTITY_WIDTH + SUMMARY_WIDTH + ELAPSED_WIDTH +
            notesWidth(gridViewColumns) + FOLLOW_WIDTH

    Row(
        modifier =
            Modifier
                .height(24.dp)
                .widthIn(min = rowWidth)
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
        // Where it ran: one dot per session, then the count. The dots are what makes "this one crossed
        // four panes" readable without counting words.
        cell(SESSION_WIDTH, background) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
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
                )
            }
        }
        cell(IDENTITY_WIDTH, background) {
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
        cell(SUMMARY_WIDTH, background) {
            Text(
                text =
                    buildString {
                        append(summary.composition.joinToString(" · ") { "${it.name ?: it.messageType} ×${it.count}" })
                        summary.status?.let { append(" · ").append(it.valueName ?: it.value) }
                    },
                color = AppTheme.Colors.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        cell(ELAPSED_WIDTH, background, Alignment.CenterEnd) {
            Text(
                text = "${summary.elapsedMillis} ms",
                color = AppTheme.Colors.warning,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        // The two things a trace can only say about itself, and neither may be left unsaid: that it is
        // missing history at the front, and that nothing carried it off this one session.
        cell(notesWidth(gridViewColumns), background) {
            Text(
                text =
                    listOfNotNull(
                        header.truncatedSessionTitles
                            .takeIf { it.isNotEmpty() }
                            ?.let { "history lost on ${it.joinToString(", ")}" },
                        header.hint,
                    ).joinToString(" · "),
                color = if (header.truncatedSessionTitles.isEmpty()) AppTheme.Colors.textDisabled else AppTheme.Colors.warning,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        cell(FOLLOW_WIDTH, background, Alignment.Center) {
            FollowTraceButton(
                following = header.isFollowed,
                onClick = { if (header.isFollowed) onUnfollow() else onFollow(header.label) },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

private const val MAX_HEADER_DOTS = 5

@Composable
private fun UngroupedHeaderRow(
    header: TraceRows.Row.UngroupedHeader,
    onToggle: () -> Unit,
) {
    val background = AppTheme.Colors.surfaceVariant
    Row(
        modifier =
            Modifier
                .height(24.dp)
                .widthIn(min = FOLD_WIDTH + SESSION_WIDTH + IDENTITY_WIDTH + SUMMARY_WIDTH + ELAPSED_WIDTH)
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
        cell(SESSION_WIDTH + IDENTITY_WIDTH + SUMMARY_WIDTH + ELAPSED_WIDTH, background) {
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
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TraceMemberRow(
    member: TraceRows.Row.Member,
    sessionTitles: List<String>,
    isSelected: Boolean,
    gridViewColumns: List<Int>,
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
        cell(SESSION_WIDTH, background) {
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
        cell(TIME_WIDTH, background, Alignment.Center) {
            Text(
                text = message.timestamp.format(timeFormatter),
                fontSize = 10.sp,
                color = AppTheme.Colors.text,
                fontFamily = FontFamily.Monospace,
            )
        }
        cell(DIR_WIDTH, background, Alignment.Center) {
            Text(
                text = if (message.direction == FixMessage.Direction.INCOMING) "IN" else "OUT",
                fontSize = 10.sp,
                color = directionColor,
                fontFamily = FontFamily.Monospace,
            )
        }
        cell(SEQ_WIDTH, background, Alignment.Center) {
            Text(
                text = extractTopLevelFieldValue(message, 34),
                fontSize = 10.sp,
                color = AppTheme.Colors.tagNumber,
                fontFamily = FontFamily.Monospace,
            )
        }
        cell(MSGTYPE_WIDTH, background, Alignment.Center) {
            Text(
                text = message.messageType,
                fontSize = 10.sp,
                color = AppTheme.Colors.text,
                fontFamily = FontFamily.Monospace,
            )
        }
        cell(SUMMARY_WIDTH, background) {
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
        cell(ELAPSED_WIDTH, background, Alignment.CenterEnd) {
            Text(
                text = member.elapsedMillis?.let { "+$it ms" }.orEmpty(),
                fontSize = 10.sp,
                color = AppTheme.Colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        gridViewColumns.forEach { tag ->
            cell(TAG_WIDTH, background, Alignment.Center) {
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
