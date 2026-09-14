package com.knapsack.fixtool.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
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
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
     * measured gap where its arrow lands — and the quote that crossed nothing is its own row
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
        // The hop's own gap, printed where its arrow lands; the gutter says the quote row started 90 ms after the
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

    // ---------------------------------------------------------------- the widths

    private fun SemanticsNodeInteraction.left(): Dp = getUnclippedBoundsInRoot().left

    private fun SemanticsNodeInteraction.right(): Dp = getUnclippedBoundsInRoot().right

    private fun SemanticsNodeInteraction.width(): Dp = getUnclippedBoundsInRoot().width

    private fun assertMoved(
        expected: Dp,
        before: Dp,
        after: Dp,
        what: String,
    ) = assertTrue(abs((after - before - expected).value) < 1.5f, "$what moved ${after - before}, expected $expected")

    /**
     * The reported defect: a lane was 200dp and nothing could change it. Drag one lane's edge and that lane widens,
     * and everything drawn to its right — the next lane's header, its chips, the ◀ a hop lands on — moves over by
     * the same distance, while the arrow between the two lane centres grows by half of it.
     */
    @Test
    fun `dragging a lane's edge widens that lane, and what is drawn to its right moves with it`() {
        composeTestRule.setContent {
            TraceLanesView(
                lanes = lanes(),
                headers = headers(),
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
            )
        }
        val headers = composeTestRule.onAllNodesWithTag("trace-lane-header")
        val clientWidth = headers[0].width()
        val venueLeft = headers[1].left()
        val quoteRight = composeTestRule.onAllNodesWithTag("trace-lane-chip")[1].right()
        val landingLeft = composeTestRule.onNodeWithTag("trace-lane-landing").left()
        val arrowWidth = composeTestRule.onNodeWithTag("trace-lane-pair").width()
        val arrowLeft = composeTestRule.onNodeWithTag("trace-lane-pair").left()

        composeTestRule.onNodeWithTag("trace-lane-resize-0", useUnmergedTree = true).performMouseInput {
            moveTo(center)
            press()
            repeat(6) { moveBy(Offset(10f, 0f)) }
            release()
        }
        composeTestRule.waitForIdle()

        assertMoved(60.dp, clientWidth, headers[0].width(), "the dragged lane's edge")
        assertMoved(60.dp, venueLeft, headers[1].left(), "the next lane's header")
        assertMoved(60.dp, quoteRight, composeTestRule.onAllNodesWithTag("trace-lane-chip")[1].right(), "its chip")
        assertMoved(60.dp, landingLeft, composeTestRule.onNodeWithTag("trace-lane-landing").left(), "the landing")
        // The arrow runs centre to centre: its start follows the dragged lane's centre, half the drag.
        assertMoved(30.dp, arrowLeft, composeTestRule.onNodeWithTag("trace-lane-pair").left(), "the arrow's start")
        assertMoved(30.dp, arrowWidth, composeTestRule.onNodeWithTag("trace-lane-pair").width(), "the arrow")
    }

    /**
     * A fill carrying two long ids does not fit a 200dp lane, which is what made Lanes unreadable. Double-click the
     * lane's header and the chip shows whole; double-click again and the lane is back to its declared width.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `double-clicking a lane's header fits its widest chip, and again puts the declared width back`() {
        val fill = at(120, "35=8|11=RFQ-A1|37=VENUE-ORDER-2026-0914-000123|17=EXEC-2026-0914-000456|39=2|")
        val panes = listOf(snapshots[0] + fill, snapshots[1])
        val widths = laneColumnWidths()
        // Room for the fill's chip to lay out whole, so its natural width can be read before the lane shrinks.
        widths.resizeBy("CLIENT", 600.dp, default = 200.dp)
        composeTestRule.setContent {
            TraceLanesView(
                lanes = lanes(panes = panes),
                headers = headers(panes),
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
                laneWidths = widths,
            )
        }
        val chip = { composeTestRule.onAllNodesWithTag("trace-lane-chip")[2] }
        val header = composeTestRule.onAllNodesWithTag("trace-lane-header")[0]
        composeTestRule.onAllNodesWithTag("trace-lane-chip").assertCountEquals(3)
        val whole = chip().width()

        widths.resizeBy("CLIENT", (-600).dp, default = 200.dp)
        composeTestRule.waitForIdle()
        assertTrue(chip().width() < whole - 1.dp, "at 200dp the fill's chip is cut: ${chip().width()} of $whole")

        header.performMouseInput { doubleClick() }
        composeTestRule.waitForIdle()
        assertTrue(chip().width() >= whole - 0.5.dp, "fitted, the chip shows whole: ${chip().width()} of $whole")
        assertTrue(header.width() < 800.dp, "fitted to the chip, not thrown to the limit: ${header.width()}")

        header.performMouseInput { doubleClick() }
        composeTestRule.waitForIdle()
        assertMoved(0.dp, 200.dp, header.width(), "the second double-click's lane edge")
    }

    private fun DpRect.overlaps(other: DpRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /**
     * A hop's gap is the number Lanes exists to show, and it was printed halfway between the two lane centres —
     * inside a lane whose chip is right-aligned, so a chip longer than half its lane covered it. At 200dp that was
     * every chip carrying an id, and fitting a lane to its chips made it every chip. Found on screen with the FX
     * venue: `+0 ms` cut in half by `8 FILLED …`. Both directions, at the declared width, the widest, and fitted.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a hop's gap is never under a chip, in either direction and at any lane width`() {
        val request = "35=R|131=RFQ-A1|55=EUR/USD|38=10000000|117=QUOTE-2026-0914-000123-LONG|"
        val fill = "35=8|11=RFQ-A1|37=VENUE-ORDER-2026-0914-000123|17=EXEC-2026-0914-000456|39=2|"
        val panes =
            listOf(
                listOf(at(0, request, out), at(120, fill)),
                listOf(at(31, request), at(100, fill, out)),
            )
        val widths = laneColumnWidths()
        composeTestRule.setContent {
            TraceLanesView(
                lanes = lanes(panes = panes),
                headers = headers(panes),
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
                laneWidths = widths,
            )
        }
        composeTestRule.onAllNodesWithTag("trace-lane-pair").assertCountEquals(2)

        fun assertGapsClear(state: String) {
            composeTestRule.waitForIdle()
            val chips = composeTestRule.onAllNodesWithTag("trace-lane-chip").fetchSemanticsNodes()
            for (gap in listOf("+31 ms", "+20 ms")) {
                val label = composeTestRule.onNodeWithText(gap).getUnclippedBoundsInRoot()
                chips.forEachIndexed { index, _ ->
                    val chip = composeTestRule.onAllNodesWithTag("trace-lane-chip")[index].getUnclippedBoundsInRoot()
                    assertTrue(
                        !label.overlaps(chip),
                        "$state: $gap at ${label.left}..${label.right} is under chip $index at ${chip.left}..${chip.right}",
                    )
                }
            }
        }

        assertGapsClear("declared width")
        widths.resizeBy("CLIENT", 600.dp, default = 200.dp)
        widths.resizeBy("VENUE", 600.dp, default = 200.dp)
        assertGapsClear("widest")
        composeTestRule.onAllNodesWithTag("trace-lane-header")[0].performMouseInput { doubleClick() }
        composeTestRule.onAllNodesWithTag("trace-lane-header")[1].performMouseInput { doubleClick() }
        assertGapsClear("fitted")
    }

    /** The exchange's opening moment was cut to `08:39:22.4` on screen: the gutter was narrower than its own clock. */
    @Test
    fun `the opening row's clock time is printed whole`() {
        composeTestRule.setContent {
            TraceLanesView(
                lanes = lanes(),
                headers = headers(),
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
            )
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeTestRule
            .onNodeWithText("10:00:00.000")
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        assertEquals(1, layouts.size)
        // The text's own one-line width against the room it was given. Not hasVisualOverflow: that compares the
        // laid-out size with the paragraph box, and reports a 75dp clock in an 88dp gutter as overflowing.
        val natural = layouts[0].multiParagraph.intrinsics.maxIntrinsicWidth
        val room = layouts[0].layoutInput.constraints.maxWidth
        assertTrue(natural <= room, "the gutter clips its own clock time: it needs ${natural}px and has ${room}px")
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

    /**
     * A hop a venue FixTool runs relayed says so under its arrow, in the words its reason recorded — and the
     * lane headers carry the negotiation's own words, not initiator and acceptor, which no longer tell the two
     * edges apart.
     */
    @Test
    fun `a relayed hop carries its reason under the arrow, and lanes wear the party they play`() {
        val relayed =
            at(40, "35=R|131=V-RFQ-1042|", out).copy(
                sendReason =
                    com.knapsack.fixtool.model.SendReason(
                        source = com.knapsack.fixtool.model.SendReason.Source.RULE,
                        at = epoch,
                        ruleIndex = 3,
                        relay =
                            com.knapsack.fixtool.model
                                .RelayRef(1, "k", "FIBUY1", "R", "responders", "FIDLR1", "RFQ-1"),
                    ),
            )
        val panes =
            listOf(
                listOf(at(0, "35=R|131=V-RFQ-1042|55=T 4.25 11/15/36|", out)),
                listOf(at(10, "35=R|131=V-RFQ-1042|55=T 4.25 11/15/36|"), relayed),
                listOf(at(45, "35=R|131=V-RFQ-1042|")),
            )
        val grouping = Traces.group(panes, dictionary)
        val drawn =
            TraceLanes.build(
                grouping.traces.first(),
                panes,
                listOf("BUY1", "VENUE ← FIBUY1", "DLR1"),
                listOf(LaneRole.INITIATOR, LaneRole.ACCEPTOR, LaneRole.INITIATOR),
                parties =
                    TraceLanes.Parties(
                        sessionGroups = listOf(null, "venue", null),
                        partyRoles = listOf("requester", null, "responder"),
                    ),
            )

        composeTestRule.setContent {
            TraceLanesView(
                lanes = drawn,
                headers =
                    TraceRows
                        .build(
                            panes,
                            listOf("BUY1", "VENUE ← FIBUY1", "DLR1"),
                            grouping,
                            dictionary,
                        ).filterIsInstance<TraceRows.Row.Header>(),
                selectedMessage = null,
                dictionary = dictionary,
                appSettings = AppSettings.default(),
            )
        }

        composeTestRule.onNodeWithTag("trace-lane-reason").assertExists()
        composeTestRule.onNodeWithText("relayed · rule 4 · to responders").assertExists()
        composeTestRule.onNodeWithText("requester").assertExists()
        composeTestRule.onNodeWithText("responder").assertExists()
        composeTestRule.onNodeWithText("venue").assertExists()
    }
}
