package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.QuoteConstraint
import com.knapsack.fixtool.model.QuoteEntry
import com.knapsack.fixtool.model.QuoteReading
import com.knapsack.fixtool.model.QuoteState
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.validationError
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A rule that reads the quote book**: the constraint, the matcher that compares against the venue's
 * own price, and the reference that puts it back on the wire.
 *
 * The claim under test is the one the RFQ proposal opens with: a venue that answers a hit the same way
 * whether the quote is live, stale, or one it never sent is not a venue anybody can test a client
 * against. Four rules on one MsgType, and the quote book chooses between them.
 *
 * Whether the *book* gets those four words right is `QuoteBookServiceTest`'s question.
 */
class AcceptorQuoteRulesTest {
    private fun condition(tag: Int, matcher: Matcher) = FieldCondition(tag, MatcherCodec.matcherToJson(matcher))

    private fun rule(
        msgType: String = "AJ",
        vararg conditions: FieldCondition,
        quote: QuoteConstraint? = null,
        template: String = "35=AI|693=\${req.693}|297=5|",
    ) = AcceptorResponseRule(
        whenMsgType = msgType,
        conditions = conditions.toList(),
        whenQuote = quote,
        steps = listOf(ResponseStep(template = template)),
    )

    /** A client hitting the offer on Q-1 at the price it believes it was quoted. */
    private fun hit(price: String = "1.09030", quoteId: String = "Q-1") =
        AcceptorResponder.buildMessage("35=AJ|693=R-1|117=$quoteId|694=1|55=EUR/USD|54=1|38=1000000|44=$price|11=C-1|")

    private fun entry(offer: String = "1.09030", state: QuoteState = QuoteState.OPEN) =
        QuoteEntry(
            quoteId = "Q-1",
            quoteReqId = "REQ-1",
            symbol = "EUR/USD",
            bid = "1.09010",
            offer = offer,
            bidSize = "1000000",
            offerSize = "1000000",
            validUntil = null,
            state = state,
        )

    private val unknown = QuoteReading.unknown("Q-1")
    private val open = QuoteReading("Q-1", entry(), QuoteConstraint.OPEN.word)
    private val expired = QuoteReading("Q-1", entry(), QuoteConstraint.EXPIRED.word)
    private val done = QuoteReading("Q-1", entry(state = QuoteState.DONE), QuoteConstraint.DONE.word)

    // ------------------------------------------------------------------ one list, four answers

    /**
     * The whole point of the slice: the same four rules, and the venue's own memory picks which one
     * answers. Nothing about the incoming message distinguishes these cases.
     */
    @Test
    fun `one rule list answers a hit four different ways, by what the venue quoted`() {
        val rules =
            listOf(
                rule(quote = QuoteConstraint.UNKNOWN, template = "35=AI|297=9|"),
                rule(quote = QuoteConstraint.EXPIRED, template = "35=AI|297=7|"),
                rule(quote = QuoteConstraint.DONE, template = "35=AI|297=5|58=Quote already answered|"),
                rule(quote = QuoteConstraint.OPEN, template = "35=8|150=F|39=2|"),
            )

        fun answering(quote: QuoteReading) =
            AcceptorResponder.explain(rules, hit(), quote = quote).firstOrNull { it.selected }?.index

        assertEquals(0, answering(unknown), "a hit on a quote this venue never sent")
        assertEquals(1, answering(expired), "a hit that arrived too late")
        assertEquals(2, answering(done), "a second hit on a quote already answered")
        assertEquals(3, answering(open), "the one case where a trade is owed")
    }

    @Test
    fun `a rule that asks the quote book does not fire when there is no book to ask`() {
        val rules = listOf(rule(quote = QuoteConstraint.OPEN))

        assertNull(AcceptorResponder.firstMatch(AcceptorResponder.compile(rules), hit(), quote = null))
        val outcome = AcceptorResponder.explain(rules, hit(), quote = null).single()
        assertNull(assertNotNull(outcome.quote).actual, "no book is not the same answer as an empty one")
        assertFalse(outcome.matched)
    }

    @Test
    fun `a rule that does not ask the quote book reports nothing about it`() {
        val outcome = AcceptorResponder.explain(listOf(rule()), hit(), quote = open).single()

        assertNull(outcome.quote, "an absent report is how 'this rule does not read the quote book' is said")
        assertTrue(outcome.matched)
    }

    @Test
    fun `the quote constraint reports what it asked and what it read, like any other condition`() {
        val outcome =
            AcceptorResponder.explain(listOf(rule(quote = QuoteConstraint.OPEN)), hit(), quote = expired).single()

        val quote = assertNotNull(outcome.quote)
        assertEquals(QuoteConstraint.OPEN, quote.constraint)
        assertEquals("Q-1", quote.quoteId, "which quote it asked about")
        assertEquals("expired", quote.actual, "what the book said, not what the rule wanted")
        assertFalse(quote.satisfied)
        assertFalse(outcome.matched)
    }

