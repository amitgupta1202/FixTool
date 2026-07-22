package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.MintingSide
import com.knapsack.fixtool.model.scenario.MatchOp
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.Test
import quickfix.Message
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Captures a multi-session RFQ flow (a quote session + a trade session, FixTool as the client on
 * both) and verifies: chronological multi-session ordering, volatile parameterization, and
 * cross-session id correlation via scenario variables.
 */
class ScenarioCaptureTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    /**
     * A message shaped the way the transport actually shapes one: `rawMessage` is the `|`-substituted
     * *display* string and `wireRaw` is the SOH bytes, and both are always present.
     *
     * The fixture used to omit `wireRaw`, which no production path does — and omitting it is not a
     * harmless simplification, because `wireRaw` is where the venue's field *order* lives, and the order
     * is half of what a captured expectation asserts. A fixture with no wire order tests a capture that
     * cannot happen.
     */
    private fun msg(raw: String, dir: FixMessage.Direction, second: Int): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.of(2026, 6, 30, 10, 0, second),
            direction = dir,
            rawMessage = raw,
            quickfixMessage = Message(),
            wireRaw = raw.replace('|', ''),
        )

    /**
     * A UTCTimestamp is UTC by definition, and a replay stamps it fresh at send time. Capture used to
     * emit LocalDateTime.now() — the system clock — so a replay from London in summer sent a
     * TransactTime an hour ahead of UTC: a value the counterparty may reject, and one that fails any
     * temporal assertion on the field echoed back. The same bug 5d165e2 fixed in the demo server.
     */
    @Test
    fun `a captured timestamp replays as UTC, not the machine's local time`() {
        val order = ScenarioCapture.CapturedSession(
            "TRADE",
            listOf(
                msg(
                    "8=FIX.4.4|35=D|34=2|49=CLI|52=20260630-10:00:02|56=TV|11=ORD-1|55=EUR/USD|54=1|" +
                        "38=1000000|40=2|60=20260630-10:00:02.000|10=003|",
                    FixMessage.Direction.OUTGOING,
                    2,
                ),
            ),
        )
        val scenario =
            ScenarioCapture.capture("sc-utc", "order", profile = null, sessions = listOf(order), dictionary = dictionary)
        val send = scenario.steps.filterIsInstance<ScenarioStep.Send>().single()
        val expr = send.raw.split("|").first { it.startsWith("60=") }.removePrefix("60=")

        // Evaluate the expression the way the runner does, from a machine that is nowhere near UTC.
        val original = TimeZone.getDefault()
        val stamped =
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo")) // +09:00, no DST
                FixMessageTemplate.evaluate(expr)
            } finally {
                TimeZone.setDefault(original)
            }

        val sent = LocalDateTime.parse(stamped, DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")).toInstant(ZoneOffset.UTC)
        val driftSeconds = abs(sent.epochSecond - Instant.now().epochSecond)
        assertTrue(
            driftSeconds < 60,
            "TransactTime must be stamped in UTC; it is off by ${driftSeconds}s (a local-time stamp in +09:00 lands ~9h out): $stamped",
        )
    }

    /**
     * A scenario is captured on one environment, committed, and replayed on another. Session identity
     * belongs to the connection, not to the behaviour under test, so it must not be asserted: seeding
     * SenderCompID(49)/TargetCompID(56)/BeginString(8) as Exact sent every step red on a teammate's
     * session — CompIDs differ, the business content does not — which defeats the whole point of a
     * scenario being portable.
     */
    @Test
    fun `a scenario captured on one environment replays on another`() {
        val dev = ScenarioCapture.CapturedSession(
            "TRADE",
            listOf(
                msg(
                    "8=FIX.4.4|35=8|34=2|49=SERVER_DEV1|56=CLIENT_A|52=20260630-10:00:03|11=ORD-1|17=EXEC-1|" +
                        "150=2|39=2|55=EUR/USD|31=1.0851|32=1000000|60=20260630-10:00:03.000|10=004|",
                    FixMessage.Direction.INCOMING,
                    3,
                ),
            ),
        )
        val scenario =
            ScenarioCapture.capture("sc-port", "fill", profile = null, sessions = listOf(dev), dictionary = dictionary)
        val expectation = scenario.steps.filterIsInstance<ScenarioStep.Expect>().single().expectation

        assertTrue(
            expectation.fields.none { it.tag in setOf(8, 49, 56, 34, 52) },
            "session identity must not be asserted: ${expectation.fields.filter { it.tag in setOf(8, 49, 56) }}",
        )

        // The same fill from a different environment: other CompIDs, other seq/time, same business content.
        val onQa =
            "8=FIX.4.4|35=8|34=57|49=SERVER_QA1|56=CLIENT_B|52=20260701-08:12:44|11=ORD-1|17=EXEC-9|" +
                "150=2|39=2|55=EUR/USD|31=1.0851|32=1000000|60=20260701-08:12:44.000|10=123|"
        // ...evaluated at the moment that fill arrived, so the seeded "~now" TransactTime is current.
        val results =
            ExpectationEvaluator.evaluate(
                RawMessageView(onQa),
                expectation,
                now = { Instant.parse("2026-07-01T08:12:44Z") },
            )

        assertTrue(
            results.all { it.passed },
            "a portable scenario must pass on another environment; failed: ${results.filter { !it.passed }}",
        )
    }

    @Test
    fun `captures a multi-session rfq with parameterization and cross-session correlation`() {
        val quote = ScenarioCapture.CapturedSession(
            "QUOTE",
            listOf(
                // out QuoteRequest (QuoteReqID QR-1)
                msg("8=FIX.4.4|35=R|34=2|49=CLI|52=20260630-10:00:00|56=QV|131=QR-1|55=EUR/USD|10=001|", FixMessage.Direction.OUTGOING, 0),
                // in Quote echoing QR-1
                msg("8=FIX.4.4|35=S|34=2|49=QV|52=20260630-10:00:01|56=CLI|131=QR-1|117=Q-9|55=EUR/USD|132=1.0850|60=20260630-10:00:01.000|10=002|", FixMessage.Direction.INCOMING, 1),
            ),
        )
        val trade = ScenarioCapture.CapturedSession(
            "TRADE",
            listOf(
                // out NewOrderSingle (ClOrdID ORD-1) that re-uses the quote's QR-1
                msg("8=FIX.4.4|35=D|34=2|49=CLI|52=20260630-10:00:02|56=TV|11=ORD-1|131=QR-1|55=EUR/USD|54=1|38=1000000|40=2|44=1.0851|60=20260630-10:00:02.000|10=003|", FixMessage.Direction.OUTGOING, 2),
                // in ExecutionReport echoing ORD-1 and QR-1
                msg("8=FIX.4.4|35=8|34=2|49=TV|52=20260630-10:00:03|56=CLI|11=ORD-1|131=QR-1|17=EXEC-1|150=2|39=2|55=EUR/USD|31=1.0851|32=1000000|60=20260630-10:00:03.000|10=004|", FixMessage.Direction.INCOMING, 3),
            ),
        )

        val scenario = ScenarioCapture.capture("sc1", "RFQ", profile = null, sessions = listOf(quote, trade), dictionary = dictionary)

        // setup clears both sessions
        val cleared = scenario.setup.filterIsInstance<ScenarioStep.ClearMessages>().map { it.session }.toSet()
        assertEquals(setOf("QUOTE", "TRADE"), cleared)

        // chronological multi-session order: send R, expect S, send D, expect 8
        val kinds = scenario.steps.map { it::class.simpleName to sessionOf(it) }
        assertEquals(
            listOf("Send" to "QUOTE", "Expect" to "QUOTE", "Send" to "TRADE", "Expect" to "TRADE"),
            kinds,
        )

        val sendR = scenario.steps[0] as ScenarioStep.Send
        val expectS = scenario.steps[1] as ScenarioStep.Expect
        val sendD = scenario.steps[2] as ScenarioStep.Send
        val expect8 = scenario.steps[3] as ScenarioStep.Expect

        // QuoteRequest: QuoteReqID parameterized to a fresh scenario variable; transport tags stripped.
        assertTrue(sendR.raw.contains("35=R"))
        assertTrue(
            sendR.raw.contains("131=\${quoteReqID = uuid:20}"),
            "QuoteReqID should mint a 20-char variable named for the field (venue id caps); got ${sendR.raw}",
        )
        assertTrue(!sendR.raw.contains("49=") && !sendR.raw.contains("8=FIX"), "transport tags should be stripped")

        // Quote echoes QR-1 -> reference matcher on the same scenario variable.
        assertEquals(Matcher.Reference("\${quoteReqID}"), matcher(expectS, 131))
        // ...and the echo also BINDS the step: on a busy session this Expect must pick the quote
        // answering this run's request, not the first quote of any kind.
        assertEquals(listOf(com.knapsack.fixtool.model.scenario.TagValue(131, "\${quoteReqID}")), expectS.match?.fields)

        // NewOrderSingle: fresh ClOrdID variable, and it re-uses the quote's variable for QuoteReqID
        // (cross-session correlation), plus a templated TransactTime.
        assertTrue(sendD.raw.contains("11=\${clOrdID = uuid:20}"), "ClOrdID should mint a variable; got ${sendD.raw}")
        assertTrue(sendD.raw.contains("131=\${quoteReqID}"), "QuoteReqID should re-reference the quote variable across sessions; got ${sendD.raw}")
        assertTrue(
            sendD.raw.contains("60=\${utcnow}"),
            "TransactTime should be templated as the utcnow shorthand (UTC); got ${sendD.raw}",
        )

        // ExecutionReport echoes both ids -> reference matchers (cross-session for QR-1).
        assertEquals(Matcher.Reference("\${clOrdID}"), matcher(expect8, 11))
        assertEquals(Matcher.Reference("\${quoteReqID}"), matcher(expect8, 131))
        // a smart-seeded field survives where there's no echo (TransactTime -> temporal).
        assertTrue(matcher(expect8, 60) is Matcher.Temporal, "TransactTime should smart-seed temporal")
    }

    @Test
    fun `candidates lists business messages chronologically across sessions and excludes admin`() {
        val a = ScenarioCapture.CapturedSession(
            "A",
            listOf(
                msg("8=FIX.4.4|35=A|34=1|49=CLI|56=SRV|10=001|", FixMessage.Direction.OUTGOING, 0), // Logon: admin
                msg("8=FIX.4.4|35=D|34=2|49=CLI|56=SRV|11=X-1|10=002|", FixMessage.Direction.OUTGOING, 1),
            ),
        )
        val b = ScenarioCapture.CapturedSession(
            "B",
            listOf(msg("8=FIX.4.4|35=8|34=2|49=SRV|56=CLI|11=X-1|10=003|", FixMessage.Direction.INCOMING, 2)),
        )

        val candidates = ScenarioCapture.candidates(listOf(b, a))

        assertEquals(listOf("A" to "D", "B" to "8"), candidates.map { it.session to it.messageType })
    }

    @Test
    fun `captureFrom builds only the curated selection and scopes setup to its sessions`() {
        val noise = msg("8=FIX.4.4|35=D|34=2|49=CLI|56=SRV|11=OLD-1|10=001|", FixMessage.Direction.OUTGOING, 0)
        val send = msg("8=FIX.4.4|35=D|34=3|49=CLI|56=SRV|11=ORD-1|10=002|", FixMessage.Direction.OUTGOING, 1)
        val reply = msg("8=FIX.4.4|35=8|34=3|49=SRV|56=CLI|11=ORD-1|17=E-1|10=003|", FixMessage.Direction.INCOMING, 2)
        val all = ScenarioCapture.candidates(
            listOf(ScenarioCapture.CapturedSession("A", listOf(noise, send, reply))),
        )

        // The review screen unticked the stale first order.
        val scenario = ScenarioCapture.captureFrom("sc2", "curated", null, all.drop(1), dictionary)

        assertEquals(2, scenario.steps.size)
        val sendStep = scenario.steps[0] as ScenarioStep.Send
        assertTrue(sendStep.raw.contains("11=\${clOrdID = uuid:20}"), "curated send should mint the ClOrdID variable; got ${sendStep.raw}")
        assertEquals(Matcher.Reference("\${clOrdID}"), matcher(scenario.steps[1] as ScenarioStep.Expect, 11))
        assertEquals(listOf("A"), scenario.setup.filterIsInstance<ScenarioStep.ClearMessages>().map { it.session })
    }

    // ----- Decision 6: capture auto-seeds a discriminator for two same-type replies ------------------

    /**
     * Two ExecutionReports for one order — an ack (ExecType 0) and a fill (ExecType 2) — both echo the same
     * ClOrdID, so the seeded id-echo constraints cannot tell them apart. Capture seeds a **value** discriminator
     * on ExecType, the reorder-proof form, in preference to a positional ordinal.
     */
    @Test
    fun `two same-type replies that differ in ExecType get a value discriminator, not an ordinal`() {
        val session = ScenarioCapture.CapturedSession(
            "TRADE",
            listOf(
                msg("8=FIX.4.4|35=D|34=2|49=CLI|56=TV|52=20260630-10:00:00|11=ORD-1|55=EUR/USD|54=1|38=1000000|40=2|60=20260630-10:00:00.000|10=001|", FixMessage.Direction.OUTGOING, 0),
                msg("8=FIX.4.4|35=8|34=3|49=TV|56=CLI|52=20260630-10:00:01|11=ORD-1|17=E-1|150=0|39=0|60=20260630-10:00:01.000|10=002|", FixMessage.Direction.INCOMING, 1),
                msg("8=FIX.4.4|35=8|34=4|49=TV|56=CLI|52=20260630-10:00:02|11=ORD-1|17=E-2|150=2|39=2|60=20260630-10:00:02.000|10=003|", FixMessage.Direction.INCOMING, 2),
            ),
        )
        val expects = ScenarioCapture.capture("sc-disc-value", "fills", null, listOf(session), dictionary)
            .steps.filterIsInstance<ScenarioStep.Expect>()

        assertEquals(2, expects.size)
        assertEquals("0", execTypeConstraint(expects[0]), "the ack should bind on ExecType=0: ${expects[0].match?.fields}")
        assertEquals("2", execTypeConstraint(expects[1]), "the fill should bind on ExecType=2: ${expects[1].match?.fields}")
        assertTrue(expects.all { it.match?.occurrence == null }, "a value discriminator must be preferred over a positional ordinal")
    }

    /**
     * Same ExecType/OrdStatus on both, so no value tells them apart — but one carries Text(58) and the other
     * does not. Capture seeds a **presence** discriminator, which is value-agnostic and so replay-safe.
     */
    @Test
    fun `two same-type replies that differ only by a tag's presence get a presence discriminator`() {
        val session = ScenarioCapture.CapturedSession(
            "TRADE",
            listOf(
                msg("8=FIX.4.4|35=D|34=2|49=CLI|56=TV|52=20260630-10:00:00|11=ORD-1|55=EUR/USD|54=1|38=1000000|40=2|60=20260630-10:00:00.000|10=001|", FixMessage.Direction.OUTGOING, 0),
                msg("8=FIX.4.4|35=8|34=3|49=TV|56=CLI|52=20260630-10:00:01|11=ORD-1|17=E-1|150=2|39=2|58=partial|60=20260630-10:00:01.000|10=002|", FixMessage.Direction.INCOMING, 1),
                msg("8=FIX.4.4|35=8|34=4|49=TV|56=CLI|52=20260630-10:00:02|11=ORD-1|17=E-2|150=2|39=2|60=20260630-10:00:02.000|10=003|", FixMessage.Direction.INCOMING, 2),
            ),
        )
        val expects = ScenarioCapture.capture("sc-disc-presence", "fills", null, listOf(session), dictionary)
            .steps.filterIsInstance<ScenarioStep.Expect>()

        assertEquals(2, expects.size)
        assertTrue(
            expects[0].match!!.fields.any { it.tag == 58 && it.op == MatchOp.PRESENT },
            "the first should require Text(58) present: ${expects[0].match?.fields}",
        )
        assertTrue(
            expects[1].match!!.fields.any { it.tag == 58 && it.op == MatchOp.ABSENT },
            "the second should require Text(58) absent: ${expects[1].match?.fields}",
        )
    }

    /**
     * Two replies with identical business content — only volatile ExecID/time/seq differ, none a stable value
     * or a presence difference. Position is the only thing that separates them, so capture falls back to
     * occurrence ordinals.
     */
    @Test
    fun `two indistinguishable same-type replies fall back to occurrence ordinals`() {
        val session = ScenarioCapture.CapturedSession(
            "TRADE",
            listOf(
                msg("8=FIX.4.4|35=D|34=2|49=CLI|56=TV|52=20260630-10:00:00|11=ORD-1|55=EUR/USD|54=1|38=1000000|40=2|60=20260630-10:00:00.000|10=001|", FixMessage.Direction.OUTGOING, 0),
                msg("8=FIX.4.4|35=8|34=3|49=TV|56=CLI|52=20260630-10:00:01|11=ORD-1|17=E-1|150=2|39=2|60=20260630-10:00:01.000|10=002|", FixMessage.Direction.INCOMING, 1),
                msg("8=FIX.4.4|35=8|34=4|49=TV|56=CLI|52=20260630-10:00:02|11=ORD-1|17=E-2|150=2|39=2|60=20260630-10:00:02.000|10=003|", FixMessage.Direction.INCOMING, 2),
            ),
        )
        val expects = ScenarioCapture.capture("sc-disc-ordinal", "fills", null, listOf(session), dictionary)
            .steps.filterIsInstance<ScenarioStep.Expect>()

        assertEquals(2, expects.size)
        assertEquals(1, expects[0].match?.occurrence, "the first reply is occurrence 1: ${expects[0].match}")
        assertEquals(2, expects[1].match?.occurrence, "the second reply is occurrence 2: ${expects[1].match}")
    }

    /**
     * **`TradeReportID(571)` is minted by whoever submits the report, and a capture holds both cases.**
     *
     * It is in the client-minted set *and* the venue-assigned one, exactly as `QuoteID(117)` is, and the
     * two readings are told apart by evidence rather than by declaration: an id this scenario's own Send
     * minted comes back as a `Reference`, and one the venue invented is asserted for presence. Listed on
     * one side only, the venue's own report seeded `Exact` and the scenario went red on its second run —
     * on a field whose value was never the behaviour under test.
     */
    @Test
    fun `a TradeReportID we minted is a reference, and one the venue minted is presence`() {
        val post = ScenarioCapture.CapturedSession(
            "POST",
            listOf(
                // out TradeCaptureReport — our submission, our TradeReportID
                msg("8=FIX.4.4|35=AE|34=2|49=CLI|52=20260630-10:00:00|56=VEN|571=OUR-TCR-1|55=EUR/USD|32=1000000|31=1.0851|60=20260630-10:00:00.000|10=001|", FixMessage.Direction.OUTGOING, 0),
                // in TradeCaptureReportAck echoing ours, carrying the venue's own secondary id
                msg("8=FIX.4.4|35=AR|34=2|49=VEN|52=20260630-10:00:01|56=CLI|571=OUR-TCR-1|818=VEN-SEC-7|939=0|10=002|", FixMessage.Direction.INCOMING, 1),
                // in TradeCaptureReport the venue published itself — its TradeReportID, not ours
                msg("8=FIX.4.4|35=AE|34=3|49=VEN|52=20260630-10:00:02|56=CLI|571=VEN-TCR-9|55=EUR/USD|32=1000000|31=1.0851|60=20260630-10:00:02.000|10=003|", FixMessage.Direction.INCOMING, 2),
            ),
        )

        val scenario = ScenarioCapture.capture("sc2", "PostTrade", profile = null, sessions = listOf(post), dictionary = dictionary)

        val send = scenario.steps.filterIsInstance<ScenarioStep.Send>().first()
        assertTrue(
            send.raw.contains("571=\${tradeReportID = uuid:20}"),
            "our own submission must mint a fresh TradeReportID per run; got ${send.raw}",
        )

        val expects = scenario.steps.filterIsInstance<ScenarioStep.Expect>()
        val ack = expects.first { it.expectation.messageType == "AR" }
        assertEquals(Matcher.Reference("\${tradeReportID}"), matcher(ack, 571))
        assertTrue(
            matcher(ack, 818) is Matcher.Presence,
            "SecondaryTradeReportID is assigned by the accepting party and is new every run; got ${matcher(ack, 818)}",
        )

        val published = expects.first { it.expectation.messageType == "AE" }
        assertTrue(
            matcher(published, 571) is Matcher.Presence,
            "a TradeReportID the venue minted must not be asserted as a literal; got ${matcher(published, 571)}",
        )
    }

    private fun execTypeConstraint(e: ScenarioStep.Expect): String? =
        e.match?.fields?.firstOrNull { it.tag == 150 && it.op == MatchOp.EQ }?.value

    private fun sessionOf(step: ScenarioStep): String? =
        when (step) {
            is ScenarioStep.Send -> step.session
            is ScenarioStep.Expect -> step.session
            is ScenarioStep.ClearMessages -> step.session
            else -> null
        }

    private fun matcher(expect: ScenarioStep.Expect, tag: Int): Matcher =
        expect.expectation.fields.first { it.tag == tag }.matcher

    /**
     * The golden is the venue's bytes, and the rows are seeded from the same bytes — one decoder, not two.
     *
     * It used to store `rawMessage`, the '|'-substituted *display* string, while the rows were seeded from
     * the SOH wire bytes. Two decodings of one message, and on a value containing a pipe they disagreed:
     * a venue rejecting an order with `58=Rejected|insufficient margin` seeded the row
     * `58 exact "Rejected|insufficient margin"` — correct — and then stored a golden that splits into
     * `58=Rejected` plus a phantom field. The editor previews the rows *against the golden*, so the row went
     * red against the very message it was captured from, and the author was invited to "fix" a matcher that
     * was already right.
     *
     * Nothing pinned the golden's format, which is why that survived. This does.
     */
    @Test
    fun `the golden is the wire, so a pipe inside a value survives capture and previews green`() {
        val soh = '\u0001'
        val display =
            "8=FIX.4.4|35=8|34=2|49=TV|52=20260630-10:00:03|56=CLI|11=ORD-1|39=8|" +
                "58=Rejected|insufficient margin|10=004|"
        val wire =
            "8=FIX.4.4${soh}35=8${soh}34=2${soh}49=TV${soh}52=20260630-10:00:03${soh}56=CLI${soh}11=ORD-1$soh" +
                "39=8${soh}58=Rejected|insufficient margin${soh}10=004$soh"
        // The transport's two forms of one message. rawMessage is lossy here *by construction* — splitting
        // it on '|' invents a field — which is exactly why the golden must not be built from it.
        val reject =
            FixMessage(
                timestamp = LocalDateTime.of(2026, 6, 30, 10, 0, 3),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = display,
                quickfixMessage = Message(),
                wireRaw = wire,
            )

        val scenario =
            ScenarioCapture.capture(
                id = "id",
                name = "reject",
                profile = null,
                sessions = listOf(ScenarioCapture.CapturedSession("TRADE", listOf(reject))),
                dictionary = dictionary,
            )
        val expect = scenario.steps.filterIsInstance<ScenarioStep.Expect>().single()

        // The row carries the whole value, pipe and all.
        val text = expect.expectation.fields.single { it.tag == 58 }
        assertEquals(Matcher.Exact("Rejected|insufficient margin"), text.matcher)

        // And the golden agrees with it — which is the property that was broken. Evaluating the captured
        // expectation against its own golden is precisely what the expectation builder previews, so this is
        // the red the author used to be shown.
        val golden = expect.expectation.golden
        assertNotNull(golden, "a captured expectation must keep the message it was captured from")
        val results = ExpectationEvaluator.evaluate(RawMessageView(golden), expect.expectation)
        val failed = results.filterNot { it.passed }
        assertTrue(failed.isEmpty(), "a captured expectation must pass its own golden: $failed")
    }

    /**
     * **Both sides of an RFQ in ONE scenario — a major client's core flow.**
     * FixTool holds the initiator session and the responder session; the scenario drives the whole
     * conversation. The crux is the responder's OUTGOING Quote echoing the initiator's QuoteReqID: the
     * value was minted by the scenario's *own* step 1, so capture must emit a REUSE of that variable —
     * `131=${quoteReqID}` — never a fresh mint (which would answer a request nobody made) and never the
     * captured literal (which would collide on the next run).
     *
     * This is the case that does NOT need receive→send variable capture: the id is born inside the
     * scenario. (A responder tested *alone* against an external initiator still does — known gap.)
     *
     * Run from **both chairs**, because the responder half of a both-sides capture is an acceptor session
     * and the app hands it [MintingSide.VENUE] — the configuration this test used to skip. From that chair
     * `QuoteReqID(131)` is not ours to mint, so the reuse below cannot be reached by asking who mints the
     * tag: it has to be reached by remembering that this scenario already minted *this value*.
     */
    @Test
    fun `both-sides RFQ in one scenario - the responder's quote reuses the initiator's minted variable`() {
        assertBothSidesRfq(emptyMap()) // sides unset — the historical default, every session a client
        assertBothSidesRfq(mapOf("INIT" to MintingSide.CLIENT, "RESP" to MintingSide.VENUE)) // what the app assigns
    }

    private fun assertBothSidesRfq(sides: Map<String, MintingSide>) {
        val request = "8=FIX.4.4|35=R|34=2|49=INIT|56=RESP|131=REQ-9|146=1|55=EUR/USD|10=001|"
        val quoteReply = "8=FIX.4.4|35=S|34=2|49=RESP|56=INIT|117=Q-1|131=REQ-9|55=EUR/USD|133=1.0853|10=002|"
        val initiator = ScenarioCapture.CapturedSession(
            "INIT",
            listOf(
                msg(request, FixMessage.Direction.OUTGOING, 0),
                msg(quoteReply, FixMessage.Direction.INCOMING, 3),
            ),
        )
        val responder = ScenarioCapture.CapturedSession(
            "RESP",
            listOf(
                msg(request, FixMessage.Direction.INCOMING, 1),
                msg(quoteReply, FixMessage.Direction.OUTGOING, 2),
            ),
        )

        val scenario = ScenarioCapture.capture("sc-rfq", "both sides", null, listOf(initiator, responder), dictionary, sides)

        // Chronological across sessions: initiator sends, responder receives, responder quotes, initiator receives.
        assertEquals(
            listOf("Send" to "INIT", "Expect" to "RESP", "Send" to "RESP", "Expect" to "INIT"),
            scenario.steps.map { it::class.simpleName to sessionOf(it) },
        )
        val sendRequest = scenario.steps[0] as ScenarioStep.Send
        val expectRequest = scenario.steps[1] as ScenarioStep.Expect
        val sendQuote = scenario.steps[2] as ScenarioStep.Send
        val expectQuote = scenario.steps[3] as ScenarioStep.Expect

        // Step 1 mints the QuoteReqID; step 2 (the responder's inbox) checks and binds on the echo.
        assertTrue(sendRequest.raw.contains("131=\${quoteReqID = uuid:20}"), "$sides: ${sendRequest.raw}")
        assertEquals(Matcher.Reference("\${quoteReqID}"), matcher(expectRequest, 131), "$sides")
        assertEquals(
            listOf(com.knapsack.fixtool.model.scenario.TagValue(131, "\${quoteReqID}")),
            expectRequest.match?.fields,
        )

        // THE CRUX: the responder's outgoing Quote REUSES ${quoteReqID} — the same variable, across
        // sessions and across a direction flip — and mints only its own QuoteID.
        assertTrue(sendQuote.raw.contains("131=\${quoteReqID}"), "$sides: the quote answers THIS run's request; got ${sendQuote.raw}")
        assertTrue(!sendQuote.raw.contains("131=\${quoteReqID = "), "$sides: a re-mint would answer a request nobody made")
        assertTrue(sendQuote.raw.contains("117=\${quoteID = uuid:20}"), "$sides: the quoter mints its own QuoteID; got ${sendQuote.raw}")

        // And the initiator's inbox checks both echoes — 117 sits in the presence set too, and the echo
        // check must win over presence for a value this scenario minted itself.
        assertEquals(Matcher.Reference("\${quoteReqID}"), matcher(expectQuote, 131))
        assertEquals(Matcher.Reference("\${quoteID}"), matcher(expectQuote, 117))
    }

    /**
     * **FixTool is generic, and the correlation-id treatment must be too.** An MDReqID is minted fresh
     * per run (venues enforce uniqueness on request ids), its echo on the snapshot becomes a Reference
     * check, and it binds the Expect to THIS run's reply — exactly what ClOrdID has always had. And a
     * quote lifetime on a send replays *in the future*: verbatim it is stale, stamped `now` it is
     * already expired on arrival — either way the venue rejects a replay for reasons that are not the
     * venue's behaviour.
     */
    @Test
    fun `market-data and dealer-quote flows get the order flow's correlation treatment`() {
        val mdRequest = msg("8=FIX.4.4|35=V|34=2|49=CLI|56=SRV|262=MDR-1|263=1|264=0|10=001|", FixMessage.Direction.OUTGOING, 0)
        val snapshot = msg("8=FIX.4.4|35=W|34=2|49=SRV|56=CLI|262=MDR-1|55=EUR/USD|10=002|", FixMessage.Direction.INCOMING, 1)
        val quote = msg("8=FIX.4.4|35=S|34=3|49=CLI|56=SRV|117=Q-1|62=20260716-10:10:00.000|10=003|", FixMessage.Direction.OUTGOING, 2)
        val candidates =
            ScenarioCapture.candidates(listOf(ScenarioCapture.CapturedSession("A", listOf(mdRequest, snapshot, quote))))

        val scenario = ScenarioCapture.captureFrom("sc-md", "md and quote", null, candidates, dictionary)

        val sendV = scenario.steps[0] as ScenarioStep.Send
        assertTrue(sendV.raw.contains("262=\${mDReqID = uuid:20}"), "MDReqID must mint a fresh id per run; got ${sendV.raw}")

        val expectW = scenario.steps[1] as ScenarioStep.Expect
        assertEquals(Matcher.Reference("\${mDReqID}"), matcher(expectW, 262), "the snapshot's MDReqID echo is a reference check")
        assertEquals(
            listOf(com.knapsack.fixtool.model.scenario.TagValue(262, "\${mDReqID}")),
            expectW.match?.fields,
            "and it binds the Expect to this run's snapshot, not the first W of any kind",
        )

        val sendS = scenario.steps[2] as ScenarioStep.Send
        assertTrue(sendS.raw.contains("117=\${quoteID = uuid:20}"), "a dealer-minted QuoteID is fresh per run; got ${sendS.raw}")
        assertTrue(
            sendS.raw.contains("62=\${utcnow+5min}"),
            "a replayed ValidUntilTime lies in the future — not verbatim (stale), not now (expired); got ${sendS.raw}",
        )
    }

    /**
     * **A value we put on the wire first is ours, whatever comes back carrying it.**
     *
     * The full RFQ is a round trip: we quote, the taker's QuoteResponse echoes our `QuoteID(117)`, and our
     * QuoteStatusReport quotes it again. The receive→send capture pass saw that middle echo, decided 117 was
     * *theirs* to bind, and rewrote **every** occurrence of the value — including the Quote that invented it,
     * three messages earlier. The scenario then opened by sending `117=${quoteID}`, a variable nothing had
     * bound yet: no mint anywhere, the run failing on the very step that was supposed to define the id.
     *
     * Order decides ownership, and it can only be read forwards: minted on a Send, echoed back to us as a
     * Reference check, quoted onwards by name.
     */
    @Test
    fun `a quoter mints its own QuoteID even when the taker echoes it back`() {
        val request = msg("8=FIX.4.4|35=R|34=2|49=TAKER|56=CLI|131=RFQ-1|146=1|55=EUR/USD|10=001|", FixMessage.Direction.INCOMING, 0)
        val quote = msg("8=FIX.4.4|35=S|34=2|49=CLI|56=TAKER|117=Q-1|131=RFQ-1|55=EUR/USD|133=1.0853|10=002|", FixMessage.Direction.OUTGOING, 1)
        val response = msg("8=FIX.4.4|35=AJ|34=3|49=TAKER|56=CLI|693=RSP-1|117=Q-1|131=RFQ-1|55=EUR/USD|10=003|", FixMessage.Direction.INCOMING, 2)
        val status = msg("8=FIX.4.4|35=AI|34=3|49=CLI|56=TAKER|117=Q-1|297=0|55=EUR/USD|10=004|", FixMessage.Direction.OUTGOING, 3)
        val candidates =
            ScenarioCapture.candidates(listOf(ScenarioCapture.CapturedSession("Q", listOf(request, quote, response, status))))

        // The quoter is the quoter from either chair: dialling out to the taker's venue (CLIENT, where
        // QuoteID is ours to mint) or accepting the taker's connection (VENUE, where it is ours to mint
        // for the other reason). 117 sits in both id sets, so the side must change nothing here.
        for (side in MintingSide.entries) {
            val scenario =
                ScenarioCapture.captureFrom("sc-rfq-round-trip", "rfq round trip", null, candidates, dictionary, mapOf("Q" to side))

            val sendQuote = scenario.steps[1] as ScenarioStep.Send
            assertTrue(
                sendQuote.raw.contains("117=\${quoteID = uuid:20}"),
                "$side: the Quote invents the QuoteID, so it must MINT it — a bare reference is bound by nothing; got ${sendQuote.raw}",
            )

            // The taker's echo is checked against what we minted, and our next send quotes the same variable.
            val expectResponse = scenario.steps[2] as ScenarioStep.Expect
            assertEquals(Matcher.Reference("\${quoteID}"), matcher(expectResponse, 117), "$side: their echo answers OUR quote")
            val sendStatus = scenario.steps[3] as ScenarioStep.Send
            assertTrue(sendStatus.raw.contains("117=\${quoteID}"), "$side: the status report reuses the minted id; got ${sendStatus.raw}")
        }
    }
}
