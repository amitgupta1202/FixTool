package com.knapsack.fixtool.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.AppMessage
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.service.FixMessageHelper
import java.time.LocalDateTime
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import quickfix.Message

/**
 * **The conversation view as the grid actually draws it.**
 *
 * [com.knapsack.fixtool.service.ConversationRowsTest] proves the render list; this proves the grid
 * consumes it. The distinction is not academic — the defects this feature shipped with all lived
 * exactly here, in the gap between a correct row list and what appeared on screen:
 *
 *  - grouping was first wired into the RAW branch while PARSED renders [HierarchicalGridView], so
 *    the toggle did nothing in the view everyone uses;
 *  - every `scrollToItem` passed a message index, which headers offset and a collapsed group can
 *    push past the end;
 *  - the group row painted its trailing spacer, drawing a phantom column past the grid's width.
 *
 * A test at this level would have caught the first. The second and third are covered by their own
 * assertions below where a headless rule can see them.
 */
class ConversationGridViewTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var dictionary: FixDictionary
    private val messages = mutableStateListOf<AppMessage>()
    private var collapsed = mutableStateOf<Set<String>>(emptySet())

    @Before
    fun setup() {
        // A LOADED dictionary: createDefault() resolves nothing, so the header would read
        // "D x1 - 8 x1 - 2" and the test would assert against a state no user ever sees.
        dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
        collapsed.value = emptySet()
        messages.clear()
        // Two orders interleaved with a heartbeat that belongs to neither.
        messages += fix("35=D|11=ORD-1|55=EUR/USD|38=1000000|", FixMessage.Direction.OUTGOING)
        messages += fix("35=0|", FixMessage.Direction.INCOMING)
        messages += fix("35=D|11=ORD-2|55=GBP/USD|38=2000000|", FixMessage.Direction.OUTGOING)
        messages += fix("35=8|37=V-1|11=ORD-1|39=2|", FixMessage.Direction.INCOMING)
    }

    private fun fix(raw: String, direction: FixMessage.Direction): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = direction,
            rawMessage = raw,
            quickfixMessage = Message(),
            wireRaw = raw.replace('|', FixMessageHelper.SOH),
        )

    private fun renderGrid(grouped: Boolean) {
        composeTestRule.setContent {
            HierarchicalGridView(
                messages = messages,
                dictionary = dictionary,
                hideProtocolTags = true,
                groupByConversation = grouped,
                collapsedConversations = collapsed.value,
                onToggleConversation = { key ->
                    collapsed.value = if (key in collapsed.value) collapsed.value - key else collapsed.value + key
                },
            )
        }
    }

    /**
     * **The regression that shipped.** Grouping was wired into the raw-text renderer while the grid
     * — what PARSED mode draws, and what nearly everyone looks at — kept rendering flat. The toggle
     * appeared to do nothing, and no unit test could tell.
     */
    @Test
    fun `grouping on draws a header row per conversation in the grid`() {
        renderGrid(grouped = true)

        composeTestRule.onNodeWithText("ORD-1", substring = true).assertExists()
        composeTestRule.onNodeWithText("ORD-2", substring = true).assertExists()
    }

    /** Off is off: the additive-only promise, asserted where a reader can see it. */
    @Test
    fun `grouping off draws no header rows`() {
        renderGrid(grouped = false)

        composeTestRule.onAllNodesWithText("Ungrouped").assertCountEquals(0)
    }

    /**
     * Nothing is hidden. The heartbeat carries no correlation id and must remain visible under its
     * own heading rather than being dropped from a view that claims to show the session.
     */
    @Test
    fun `messages with no correlation id appear under Ungrouped`() {
        renderGrid(grouped = true)

        composeTestRule.onNodeWithText("Ungrouped").assertExists()
    }

    /** The summary quotes the dictionary rather than inventing a verdict — 39=2 is FILLED. */
    @Test
    fun `the header quotes the last stated status`() {
        renderGrid(grouped = true)

        composeTestRule.onNodeWithText("FILLED", substring = true, ignoreCase = true).assertExists()
    }

    /** Clicking a group row folds it: the header stays, its members go. */
    @Test
    fun `clicking a header collapses that conversation only`() {
        renderGrid(grouped = true)

        // The ExecutionReport belongs to ORD-1; ORD-2's own row must survive the fold.
        composeTestRule.onAllNodesWithText("ExecutionReport").assertCountEquals(1)

        composeTestRule.onNodeWithText("Ungrouped").performClick()
        composeTestRule.waitForIdle()

        // Ungrouped folded: its heartbeat row is gone, every conversation header remains.
        composeTestRule.onNodeWithText("Ungrouped").assertExists()
        composeTestRule.onAllNodesWithText("Heartbeat").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("ExecutionReport").assertCountEquals(1)
    }
}