    // ------------------------------------------------------------------ comparing against the venue's own price

    /**
     * The claim no literal and no tag can express. The client sends the price it believes, which is
     * exactly what is under test, so the comparison has to reach the venue's own book.
     */
    @Test
    fun `a hit at the quoted price matches and a hit at any other price does not`() {
        val rules =
            listOf(
                rule(
                    conditions = arrayOf(condition(44, Matcher.QuoteField("offer"))),
                    quote = QuoteConstraint.OPEN,
                    template = "35=8|31=\${quote.offer}|",
                ),
            )

        assertTrue(AcceptorResponder.explain(rules, hit("1.09030"), quote = open).single().matched)
        assertFalse(
            AcceptorResponder.explain(rules, hit("1.09029"), quote = open).single().matched,
            "one pip off the quote is not the quoted price",
        )
    }

    @Test
    fun `a comparison against a quote the venue never sent is false rather than an error`() {
        val rules = listOf(rule(conditions = arrayOf(condition(44, Matcher.QuoteField("offer")))))

        assertFalse(
            AcceptorResponder.explain(rules, hit(), quote = unknown).single().matched,
            "an unknown quote has no offer, so 'the client hit our offer' is not true of it",
        )
        assertFalse(
            AcceptorResponder.explain(rules, hit(), quote = null).single().matched,
            "and no book at all is the same answer",
        )
    }

    @Test
    fun `the dry run reports the comparison it actually made`() {
        val rules = listOf(rule(conditions = arrayOf(condition(44, Matcher.QuoteField("offer")))))

        fun conditionAgainst(quote: QuoteReading?) =
            AcceptorResponder
                .explain(rules, hit(), quote = quote)
                .single()
                .conditions
                .single()

        val resolved = conditionAgainst(open)
        assertEquals(Matcher.Exact("1.09030"), resolved.matcher, "a reader has to see the venue's own price")

        val left = conditionAgainst(unknown)
        assertEquals(
            Matcher.QuoteField("offer"),
            left.matcher,
            "with nothing to resolve against, the truest thing to report is the question",
        )
    }

    // ------------------------------------------------------------------ the reply reads the quote

    @Test
    fun `a reply renders the venue's own price, and arithmetic on it`() {
        val rule =
            rule(
                quote = QuoteConstraint.OPEN,
                template = "35=8|150=F|31=\${quote.offer}|6=\${quote.offer}|32=\${req.38}|55=\${quote.symbol}|",
            )

        val sent =
            AcceptorResponder
                .plan(rule, hit(), quote = { open })
                .single()
                .render()
                .replace('\u0001', '|')

        assertTrue(sent.contains("31=1.09030"), sent)
        assertTrue(sent.contains("6=1.09030"), sent)
        assertTrue(sent.contains("55=EUR/USD"), sent)
        assertTrue(sent.contains("32=1000000"), "the request's own quantity still resolves: $sent")
    }

    @Test
    fun `a reply that reads a quote there is none of refuses, and says which quote`() {
        val refusal = AcceptorResponder.quoteRefusal("35=8|31=\${quote.offer}|", unknown)

        assertNotNull(refusal)
        assertTrue(refusal.contains("Q-1"), "the refusal names the quote it could not find: $refusal")
        assertTrue(refusal.contains("has not quoted"), refusal)
    }

    @Test
    fun `a reply naming a field the quote book has not got is named as a typo`() {
        val refusal = AcceptorResponder.quoteRefusal("35=8|31=\${quote.midPrice}|", open)

        assertNotNull(refusal)
        assertTrue(refusal.contains("midPrice"), refusal)
        assertTrue(refusal.contains("offer"), "the refusal lists the names there are: $refusal")
    }

    @Test
    fun `a reply that reads a field the venue never quoted refuses rather than sending a blank`() {
        val bidless = QuoteReading("Q-1", entry().copy(bid = null), QuoteConstraint.OPEN.word)

        val refusal = AcceptorResponder.quoteRefusal("35=8|31=\${quote.bid}|", bidless)

        assertNotNull(refusal)
        assertTrue(refusal.contains("did not quote a bid"), refusal)
    }

    @Test
    fun `a reply reading no quote at all is left alone`() {
        assertNull(AcceptorResponder.quoteRefusal("35=8|31=\${req.44}|", null))
        assertEquals(
            "35=8|31=\${req.44}|",
            AcceptorResponder.resolveQuoteRefs("35=8|31=\${req.44}|", null),
            "a template with no quote reference must survive the pass untouched",
        )
    }

    // ------------------------------------------------------------------ what the rule refuses to be

