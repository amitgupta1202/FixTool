package com.knapsack.fixtool.model

import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.validationError
import com.knapsack.fixtool.service.AcceptorResponder
import com.knapsack.fixtool.service.MatcherCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The words a relaying venue is written in, and what a rule written in them may and may not say.**
 *
 * Nothing here relays anything. These are the decisions that have to be right before the engine reads a
 * single one of these fields: that an address parses or is refused by name, that a role is found the way an
 * author expects, that a rule which would misbehave in an older FixTool cannot be saved in this one, and that
 * all of it survives the trip to disk and back. See `docs/rfq-relay-impl-plan.md`, step 1.
 */
class RelayModelTest {
    private fun condition(tag: Int, matcher: Matcher) = FieldCondition(tag, MatcherCodec.matcherToJson(matcher))

    private val fromRequester = condition(49, Matcher.CounterpartyRole("requester"))
    private val fromResponder = condition(49, Matcher.CounterpartyRole("responder"))
    private val rfqOpenBy131 = condition(131, Matcher.RfqState("open"))
    private val rfqOpenBy117 = condition(117, Matcher.RfqState("open"))

    private fun rule(
        msgType: String,
        vararg conditions: FieldCondition,
        steps: List<ResponseStep>,
        whenResponders: String? = null,
    ) = AcceptorResponseRule(
        whenMsgType = msgType,
        conditions = conditions.toList(),
        steps = steps,
        whenResponders = whenResponders,
    )

    private fun step(to: String?, template: String = "35=S|131=\${req.uuid}|") = ResponseStep(template = template, to = to)

    // ------------------------------------------------------------------ addresses

    @Test
    fun `an address is one of the words, a named counterparty, or nothing at all, which is the sender`() {
        assertEquals(StepAddress.Sender, StepAddress.parse(null))
        assertEquals(StepAddress.Sender, StepAddress.parse("  "))
        assertEquals(StepAddress.Quoter, StepAddress.parse("quoter"))
        assertEquals(StepAddress.CompId("FIDLR1"), StepAddress.parse("compId:FIDLR1"))
        assertEquals("compId:FIDLR1", StepAddress.CompId("FIDLR1").word)
        assertNull(StepAddress.parse("dealer"), "a word outside the vocabulary is not quietly the sender")
        assertNull(StepAddress.parse("compId:"), "a named counterparty with no name goes nowhere")
    }

    @Test
    fun `only the sender stays in the conversation the trigger arrived on`() {
        assertFalse(StepAddress.Sender.relays)
        assertTrue(StepAddress.Responders.relays)
        assertTrue(StepAddress.Quoter.needsAQuote)
        assertFalse(StepAddress.Quoted.needsAQuote, "quoted is every live quote, not the one the trigger names")
        assertTrue(StepAddress.Asked.needsAnRfq)
        assertFalse(StepAddress.Responders.needsAnRfq, "responders is who the venue declares, RFQ or no RFQ")
    }

    // ------------------------------------------------------------------ roles

    @Test
    fun `an exact CompID beats a family, and a longer family beats a shorter one`() {
        val declared =
            listOf(
                Counterparty("FIDLR*", "responder"),
                Counterparty("FIDLRLG*", "requester"),
                Counterparty("FIDLR9", "requester"),
            )

        assertEquals(PartyRole.RESPONDER, roleOf(declared, "FIDLR1"))
        assertEquals(PartyRole.REQUESTER, roleOf(declared, "FIDLRLG3"), "the longer prefix is the more specific")
        assertEquals(PartyRole.REQUESTER, roleOf(declared, "FIDLR9"), "an exact entry carves one out of a family")
        assertNull(roleOf(declared, "FIBUY1"))
    }

    @Test
    fun `a role that is not a word is skipped rather than guessed at`() {
        assertNull(roleOf(listOf(Counterparty("FIBUY1", "buyer")), "FIBUY1"))
        assertEquals(SenderRole.UNLISTED, SenderRole.entries.single { it.matches(null) })
    }

    // ------------------------------------------------------------------ the two matchers

    @Test
    fun `a role reads the sender and an RFQ is found by its request or its quote, and nothing else`() {
        assertNull(fromRequester.reason())
        assertNull(rfqOpenBy131.reason())
        assertNull(rfqOpenBy117.reason())
        assertTrue(condition(55, Matcher.CounterpartyRole("requester")).reason()!!.contains("tag 49"))
        assertTrue(condition(49, Matcher.CounterpartyRole("dealer")).reason()!!.contains("requester, responder, unlisted"))
        assertTrue(condition(11, Matcher.RfqState("open")).reason()!!.contains("131"))
        assertTrue(condition(131, Matcher.RfqState("live")).reason()!!.contains("unknown, open, done, expired"))
    }

