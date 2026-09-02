package com.knapsack.fixtool.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.LaneRole
import com.knapsack.fixtool.service.TraceLanes
import com.knapsack.fixtool.service.TraceRows
import com.knapsack.fixtool.service.Traces
import com.knapsack.fixtool.viewmodel.TraceRendering
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import quickfix.Message
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * **Lanes as it appears on screen.**
 *
 * [com.knapsack.fixtool.service.TraceLanesTest] proves the model — which columns, which pairs, which
 * gaps. This proves the drawing, which is where the conversation view's shipped defects all lived, and
 * it pins the three things only the drawing can be wrong about: that the columns are in the order the
 * model put them and carry the profile's own word for each side, that a hop is drawn **once** with its
 * arrow rather than twice, and that with nothing followed the panel offers the choice instead of an
 * empty grid.
 */
class TraceLanesViewTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var dictionary: FixDictionary
    private val epoch: LocalDateTime = LocalDateTime.of(2026, 9, 2, 10, 0, 0)
    private val out = FixMessage.Direction.OUTGOING

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

    @Before
    fun setup() {
        // A LOADED dictionary: createDefault() names nothing, so a chip would read "R" twice over and
        // the test would assert against a state no user ever sees.
        dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    }

    /**
     * A both-sides RFQ in miniature. The request leaves the client and arrives on the venue's pane as
     * the same bytes — one hop — and the venue then quotes an LP under its own id.
     */
    private val snapshots: List<List<FixMessage>>
        get() =
            listOf(
                listOf(at(0, "35=R|131=RFQ-A1|55=EUR/USD|38=10000000|", out)),
                listOf(
                    at(31, "35=R|131=RFQ-A1|55=EUR/USD|38=10000000|"),
                    at(90, "35=S|131=RFQ-A1|117=Q-77|", out),
                ),
            )

    private val titles = listOf("CLIENT", "VENUE")
    private val roles = listOf(LaneRole.INITIATOR, LaneRole.ACCEPTOR)

    private fun lanes(
        panes: List<List<FixMessage>> = snapshots,
        laneTitles: List<String> = titles,
        laneRoles: List<LaneRole> = roles,
    ): TraceLanes.Lanes {
        val grouping = Traces.group(panes, dictionary)
        return TraceLanes.build(grouping.traces.first(), panes, laneTitles, laneRoles)
    }

    private fun headers(panes: List<List<FixMessage>> = snapshots): List<TraceRows.Row.Header> =
        TraceRows
            .build(panes, titles, Traces.group(panes, dictionary), dictionary)
            .filterIsInstance<TraceRows.Row.Header>()

    // ---------------------------------------------------------------- the columns

    @Test
    fun `one column per session, in the model's order, each naming its side`() {
        composeTestRule.setContent {
            TraceLanesView(
                lanes = lanes(),
                headers = headers(),
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
            )
        }

        composeTestRule.onAllNodesWithTag("trace-lane-header").assertCountEquals(2)
        composeTestRule.onAllNodesWithTag("trace-lane-divider").assertCountEquals(1)
        composeTestRule.onNodeWithText("CLIENT").assertExists()
        composeTestRule.onNodeWithText("VENUE").assertExists()
        // The profile's own word, not a guess off the CompIDs.
        composeTestRule.onNodeWithText("initiator").assertExists()
        composeTestRule.onNodeWithText("acceptor").assertExists()
    }

    /** A rule with nothing on one side of it would be a claim about topology, so it is not drawn. */
    @Test
    fun `the dashed rule appears only when both sides are present`() {
        composeTestRule.setContent {
            TraceLanesView(
                lanes = lanes(laneRoles = listOf(LaneRole.ACCEPTOR, LaneRole.ACCEPTOR)),
                headers = headers(),
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
            )
        }

        composeTestRule.onAllNodesWithTag("trace-lane-divider").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("trace-lane-header").assertCountEquals(2)
    }

    // ---------------------------------------------------------------- the rows

    /**
     * **One hop, one row, one arrow.** The request appears on two panes and is drawn once, with the
     * measured gap on the arrow between the lanes — and the quote that crossed nothing is its own row
     * with no arrow at all.
     */
    @Test
    fun `a same-bytes pair is drawn once, with the arrow and the gap it measures`() {
        composeTestRule.setContent {
            TraceLanesView(
                lanes = lanes(),
                headers = headers(),
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
            )
        }

        // Three messages across two panes, two rows: the hop, then the venue's quote.
        composeTestRule.onAllNodesWithTag("trace-lane-row").assertCountEquals(2)
        composeTestRule.onAllNodesWithTag("trace-lane-chip").assertCountEquals(2)
        composeTestRule.onAllNodesWithTag("trace-lane-pair").assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("trace-lane-landing").assertCountEquals(1)
        // The hop's own gap, printed on its arrow; the gutter says the quote row started 90 ms after the
        // request row did (since the previous row STARTED, so it never goes negative).
        composeTestRule.onAllNodesWithText("+31 ms").assertCountEquals(1)
        composeTestRule.onNodeWithText("+90 ms").assertExists()
        composeTestRule.onNodeWithText("QuoteRequest").assertExists()
        composeTestRule.onNodeWithText("Quote").assertExists()
    }

    /** The chip cites the values the grouping actually joined on, and a click selects app-wide. */
    @Test
    fun `a chip click selects that message with the pane it came from`() {
        var picked: Pair<Int, String>? = null
        composeTestRule.setContent {
            TraceLanesView(
                lanes = lanes(),
                headers = headers(),
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
                onSelectMember = { located, message -> picked = located.session to message.messageType },
            )
        }

        composeTestRule.onNodeWithText("RFQ-A1").assertExists()
        composeTestRule.onAllNodesWithTag("trace-lane-chip")[0].performClick()
        assertEquals(0 to "R", picked, "the OUT side of the hop, on the client's pane")
    }

    // ---------------------------------------------------------------- nothing followed

    /**
     * Lanes draws one exchange, so with none chosen it offers the choice. An empty grid would read as
     * "there are no traces", which is a different and false statement.
     */
    @Test
    fun `with nothing followed the headers are listed, and following one draws it`() {
        val followed = mutableStateOf<String?>(null)
        composeTestRule.setContent {
            TraceLanesView(
                lanes = if (followed.value == null) null else lanes(),
                headers = headers(),
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
                onFollow = { followed.value = it },
            )
        }

        composeTestRule.onNodeWithTag("trace-lanes-empty").assertExists()
        composeTestRule.onAllNodesWithTag("trace-lanes-pick").assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("trace-lane-row").assertCountEquals(0)

        composeTestRule.onAllNodesWithTag("trace-lanes-pick")[0].performClick()

        assertEquals("RFQ-A1", followed.value, "the click is a Follow of that trace")
        composeTestRule.onAllNodesWithTag("trace-lanes-empty").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("trace-lane-row").assertCountEquals(2)
    }

    // ---------------------------------------------------------------- the toggle in the panel

    /** The two drawings are one panel: the toggle switches what is on screen and nothing else. */
    @Test
    fun `the panel header switches between the Ledger and Lanes`() {
        val rendering = mutableStateOf(TraceRendering.LEDGER)
        composeTestRule.setContent {
            TracePanel(
                rows = TraceRows.build(snapshots, titles, Traces.group(snapshots, dictionary), dictionary),
                sessionTitles = titles,
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
                rendering = rendering.value,
                lanes = if (rendering.value == TraceRendering.LANES) lanes() else null,
                onSetRendering = { rendering.value = it },
            )
        }

        composeTestRule.onAllNodesWithTag("trace-lanes").assertCountEquals(0)
        composeTestRule.onNodeWithTag("trace-render-lanes").performClick()

        assertEquals(TraceRendering.LANES, rendering.value)
        composeTestRule.onNodeWithTag("trace-lanes").assertExists()
        composeTestRule.onAllNodesWithTag("trace-lane-row").assertCountEquals(2)

        composeTestRule.onNodeWithTag("trace-render-ledger").performClick()
        composeTestRule.onAllNodesWithTag("trace-lanes").assertCountEquals(0)
        composeTestRule.onNodeWithTag("trace-header-RFQ-A1").assertExists()
    }
}