    @Test
    fun `a reply that reads the quote needs a trigger that requires one`() {
        val loose = rule(template = "35=8|31=\${quote.offer}|")

        val problem = assertNotNull(loose.validationError())
        assertTrue(problem.contains("when the quote is"), problem)

        assertNull(
            loose.copy(whenQuote = QuoteConstraint.OPEN).validationError(),
            "with the constraint in place the rule is usable",
        )
        assertNotNull(
            loose.copy(whenQuote = QuoteConstraint.UNKNOWN).validationError(),
            "a quote this venue never sent is exactly the one a reply cannot read",
        )
    }

    @Test
    fun `a rule comparing against a quote it also requires to be unknown can never fire, and says so`() {
        val dead =
            rule(
                conditions = arrayOf(condition(44, Matcher.QuoteField("offer"))),
                quote = QuoteConstraint.UNKNOWN,
            )

        val problem = assertNotNull(dead.validationError())
        assertTrue(problem.contains("can never match"), problem)
    }

    @Test
    fun `a condition naming a field the quote book has not got is refused by name`() {
        val typo = rule(conditions = arrayOf(condition(44, Matcher.QuoteField("midPrice"))))

        val problem = assertNotNull(typo.validationError())
        assertTrue(problem.contains("midPrice"), problem)
        assertTrue(problem.contains("quoteId"), "the refusal lists the names there are: $problem")
    }

    @Test
    fun `a quote constraint makes a rule conditioned even with no tag conditions`() {
        assertFalse(
            rule(quote = QuoteConstraint.OPEN).isUnconditional(),
            "a rule that asks the quote book does not answer every message of its type",
        )
        assertTrue(rule().isUnconditional())
    }

    // ------------------------------------------------------------------ the two sides of the tool

    @Test
    fun `a quote field is refused in a scenario, where there is no venue book`() {
        val problem = assertNotNull(Matcher.QuoteField("offer").validationError())

        assertTrue(problem.contains("no venue book"), problem)
    }

    @Test
    fun `a quote field survives the codec, and is described in words`() {
        val matcher = Matcher.QuoteField("offer")

        val json = MatcherCodec.matcherToJson(matcher)
        assertEquals("quoteField", json["type"]?.toString()?.trim('"'))
        assertEquals(matcher, MatcherCodec.parseMatcher(json))
        assertEquals("equal to the quote's offer", ExpectationEvaluator.describe(matcher))
    }

    @Test
    fun `every name the reply vocabulary offers is a name the quote answers to`() {
        // Fully quoted, validity included: a name the vocabulary offers must be answerable, and the
        // separate question of what a *partly* quoted quote does is the refusal test above.
        val quote = entry().copy(validUntil = 1_754_000_000_000)

        QuoteEntry.FIELDS.forEach { name ->
            assertNotNull(quote.field(name), "'$name' is offered as a name and the quote cannot answer it")
        }
    }

    // ------------------------------------------------------------------ the dry run cannot drift

    /**
     * The same cross-check the order book's constraint gets, and for the same reason: `explain` and
     * `firstMatch` are two implementations of one judgement, and a dry run that fires where the wire
     * does nothing moves the bug from the rule into the tool.
     */
    @Test
    fun `selection agrees with the live matcher on every quote case here`() {
        val cases: List<List<AcceptorResponseRule>> =
            listOf(
                listOf(rule(quote = QuoteConstraint.OPEN)),
                listOf(rule(quote = QuoteConstraint.UNKNOWN)),
                listOf(rule(quote = QuoteConstraint.EXPIRED), rule(quote = QuoteConstraint.DONE)),
                listOf(rule(quote = QuoteConstraint.DONE), rule()),
                listOf(rule(conditions = arrayOf(condition(44, Matcher.QuoteField("offer"))))),
                listOf(
                    rule(
                        conditions = arrayOf(condition(44, Matcher.QuoteField("offer"))),
                        quote = QuoteConstraint.OPEN,
                    ),
                    rule(quote = QuoteConstraint.OPEN, template = "35=AI|297=5|58=Price is not the quoted price|"),
                ),
                listOf(rule(conditions = arrayOf(condition(55, Matcher.QuoteField("symbol"))))),
                listOf(rule(conditions = arrayOf(condition(44, Matcher.QuoteField("midPrice"))))),
            )
        val quotes = listOf(null, unknown, open, expired, done)
        val messages = listOf(hit("1.09030"), hit("1.09029"), hit(quoteId = "Q-9"))

        cases.forEachIndexed { index, rules ->
            quotes.forEach { quote ->
                messages.forEach { message ->
                    val explained =
                        AcceptorResponder.explain(rules, message, quote = quote).firstOrNull { it.selected }?.rule
                    val live =
                        AcceptorResponder.firstMatch(AcceptorResponder.compile(rules), message, quote = quote)
                    assertEquals(
                        live,
                        explained,
                        "case $index against ${quote?.word ?: "no book"}: both paths must pick the same rule",
                    )
                }
            }
        }
    }
}