    @Test
    fun `both are refused in a scenario, which has no venue to ask`() {
        assertNotNull(Matcher.CounterpartyRole("requester").validationError())
        assertNotNull(Matcher.RfqState("open").validationError())
    }

    @Test
    fun `both round-trip through the matcher JSON`() {
        listOf(Matcher.CounterpartyRole("responder"), Matcher.RfqState("expired")).forEach { matcher ->
            assertEquals(matcher, MatcherCodec.parseMatcher(MatcherCodec.matcherToJson(matcher)))
        }
        assertEquals(JsonPrimitive("role"), MatcherCodec.matcherToJson(Matcher.CounterpartyRole("requester"))["type"])
    }

    /**
     * **Why they are matchers at all.** A FixTool that predates relaying cannot parse a matcher type it has
     * never heard of, and its compile drops the whole rule. A field it has never heard of it simply ignores,
     * and the rule would fire for everyone, answering the sender with a message meant for a dealer.
     */
    @Test
    fun `a condition nobody can parse takes its whole rule out of the live set`() {
        val fromTheFuture = FieldCondition(49, JsonObject(mapOf("type" to JsonPrimitive("somethingNewer"))))
        val rule = rule("R", fromTheFuture, steps = listOf(step(to = null)))

        assertTrue(AcceptorResponder.compile(listOf(rule)).isEmpty())
    }

    // ------------------------------------------------------------------ what a relay rule may say

    @Test
    fun `the rules a platform needs are all writable`() {
        val openRfq = rule("R", fromRequester, steps = listOf(step("responders")))
        val relayQuote =
            rule("S", fromResponder, rfqOpenBy131, steps = listOf(step("requester", "35=S|131=\${to.131}|117=\${uuid:10}|")))
        val lift =
            rule(
                "AJ",
                fromRequester,
                rfqOpenBy117,
                steps =
                    listOf(
                        step(null, "35=8|37=\${req.uuid}|11=\${req.11}|"),
                        step("quoter", "35=AJ|694=1|117=\${to.117}|"),
                        step("cover", "35=AJ|694=4|117=\${to.117}|"),
                        step("others", "35=AJ|694=5|117=\${to.117}|"),
                    ),
            )
        val refuseWhenNobodyIsThere =
            rule("R", fromRequester, steps = listOf(step(null, "35=AG|131=\${req.131}|658=99|")), whenResponders = "none")
        val confirmToRequester = rule("R", fromRequester, steps = listOf(step("requester", "35=AG|131=\${req.131}|658=99|")))

        listOf(openRfq, relayQuote, lift, refuseWhenNobodyIsThere, confirmToRequester).forEach { written ->
            val declared = listOf(Counterparty("FIBUY1", "requester"), Counterparty("FIDLR*", "responder"))
            assertNull(written.validationError(declared), "refused: ${written.validationError(declared)}")
        }
    }

    @Test
    fun `a relay with no role or RFQ condition is refused, for the sake of an older build`() {
        val bare = rule("R", steps = listOf(step("responders")))
        assertTrue(bare.validationError()!!.contains("older FixTool"), bare.validationError())

        val bareCheck = rule("R", steps = listOf(step(null, "35=AG|131=\${req.131}|658=99|")), whenResponders = "none")
        assertTrue(bareCheck.validationError()!!.contains("older FixTool"), bareCheck.validationError())
    }

    @Test
    fun `an address outside the vocabulary is refused by name, and its rule never compiles`() {
        val wrong = rule("R", fromRequester, steps = listOf(step("dealers")))

        assertTrue(wrong.validationError()!!.contains("'dealers'"), wrong.validationError())
        assertTrue(AcceptorResponder.compile(listOf(wrong)).isEmpty())
    }

    @Test
    fun `the parts of an RFQ can only be addressed when the trigger finds one`() {
        val quoterWithoutAQuote = rule("AJ", fromRequester, rfqOpenBy131, steps = listOf(step("quoter", "35=AJ|694=1|")))
        assertTrue(quoterWithoutAQuote.validationError()!!.contains("tag 117"), quoterWithoutAQuote.validationError())

        val askedWithoutAnRfq = rule("S", fromResponder, steps = listOf(step("asked", "35=AI|297=7|")))
        assertTrue(askedWithoutAnRfq.validationError()!!.contains("RFQ is"), askedWithoutAnRfq.validationError())
    }

    @Test
    fun `a counterparty being asked has no ids of its own to read yet`() {
        val fanOutReadingTo = rule("R", fromRequester, steps = listOf(step("responders", "35=R|131=\${to.131}|")))
        assertTrue(fanOutReadingTo.validationError()!!.contains("draw an id"), fanOutReadingTo.validationError())

        val quoteIdToEveryoneAsked =
            rule("AJ", fromRequester, rfqOpenBy117, steps = listOf(step("asked", "35=AJ|694=5|117=\${to.117}|")))
        assertTrue(
            quoteIdToEveryoneAsked.validationError()!!.contains("not every one of them holds a quote"),
            quoteIdToEveryoneAsked.validationError(),
        )
    }

