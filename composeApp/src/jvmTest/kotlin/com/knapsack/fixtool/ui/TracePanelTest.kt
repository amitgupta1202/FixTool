package com.knapsack.fixtool.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.width
import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.TraceKey
import com.knapsack.fixtool.service.TraceRows
import com.knapsack.fixtool.service.Traces
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import quickfix.Message
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The Ledger as it appears on screen.**
 *
 * [com.knapsack.fixtool.service.TraceRowsTest] proves the row list; this proves the panel draws it — the
 * gap where the conversation view's shipped defects all lived. Two claims matter most here and neither
 * can be checked in the row builder: that a header states how many sessions the exchange touched
 * *without anyone opening it*, which is the question the panel exists to answer at a glance, and that
 * the fold actually folds, so a Ledger over a busy app is readable at all.
 */
class TracePanelTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var dictionary: FixDictionary
    private val epoch: LocalDateTime = LocalDateTime.of(2026, 9, 2, 10, 0, 0)

    private fun at(
        millis: Long,
        raw: String,
        direction: FixMessage.Direction = FixMessage.Direction.INCOMING,
    ): FixMessage =
        FixMessage(
            timestamp = epoch.plusNanos(millis * 1_000_000L),
            direction = direction,
            rawMessage = raw,
            quickfixMessage = Message(),
            wireRaw = raw.replace('|', FixMessageHelper.SOH),
        )

    private val out = FixMessage.Direction.OUTGOING

    private val snapshots
        get() =
            listOf(
                listOf(
                    at(0, "35=R|131=RFQ-A1|55=EUR/USD|38=10000000|", out),
                    at(5, "35=0|"),
                    at(40, "35=S|131=RFQ-A1|117=Q-77|"),
                ),
                listOf(
                    at(10, "35=R|131=V-2291|55=EUR/USD|38=10000000|"),
                    at(30, "35=S|131=V-2291|117=Q-77|", out),
                ),
            )

    private val titles = listOf("CLIENT", "LP-1")

    /** Held on the test rather than inside `setContent`, so a click's effect survives recomposition. */
    private val expandedState = mutableStateOf<Set<TraceKey>>(emptySet())
    private val ungroupedState = mutableStateOf(false)

    @Before
    fun setup() {
        expandedState.value = emptySet()
        ungroupedState.value = false
        // A LOADED dictionary: createDefault() names nothing, so the header would read "R ×2 · S ×2"
        // and the test would assert against a state no user ever sees.
        dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    }

    private fun rows(
        expanded: Set<TraceKey> = emptySet(),
        ungroupedExpanded: Boolean = false,
        followedAnchor: String? = null,
    ): List<TraceRows.Row> {
        val panes = snapshots
        return TraceRows.build(
            snapshots = panes,
            sessionTitles = titles,
            grouping = Traces.group(panes, dictionary),
            dictionary = dictionary,
            expanded = expanded,
            ungroupedExpanded = ungroupedExpanded,
            followedAnchor = followedAnchor,
        )
    }

    /**
     * The headline claim: *which exchanges crossed more than one session* is read off a shut panel.
     * The whole point of collapsing by default is that this line is legible before anything is opened.
     */
    @Test
    fun `a header states its session count and its composition with nothing expanded`() {
        composeTestRule.setContent {
            TracePanel(
                rows = rows(),
                sessionTitles = titles,
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
            )
        }

        composeTestRule.onNodeWithTag("trace-header-RFQ-A1").assertExists()
        composeTestRule.onNodeWithText("2 sessions").assertExists()
        composeTestRule.onNodeWithText("QuoteRequest ×2 · Quote ×2").assertExists()
        composeTestRule.onNodeWithText("RFQ-A1 · EUR/USD 10000000").assertExists()
        composeTestRule.onAllNodesWithTag("trace-member").assertCountEquals(0)
    }

    @Test
    fun `the status line counts what the panel is showing, and names what is followed`() {
        composeTestRule.setContent {
            TracePanel(
                rows = rows(),
                sessionTitles = titles,
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
            )
        }

        composeTestRule
            .onNodeWithTag("trace-panel-status")
            .assertTextContains("1 trace across 2 sessions · 1 ungrouped")
    }

    @Test
    fun `following names the trace, its panes and its end-to-end elapsed`() {
        composeTestRule.setContent {
            TracePanel(
                rows = rows(followedAnchor = "V-2291"),
                sessionTitles = titles,
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
                followingLabel = "RFQ-A1",
            )
        }

        composeTestRule
            .onNodeWithTag("trace-panel-status")
            .assertTextContains("Following RFQ-A1 · 2 sessions · 4 messages · 40 ms")
        // The header that IS the followed one draws its affordance pressed, and pressing it stops.
        composeTestRule.onNodeWithTag("unfollow-trace").assertExists()
    }

    /**
     * An id followed before the venue echoed it is a state this app deliberately holds — the panel says
     * so rather than reporting the counts of an unrelated view.
     */
    @Test
    fun `an anchor that has not arrived is reported, not silently counted away`() {
        composeTestRule.setContent {
            TracePanel(
                rows = rows(),
                sessionTitles = titles,
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
                followingLabel = "V-ORD-8813",
            )
        }

        composeTestRule
            .onNodeWithTag("trace-panel-status")
            .assertTextContains("Following V-ORD-8813 · not yet arrived")
    }

    @Test
    fun `the fold shows and hides a trace's messages`() {
        composeTestRule.setContent {
            TracePanel(
                rows = rows(expanded = expandedState.value),
                sessionTitles = titles,
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
                onToggleTrace = { key ->
                    expandedState.value =
                        if (key in expandedState.value) expandedState.value - key else expandedState.value + key
                },
            )
        }

        composeTestRule.onAllNodesWithTag("trace-member").assertCountEquals(0)

        composeTestRule.onNodeWithTag("trace-header-RFQ-A1").performClick()
        // Folding is keyed by (opener session, label) — see TraceKey — and the header hands that key back.
        assertEquals(setOf(TraceKey("CLIENT", "RFQ-A1")), expandedState.value)
        composeTestRule.onAllNodesWithTag("trace-member").assertCountEquals(4)
        // The gap to the previous message of this trace, on whichever pane it landed — so the client's
        // request to the LP's copy of it is +10 ms, and the LP's quote back is +20 ms after that.
        composeTestRule.onAllNodesWithText("+10 ms").assertCountEquals(2)
        composeTestRule.onNodeWithText("+20 ms").assertExists()

        composeTestRule.onNodeWithTag("trace-header-RFQ-A1").performClick()
        composeTestRule.onAllNodesWithTag("trace-member").assertCountEquals(0)
    }

    /** Last, counted, and openable: the messages the grouping could not explain are never hidden. */
    @Test
    fun `the ungrouped bucket is drawn and folds on its own`() {
        composeTestRule.setContent {
            TracePanel(
                rows = rows(ungroupedExpanded = ungroupedState.value),
                sessionTitles = titles,
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
                onToggleUngrouped = { ungroupedState.value = !ungroupedState.value },
            )
        }

        composeTestRule.onNodeWithText("Ungrouped · 1 message · no correlation id").assertExists()
        composeTestRule.onNodeWithTag("trace-ungrouped-header").performClick()
        composeTestRule.onAllNodesWithTag("trace-member").assertCountEquals(1)
    }

    @Test
    fun `a row click selects that message, with the pane it came from`() {
        var picked: Pair<Int, String>? = null
        composeTestRule.setContent {
            TracePanel(
                rows = rows(expanded = setOf(TraceKey("CLIENT", "RFQ-A1"))),
                sessionTitles = titles,
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
                onSelectMember = { located, message -> picked = located.session to message.messageType },
            )
        }

        composeTestRule.onAllNodesWithTag("trace-member")[1].performClick()
        assertEquals(1 to "R", picked, "the LP's copy of the request — the second row in merged order")
    }

    @Test
    fun `expand all and collapse all reach every trace on screen, bucket included`() {
        var expandedKeys: Collection<TraceKey>? = null
        var collapsed = 0
        composeTestRule.setContent {
            TracePanel(
                rows = rows(),
                sessionTitles = titles,
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
                onExpandAll = { expandedKeys = listOf(TraceKey("CLIENT", "RFQ-A1")) },
                onCollapseAll = { collapsed++ },
            )
        }

        composeTestRule.onNodeWithTag("trace-expand-all").performClick()
        assertEquals(listOf(TraceKey("CLIENT", "RFQ-A1")), expandedKeys)

        composeTestRule.onNodeWithTag("trace-collapse-all").performClick()
        assertEquals(1, collapsed)
    }

    /** Closing is closing. The panes stay narrowed and the toolbar chip goes on naming what to. */
    @Test
    fun `the close button closes the panel without unfollowing`() {
        var closed = 0
        var unfollowed = 0
        composeTestRule.setContent {
            TracePanel(
                rows = rows(followedAnchor = "RFQ-A1"),
                sessionTitles = titles,
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
                onUnfollow = { unfollowed++ },
                onClose = { closed++ },
            )
        }

        composeTestRule.onNodeWithTag("trace-close").performClick()
        assertEquals(1, closed)
        assertEquals(0, unfollowed)
    }

    // ------------------------------------------------------------------ the grid's columns

    private fun right(tag: String) = composeTestRule.onNodeWithTag(tag).getUnclippedBoundsInRoot().right

    private fun assertSameX(
        expected: Dp,
        actual: Dp,
        what: String,
    ) = assertTrue(abs((expected - actual).value) < 0.5f, "$what: expected $expected, got $actual")

    /**
     * **Every row ends on the header's last rule.** The trace row used to run 28dp past it for a follow button no
     * column stood over, and further with fewer than three tag columns, because its notes were held to 360dp
     * whatever the columns under them added up to; the ungrouped row stopped at Elapsed. None of that could be
     * seen in a row list — only in the widths the panel lays out.
     */
    @Test
    fun `every kind of row ends where the header ends, with three tag columns and with none`() {
        val settings = mutableStateOf(AppSettings.default())
        composeTestRule.setContent {
            TracePanel(
                rows = rows(expanded = setOf(TraceKey("CLIENT", "RFQ-A1")), ungroupedExpanded = true),
                sessionTitles = titles,
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = settings.value,
            )
        }

        fun assertFlush(label: String) {
            val header = right("grid-header")
            assertSameX(header, right("trace-header-RFQ-A1"), "$label: the trace row's right edge")
            assertSameX(header, right("trace-ungrouped-header"), "$label: the ungrouped row's right edge")
            composeTestRule.onAllNodesWithTag("trace-member").fetchSemanticsNodes().indices.forEach { i ->
                val member = composeTestRule.onAllNodesWithTag("trace-member")[i].getUnclippedBoundsInRoot().right
                assertSameX(header, member, "$label: member $i's right edge")
            }
        }

        assertEquals(3, AppSettings.default().gridViewColumns.size, "the default this test is named for")
        assertFlush("three tag columns")

        settings.value = AppSettings.default().copy(gridViewColumns = emptyList())
        composeTestRule.waitForIdle()
        assertFlush("no tag columns")
    }

    /** The follow button is in the Session column — a column the header has — not in a cell beyond the last one. */
    @Test
    fun `a trace row's follow button stands inside the Session column`() {
        composeTestRule.setContent {
            TracePanel(
                rows = rows(),
                sessionTitles = titles,
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
            )
        }

        val session = composeTestRule.onNodeWithTag("trace-column-Session").getUnclippedBoundsInRoot()
        val follow = composeTestRule.onNodeWithTag("follow-trace").getUnclippedBoundsInRoot()
        assertTrue(
            follow.left >= session.left && follow.right <= session.right,
            "follow at ${follow.left}..${follow.right}, Session at ${session.left}..${session.right}",
        )
    }

    /** The reported defect: the Ledger's columns could not be resized. Drag one, and the rows go with the header. */
    @Test
    fun `dragging a column's edge widens it in the header and in every row`() {
        composeTestRule.setContent {
            TracePanel(
                rows = rows(expanded = setOf(TraceKey("CLIENT", "RFQ-A1"))),
                sessionTitles = titles,
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
            )
        }
        val summaryBefore = composeTestRule.onNodeWithTag("trace-column-Summary").getUnclippedBoundsInRoot().width
        val elapsedBefore = composeTestRule.onNodeWithTag("trace-column-Elapsed").getUnclippedBoundsInRoot().left

        composeTestRule.onNodeWithTag("trace-column-Summary-resize", useUnmergedTree = true).performMouseInput {
            moveTo(center)
            press()
            repeat(6) { moveBy(Offset(10f, 0f)) }
            release()
        }
        composeTestRule.waitForIdle()

        val grown = composeTestRule.onNodeWithTag("trace-column-Summary").getUnclippedBoundsInRoot().width - summaryBefore
        assertTrue(abs(grown.value - 60f) < 1.5f, "a 60dp drag at 1x widens Summary by 60dp, got $grown")
        val elapsedAfter = composeTestRule.onNodeWithTag("trace-column-Elapsed").getUnclippedBoundsInRoot().left
        assertSameX(elapsedBefore + grown, elapsedAfter, "Elapsed moved over")
        assertSameX(right("grid-header"), right("trace-header-RFQ-A1"), "the trace row followed the header")
        val member = composeTestRule.onAllNodesWithTag("trace-member")[0].getUnclippedBoundsInRoot().right
        assertSameX(right("grid-header"), member, "a member row followed the header")
    }
}
