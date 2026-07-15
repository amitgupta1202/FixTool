package com.knapsack.fixtool.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.MatchContextMode
import com.knapsack.fixtool.model.scenario.TagResult
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.service.FixMessageHelper
import quickfix.Field
import quickfix.FieldMap
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun MessageDetailPanel(
    message: FixMessage?,
    dictionary: FixDictionary,
    onClose: () -> Unit,
    onPasteMessage: ((String) -> Unit)? = null,
    appSettings: com.knapsack.fixtool.model.AppSettings =
        com.knapsack.fixtool.model.AppSettings
            .default(),
    modifier: Modifier = Modifier,
    // Optional hoisted search state. When provided (e.g. by the app host, backed by the ViewModel),
    // the search box and context toggle are driven externally so they can be set via the control
    // surface / MCP. When null, the panel keeps its own internal state (used by standalone callers
    // and unit tests).
    externalSearchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    externalMatchContextMode: MatchContextMode? = null,
    onMatchContextModeChange: ((MatchContextMode) -> Unit)? = null,
    // Per-tag assertion results for the displayed message (from the last scenario run) — colors each
    // tag row green/red and shows expected-vs-actual. A list, not a tag-keyed map: the same tag is
    // asserted once per occurrence (the four PartyRoles of four party entries), and those must not
    // collapse into one.
    tagResults: List<TagResult> = emptyList(),
    // Failure → deep-link: opens the diff window on the step that produced these results. Shown on the
    // failure banner only when provided. This panel diagnoses a failure; it is not where one is
    // repaired — that is the diff window, the only surface that can see both the expectation and the
    // message that arrived.
    onEditAssertion: ((Int?) -> Unit)? = null,
    // "Diff against…" — open the plain diff viewer with this message as side A and the other side armed
    // for a session pick or a paste (Phase 7 entry point). Available on any message with wire bytes.
    onDiffAgainst: ((FixMessage) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var rawMessageSplitRatio by remember { mutableStateOf(0.2f) } // Raw message section takes 30% by default

    var localSearchQuery by remember { mutableStateOf("") }
    var localMatchContextMode by remember { mutableStateOf(MatchContextMode.IDENTITY) }
    val searchQuery = externalSearchQuery ?: localSearchQuery
    val setSearchQuery: (String) -> Unit = onSearchQueryChange ?: { localSearchQuery = it }
    val matchContextMode = externalMatchContextMode ?: localMatchContextMode
    val setMatchContextMode: (MatchContextMode) -> Unit = onMatchContextModeChange ?: { localMatchContextMode = it }

    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .background(panelBackgroundColor),
    ) {
        // Always show the panel structure, with raw message section for paste functionality
        run {
            // State for expanded groups (moved up to be accessible by toolbar buttons)
            var expandedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
            // State for expanded field values (track by unique key: parentKey_tag)
            var expandedFields by remember { mutableStateOf<Set<String>>(emptySet()) }

            Column(modifier = Modifier.fillMaxSize()) {
                // Top border
                HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

                // Header
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(headerBackgroundColor)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Message Details",
                        color = headerTextColor,
                        fontSize = 11.sp,
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Only show message-specific buttons when a message is selected
                    if (message != null) {
                        // "Diff against…" — start a plain diff with this message on one side (Phase 7 entry point).
                        if (onDiffAgainst != null) {
                            TooltipIconButton(
                                tooltip = "Diff against another message…",
                                onClick = { onDiffAgainst(message) },
                                modifier = buttonSize.testTag("detail-diff-against"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CompareArrows,
                                    contentDescription = "Diff against",
                                    tint = iconTintColor,
                                    modifier = iconSize,
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        // Toggle expand/collapse all button
                        val allGroupKeys = remember(message) { collectAllGroupKeys(message) }
                        val allExpanded = allGroupKeys.isNotEmpty() && expandedGroups.containsAll(allGroupKeys)

                        TooltipIconButton(
                            tooltip = if (allExpanded) "Collapse All Groups" else "Expand All Groups",
                            onClick = {
                                expandedGroups = if (allExpanded) emptySet() else allGroupKeys
                            },
                            modifier = buttonSize,
                        ) {
                            Icon(
                                imageVector = if (allExpanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                                contentDescription = if (allExpanded) "Collapse All" else "Expand All",
                                tint = iconTintColor,
                                modifier = iconSize,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Close button
                    TooltipIconButton(
                        tooltip = "Close Detail Panel",
                        onClick = onClose,
                        modifier = buttonSize,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = iconTintColor,
                            modifier = iconSize,
                        )
                    }
                }

                HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

                // Search bar
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(headerBackgroundColor)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = searchIconColor,
                        modifier = searchIconSize,
                    )

                    val searchInteractionSource = remember { MutableInteractionSource() }
                    val searchIsFocused by searchInteractionSource.collectIsFocusedAsState()

                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { setSearchQuery(it) },
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(22.dp)
                                .background(panelBackgroundColor, searchFieldShape)
                                .border(
                                    width = 1.dp,
                                    color = getSearchBorderColor(searchIsFocused),
                                    shape = searchFieldShape,
                                ).padding(horizontal = 6.dp, vertical = 3.dp),
                        textStyle = searchTextStyle,
                        singleLine = true,
                        cursorBrush = SolidColor(focusedBorderColor),
                        interactionSource = searchInteractionSource,
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty() && !searchIsFocused) {
                                Text(
                                    text = "Search tags, names, or values...",
                                    style = searchPlaceholderStyle,
                                )
                            }
                            innerTextField()
                        },
                    )
                }

                // Match-context toggle: only relevant while actively searching a message
                if (message != null && searchQuery.isNotBlank()) {
                    MatchContextToggle(
                        mode = matchContextMode,
                        onModeChange = { setMatchContextMode(it) },
                    )
                }

                HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

                // Message direction and timestamp (only when message is selected)
                if (message != null) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(metadataBackgroundColor)
                                .padding(12.dp),
                    ) {
                        // Match session coloring: blue for outgoing, red for incoming rejects, green for other incoming
                        val (directionColor, directionText) = getDirectionInfo(message, appSettings)

                        Text(
                            text = directionText,
                            color = directionColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = message.timestamp.toString(),
                            color = timestampColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    HorizontalDivider(
                        color = AppTheme.Separators.color,
                        thickness = AppTheme.Separators.dividerThickness,
                    )

                    // Scenario assertion verdict for this message — the summary first, so a failure
                    // is announced before the user scans the tag list.
                    if (tagResults.isNotEmpty()) {
                        AssertionSummaryBanner(
                            results = tagResults,
                            dictionary = dictionary,
                            onEditAssertion = onEditAssertion,
                        )
                        HorizontalDivider(
                            color = AppTheme.Separators.color,
                            thickness = AppTheme.Separators.dividerThickness,
                        )
                    }
                }

                // Resizable split between fields list and raw message
                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                    val totalHeightPx = with(density) { maxHeight.toPx() }
                    val fieldsHeight = if (message != null) maxHeight * (1f - rawMessageSplitRatio) else 0.dp
                    val rawMessageHeight = if (message != null) maxHeight * rawMessageSplitRatio else maxHeight

                    Column {
                        // Fields list (only when message is selected)
                        if (message != null) {
                            // Keyed by tag, carrying every result on that tag *and* how many times the
                            // tag really occurs on the wire. Both halves matter: the count is what lets a
                            // row say "1 of 4 checked" instead of painting a green tick on three entries
                            // nobody asserted.
                            val verdictsByTag =
                                remember(tagResults, message) {
                                    val occurrences =
                                        FixMessageHelper.fieldsForDisplay(message).groupingBy { it.first }.eachCount()
                                    tagResults.groupBy { it.tag }.mapValues { (tag, results) ->
                                        TagVerdict(results, occurrences[tag] ?: results.size)
                                    }
                                }
                            Box(modifier = Modifier.height(fieldsHeight)) {
                                SelectionContainer {
                                    LazyColumn(
                                        state = listState,
                                        modifier =
                                            Modifier
                                                .fillMaxSize()
                                                .padding(end = 16.dp),
                                    ) {
                                        // Render using QuickFIX Message object if available, otherwise fallback to fieldTree
                                        renderQuickFixMessage(
                                            message = message.quickfixMessage,
                                            dictionary = dictionary,
                                            tagResults = verdictsByTag,
                                            hideProtocolTags = appSettings.hideProtocolTags,
                                            searchQuery = searchQuery,
                                            matchContextMode = matchContextMode,
                                            protocolTags = appSettings.protocolTags,
                                            expandedGroups = expandedGroups,
                                            onToggleGroup = { key ->
                                                expandedGroups =
                                                    if (key in expandedGroups) {
                                                        expandedGroups - key
                                                    } else {
                                                        expandedGroups + key
                                                    }
                                            },
                                            expandedFields = expandedFields,
                                            onToggleField = { key ->
                                                expandedFields =
                                                    if (key in expandedFields) {
                                                        expandedFields - key
                                                    } else {
                                                        expandedFields + key
                                                    }
                                            },
                                        )
                                    }
                                }

                                // Vertical Scrollbar
                                VerticalScrollbar(
                                    adapter = rememberScrollbarAdapter(listState),
                                    modifier =
                                        Modifier
                                            .align(Alignment.CenterEnd)
                                            .fillMaxHeight()
                                            .padding(end = 4.dp),
                                )
                            }

                            // Draggable divider (only shown when message is selected)
                            var isHovering by remember { mutableStateOf(false) }
                            Box(
                                modifier =
                                    Modifier
                                        .height(AppTheme.Separators.dividerThickness)
                                        .fillMaxWidth()
                                        .background(
                                            if (isHovering) AppTheme.Separators.hoverColor else AppTheme.Separators.color,
                                        ).pointerHoverIcon(
                                            PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)),
                                        ).hoverable(
                                            interactionSource =
                                                remember { MutableInteractionSource() }
                                                    .also { source ->
                                                        LaunchedEffect(source) {
                                                            source.interactions.collect { interaction ->
                                                                isHovering =
                                                                    interaction is androidx.compose.foundation.interaction.HoverInteraction.Enter
                                                            }
                                                        }
                                                    },
                                        ).pointerInput(Unit) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                val deltaRatio = dragAmount.y / totalHeightPx
                                                rawMessageSplitRatio =
                                                    (rawMessageSplitRatio - deltaRatio).coerceIn(0.2f, 0.8f)
                                            }
                                        },
                            )
                        }

                        // Raw message
                        Column(
                            modifier =
                                Modifier
                                    .height(rawMessageHeight)
                                    .fillMaxWidth()
                                    .background(metadataBackgroundColor)
                                    .padding(12.dp),
                        ) {
                            // Header with label and copy/paste buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "RAW MESSAGE",
                                    color = timestampColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    // Paste button
                                    if (onPasteMessage != null) {
                                        TooltipIconButton(
                                            tooltip = "Paste Message from Clipboard",
                                            onClick = {
                                                try {
                                                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                                                    val data =
                                                        clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor)
                                                    if (data is String && data.isNotBlank()) {
                                                        onPasteMessage(data)
                                                    }
                                                } catch (e: Exception) {
                                                    // Clipboard error - ignore
                                                }
                                            },
                                            modifier = rawActionButtonSize,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentPaste,
                                                contentDescription = "Paste",
                                                tint = iconTintColor,
                                                modifier = rawActionIconSize,
                                            )
                                        }
                                    }

                                    // Copy button (only when message exists)
                                    if (message != null) {
                                        TooltipIconButton(
                                            tooltip = "Copy All",
                                            onClick = {
                                                if (message.rawMessage.isNotBlank()) {
                                                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                                                    clipboard.setContents(
                                                        StringSelection(message.rawMessage),
                                                        null,
                                                    )
                                                }
                                            },
                                            modifier = rawActionButtonSize,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy All",
                                                tint = iconTintColor,
                                                modifier = rawActionIconSize,
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (message != null) {
                                    // Show message raw content when selected
                                    SelectionContainer {
                                        val rawScrollState = rememberScrollState()
                                        Text(
                                            text = message.rawMessage,
                                            color = rawMessageTextColor,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier =
                                                Modifier
                                                    .fillMaxSize()
                                                    .verticalScroll(rawScrollState)
                                                    .padding(end = 16.dp),
                                        )
                                    }
                                } else {
                                    // Show prompt to paste when no message selected
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "Paste a FIX message to visualize it\n(Click the paste button above)",
                                            color = placeholderTextColor,
                                            fontSize = 11.sp,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                    }
                                }

                                // Scrollbar for raw message
                                VerticalScrollbar(
                                    adapter = rememberScrollbarAdapter(rememberScrollState()),
                                    modifier =
                                        Modifier
                                            .align(Alignment.CenterEnd)
                                            .fillMaxHeight()
                                            .padding(end = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Threshold for considering a value "long" and expandable
private const val LONG_VALUE_THRESHOLD = 50

/**
 * The verdict of a scenario assertion on this message: "all N checks passed", or which tags broke.
 * Failures lead; the per-tag rows below carry the expected-vs-actual detail.
 */
@Composable
private fun AssertionSummaryBanner(
    results: List<TagResult>,
    dictionary: FixDictionary,
    onEditAssertion: ((Int?) -> Unit)? = null,
) {
    val failed = results.filterNot { it.passed }
    val background = if (failed.isEmpty()) AppTheme.Colors.notificationSuccessBackground else AppTheme.Colors.notificationErrorBackground
    Column(modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 12.dp, vertical = 6.dp)) {
        if (failed.isEmpty()) {
            Text(
                text = "✓ Scenario assertion passed — all ${results.size} checked tags matched",
                color = AppTheme.Colors.success,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "✗ Scenario assertion failed — ${failed.size} of ${results.size} checked tags",
                    color = AppTheme.Colors.error,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // The one way out of this panel and into a repair. It opens the step that failed; the
                // reconcile view there shows the expectation against the message side by side, which is
                // the only place a missing tag or a moved entry can even be seen, let alone fixed.
                if (onEditAssertion != null) {
                    SlimButton(
                        "Reconcile assertions…",
                        onClick = { onEditAssertion(null) },
                        color = AppTheme.Colors.error,
                    )
                }
            }
            failed.take(5).forEach { tr ->
                val name = dictionary.getFieldName(tr.tag) ?: ""
                Text(
                    text = "   ${tr.tag} $name${occurrenceSuffix(tr, failed)} — ${describe(tr)}",
                    color = AppTheme.Colors.error,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Each failing tag is its own door, and it lands on its own row. "Reconcile assertions →,
                    // scrolled to the row that was clicked" was the promise; a diff that opens at the top of a
                    // forty-row expectation and leaves the author to find the tag again has not kept it.
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .let { m ->
                                if (onEditAssertion == null) m else m.clickable { onEditAssertion(tr.tag) }
                            }.testTag("failed-tag-${tr.tag}"),
                )
            }
            if (failed.size > 5) {
                Text("   +${failed.size - 5} more — see the red rows below", color = AppTheme.Colors.error, fontSize = 10.sp)
            }
        }
    }
}

/** "#2" when the same tag failed more than once, so two party entries do not read as one row twice. */
private fun occurrenceSuffix(result: TagResult, failed: List<TagResult>): String =
    if (failed.count { it.tag == result.tag } > 1) " #${result.occurrence + 1}" else ""

/**
 * A failure in one line, in the words of what actually went wrong.
 *
 * "expected X, got nothing" is true of a tag the venue dropped *and* of an entry it merely moved, and
 * they are not the same bug — one is a regression, the other is a reshuffle that a click will accept.
 * The status says which.
 */
private fun describe(tr: TagResult): String =
    when (tr.status) {
        TagStatus.MISSING -> "expected ${tr.expected}, but the reply has no such tag"
        TagStatus.MOVED -> "expected ${tr.expected} here — the value is in the reply, but not in this position"
        TagStatus.UNEXPECTED -> "the reply carries this tag (${tr.actual}) and the expectation never mentions it"
        TagStatus.INVALID -> tr.expected
        else -> "expected ${tr.expected}, got ${tr.actual ?: "nothing (tag absent)"}"
    }

@Composable
private fun FieldRow(
    tag: Int,
    value: String,
    dictionary: FixDictionary,
    indentLevel: Int = 0,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
    searchQuery: String = "",
    // The verdict on this *tag*, not on this row — see [TagVerdict].
    verdict: TagVerdict? = null,
) {
    val fieldName = dictionary.getFieldName(tag) ?: tag.toString()
    val translation = dictionary.getFieldValueDescription(tag, value)

    val displayValue =
        if (translation != null && translation != value) {
            "$value [$translation]"
        } else {
            value
        }

    val isLongValue = displayValue.length > LONG_VALUE_THRESHOLD

    // A failed assertion owns the row (red tint + detail line below); a passed one stays subtle —
    // a green check only — so failures are the thing the eye lands on, not a wall of green.
    val tagResults = verdict?.results.orEmpty()
    val failures = tagResults.filterNot { it.passed }
    val failedAssertion = failures.isNotEmpty()
    val tagResult = failures.firstOrNull() ?: tagResults.firstOrNull()
    val rowBackground = if (failedAssertion) AppTheme.Colors.notificationErrorBackground else fieldRowBackgroundColor
    Column(modifier = Modifier.fillMaxWidth().background(rowBackground)) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .let { mod ->
                    if (isLongValue) {
                        mod.clickable { onToggleExpand() }
                    } else {
                        mod
                    }
                }.padding(start = (8 + indentLevel * 8).dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = if (isExpanded) Alignment.Top else Alignment.CenterVertically,
    ) {
        // Tag number
        Text(
            text = highlightMatches(tag.toString(), searchQuery, searchHighlightColor),
            color = tagNumberColor,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(35.dp),
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Field name
        Text(
            text = highlightMatches(fieldName, searchQuery, searchHighlightColor),
            color = fieldNameColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(130.dp),
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Value with expand/collapse support
        Text(
            text = highlightMatches(displayValue, searchQuery, searchHighlightColor),
            color = fieldValueColor,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = isExpanded,
            maxLines = if (isExpanded) Int.MAX_VALUE else 1,
            overflow = if (isExpanded) TextOverflow.Visible else TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // Expand/collapse indicator for long values
        if (isLongValue) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isExpanded) "▲" else "▼",
                color = expandIndicatorColor,
                fontSize = 8.sp,
                modifier = Modifier.padding(end = 4.dp),
            )
        }

        // Scenario assertion verdict chip: a quiet check for a pass, a loud cross for a failure — and a
        // muted count when only *some* of the tag's occurrences are asserted.
        //
        // That last case is the one that matters. A green tick here is keyed to the tag, not to this
        // row, because the panel renders QuickFIX's parsed tree and its iteration order is not the
        // wire's — so no row can honestly claim to be "the second 452". If an OPEN expectation asserts
        // one PartyRole and the reply carries four entries, ticking all four would tell an engineer that
        // four entries were checked when three were never asserted at all. It says "1 of 4 checked"
        // instead, and the reconcile view says which.
        if (tagResult != null && verdict != null) {
            Spacer(modifier = Modifier.width(6.dp))
            val partial = !failedAssertion && !verdict.fullyAsserted
            Text(
                text = when {
                    failedAssertion -> "✗ ${tagResult.matcher}"
                    partial -> "◐ ${verdict.results.size} of ${verdict.occurrences} checked"
                    else -> "✓ ${tagResult.matcher}"
                },
                color = when {
                    failedAssertion -> AppTheme.Colors.error
                    partial -> AppTheme.Colors.textSecondary
                    else -> AppTheme.Colors.success
                },
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (failedAssertion) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
    // Each failure gets its expected-vs-actual spelled out full-width, with the actual value's
    // dictionary meaning — never truncated into an unreadable suffix. Several, when the tag is asserted
    // more than once: this is a diagnosis, and it names every occurrence that broke.
    //
    // There are no quick-fix chips here any more, and no Save. This surface renders the message that
    // *arrived*, so it can never be the place a failure is repaired: a tag the venue stopped sending has
    // no row to click, and a reordered group entry looks perfectly fine tag by tag — every value
    // matches, nothing is red, and the step still failed. Reconciling happens in the diff view, which
    // can see both sides. The viewer diagnoses; the diff view authors.
    for (failure in failures) {
        val actualText =
            failure.actual?.let { actual ->
                val desc = dictionary.getFieldValueDescription(tag, actual)?.takeIf { it != actual }
                if (desc != null) "$actual ($desc)" else actual
            } ?: "nothing — tag absent"
        Text(
            text = "${occurrenceLabel(failure, tagResults.size)}expected ${failure.expected} · actual $actualText",
            color = AppTheme.Colors.error,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().padding(start = (8 + indentLevel * 8 + 39).dp, end = 8.dp, bottom = 4.dp),
        )
    }
    }
}

/**
 * Everything the run said about one **tag**, plus how many times that tag actually occurs on the wire.
 *
 * The viewer judges by tag, not by row, and that is deliberate rather than lazy: it renders QuickFIX's
 * parsed tree, whose iteration order is not the wire's, so a row's position here cannot be turned into
 * "the k-th 452 on the wire" without the kind of guess this model exists to eliminate — and a guess
 * would put a green tick on a row whose assertion actually failed.
 *
 * So a tag with *any* failing occurrence shows red on every row (over-red, never falsely green), and a
 * tag only *partly* asserted shows neither green nor red — it says how many of its occurrences were
 * checked. [occurrences] is what makes that distinction possible; without it, one passing assertion
 * painted a green tick on four party entries, three of which nobody had asserted.
 */
data class TagVerdict(val results: List<TagResult>, val occurrences: Int) {
    /** Every occurrence of this tag carries an assertion, so a green tick speaks for all of them. */
    val fullyAsserted: Boolean get() = occurrences > 0 && results.size >= occurrences
}

/** "occurrence 2: " when a tag is asserted more than once, "" when it is not. */
private fun occurrenceLabel(result: TagResult, assertionsOnTag: Int): String =
    if (assertionsOnTag > 1) "occurrence ${result.occurrence + 1}: " else ""

@Composable
private fun GroupHeaderRow(
    tag: Int,
    count: Int,
    dictionary: FixDictionary,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    indentLevel: Int = 0,
    searchQuery: String = "",
) {
    val groupName = dictionary.getFieldName(tag) ?: "Group $tag"

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(groupHeaderBackgroundColor)
                .clickable { onToggle() }
                .padding(start = (8 + indentLevel * 8).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        // Tag number (aligned with FieldRow tag column)
        Text(
            text = tag.toString(),
            color = tagNumberColor,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(35.dp),
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Group indicator icon (collapsible)
        Text(
            text = if (isExpanded) "▼" else "▶",
            color = groupHeaderTextColor,
            fontSize = 8.sp,
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Group name
        Text(
            text = highlightMatches("$groupName ($count)", searchQuery, searchHighlightColor),
            color = groupHeaderTextColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GroupInstanceHeader(
    instanceNumber: Int,
    indentLevel: Int = 0,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(groupInstanceBackgroundColor)
                .padding(start = (8 + indentLevel * 8).dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            text = "[$instanceNumber]",
            color = groupInstanceTextColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Renders a QuickFIX Message using its native hierarchical structure
 */
private fun LazyListScope.renderQuickFixMessage(
    message: quickfix.Message,
    dictionary: FixDictionary,
    hideProtocolTags: Boolean,
    searchQuery: String,
    matchContextMode: MatchContextMode,
    protocolTags: Set<Int>,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    expandedFields: Set<String>,
    onToggleField: (String) -> Unit,
    indentLevel: Int = 0,
    parentKey: String = "",
    tagResults: Map<Int, TagVerdict> = emptyMap(),
) {
    // Render header fields
    renderFieldMap(
        fieldMap = message.header,
        dictionary = dictionary,
        hideProtocolTags = hideProtocolTags,
        searchQuery = searchQuery,
        matchContextMode = matchContextMode,
        protocolTags = protocolTags,
        expandedGroups = expandedGroups,
        onToggleGroup = onToggleGroup,
        expandedFields = expandedFields,
        onToggleField = onToggleField,
        indentLevel = indentLevel,
        parentKey = parentKey,
        tagResults = tagResults,
    )

    // Render body fields
    renderFieldMap(
        fieldMap = message,
        dictionary = dictionary,
        hideProtocolTags = hideProtocolTags,
        searchQuery = searchQuery,
        matchContextMode = matchContextMode,
        protocolTags = protocolTags,
        expandedGroups = expandedGroups,
        onToggleGroup = onToggleGroup,
        expandedFields = expandedFields,
        onToggleField = onToggleField,
        indentLevel = indentLevel,
        parentKey = parentKey,
        tagResults = tagResults,
    )

    // Render trailer fields
    renderFieldMap(
        fieldMap = message.trailer,
        dictionary = dictionary,
        hideProtocolTags = hideProtocolTags,
        searchQuery = searchQuery,
        matchContextMode = matchContextMode,
        protocolTags = protocolTags,
        expandedGroups = expandedGroups,
        onToggleGroup = onToggleGroup,
        expandedFields = expandedFields,
        onToggleField = onToggleField,
        indentLevel = indentLevel,
        parentKey = parentKey,
        tagResults = tagResults,
    )
}

/**
 * Renders a FieldMap (which can be header, body, trailer, or a group)
 */
private fun LazyListScope.renderFieldMap(
    fieldMap: FieldMap,
    dictionary: FixDictionary,
    hideProtocolTags: Boolean,
    searchQuery: String,
    matchContextMode: MatchContextMode,
    protocolTags: Set<Int>,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    expandedFields: Set<String>,
    onToggleField: (String) -> Unit,
    indentLevel: Int = 0,
    parentKey: String = "",
    tagResults: Map<Int, TagVerdict> = emptyMap(),
) {
    val iterator = fieldMap.iterator()

    while (iterator.hasNext()) {
        @Suppress("UNCHECKED_CAST")
        val field = iterator.next() as Field<*>
        val tag = field.tag

        // Skip protocol tags if hidden
        if (hideProtocolTags && tag in protocolTags) {
            continue
        }

        val value = field.getObject().toString()

        // Create a unique key for this field (parentKey_tag)
        val fieldKey = if (parentKey.isEmpty()) tag.toString() else "${parentKey}_$tag"

        // Check if this is a repeating group
        try {
            val groupCount = fieldMap.getGroupCount(tag)
            if (groupCount > 0) {
                // This is a repeating group count field
                val groupKey =
                    if (parentKey.isEmpty()) {
                        "${tag}_$groupCount"
                    } else {
                        "$parentKey/${tag}_$groupCount"
                    }

                // Check if the group or any of its children match the search query
                val matchesSearch =
                    if (searchQuery.isNotBlank()) {
                        groupMatchesSearch(
                            fieldMap = fieldMap,
                            tag = tag,
                            groupCount = groupCount,
                            dictionary = dictionary,
                            searchQuery = searchQuery,
                            hideProtocolTags = hideProtocolTags,
                            protocolTags = protocolTags,
                        )
                    } else {
                        true
                    }

                if (!matchesSearch) {
                    continue
                }

                // While searching with a context mode, reveal each matching instance together with
                // its surrounding context instead of the legacy "matched rows only" view.
                if (searchQuery.isNotBlank() && matchContextMode != MatchContextMode.BARE) {
                    renderSearchedGroup(
                        fieldMap = fieldMap,
                        tag = tag,
                        groupCount = groupCount,
                        groupKey = groupKey,
                        dictionary = dictionary,
                        searchQuery = searchQuery,
                        mode = matchContextMode,
                        hideProtocolTags = hideProtocolTags,
                        protocolTags = protocolTags,
                        expandedFields = expandedFields,
                        onToggleField = onToggleField,
                        indentLevel = indentLevel,
                        tagResults = tagResults,
                    )
                    continue
                }

                val isExpanded = expandedGroups.contains(groupKey)

                // Render group header
                item {
                    GroupHeaderRow(
                        tag = tag,
                        count = groupCount,
                        dictionary = dictionary,
                        isExpanded = isExpanded,
                        onToggle = { onToggleGroup(groupKey) },
                        indentLevel = indentLevel,
                        searchQuery = searchQuery,
                    )
                }

                // Render group instances if expanded
                if (isExpanded) {
                    for (i in 1..groupCount) {
                        try {
                            val group = fieldMap.getGroup(i, tag)

                            item {
                                GroupInstanceHeader(
                                    instanceNumber = i,
                                    indentLevel = indentLevel + 1,
                                )
                            }

                            // Recursively render group fields
                            renderFieldMap(
                                fieldMap = group,
                                dictionary = dictionary,
                                hideProtocolTags = hideProtocolTags,
                                searchQuery = searchQuery,
                                matchContextMode = matchContextMode,
                                protocolTags = protocolTags,
                                expandedGroups = expandedGroups,
                                onToggleGroup = onToggleGroup,
                                expandedFields = expandedFields,
                                onToggleField = onToggleField,
                                indentLevel = indentLevel + 1,
                                parentKey = "${groupKey}_$i",
                                tagResults = tagResults,
                            )
                        } catch (e: Exception) {
                            // Skip invalid groups
                        }
                    }
                }
            } else {
                // Regular field (not a group)
                // Apply search filter for regular fields
                if (searchQuery.isNotBlank()) {
                    if (!fieldMatchesSearch(tag, value, dictionary, searchQuery)) {
                        continue
                    }
                }

                item {
                    FieldRow(
                        tag = tag,
                        value = value,
                        dictionary = dictionary,
                        indentLevel = indentLevel,
                        isExpanded = expandedFields.contains(fieldKey),
                        onToggleExpand = { onToggleField(fieldKey) },
                        searchQuery = searchQuery,
                        verdict = tagResults[tag],
                    )
                }
            }
        } catch (e: Exception) {
            // If getGroupCount throws an exception, treat as regular field
            // Apply search filter
            if (searchQuery.isNotBlank()) {
                if (!fieldMatchesSearch(tag, value, dictionary, searchQuery)) {
                    continue
                }
            }

            item {
                FieldRow(
                    tag = tag,
                    value = value,
                    dictionary = dictionary,
                    indentLevel = indentLevel,
                    isExpanded = expandedFields.contains(fieldKey),
                    onToggleExpand = { onToggleField(fieldKey) },
                )
            }
        }
    }
}

/**
 * Compact three-way toggle controlling how much context a search match reveals. Only shown while a
 * search is active.
 */
@Composable
private fun MatchContextToggle(
    mode: MatchContextMode,
    onModeChange: (MatchContextMode) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(headerBackgroundColor)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Context:",
            color = timestampColor,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
        MatchContextMode.entries.forEach { entry ->
            val selected = entry == mode
            Text(
                text = entry.label,
                color = if (selected) AppTheme.Colors.background else AppTheme.Colors.textSecondary,
                fontSize = 9.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
                modifier =
                    Modifier
                        .clip(searchFieldShape)
                        .background(if (selected) AppTheme.Colors.primary else AppTheme.Colors.surfaceVariant)
                        .clickable { onModeChange(entry) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Renders a repeating group while a search is active (IDENTITY/FULL modes). Only the instances that
 * contain a match are shown; each keeps its surrounding context per [mode]. The group header is
 * always shown as an ancestor anchor so the match never loses its path.
 */
private fun LazyListScope.renderSearchedGroup(
    fieldMap: FieldMap,
    tag: Int,
    groupCount: Int,
    groupKey: String,
    dictionary: FixDictionary,
    searchQuery: String,
    mode: MatchContextMode,
    hideProtocolTags: Boolean,
    protocolTags: Set<Int>,
    expandedFields: Set<String>,
    onToggleField: (String) -> Unit,
    indentLevel: Int,
    tagResults: Map<Int, TagVerdict> = emptyMap(),
) {
    item {
        GroupHeaderRow(
            tag = tag,
            count = groupCount,
            dictionary = dictionary,
            isExpanded = true,
            onToggle = {},
            indentLevel = indentLevel,
            searchQuery = searchQuery,
        )
    }

    for (i in 1..groupCount) {
        try {
            val group = fieldMap.getGroup(i, tag)
            if (!fieldMapMatchesSearch(group, dictionary, searchQuery, hideProtocolTags, protocolTags)) {
                continue
            }

            item {
                GroupInstanceHeader(
                    instanceNumber = i,
                    indentLevel = indentLevel + 1,
                )
            }

            val instanceKey = "${groupKey}_$i"
            if (mode == MatchContextMode.FULL) {
                renderFullFieldMap(
                    fieldMap = group,
                    dictionary = dictionary,
                    searchQuery = searchQuery,
                    hideProtocolTags = hideProtocolTags,
                    protocolTags = protocolTags,
                    expandedFields = expandedFields,
                    onToggleField = onToggleField,
                    indentLevel = indentLevel + 1,
                    parentKey = instanceKey,
                    tagResults = tagResults,
                )
            } else {
                renderIdentityInstance(
                    group = group,
                    dictionary = dictionary,
                    searchQuery = searchQuery,
                    hideProtocolTags = hideProtocolTags,
                    protocolTags = protocolTags,
                    expandedFields = expandedFields,
                    onToggleField = onToggleField,
                    indentLevel = indentLevel + 1,
                    parentKey = instanceKey,
                    tagResults = tagResults,
                )
            }
        } catch (e: Exception) {
            // Skip invalid groups
        }
    }
}

/**
 * Renders every field of [fieldMap] in full, with all nested groups force-expanded. Used by FULL
 * mode once an instance is known to contain a match.
 */
private fun LazyListScope.renderFullFieldMap(
    fieldMap: FieldMap,
    dictionary: FixDictionary,
    searchQuery: String,
    hideProtocolTags: Boolean,
    protocolTags: Set<Int>,
    expandedFields: Set<String>,
    onToggleField: (String) -> Unit,
    indentLevel: Int,
    parentKey: String,
    tagResults: Map<Int, TagVerdict> = emptyMap(),
) {
    val iterator = fieldMap.iterator()
    while (iterator.hasNext()) {
        @Suppress("UNCHECKED_CAST")
        val field = iterator.next() as Field<*>
        val tag = field.tag
        if (hideProtocolTags && tag in protocolTags) {
            continue
        }
        val value = field.getObject().toString()
        val fieldKey = "${parentKey}_$tag"
        val groupCount = groupCountOrZero(fieldMap, tag)
        if (groupCount > 0) {
            val groupKey = "$parentKey/${tag}_$groupCount"
            item {
                GroupHeaderRow(
                    tag = tag,
                    count = groupCount,
                    dictionary = dictionary,
                    isExpanded = true,
                    onToggle = {},
                    indentLevel = indentLevel,
                    searchQuery = searchQuery,
                )
            }
            for (i in 1..groupCount) {
                try {
                    val nested = fieldMap.getGroup(i, tag)
                    item {
                        GroupInstanceHeader(
                            instanceNumber = i,
                            indentLevel = indentLevel + 1,
                        )
                    }
                    renderFullFieldMap(
                        fieldMap = nested,
                        dictionary = dictionary,
                        searchQuery = searchQuery,
                        hideProtocolTags = hideProtocolTags,
                        protocolTags = protocolTags,
                        expandedFields = expandedFields,
                        onToggleField = onToggleField,
                        indentLevel = indentLevel + 1,
                        parentKey = "${groupKey}_$i",
                        tagResults = tagResults,
                    )
                } catch (e: Exception) {
                    // Skip invalid groups
                }
            }
        } else {
            item {
                FieldRow(
                    tag = tag,
                    value = value,
                    dictionary = dictionary,
                    indentLevel = indentLevel,
                    isExpanded = expandedFields.contains(fieldKey),
                    onToggleExpand = { onToggleField(fieldKey) },
                    searchQuery = searchQuery,
                    verdict = tagResults[tag],
                )
            }
        }
    }
}

/**
 * Renders a single matching group instance in IDENTITY mode: its identity field plus any simple
 * field that matches, plus nested sub-groups that contain a match (recursively).
 */
private fun LazyListScope.renderIdentityInstance(
    group: FieldMap,
    dictionary: FixDictionary,
    searchQuery: String,
    hideProtocolTags: Boolean,
    protocolTags: Set<Int>,
    expandedFields: Set<String>,
    onToggleField: (String) -> Unit,
    indentLevel: Int,
    parentKey: String,
    tagResults: Map<Int, TagVerdict> = emptyMap(),
) {
    val includedTags = identityInstanceTags(group, dictionary, searchQuery, hideProtocolTags, protocolTags).toSet()
    val iterator = group.iterator()
    while (iterator.hasNext()) {
        @Suppress("UNCHECKED_CAST")
        val field = iterator.next() as Field<*>
        val tag = field.tag
        if (hideProtocolTags && tag in protocolTags) {
            continue
        }
        val groupCount = groupCountOrZero(group, tag)
        if (groupCount > 0) {
            if (!groupMatchesSearch(group, tag, groupCount, dictionary, searchQuery, hideProtocolTags, protocolTags)) {
                continue
            }
            renderSearchedGroup(
                fieldMap = group,
                tag = tag,
                groupCount = groupCount,
                groupKey = "$parentKey/${tag}_$groupCount",
                dictionary = dictionary,
                searchQuery = searchQuery,
                mode = MatchContextMode.IDENTITY,
                hideProtocolTags = hideProtocolTags,
                protocolTags = protocolTags,
                expandedFields = expandedFields,
                onToggleField = onToggleField,
                indentLevel = indentLevel,
                tagResults = tagResults,
            )
            continue
        }
        if (tag !in includedTags) {
            continue
        }
        val value = field.getObject().toString()
        val fieldKey = "${parentKey}_$tag"
        item {
            FieldRow(
                tag = tag,
                value = value,
                dictionary = dictionary,
                indentLevel = indentLevel,
                isExpanded = expandedFields.contains(fieldKey),
                onToggleExpand = { onToggleField(fieldKey) },
                searchQuery = searchQuery,
                verdict = tagResults[tag],
            )
        }
    }
}

/** Returns the repeating-group count for [tag] in [fieldMap], or 0 when [tag] is not a group. */
private fun groupCountOrZero(fieldMap: FieldMap, tag: Int): Int =
    try {
        fieldMap.getGroupCount(tag)
    } catch (e: Exception) {
        0
    }

/**
 * The identity field of a group instance: its first simple (non-group) field — e.g. PartyID for a
 * NoPartyIDs entry. Returns null when the instance has no simple field.
 */
internal fun identityFieldTag(
    fieldMap: FieldMap,
    hideProtocolTags: Boolean,
    protocolTags: Set<Int>,
): Int? {
    val iterator = fieldMap.iterator()
    while (iterator.hasNext()) {
        @Suppress("UNCHECKED_CAST")
        val field = iterator.next() as Field<*>
        val tag = field.tag
        if (hideProtocolTags && tag in protocolTags) {
            continue
        }
        if (groupCountOrZero(fieldMap, tag) > 0) {
            continue
        }
        return tag
    }
    return null
}

/**
 * The simple-field tags to render for a group instance in IDENTITY mode: the identity field plus
 * any simple field whose tag/name/value/translation matches the query, in FIX field order. Nested
 * groups are handled separately by the renderer.
 */
internal fun identityInstanceTags(
    fieldMap: FieldMap,
    dictionary: FixDictionary,
    searchQuery: String,
    hideProtocolTags: Boolean,
    protocolTags: Set<Int>,
): List<Int> {
    val identityTag = identityFieldTag(fieldMap, hideProtocolTags, protocolTags)
    val tags = mutableListOf<Int>()
    val iterator = fieldMap.iterator()
    while (iterator.hasNext()) {
        @Suppress("UNCHECKED_CAST")
        val field = iterator.next() as Field<*>
        val tag = field.tag
        if (hideProtocolTags && tag in protocolTags) {
            continue
        }
        if (groupCountOrZero(fieldMap, tag) > 0) {
            continue
        }
        val value = field.getObject().toString()
        if (tag == identityTag || fieldMatchesSearch(tag, value, dictionary, searchQuery)) {
            tags.add(tag)
        }
    }
    return tags
}

/**
 * Builds an [AnnotatedString] highlighting every case-insensitive occurrence of [query] in [text].
 * Returns the plain text when [query] is blank or absent.
 */
internal fun highlightMatches(
    text: String,
    query: String,
    highlightColor: Color,
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    if (!lowerText.contains(lowerQuery)) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        var start = 0
        while (true) {
            val index = lowerText.indexOf(lowerQuery, start)
            if (index < 0) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, index))
            withStyle(SpanStyle(background = highlightColor)) {
                append(text.substring(index, index + lowerQuery.length))
            }
            start = index + lowerQuery.length
        }
    }
}

/**
 * Checks if a field matches the search query
 */
private fun fieldMatchesSearch(
    tag: Int,
    value: String,
    dictionary: FixDictionary,
    searchQuery: String,
): Boolean {
    val query = searchQuery.lowercase()
    val fieldName = dictionary.getFieldName(tag)?.lowercase() ?: ""
    val translation = dictionary.getFieldValueDescription(tag, value)?.lowercase() ?: ""

    return tag.toString().contains(query) ||
        fieldName.contains(query) ||
        value.lowercase().contains(query) ||
        translation.contains(query)
}

/**
 * Recursively checks if a group or any of its children match the search query
 */
private fun groupMatchesSearch(
    fieldMap: FieldMap,
    tag: Int,
    groupCount: Int,
    dictionary: FixDictionary,
    searchQuery: String,
    hideProtocolTags: Boolean,
    protocolTags: Set<Int>,
): Boolean {
    val query = searchQuery.lowercase()
    val groupName = dictionary.getFieldName(tag)?.lowercase() ?: ""

    // Check if the group header itself matches
    if (tag.toString().contains(query) || groupName.contains(query)) {
        return true
    }

    // Check if any child field in any group instance matches
    for (i in 1..groupCount) {
        try {
            val group = fieldMap.getGroup(i, tag)
            if (fieldMapMatchesSearch(group, dictionary, searchQuery, hideProtocolTags, protocolTags)) {
                return true
            }
        } catch (e: Exception) {
            // Skip invalid groups
        }
    }

    return false
}

/**
 * Recursively checks if any field in a FieldMap matches the search query
 */
private fun fieldMapMatchesSearch(
    fieldMap: FieldMap,
    dictionary: FixDictionary,
    searchQuery: String,
    hideProtocolTags: Boolean,
    protocolTags: Set<Int>,
): Boolean {
    val iterator = fieldMap.iterator()

    while (iterator.hasNext()) {
        @Suppress("UNCHECKED_CAST")
        val field = iterator.next() as Field<*>
        val tag = field.tag

        // Skip protocol tags if hidden
        if (hideProtocolTags && tag in protocolTags) {
            continue
        }

        val value = field.getObject().toString()

        // Check if this field matches
        if (fieldMatchesSearch(tag, value, dictionary, searchQuery)) {
            return true
        }

        // Check if this is a nested group and any of its children match
        try {
            val groupCount = fieldMap.getGroupCount(tag)
            if (groupCount > 0) {
                if (groupMatchesSearch(
                        fieldMap = fieldMap,
                        tag = tag,
                        groupCount = groupCount,
                        dictionary = dictionary,
                        searchQuery = searchQuery,
                        hideProtocolTags = hideProtocolTags,
                        protocolTags = protocolTags,
                    )
                ) {
                    return true
                }
            }
        } catch (e: Exception) {
            // Not a group, skip
        }
    }

    return false
}

/**
 * Collects all group keys from a message for expand all functionality
 */
private fun collectAllGroupKeys(message: FixMessage): Set<String> {
    val keys = mutableSetOf<String>()

    collectGroupKeysFromFieldMap(message.quickfixMessage.header, keys)
    collectGroupKeysFromFieldMap(message.quickfixMessage, keys)
    collectGroupKeysFromFieldMap(message.quickfixMessage.trailer, keys)

    return keys
}

/**
 * Recursively collects group keys from a FieldMap
 */
private fun collectGroupKeysFromFieldMap(
    fieldMap: FieldMap,
    keys: MutableSet<String>,
    parentKey: String = "",
) {
    val iterator = fieldMap.iterator()

    while (iterator.hasNext()) {
        @Suppress("UNCHECKED_CAST")
        val field = iterator.next() as Field<*>
        val tag = field.tag

        try {
            val groupCount = fieldMap.getGroupCount(tag)
            if (groupCount > 0) {
                val groupKey =
                    if (parentKey.isEmpty()) {
                        "${tag}_$groupCount"
                    } else {
                        "$parentKey/${tag}_$groupCount"
                    }
                keys.add(groupKey)

                // Recursively collect keys from nested groups
                for (i in 1..groupCount) {
                    try {
                        val group = fieldMap.getGroup(i, tag)
                        collectGroupKeysFromFieldMap(group, keys, groupKey)
                    } catch (e: Exception) {
                        // Skip invalid groups
                    }
                }
            }
        } catch (e: Exception) {
            // Not a group, skip
        }
    }
}

// Color constants
private val panelBackgroundColor = AppTheme.Colors.background
private val headerBackgroundColor = AppTheme.Colors.surface
private val metadataBackgroundColor = AppTheme.Colors.surfaceVariant
private val fieldRowBackgroundColor = AppTheme.Colors.background
private val groupHeaderBackgroundColor = AppTheme.Colors.surfaceVariant
private val groupInstanceBackgroundColor = AppTheme.Colors.surfaceHeader

private val headerTextColor = AppTheme.Colors.text
private val iconTintColor = AppTheme.Colors.textSecondary
private val searchIconColor = AppTheme.Colors.textDisabled
private val timestampColor = AppTheme.Colors.textSecondary
private val rawMessageTextColor = AppTheme.Colors.text
private val placeholderTextColor = AppTheme.Colors.textDisabled

private val tagNumberColor = AppTheme.Colors.tagNumber
private val fieldNameColor = AppTheme.Colors.primary
private val fieldValueColor = AppTheme.Colors.text
private val groupHeaderTextColor = Color(0xFFD4A574) // Keep unique group header color
private val groupInstanceTextColor = AppTheme.Colors.fieldValue
private val expandIndicatorColor = AppTheme.Colors.textSecondary

// Translucent amber background drawn behind text matching the active search query
private val searchHighlightColor = Color(0x66FFD54F)

private val focusedBorderColor = AppTheme.Colors.primary
private val unfocusedBorderColor = AppTheme.Colors.border

private val outgoingMessageColor = AppTheme.Colors.messageOutgoing
private val incomingMessageColor = AppTheme.Colors.messageIncoming
private val rejectionMessageColor = AppTheme.Colors.messageRejection

// Modifier constants
private val buttonSize = Modifier.size(24.dp)
private val iconSize = Modifier.size(16.dp)
private val searchIconSize = Modifier.size(16.dp)
private val rawActionButtonSize = Modifier.size(20.dp)
private val rawActionIconSize = Modifier.size(14.dp)
private val searchFieldShape = RoundedCornerShape(2.dp)

// Text styles
private val searchTextStyle =
    TextStyle(
        fontSize = 10.sp,
        color = AppTheme.Colors.text,
        fontFamily = FontFamily.Monospace,
    )

private val searchPlaceholderStyle =
    TextStyle(
        fontSize = 10.sp,
        color = AppTheme.Colors.textDisabled,
        fontFamily = FontFamily.Monospace,
    )

// Helper functions
private fun getSearchBorderColor(isFocused: Boolean): Color =
    if (isFocused) focusedBorderColor else unfocusedBorderColor

private fun getDirectionInfo(message: FixMessage, appSettings: com.knapsack.fixtool.model.AppSettings): Pair<Color, String> =
    when (message.direction) {
        FixMessage.Direction.INCOMING ->
            appSettings.messageColorScheme.getMessageColor(
                message.direction,
                message.isRejectionOrLogout(),
                true,
            ) to "INCOMING"

        FixMessage.Direction.OUTGOING ->
            appSettings.messageColorScheme.getMessageColor(
                message.direction,
                false,
                true,
            ) to "OUTGOING"
    }
