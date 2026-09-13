package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.StepAddress
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.MatcherCodec
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A rule card on a venue that relays**: the three rows a trigger asks the venue through, the To menu on each
 * step, who a step would reach, and the card judged against the counterparties the venue declares.
 *
 * Every row writes the stored form the engine reads — a `role` matcher on 49, an `rfq` matcher on 131 or 117, the
 * `whenResponders` string, a step's `to` word — so each test asserts the rule that came out, not the pixels.
 */
class AcceptorRelayCardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun condition(tag: Int, matcher: Matcher) = FieldCondition(tag, MatcherCodec.matcherToJson(matcher))

    private val venue =
        listOf(
            Counterparty("FIBUY1", "requester"),
            Counterparty("FIDLR1", "responder"),
            Counterparty("FIDLR2", "responder"),
            Counterparty("FIDLRLG*", "responder"),
        )

    private val quoteRequest = AcceptorResponseRule(whenMsgType = "R", steps = listOf(ResponseStep("35=R|131=\${req.uuid}|")))

    private val relayRequest =
        AcceptorResponseRule(
            whenMsgType = "R",
            conditions = listOf(condition(49, Matcher.CounterpartyRole("requester"))),
            steps = listOf(ResponseStep("35=R|131=\${req.uuid}|55=\${req.55}|", to = "responders")),
        )

    private val lift =
        AcceptorResponseRule(
            whenMsgType = "AJ",
            conditions = listOf(condition(49, Matcher.CounterpartyRole("requester")), condition(117, Matcher.RfqState("open"))),
            steps =
                listOf(
                    ResponseStep("35=AJ|694=1|117=\${to.117}|", to = "quoter"),
                    ResponseStep("35=8|39=2|117=\${to.117}|", to = "quoter"),
                    ResponseStep("35=8|39=2|11=\${req.11}|"),
                ),
        )

    private var latest: List<AcceptorResponseRule> = emptyList()

    private fun render(
        rules: List<AcceptorResponseRule>,
        counterparties: List<Counterparty>? = venue,
        online: Set<String>? = null,
    ) {
        latest = rules
        composeTestRule.setContent {
            var live by remember { mutableStateOf(rules) }
            Box(modifier = Modifier.width(760.dp)) {
                AcceptorRulesEditor(
                    rules = live,
                    onRulesChange = {
                        live = it
                        latest = it
                    },
                    counterparties = counterparties,
                    onlineCompIds = online,
                )
            }
        }
    }

    private fun openRule(position: Int) {
        composeTestRule.onNodeWithTag("rule-expand-$position").performClick()
        composeTestRule.waitForIdle()
    }

    private fun pick(tag: String, word: String) {
        composeTestRule.onNodeWithTag(tag).performClick()
        composeTestRule.onNodeWithText(word).performClick()
        composeTestRule.waitForIdle()
    }

    // ------------------------------------------------------------------ which cards carry the rows

    @Test
    fun `the venue's rows are on a card where the venue declares counterparties, and on no other`() {
        render(listOf(quoteRequest), counterparties = null)
        openRule(0)

        composeTestRule.onNodeWithTag("rule-when-sender").assertDoesNotExist()
        composeTestRule.onNodeWithTag("rule-when-rfq").assertDoesNotExist()
        composeTestRule.onNodeWithTag("rule-responders-online").assertDoesNotExist()
        composeTestRule.onNodeWithTag("step-to-0-1").assertDoesNotExist()
    }

    @Test
    fun `a rule already written in the venue's terms shows its rows wherever it is opened`() {
        render(listOf(relayRequest), counterparties = null)
        openRule(0)

        composeTestRule.onNodeWithTag("rule-when-sender").assertTextEquals("requester ▾")
        composeTestRule.onNodeWithTag("step-to-0-1").assertTextEquals("to responders ▾")
        // Drawn as the row, so it is not drawn a second time as a tag-49 condition.
        composeTestRule.onNodeWithText("and tag").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ the rows write the stored form

    @Test
    fun `the sender row writes a role condition on 49, and any takes it back off`() {
        render(listOf(quoteRequest))
        openRule(0)

        pick("rule-when-sender", "requester")
        val condition = latest.single().conditions.single()
        assertEquals(49, condition.tag)
        assertEquals(Matcher.CounterpartyRole("requester"), condition.parsed())

        pick("rule-when-sender", "any")
        assertTrue(latest.single().conditions.isEmpty(), "a rule must be able to stop asking, not only start")
    }

    @Test
    fun `the RFQ row reads a lift through 117 and can be moved to 131`() {
        render(listOf(lift.copy(conditions = listOf(condition(49, Matcher.CounterpartyRole("requester"))))))
        openRule(0)

        pick("rule-when-rfq", "open")
        assertEquals(listOf(49, 117), latest.single().conditions.map { it.tag }, "a new condition goes after the ones there")
        assertEquals(Matcher.RfqState("open"), latest.single().conditions[1].parsed())

        pick("rule-when-rfq-tag", "131")
        assertEquals(listOf(49, 131), latest.single().conditions.map { it.tag }, "moved where it stands")
    }

    @Test
    fun `the responders row writes the word the engine reads`() {
        render(listOf(relayRequest))
        openRule(0)

        pick("rule-responders-online", "none")

        assertEquals("none", latest.single().whenResponders)
    }

    @Test
    fun `a second role condition stays in the list, so nothing a hand-edited profile carries is hidden`() {
        val twice =
            relayRequest.copy(
                conditions = relayRequest.conditions + condition(49, Matcher.CounterpartyRole("unlisted")),
            )
        render(listOf(twice))
        openRule(0)

        composeTestRule.onNodeWithTag("rule-when-sender").assertTextEquals("requester ▾")
        composeTestRule.onAllNodesWithText("and tag").assertCountEquals(1)
    }

    // ------------------------------------------------------------------ To

    @Test
    fun `picking an address writes it on the step, and the sender writes nothing`() {
        render(listOf(relayRequest.copy(steps = listOf(ResponseStep("35=R|131=\${req.uuid}|")))))
        openRule(0)

        composeTestRule.onNodeWithTag("step-to-0-1").assertTextEquals("to sender ▾")
        pick("step-to-0-1", "responders")
        assertEquals(
            "responders",
            latest
                .single()
                .steps
                .single()
                .to,
        )

        pick("step-to-0-1", "FIDLR2")
        assertEquals(
            "compId:FIDLR2",
            latest
                .single()
                .steps
                .single()
                .to,
        )

        pick("step-to-0-1", "sender")
        assertNull(
            latest
                .single()
                .steps
                .single()
                .to,
            "a step moved back to the sender is the step it was before relaying",
        )
    }

    @Test
    fun `a step to the responders says who is here and who would be counted as not delivered`() {
        render(listOf(relayRequest), online = setOf("FIDLR1", "FIDLRLG3", "FIBUY1"))
        openRule(0)

        composeTestRule
            .onNodeWithTag("step-to-preview-0-1")
            .assertTextEquals("↳ FIDLR1, FIDLRLG3 online now · FIDLR2 not logged on, so counted as not delivered")
    }

    @Test
    fun `a rule that sends the quoter a fill says it books a trade, closed and open`() {
        render(listOf(lift))

        composeTestRule
            .onNodeWithTag("rule-digest-0")
            .assertTextEquals("the sender is requester · the RFQ is open (via 117) · 3 steps to quoter, sender · books a trade")

        openRule(0)
        composeTestRule.onNodeWithTag("rule-books-trade-0").assertExists()
    }

    // ------------------------------------------------------------------ judged against the venue

    @Test
    fun `a relay rule on a venue that declares nobody is refused on its card`() {
        render(listOf(relayRequest), counterparties = emptyList())

        composeTestRule.onNodeWithText("declares no counterparties", substring = true).assertExists()
    }

    @Test
    fun `where no venue is in hand, nothing is judged against a list nobody gave`() {
        render(listOf(relayRequest), counterparties = null)

        composeTestRule.onNodeWithText("declares no counterparties", substring = true).assertDoesNotExist()
    }

    // ------------------------------------------------------------------ the words, without a window

    @Test
    fun `the To menu offers every exact counterparty and keeps an address it does not list`() {
        val options = addressOptions(venue, StepAddress.CompId("FIDLR9"))

        assertEquals(StepAddress.Sender, options.first())
        assertTrue(StepAddress.CompId("FIDLR1") in options && StepAddress.CompId("FIBUY1") in options)
        assertTrue(options.none { it is StepAddress.CompId && it.compId.endsWith("*") }, "a family is not one counterparty")
        assertEquals(StepAddress.CompId("FIDLR9"), options.last())
    }

    @Test
    fun `a named counterparty's preview says its part and whether it is here`() {
        assertEquals("FIDLR1 · responder · logged on", recipientsPreview(StepAddress.CompId("FIDLR1"), venue, setOf("FIDLR1")))
        assertEquals(
            "FIDLR9 · not a counterparty this venue declares · not logged on, so counted as not delivered",
            recipientsPreview(StepAddress.CompId("FIDLR9"), venue, emptySet()),
        )
        assertNull(recipientsPreview(StepAddress.Quoter, venue, emptySet()), "who a quote reaches is the book's to say")
        assertEquals(
            "no counterparty is declared a responder, so this reaches nobody",
            recipientsPreview(StepAddress.Responders, venue.take(1), null),
        )
    }
}