    @Test
    fun `a name the RFQ book does not have is refused, and so is a state no RFQ can be in`() {
        val badName =
            rule("S", fromResponder, rfqOpenBy131, steps = listOf(step("requester", "35=S|448=\${rfq.dealer}|")))
        assertTrue(badName.validationError()!!.contains("\${rfq.dealer}"), badName.validationError())

        val badAnswer = rule("R", fromRequester, steps = listOf(step(null, "35=AG|658=99|")), whenResponders = "maybe")
        assertTrue(badAnswer.validationError()!!.contains("none, some"), badAnswer.validationError())
    }

    @Test
    fun `a venue that declares nobody cannot relay, and a caller with no venue in hand is not told it cannot`() {
        val relay = rule("R", fromRequester, steps = listOf(step("responders")))

        assertTrue(relay.validationError(emptyList())!!.contains("declares no counterparties"))
        assertNull(relay.validationError(null), "a preset judged on its own has no venue to be missing from")
    }

    @Test
    fun `asking whether responders are online is a condition, so it is not an unconditional rule`() {
        val rule = rule("R", steps = listOf(step(null, "35=AG|658=99|")), whenResponders = "none")
        assertFalse(rule.isUnconditional())
    }

    // ------------------------------------------------------------------ on disk

    @Test
    fun `every new field survives a profile round trip, and a profile without them loads unchanged`() {
        val json = Json { ignoreUnknownKeys = true }
        val config =
            FixConnectionConfig(
                connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                targetCompID = "*",
                counterparties = listOf(Counterparty("FIBUY1", "requester"), Counterparty("FIDLR*", "responder")),
                rfqExpirySeconds = 60,
                acceptorResponseRules =
                    listOf(
                        rule("R", fromRequester, steps = listOf(step("responders")), whenResponders = "some"),
                    ),
            )

        val decoded = json.decodeFromString(FixConnectionConfig.serializer(), json.encodeToString(FixConnectionConfig.serializer(), config))

        assertEquals(config, decoded)
        val old = json.decodeFromString(FixConnectionConfig.serializer(), """{"connectionType":"ACCEPTOR"}""")
        assertTrue(old.counterparties.isEmpty())
        assertNull(old.rfqExpirySeconds)
    }

    @Test
    fun `a relay reference rides on a reason and is absent from every reason that is not one`() {
        val plain = SendReason(source = SendReason.Source.RULE, at = java.time.LocalDateTime.now(), ruleIndex = 3)
        assertNull(plain.relay)
        val relayed =
            plain.copy(
                relay = RelayRef(7L, "FIX.4.4:FIRFQ_VENUE->FIBUY1", "FIBUY1", "R", "responders", "FIDLR1", "RFQ-1"),
            )
        assertEquals("FIDLR1", relayed.relay?.recipientCompId)
    }

    @Test
    fun `an initiator answers by rule once it has one, and never addresses anyone but its counterparty`() {
        val quote = AcceptorResponseRule(whenMsgType = "R", steps = listOf(ResponseStep("35=S|131=\${req.131}|")))
        val relay =
            AcceptorResponseRule(
                whenMsgType = "S",
                conditions = listOf(FieldCondition(49, MatcherCodec.matcherToJson(Matcher.CounterpartyRole("responder")))),
                steps = listOf(ResponseStep("35=S|", to = "requester")),
            )
        val initiator = FixConnectionConfig(connectionType = FixConnectionConfig.ConnectionType.INITIATOR)

        assertFalse(initiator.answersByRule(), "an initiator with no rules is the plain client it always was")
        assertTrue(initiator.copy(acceptorResponseRules = listOf(quote)).answersByRule())
        assertTrue(FixConnectionConfig(connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR).answersByRule())

        assertNull(quote.validationError(initiator))
        assertTrue(relay.validationError(initiator)!!.contains("initiator"))
        assertFalse(
            relay.validationError(listOf(Counterparty("FIDLR1", "responder"))).orEmpty().contains("initiator"),
            "the same rule on a venue is judged as a venue's",
        )
    }

    // ------------------------------------------------------------------ when the RFQ expires

    private val parties = listOf(Counterparty("FIBUY1", "requester"), Counterparty("FIDLR1", "responder"))

    private fun onExpiry(vararg steps: ResponseStep, whenQuotes: String? = null, conditions: List<FieldCondition> = emptyList()) =
        AcceptorResponseRule(whenMsgType = WHEN_RFQ_EXPIRES, conditions = conditions, steps = steps.toList(), whenQuotes = whenQuotes)

