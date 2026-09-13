package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.PartyRole
import com.knapsack.fixtool.model.QuoteConstraint
import com.knapsack.fixtool.model.QuoteEntry
import com.knapsack.fixtool.model.QuoteReading
import com.knapsack.fixtool.model.QuoteState
import com.knapsack.fixtool.model.RfqReading
import com.knapsack.fixtool.model.StepAddress
import com.knapsack.fixtool.model.WHEN_RFQ_EXPIRES
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.ui.thirtySeconds
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The fixed-income platform and its dealer, held to a Treasury desk's vocabulary and to FIX 4.4.**
 *
 * The platform's rules are judged the way a running platform judges them: against its own counterparties, with a
 * venue that answers who each address reaches and what each id is called on the far side. Every message either
 * side can send is built and validated against the bundled dictionary — repeating groups included, because a
 * `Parties` block in the wrong place renders perfectly and is still malformed. The rest is the domain: a request
 * reaches the dealers under the platform's id, a quote reaches the buy side under its own, a lift confirms both
 * sides with one OrderID and one trade report id, and the dealer quotes the grid.
 */
class FiRfqPlatformPresetTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private val platform = AcceptorPresets.insert(emptyList(), FiRfqPlatformPreset.preset).rules
    private val dealer = AcceptorPresets.insert(emptyList(), FiRfqPlatformPreset.dealerPreset).rules

    private val tenYear = FiRfqPlatformPreset.ISSUES.last()

    private fun field(raw: String, tag: Int) = raw.split("|").firstOrNull { it.startsWith("$tag=") }?.substringAfter("=")

    private fun request(raw: String) =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw,
            quickfixMessage = AcceptorResponder.buildMessage(raw, dictionary),
        )

    // ---------------------------------------------------------------- a venue made of a table

    /** Every address reaches one counterparty, and every id has a name on the far side. */
    private object TableVenue : RelayVenue {
        override fun resolve(address: StepAddress, trigger: RelayTrigger): Resolution =
            Resolution(
                listOf(
                    when (address) {
                        StepAddress.Sender -> Recipient(null, trigger.sessionKey, trigger.compId, address)
                        StepAddress.Requester -> Recipient(null, "buy", BUYER, address)
                        StepAddress.Quotes -> Recipient(null, "buy", BUYER, address, quoteId = "V-Q-1")
                        StepAddress.Cover -> Recipient(null, "d2", "FIDLR2", address, quoteId = "D2-551")
                        else -> Recipient(null, "d1", DEALER, address, quoteId = "D1-Q-88")
                    },
                ),
            )

        override fun toValue(recipient: Recipient, trigger: RelayTrigger, tag: Int): String? =
            when (tag) {
                117 -> recipient.quoteId ?: "V-Q-1"
                131 -> if (recipient.compId == BUYER) "BUY-RFQ-7" else "V-RFQ-1"
                11 -> "D1-TRD-1"
                else -> null
            }

        override fun rfqField(trigger: RelayTrigger, name: String): String? =
            mapOf("requester" to BUYER, "quoter" to DEALER, "asked" to "2", "quoted" to "2", "state" to "open")[name]
    }

    /** The platform's own record of the quote it showed the buy side, as its quote book would hand it over. */
    private fun shown(issue: FiRfqPlatformPreset.Issue = tenYear) =
        QuoteReading(
            quoteId = "V-Q-1",
            entry =
                QuoteEntry(
                    quoteId = "V-Q-1",
                    quoteReqId = "BUY-RFQ-7",
                    symbol = issue.symbol,
                    bid = issue.bidPx,
                    offer = issue.offerPx,
                    bidSize = "10000000",
                    offerSize = "10000000",
                    validUntil = null,
                    state = QuoteState.OPEN,
                ),
            word = QuoteConstraint.OPEN.word,
        )

    /** What a rule's own trigger says the venue holds: the sender's role, the RFQ's state, who is online. */
    private fun readingFor(rule: AcceptorResponseRule): VenueReading {
        val role = rule.trigger().firstNotNullOfOrNull { it.parsed() as? Matcher.CounterpartyRole }?.role
        val rfq = rule.trigger().firstOrNull { it.parsed() is Matcher.RfqState }
        val word = (rfq?.parsed() as? Matcher.RfqState)?.state ?: "unknown"
        return VenueReading(
            senderRole = PartyRole.byWord(role),
            rfqBy131 = RfqReading("RFQ-1", null, word).takeIf { rfq?.tag == 131 },
            rfqBy117 = RfqReading("RFQ-1", null, word).takeIf { rfq?.tag == 117 },
            respondersOnline = rule.whenResponders != "none",
        )
    }

    /** The message [rule] is written against, derived from its own conditions. */
    private fun sampleFor(rule: AcceptorResponseRule): String {
        fun exact(tag: Int) = (rule.trigger().firstOrNull { it.tag == tag }?.parsed() as? Matcher.Exact)?.value

        fun has(tag: Int) = rule.trigger().any { it.tag == tag }
        val role = rule.trigger().firstNotNullOfOrNull { it.parsed() as? Matcher.CounterpartyRole }?.role
        val sender = if (role == "responder") DEALER else BUYER
        val cusip = exact(48) ?: (rule.trigger().firstOrNull { it.tag == 48 }?.parsed() as? Matcher.OneOf)?.values?.first()
        val issue = FiRfqPlatformPreset.ISSUES.firstOrNull { it.cusip == cusip } ?: tenYear
        val side = exact(54)
        return when (rule.whenMsgType) {
            "R" ->
                listOfNotNull(
                    "35=R",
                    "49=$sender",
                    "131=BUY-RFQ-7",
                    "146=1",
                    "55=${issue.symbol}",
                    "48=${cusip ?: "912810XX0"}",
                    "22=1",
                    side?.let { "54=$it" } ?: "54=1".takeIf { has(54) },
                    "38=10000000".takeIf { has(38) || rule.trigger().isEmpty() },
                ).joinToString("|")
            "S" ->
                listOfNotNull(
                    "35=S",
                    "49=$sender",
                    "131=V-RFQ-1",
                    "117=D1-Q-88",
                    "55=${issue.symbol}",
                    "48=${issue.cusip}",
                    "22=1",
                    side?.let { "54=$it" },
                    "132=${issue.bidPx}".takeIf { has(132) },
                    "133=${issue.offerPx}".takeIf { has(133) },
                    "134=10000000".takeIf { has(134) },
                    "135=10000000".takeIf { has(135) },
                    "632=${issue.bidYield}".takeIf { has(632) },
                    "634=${issue.offerYield}".takeIf { has(634) },
                    "62=20260913-12:00:30.000".takeIf { has(62) },
                ).joinToString("|")
            "AJ" -> {
                val level = if (side == "2") issue.bidPx else issue.offerPx
                "35=AJ|49=$sender|693=BUY-RESP-1|694=${exact(694) ?: "1"}|117=V-Q-1|11=BUY-TRD-7|" +
                    "55=${issue.symbol}|54=${side ?: "1"}|38=10000000|44=$level"
            }
            // A rule on expiry reads the RFQ's opening request, which is the buy side's.
            WHEN_RFQ_EXPIRES ->
                "35=R|49=$BUYER|131=BUY-RFQ-7|146=1|55=${issue.symbol}|48=${issue.cusip}|22=1|54=1|38=10000000"
            else -> error("no sample for 35=${rule.whenMsgType}")
        }
    }

    /** Everything [rule] would send for its sample, rendered the way the dispatcher renders it. */
    private fun sends(rule: AcceptorResponseRule, raw: String = sampleFor(rule)): List<Pair<String?, String>> {
        val message = AcceptorResponder.buildMessage(raw, dictionary)
        val trigger =
            RelayTrigger(null, "sender", field(raw, 49) ?: BUYER, field(raw, 35), RfqBookService.fieldsOf(message), "RFQ-1")
        return AcceptorResponder
            .planRelay(rule, message, request(raw), dictionary, TableVenue, trigger, quote = { shown() }) { null }
            .sends
            .map { it.to?.address?.word to it.render() }
    }

    /** Which platform rule answers [raw], judged with the venue reading [reading]. */
    private fun winner(raw: String, reading: VenueReading, rules: List<AcceptorResponseRule> = platform) =
        AcceptorResponder.firstMatch(AcceptorResponder.compile(rules), AcceptorResponder.buildMessage(raw, dictionary), null, shown(), reading)

    // ---------------------------------------------------------------- the shape of the bundles

    @Test
    fun `the platform's cards read in the order its refusals depend on`() {
        val read = platform.map { rule -> rule.whenMsgType + describe(rule) }

        assertEquals(
            listOf(
                "R role:responder 131 55",
                "R role:requester 48∈run 131 55 38 responders:none",
                "R role:requester 48∈run 131 55 38 54",
                "R role:requester 48∈run 131 55 38",
                "R role:requester 48∈run 131 55",
                "R role:requester 131 55",
                "R",
            ),
            read.filter { it.startsWith("R") },
        )
        assertEquals("S role:responder rfq:open@131 117 55 48 62 132 133 134 135 632 634", read.first { it.startsWith("S") })
        assertEquals("S", read.last { it.startsWith("S") })
        assertTrue(read.indexOfFirst { it.startsWith("AJ") && it.contains("694=1 54=1") } < read.indexOf(read.first { it.contains("694=1 55") }))
        assertEquals("AJ", read.last { it.startsWith("AJ") })
        assertEquals(setOf("@rfq-expired quotes:some", "@rfq-expired quotes:none"), read.filter { it.startsWith("@") }.toSet())
        assertEquals(25, platform.size, "the summary counts the rules, so the count is the summary")
    }

    private fun describe(rule: AcceptorResponseRule): String =
        rule.trigger().joinToString("") { c ->
            when (val m = c.parsed()) {
                is Matcher.Exact -> " ${c.tag}=${m.value}"
                is Matcher.OneOf -> " ${c.tag}∈run"
                is Matcher.QuoteField -> " ${c.tag}=quote.${m.name}"
                is Matcher.CounterpartyRole -> " role:${m.role}"
                is Matcher.RfqState -> " rfq:${m.state}@${c.tag}"
                Matcher.Presence -> " ${c.tag}"
                else -> " ${c.tag}?"
            }
        } + (rule.whenResponders?.let { " responders:$it" } ?: "") + (rule.whenQuotes?.let { " quotes:$it" } ?: "")

    @Test
    fun `every rule of both bundles is reachable`() {
        listOf(platform, dealer).forEach { rules ->
            rules.indices.forEach { index ->
                assertNull(AcceptorResponder.shadowingRule(rules, index), "rule ${index + 1} (35=${rules[index].whenMsgType}) never fires")
            }
        }
    }

    @Test
    fun `the platform's rules are usable on the platform, and the dealer's on an initiator`() {
        platform.forEach {
            assertNull(
                it.validationError(FiRfqPlatformPreset.COUNTERPARTIES),
                "35=${it.whenMsgType}: ${it.validationError(FiRfqPlatformPreset.COUNTERPARTIES)}",
            )
        }
        dealer.forEach { assertNull(it.validationError(null, initiator = true), "dealer 35=${it.whenMsgType}: ${it.validationError(null, true)}") }
    }

    @Test
    fun `every rule of the platform fires against the message it was written for`() {
        platform.filterNot { it.whenMsgType == WHEN_RFQ_EXPIRES }.forEach { rule ->
            val outcome =
                AcceptorResponder
                    .explain(listOf(rule), AcceptorResponder.buildMessage(sampleFor(rule), dictionary), null, shown(), readingFor(rule))
                    .single()
            assertTrue(outcome.selected, "35=${rule.whenMsgType}${describe(rule)} does not answer ${sampleFor(rule)}")
        }
    }

    /**
     * **Nothing either side can send is malformed against FIX 4.4.** Every reply of every rule, built the way the
     * acceptor builds it and validated the way a counterparty's engine validates it.
     */
    @Test
    fun `every message the platform and the dealer can send validates against the bundled dictionary`() {
        val dd = assertNotNull(dictionary.getDataDictionary())
        (platform + dealer).forEach { rule ->
            val raw = sampleFor(rule)
            val message = AcceptorResponder.buildMessage(raw, dictionary)
            val trigger = RelayTrigger(null, "sender", field(raw, 49) ?: BUYER, field(raw, 35), RfqBookService.fieldsOf(message), "RFQ-1")
            AcceptorResponder
                .planRelay(rule, message, request(raw), dictionary, TableVenue, trigger, quote = { shown() }) { null }
                .sends
                .forEach { planned ->
                    val rendered = planned.render()
                    assertTrue(!rendered.contains("\${"), "35=${rule.whenMsgType} sends an expression it never evaluated: $rendered")
                    assertTrue(rendered.split("|").none { it.endsWith("=") }, "35=${rule.whenMsgType} sends an empty field: $rendered")
                    runCatching { dd.validate(planned.build(), true) }
                        .onFailure { throw AssertionError("35=${rule.whenMsgType} plans a malformed reply: ${it.message} in $rendered", it) }
                }
        }
    }

    // ---------------------------------------------------------------- the platform

    private fun onExpiry(quotesStanding: Boolean) =
        VenueReading(
            senderRole = PartyRole.REQUESTER,
            rfqBy131 = RfqReading("RFQ-1", null, "expired"),
            rfqBy117 = RfqReading("RFQ-1", null, "expired"),
            respondersOnline = true,
            quotesStanding = quotesStanding,
        )

    /**
     * **An RFQ nobody traded is ended to everyone holding part of it.** A quoted one: each quote by name, to the buy
     * side under the id it was shown and to its dealer under its own. An unquoted one: the request refused to the buy
     * side, under the buy side's own QuoteReqID.
     */
    @Test
    fun `an RFQ that expires is ended quote by quote to both sides, or refused to the buy side when nobody quoted`() {
        val compiled = AcceptorResponder.compile(platform)
        val opening = RfqBookService.fieldsOf(AcceptorResponder.buildMessage(sampleFor(platform.last()), dictionary))

        val quoted = assertNotNull(AcceptorResponder.firstMatchOnExpiry(compiled, opening, onExpiry(quotesStanding = true)))
        val toEach = sends(quoted)
        assertEquals(listOf("quotes", "quoted"), toEach.map { it.first })
        toEach.forEach { (_, raw) ->
            assertEquals("AI", field(raw, 35))
            assertEquals("7", field(raw, 297), "297=7 is Expired")
            assertEquals(tenYear.symbol, field(raw, 55))
        }
        assertEquals("V-Q-1", field(toEach[0].second, 117), "the buy side is told of the quote as it was shown it")
        assertEquals("BUY-RFQ-7", field(toEach[0].second, 131), "under its own QuoteReqID")
        assertEquals("D1-Q-88", field(toEach[1].second, 117), "the dealer is told of its own quote")
        assertEquals("V-RFQ-1", field(toEach[1].second, 131), "under the QuoteReqID the platform sent it")

        val unquoted = assertNotNull(AcceptorResponder.firstMatchOnExpiry(compiled, opening, onExpiry(quotesStanding = false)))
        val refusal = sends(unquoted).single()
        assertEquals("requester", refusal.first)
        assertEquals("AG", field(refusal.second, 35))
        assertEquals("99", field(refusal.second, 658))
        assertEquals("BUY-RFQ-7", field(refusal.second, 131), "the buy side's own id")
    }

    @Test
    fun `a buy side's request reaches the dealers under the platform's id, with who is asking`() {
        val raw = "35=R|49=$BUYER|131=BUY-RFQ-7|146=1|55=${tenYear.symbol}|48=${tenYear.cusip}|22=1|54=1|38=10000000"
        val rule = assertNotNull(winner(raw, VenueReading(PartyRole.REQUESTER, null, null, respondersOnline = true)))

        val (to, relayed) = sends(rule, raw).single()
        assertEquals("responders", to)
        assertTrue(field(relayed, 131) != "BUY-RFQ-7", "a dealer never sees the buy side's own id: $relayed")
        assertEquals("1", field(relayed, 54))
        assertEquals(tenYear.cusip, field(relayed, 48))
        assertEquals(BUYER, field(relayed, 448))
        assertEquals("13", field(relayed, 452), "role 13 is Order Origination Firm")
    }

    @Test
    fun `a request is refused by name when it cannot be carried`() {
        fun reject(raw: String, role: PartyRole?, online: Boolean = true) =
            sends(assertNotNull(winner(raw, VenueReading(role, null, null, online), platform)), raw).single().second

        val tenYearRequest = "35=R|131=Q-1|146=1|55=${tenYear.symbol}|48=${tenYear.cusip}|22=1|38=10000000"
        assertEquals("6", field(reject("35=R|49=$DEALER|${tenYearRequest.removePrefix("35=R|")}", PartyRole.RESPONDER), 658))
        assertEquals("99", field(reject("35=R|49=$BUYER|${tenYearRequest.removePrefix("35=R|")}", PartyRole.REQUESTER, online = false), 658))
        assertEquals("1", field(reject("35=R|49=$BUYER|131=Q-2|146=1|55=T 9.875 11/15/45|48=912810XX0|22=1|38=10000000", PartyRole.REQUESTER), 658))
        assertEquals("99", field(reject("35=R|49=$BUYER|131=Q-3|146=1|55=${tenYear.symbol}|48=${tenYear.cusip}|22=1", PartyRole.REQUESTER), 658))
        assertTrue(reject("35=R|49=ROGUE|131=Q-4|146=1|55=${tenYear.symbol}|48=${tenYear.cusip}|22=1|38=1", null).startsWith("35=j"))
    }

    @Test
    fun `a dealer's quote reaches the buy side under its own ids, with who quoted, in the shape it was quoted`() {
        val raw =
            "35=S|49=$DEALER|131=V-RFQ-1|117=D1-Q-88|55=${tenYear.symbol}|48=${tenYear.cusip}|22=1|54=1|" +
                "133=${tenYear.offerPx}|135=10000000|634=${tenYear.offerYield}|62=20260913-12:00:30.000"
        val reading = VenueReading(PartyRole.RESPONDER, RfqReading("RFQ-1", null, "open"), null, true)
        val (to, shown) = sends(assertNotNull(winner(raw, reading)), raw).single()

        assertEquals("requester", to)
        assertEquals("BUY-RFQ-7", field(shown, 131), "the buy side's own QuoteReqID")
        assertTrue(field(shown, 117) != "D1-Q-88", "the platform's own QuoteID: $shown")
        assertEquals(tenYear.offerPx, field(shown, 133))
        assertNull(field(shown, 132), "an offer is shown as an offer: $shown")
        assertEquals("1", field(shown, 537))
        assertEquals(DEALER, field(shown, 448))
        assertEquals("35", field(shown, 452), "role 35 is Liquidity provider")
    }

    @Test
    fun `a quote for an RFQ that is no longer open is refused to its dealer and shown to nobody`() {
        val raw =
            "35=S|49=$DEALER|131=V-RFQ-1|117=D1-Q-88|55=${tenYear.symbol}|48=${tenYear.cusip}|22=1|54=1|" +
                "133=${tenYear.offerPx}|135=10000000|634=${tenYear.offerYield}|62=20260913-12:00:30.000"
        val reading = VenueReading(PartyRole.RESPONDER, RfqReading("RFQ-1", null, "done"), null, true)
        val (to, status) = sends(assertNotNull(winner(raw, reading)), raw).single()

        assertEquals("sender", to)
        assertEquals("AI", field(status, 35))
        assertEquals("5", field(status, 297))
    }

    /** One OrderID and one TradeReportID, the ids the two sides reconcile on, and the other side named on each. */
    @Test
    fun `a lift confirms both sides with an ExecutionReport and a TradeCaptureReport each, and tells the dealers who lost`() {
        val raw = sampleFor(platform.first { it.trigger().any { c -> c.parsed() == Matcher.Exact("1") && c.tag == 54 } && it.whenMsgType == "AJ" })
        val lift = assertNotNull(winner(raw, VenueReading(PartyRole.REQUESTER, null, RfqReading("RFQ-1", null, "open"), true)))
        val sent = sends(lift, raw)

        assertEquals(listOf("sender", "sender", "quoter", "quoter", "quoter", "cover", "others"), sent.map { it.first })
        val (buyerFill, buyerReport) = sent[0].second to sent[1].second
        val (dealerFill, dealerReport) = sent[3].second to sent[4].second

        assertEquals(listOf("8", "AE", "AJ", "8", "AE", "AJ", "AJ"), sent.map { field(it.second, 35) })
        assertEquals(field(buyerFill, 37), field(dealerFill, 37), "one OrderID for both sides")
        assertEquals(field(buyerReport, 571), field(dealerReport, 571), "one TradeReportID for both sides")
        assertEquals("1", field(buyerFill, 54))
        assertEquals("2", field(dealerFill, 54), "the dealer's fill is on its own side")
        assertEquals(tenYear.offerPx, field(buyerFill, 31), "booked at the level the platform showed")
        assertEquals(DEALER, field(buyerFill, 448))
        assertEquals(BUYER, field(dealerFill, 448))
        assertEquals("17", field(dealerReport, 452), "role 17 is Contra firm")
        assertEquals("D1-Q-88", field(sent[2].second, 117), "the dealer is told of its own quote")
        assertEquals("4", field(sent[5].second, 694))
        assertEquals("D2-551", field(sent[5].second, 117))
        assertEquals("5", field(sent[6].second, 694))
        assertTrue(lift.booksATrade(), "a lift is a trade, recorded as it is decided")
    }

    @Test
    fun `a lift at a level the platform did not show is refused, and so is a counter`() {
        val reading = VenueReading(PartyRole.REQUESTER, null, RfqReading("RFQ-1", null, "open"), true)
        val wrongLevel = "35=AJ|49=$BUYER|693=R-1|694=1|117=V-Q-1|11=T-1|55=${tenYear.symbol}|54=1|38=10000000|44=98.000000"
        val counter = "35=AJ|49=$BUYER|693=R-2|694=2|117=V-Q-1|55=${tenYear.symbol}"

        assertEquals("5", field(sends(assertNotNull(winner(wrongLevel, reading)), wrongLevel).single().second, 297))
        assertTrue(field(sends(assertNotNull(winner(counter, reading)), counter).single().second, 58)!!.contains("firm"))
    }

    // ---------------------------------------------------------------- the dealer

    @Test
    fun `a dealer quotes a disclosed side one way and an undisclosed request two-way, at the size asked`() {
        fun quote(side: String?): String {
            val raw =
                listOfNotNull(
                    "35=R",
                    "131=V-RFQ-1",
                    "146=1",
                    "55=${tenYear.symbol}",
                    "48=${tenYear.cusip}",
                    "22=1",
                    side?.let {
                        "54=$it"
                    },
                    "38=25000000",
                ).joinToString("|")
            val rule = assertNotNull(winner(raw, VenueReading(null, null, null, true), dealer))
            return AcceptorResponder.plan(rule, AcceptorResponder.buildMessage(raw, dictionary), request(raw), dictionary).single().render()
        }

        val toBuyer = quote("1")
        assertEquals(tenYear.offerPx, field(toBuyer, 133))
        assertNull(field(toBuyer, 132), toBuyer)
        assertEquals("25000000", field(toBuyer, 135), "the size the request asked for")
        assertEquals("V-RFQ-1", field(toBuyer, 131), "the platform's QuoteReqID, answered as given")

        val twoWay = quote(null)
        assertEquals(tenYear.bidPx, field(twoWay, 132))
        assertEquals(tenYear.offerPx, field(twoWay, 133))
        assertNull(field(twoWay, 54), twoWay)
    }

    @Test
    fun `the dealer has no rule for a QuoteResponse, because the platform's fill is what confirms a trade`() {
        assertTrue(dealer.none { it.whenMsgType == "AJ" }, "a dealer that answered a lift would confirm a trade nobody asked it to")
    }

    @Test
    fun `every level is on the 32nd grid, reads the way its Text says, and yields move opposite prices`() {
        FiRfqPlatformPreset.ISSUES.forEach { issue ->
            val (bid32, offer32) = issue.thirtySeconds.split(" / ")
            assertEquals(bid32, thirtySeconds(issue.bidPx), "${issue.symbol} bid")
            assertEquals(offer32, thirtySeconds(issue.offerPx), "${issue.symbol} offer")
            assertTrue(BigDecimal(issue.bidPx) < BigDecimal(issue.offerPx), "${issue.symbol}: an inverted market")
            assertTrue(BigDecimal(issue.bidYield) > BigDecimal(issue.offerYield), "${issue.symbol}: the lower price is the higher yield")
            if (BigDecimal(issue.offerPx) < BigDecimal(100)) {
                assertTrue(BigDecimal(issue.offerYield) > BigDecimal(issue.coupon), "${issue.symbol}: below par yields above its coupon")
            }
        }
        // The same half a 32nd is worth more yield the sooner the issue matures: the shortest has the widest spread.
        val spreads = FiRfqPlatformPreset.ISSUES.sortedBy { it.maturity }.map { BigDecimal(it.bidYield) - BigDecimal(it.offerYield) }
        assertEquals(spreads.sortedDescending(), spreads, "yield spreads by maturity: $spreads")
    }

    /** Illustrative is not malformed: a CUSIP whose ninth character is wrong is refused by any system that reads one. */
    @Test
    fun `every CUSIP carries its own check digit`() {
        fun checkDigit(base: String): Char {
            val sum =
                base.withIndex().sumOf { (i, c) ->
                    val value = (if (c.isDigit()) c - '0' else c - 'A' + 10) * (if (i % 2 == 1) 2 else 1)
                    value / 10 + value % 10
                }
            return '0' + (10 - sum % 10) % 10
        }
        FiRfqPlatformPreset.CUSIPS.forEach { cusip ->
            assertEquals(9, cusip.length, cusip)
            assertEquals(checkDigit(cusip.take(8)), cusip.last(), "$cusip has the wrong check digit")
        }
        assertEquals('0', checkDigit("03783310"), "the check itself, on a CUSIP everyone knows")
    }

    private companion object {
        const val BUYER = FiRfqPlatformPreset.BUY_SIDE_1
        const val DEALER = FiRfqPlatformPreset.DEALER_1
    }
}
