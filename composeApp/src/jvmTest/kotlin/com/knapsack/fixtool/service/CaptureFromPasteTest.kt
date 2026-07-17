package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepOrigin
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **W2's first half: a wire from a real server becomes a scenario.**
 *
 * The fixture is `tools/fake-venue`'s own message log — the log it writes precisely so that this can be tested
 * against bytes FixTool did not compose: a `NewOrderSingle` going out, and the venue's hostile ExecutionReport
 * coming back with `37` before `11`, its party group mid-body, and **a pipe inside `58`**.
 *
 * Two rules the paste path may not break, and they are the two that would be easiest to break quietly:
 *
 * 1. **The direction is not guessed.** A reply mis-marked as a Send becomes a step that asserts *nothing* —
 *    the scenario would send the venue's own ExecutionReport back at it and report green. So an undirected row
 *    is refused a save, by name, and the bytes settle it wherever they can (`SenderCompID(49)`).
 * 2. **Everything a paste makes is badged.** Every step of a pasted capture carries `origin = PASTED`, and the
 *    badge survives the save — because FixTool did not watch these bytes arrive and must never imply it did.
 */
class CaptureFromPasteTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val soh = "\u0001"

    /** A real frame — BodyLength and CheckSum over the bytes, exactly as the venue computes them. */
    private fun frame(vararg body: String): String {
        val fields = body.joinToString(soh, postfix = soh)
        val head = "8=FIX.4.4${soh}9=${fields.length}$soh"
        val checksum = (head + fields).toByteArray(Charsets.ISO_8859_1).sumOf { it.toInt() and 0xFF } % 256
        return head + fields + "10=%03d".format(checksum) + soh
    }

    /** The NewOrderSingle FixTool sent. */
    private val order =
        frame(
            *(
                "35=D 34=2 49=FIXTOOL 52=20260714-08:12:31.500 56=FAKE_VENUE " +
                    "11=ORD-1 55=EUR/USD 54=1 38=1000000 40=1 60=20260714-08:12:31.500"
            ).split(" ").toTypedArray(),
        )

    /** The venue's hostile ExecutionReport: 37 before 11, the group mid-body, and a pipe inside 58. */
    private val execution =
        frame(
            *(
                "35=8 34=2 49=FAKE_VENUE 52=20260714-08:12:31.517 56=FIXTOOL " +
                    "37=VENUE-ORD-9 11=ORD-1 17=EXEC-1 150=2 39=2 151=0 " +
                    "453=2 448=FIRMA 447=D 452=1 448=FIRMB 447=D 452=4 " +
                    "58=filled|in\u00A0full 60=20260714-08:12:31.517"
            ).split(" ").map { it.replace("\u00A0", " ") }.toTypedArray(),
        )

    /** What the venue's message log holds: a prefix, a direction word of its own, and the bytes. */
    private val log = "20260714-08:12:31.500 IN $order\n20260714-08:12:31.517 OUT $execution"

    /** FixTool's side of the session: it is FIXTOOL, and it is talking to FAKE_VENUE. */
    private fun scanned() = ScenarioCapture.fromPaste(log, "Venue", senderCompId = "FIXTOOL", targetCompId = "FAKE_VENUE")

    @Test
    fun `a log fragment becomes candidates, and the pipe inside 58 survives`() {
        val scan = scanned()

        assertTrue(scan.refused.isEmpty(), "both lines read: ${scan.refused}")
        assertEquals(2, scan.candidates.size)
        assertEquals(listOf("D", "8"), scan.candidates.map { it.messageType })
        assertEquals(
            "filled|in full",
            scan.candidates[1]
                .fields
                .single { it.first == 58 }
                .second,
            "the trap: 58 is ONE field, and a `|` inside it is an ordinary character",
        )
        assertTrue(scan.candidates.all { it.pasted }, "no source row: these never arrived on a wire here")
        assertTrue(scan.candidates.all { it.source == null }, "and none is invented, which would be a lie")
    }

    /**
     * **The bytes decide the direction where they can.** `49=FIXTOOL` is us, so that message went out;
     * `49=FAKE_VENUE` is them, so it came in. The venue's own `IN`/`OUT` words are its point of view, and are
     * not consulted — a word in someone else's log is not evidence about ours.
     */
    @Test
    fun `SenderCompID settles the direction, and the log's own words are not consulted`() {
        val scan = scanned()

        assertEquals(FixMessage.Direction.OUTGOING, scan.candidates[0].direction, "49=FIXTOOL: we sent it")
        assertEquals(FixMessage.Direction.INCOMING, scan.candidates[1].direction, "49=FAKE_VENUE: they sent it")
        assertTrue(ScenarioCapture.undirected(scan.candidates).isEmpty())
    }

    /**
     * And where they cannot decide it, **nobody guesses**. A log from a third party, or a session with no
     * config: the row is undirected, and it blocks the save by name rather than defaulting into a Send that
     * asserts nothing.
     */
    @Test
    fun `a message whose CompIDs mean nothing here is undirected, and it blocks the save`() {
        val scan = ScenarioCapture.fromPaste(log, "Venue", senderCompId = null, targetCompId = null)

        assertEquals(2, scan.candidates.size)
        assertTrue(scan.candidates.all { it.direction == null }, "nothing here says which way these went")
        assertEquals(2, ScenarioCapture.undirected(scan.candidates).size, "and the review refuses the save")

        // The engine keeps the same rule as the review: an undirected row cannot become a step at all.
        val scenario = ScenarioCapture.captureFrom("id", "paste", null, scan.candidates, dictionary)
        assertTrue(scenario.steps.isEmpty(), "a row nobody has directed is not silently made into a Send")
    }

    /** A line the reader refuses is not a candidate — and it is **reported**, never dropped on the floor. */
    @Test
    fun `a line whose reading the bytes disprove is reported, not captured`() {
        val piped = execution.replace(soh, "|")
        val scan = ScenarioCapture.fromPaste(piped, "Venue", senderCompId = "FIXTOOL", targetCompId = "FAKE_VENUE")

        assertTrue(scan.candidates.isEmpty(), "nothing is captured from a reading the message itself disproves")
        assertEquals(1, scan.refused.size)
        assertTrue(scan.refused.single().contains("line 1"), "and the line is named: ${scan.refused.single()}")
        // The unreadable segment is the *first* evidence, and it is the plainest: `in full` is not a field and
        // cannot be one. (Where a mangled pipe happens to leave every segment parseable, the message's own
        // CheckSum(10) catches it instead — see WirePasteTest.)
        assertTrue(scan.refused.single().contains("\"in full\""), scan.refused.single())
    }

    /**
     * **The whole point: it runs.** Sends become parameterized Send steps with a fresh id; replies become
     * seeded expectations that bind to *this run's* reply; and every step of it is badged `pasted`.
     */
    @Test
    fun `a pasted capture is a runnable scenario, and every step of it is badged`() {
        val candidates = scanned().candidates
        val scenario = ScenarioCapture.captureFrom("sc-paste", "from the log", null, candidates, dictionary)

        assertEquals(2, scenario.steps.size)
        val send = scenario.steps[0] as ScenarioStep.Send
        val expect = scenario.steps[1] as ScenarioStep.Expect

        // Parameterized exactly as a live capture parameterizes: a fresh id each run, and a fresh timestamp.
        assertTrue(send.raw.contains("11=\${id0 = uuid:20}"), send.raw)
        assertTrue(send.raw.contains("60=\${utcnow}"), "the send stamps its own TransactTime, in UTC")
        assertTrue(send.raw.contains("55=EUR/USD"), "and the rest of it is the venue's own bytes")

        // The reply's echo is a reference matcher AND a bind constraint, so the step binds to ITS run's reply.
        val echoed = expect.expectation.fields.single { it.tag == 11 }
        assertEquals(Matcher.Reference("\${id0}"), echoed.matcher)
        val constraints = expect.match?.fields ?: emptyList()
        val bound = constraints.single { it.tag == 11 }
        assertEquals("\${id0}", bound.value, "and it binds the step to THIS run's reply, not the first of its type")

        // The golden is the venue's bytes as read — pipe and all — because that is what the rows were seeded from.
        assertTrue(expect.expectation.golden!!.contains("58=filled|in full"), "the golden keeps the pipe")
        val text = expect.expectation.fields.single { it.tag == 58 }
        assertEquals(
            "filled|in full",
            (text.matcher as Matcher.Exact).value,
            "and so does the row seeded from it — the assertion is the value the venue sent, not a truncation",
        )

        // Two entries of the party group, asserted separately, in the order the venue sent them.
        val roles =
            expect.expectation.fields
                .filter { it.tag == 452 }
                .map { (it.matcher as Matcher.Exact).value }
        assertEquals(listOf("1", "4"), roles, "FIRMA holds role 1 and FIRMB role 4, each in its own entry")

        assertTrue(
            scenario.steps.all { it.origin == StepOrigin.PASTED },
            "every artifact built from a paste is badged — FixTool did not watch these bytes arrive",
        )
        assertNull(
            scenario.steps.firstOrNull { it.origin == StepOrigin.LIVE },
            "and not one of them claims otherwise",
        )
    }

    /**
     * A pasted candidate's timestamp is its own SendingTime(52) — so a paste whose lines carry
     * out-of-order 52s (interleaved log fragments) must NOT let that time decide the occurrence
     * ordinals. The runner binds "the k-th same-type match" in replay order, which is step order;
     * an ordinal assigned by SendingTime would bind the 1st ordinal to the 2nd step.
     */
    @Test
    fun `occurrence ordinals follow step order, not the pasted SendingTime`() {
        // Two ERs the constraints cannot separate (same 11, same shape) — the ordinal seeder must fire.
        // The FIRST line carries the LATER SendingTime, so a timestamp sort would swap the ordinals.
        val er1 =
            frame(
                *"35=8 34=2 49=FAKE_VENUE 52=20260714-08:12:35.000 56=FIXTOOL 37=V-1 11=ORD-1 17=E-1 150=0 39=0".split(" ").toTypedArray(),
            )
        val er2 =
            frame(
                *"35=8 34=3 49=FAKE_VENUE 52=20260714-08:12:31.000 56=FIXTOOL 37=V-2 11=ORD-1 17=E-2 150=0 39=0".split(" ").toTypedArray(),
            )
        val scan = ScenarioCapture.fromPaste("$er1\n$er2", "Venue", senderCompId = "FIXTOOL", targetCompId = "FAKE_VENUE")
        assertEquals(2, scan.candidates.size, scan.refused.joinToString())

        val scenario = ScenarioCapture.captureFrom("id", "paste", null, scan.candidates, dictionary)
        val expects = scenario.steps.filterIsInstance<ScenarioStep.Expect>()
        assertEquals(2, expects.size)
        assertEquals(
            listOf(1, 2),
            expects.map { it.match?.occurrence },
            "the 1st step binds the 1st arrival, whatever its SendingTime says",
        )
    }
}