    /** The example's two rules: quotes ended by name to both sides, or the request refused when nobody quoted. */
    @Test
    fun `a rule on expiry is written with addresses and the quotes-standing question, and needs no RFQ condition`() {
        val quoted =
            onExpiry(
                ResponseStep("35=AI|117=\${to.117}|131=\${to.131}|55=\${req.55}|297=7|", to = "quotes"),
                ResponseStep("35=AI|117=\${to.117}|131=\${to.131}|55=\${req.55}|297=7|", to = "quoted"),
                whenQuotes = "some",
            )
        val unquoted = onExpiry(ResponseStep("35=AG|131=\${to.131}|658=99|146=1|55=\${req.55}|", to = "requester"), whenQuotes = "none")

        assertNull(quoted.validationError(parties))
        assertNull(unquoted.validationError(parties))
        assertEquals(StepAddress.Quotes, StepAddress.parse("quotes"))
        assertTrue(StepAddress.Quotes.needsAnRfq)
        assertFalse(StepAddress.Quotes.needsAQuote, "quotes is every quote that stands, not the one a message names")
    }

    @Test
    fun `nothing sent a rule on expiry, so it cannot answer the sender or reach a party through a named quote`() {
        assertTrue(onExpiry(ResponseStep("35=AG|658=99|")).validationError(parties)!!.contains("goes to the sender"))
        assertTrue(onExpiry(ResponseStep("35=AJ|694=4|117=\${to.117}|", to = "cover")).validationError(parties)!!.contains("quotes or quoted"))
        assertTrue(
            onExpiry(ResponseStep("35=AI|117=\${to.117}|297=7|", to = "requester")).validationError(parties)!!.contains("address it to quotes"),
        )
        assertTrue(
            onExpiry(ResponseStep("35=AG|658=99|", to = "requester"), conditions = listOf(rfqOpenBy131))
                .validationError(parties)!!
                .contains("always expired"),
        )
        assertTrue(
            onExpiry(ResponseStep("35=AG|658=99|", to = "requester"))
                .copy(whenQuote = QuoteConstraint.OPEN)
                .validationError(parties)!!
                .contains("order or a quote"),
        )
    }

    @Test
    fun `quotes standing is a word, and on a message it needs the RFQ the message names`() {
        val pass = rule("AJ", fromRequester, rfqOpenBy117, steps = listOf(step("quoted", "35=AJ|694=6|117=\${to.117}|")))
        assertNull(pass.copy(whenQuotes = "some").validationError(parties))
        assertTrue(pass.copy(whenQuotes = "many").validationError(parties)!!.contains("the answers are none, some"))
        val noRfq = rule("R", fromRequester, steps = listOf(step("responders")))
        assertTrue(noRfq.copy(whenQuotes = "none").validationError(parties)!!.contains("add an 'RFQ is' condition"))
        assertFalse(noRfq.copy(whenQuotes = "none").isUnconditional())
    }

    @Test
    fun `a step to quotes may read the quote it names, and one to every responder still may not`() {
        val toQuotes = rule("AJ", fromRequester, rfqOpenBy117, steps = listOf(step("quotes", "35=AI|117=\${to.117}|297=11|")))
        assertNull(toQuotes.validationError(parties))
        val toAsked = rule("AJ", fromRequester, rfqOpenBy117, steps = listOf(step("asked", "35=AI|117=\${to.117}|297=11|")))
        assertTrue(toAsked.validationError(parties)!!.contains("address quotes, quoted"))
    }

    @Test
    fun `a rule on expiry and the quotes question survive a profile round trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val rule = onExpiry(ResponseStep("35=AG|658=99|", to = "requester"), whenQuotes = "none")
        val decoded = json.decodeFromString(AcceptorResponseRule.serializer(), json.encodeToString(AcceptorResponseRule.serializer(), rule))
        assertEquals(rule, decoded)
        assertFalse(json.encodeToString(AcceptorResponseRule.serializer(), rule.copy(whenQuotes = null)).contains("whenQuotes"))
    }

    @Test
    fun `a reason for a step sent on expiry says the RFQ expired, and whose RFQ it was`() {
        val reason =
            SendReason(
                source = SendReason.Source.RULE,
                at = java.time.LocalDateTime.of(2026, 9, 13, 9, 15, 0),
                ruleIndex = 22,
                whenMsgType = WHEN_RFQ_EXPIRES,
                relay = RelayRef(7L, "FIX.4.4:FIRFQ_VENUE->FIBUY1", "FIBUY1", WHEN_RFQ_EXPIRES, "quoted", "FIDLR1", "RFQ-1"),
            )
        assertEquals("sent by rule 23 — the RFQ expired at 09:15:00.000 → quoted FIDLR1, on FIBUY1's RFQ", reason.line())
    }
}
