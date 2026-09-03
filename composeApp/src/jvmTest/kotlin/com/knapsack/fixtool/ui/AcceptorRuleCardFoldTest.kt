package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.MatcherCodec
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

/**
 * **A rule you can read without opening it, and a number you can look up.**
 *
 * The FX venue preset is twenty-one rules, and until this the editor drew all twenty-one open: a
 * checkbox, a MsgType field, a matcher per condition, a delay and a raw FIX template per step, with no
 * rule numbered and nothing marking the one that had just answered. That is a fine surface for
 * *writing* a venue and the wrong one for reading it — which is what a new user does first and what a
 * demo does exclusively.
 *
 * Three claims are pinned here, and each was a way the list failed to answer a question it was the
 * only thing that could answer:
 *
 * - **The number.** `SendReason` prints "sent by rule 7" on every reply the venue sends, the shadowing
 *   warning says "rule 1 answers every 35=D", and `/acceptor/rules` addresses rules by index. The card
 *   was the one surface that knew its own position and did not print it, so a reader told which rule
 *   answered them counted cards to find it.
 * - **The digest.** Closed, a rule still has to say what it answers and what it sends back, or the
 *   fold has traded twenty-one unreadable cards for twenty-one opaque ones.
 * - **The mark.** The reply names its rule; this is the same fact from the other end, so the answer to
 *   "which of these fired?" is on the list rather than only on the message.
 */
class AcceptorRuleCardFoldTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun condition(tag: Int, matcher: Matcher) = FieldCondition(tag, MatcherCodec.matcherToJson(matcher))

    /** The FX venue's limit-order rule, in miniature: two conditions, a three-step reply over 500ms. */
    private val limitFlow =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions =
                listOf(
                    condition(55, Matcher.OneOf(listOf("EUR/USD", "GBP/USD"))),
                    condition(40, Matcher.Exact("2")),
                ),
            steps =
                listOf(
                    ResponseStep(template = "35=8|150=0|39=0"),
                    ResponseStep(template = "35=8|150=1|39=1", delayMillis = 250),
                    ResponseStep(template = "35=8|150=F|39=2", delayMillis = 250),
                ),
        )

    /** The rule every list of them ends with, and the one whose card used to look empty. */
    private val catchAll =
        AcceptorResponseRule(whenMsgType = "D", steps = listOf(ResponseStep(template = "35=8|39=8|103=1")))

    private fun render(
        rules: List<AcceptorResponseRule>,
        fired: RuleFiredMark? = null,
        width: Int = 700,
        onRulesChange: (List<AcceptorResponseRule>) -> Unit = {},
    ) {
        composeTestRule.setContent {
            var live by remember { mutableStateOf(rules) }
            Box(modifier = Modifier.width(width.dp)) {
                AcceptorRulesEditor(
                    rules = live,
                    onRulesChange = {
                        live = it
                        onRulesChange(it)
                    },
                    firedRule = fired,
                )
            }
        }
    }

    // ---------------------------------------------------------------- closed by default

    @Test
    fun `a card starts closed, saying what the rule answers and what it sends back`() {
        render(listOf(limitFlow))

        composeTestRule
            .onNodeWithTag("rule-digest-0")
            .assertTextEquals("55 oneOf [EUR/USD,GBP/USD] · 40 exact 2 · 3 steps over 500ms")

        // The claim the digest makes about time is the one thing raw JSON cannot show, so it is worth
        // saying twice: 250 + 250 is 500ms end to end, not 250.
        composeTestRule.onNodeWithText("35=8|150=0|39=0").assertDoesNotExist()
        composeTestRule.onNodeWithTag("step-edit-0-1").assertDoesNotExist()
    }

    @Test
    fun `each card prints the number every other surface names it by`() {
        render(listOf(limitFlow, catchAll, limitFlow))

        composeTestRule.onNodeWithTag("rule-number-0").assertTextEquals("1.")
        composeTestRule.onNodeWithTag("rule-number-1").assertTextEquals("2.")
        composeTestRule.onNodeWithTag("rule-number-2").assertTextEquals("3.")
    }

    /**
     * A rule with no conditions is the catch-all — the reason every card above it in the same MsgType
     * has to be read in order. Drawn closed with an empty condition list it said nothing at all, which
     * reads as a rule that has not been finished rather than the one that answers everything else.
     */
    @Test
    fun `a rule with no conditions says it answers anything of its type`() {
        render(listOf(catchAll))

        composeTestRule.onNodeWithTag("rule-digest-0").assertTextEquals("any 35=D · 1 step")
    }

    // ---------------------------------------------------------------- opening

    @Test
    fun `opening a card gives back the fields, and closing it puts them away`() {
        render(listOf(limitFlow))

        composeTestRule.onNodeWithTag("rule-expand-0").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("35=8|150=0|39=0").assertExists()
        composeTestRule.onNodeWithTag("rule-when-order").assertExists()
        composeTestRule.onNodeWithText("+ condition").assertExists()
        // The digest is the closed card's content, so an open one must not repeat it.
        composeTestRule.onNodeWithTag("rule-digest-0").assertDoesNotExist()

        composeTestRule.onNodeWithTag("rule-expand-0").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("35=8|150=0|39=0").assertDoesNotExist()
        composeTestRule.onNodeWithTag("rule-digest-0").assertExists()
    }

    @Test
    fun `one button opens every rule, and the same button closes them`() {
        render(listOf(limitFlow, catchAll))

        composeTestRule.onNodeWithTag("rules-expand-all").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rule-digest-0").assertDoesNotExist()
        composeTestRule.onNodeWithTag("rule-digest-1").assertDoesNotExist()
        composeTestRule.onNodeWithText("35=8|39=8|103=1").assertExists()

        composeTestRule.onNodeWithTag("rules-expand-all").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rule-digest-0").assertExists()
        composeTestRule.onNodeWithTag("rule-digest-1").assertExists()
    }

    /**
     * Expansion is held by position, and a move renumbers everything below it — so a card left open
     * across a reorder would be showing its neighbour's fields under its own number. Dropping them all
     * is the honest answer, and the one thing that must not happen is the quiet wrong one.
     */
    @Test
    fun `moving a rule closes the lot rather than leaving a card open over another rule's fields`() {
        render(listOf(limitFlow, catchAll))

        composeTestRule.onNodeWithTag("rules-expand-all").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("35=8|150=0|39=0").assertExists()

        composeTestRule.onAllNodesWithContentDescription("Move rule later")[0].performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rule-digest-0").assertExists()
        composeTestRule.onNodeWithTag("rule-digest-1").assertExists()
        composeTestRule.onNodeWithText("35=8|150=0|39=0").assertDoesNotExist()
    }

    /** An empty rule says nothing until it is filled in, and the fields to fill are behind the fold. */
    @Test
    fun `a rule added by hand arrives open`() {
        render(listOf(limitFlow))

        composeTestRule.onAllNodesWithContentDescription("Add rule")[0].performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rule-digest-0").assertExists()
        composeTestRule.onNodeWithTag("rule-digest-1").assertDoesNotExist()
        composeTestRule.onNodeWithTag("rule-when-order").assertExists()
    }

    // ---------------------------------------------------------------- the mark

    @Test
    fun `the rule that answered is marked on its own card, and says when`() {
        render(
            rules = listOf(limitFlow, catchAll),
            fired = RuleFiredMark(ruleIndex = 1, at = LocalDateTime.of(2026, 9, 3, 9, 14, 22, 104_000_000)),
        )

        // The same clock the reply's own reason line prints, so the card and the message agree.
        composeTestRule.onNodeWithTag("rule-fired-1").assertTextEquals("fired 09:14:22.104")
        composeTestRule.onNodeWithTag("rule-fired-0").assertDoesNotExist()
    }

    @Test
    fun `no mark is shown when nothing has fired`() {
        render(listOf(limitFlow, catchAll))

        composeTestRule.onNodeWithTag("rule-fired-0").assertDoesNotExist()
        composeTestRule.onNodeWithTag("rule-fired-1").assertDoesNotExist()
    }

    /** The order constraint is part of why a rule fires, so the closed card has to carry it too. */
    @Test
    fun `what a rule asks the book is in its digest`() {
        render(
            listOf(
                AcceptorResponseRule(
                    whenMsgType = "F",
                    conditions = listOf(condition(41, Matcher.Presence)),
                    whenOrder = OrderConstraint.WORKING,
                    steps = listOf(ResponseStep(template = "35=8|150=6|39=6")),
                ),
            ),
        )

        composeTestRule
            .onNodeWithTag("rule-digest-0")
            .assertTextEquals("41 presence · the order is working · 1 step")
    }
}
