package com.knapsack.fixtool.service.compare

import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The pipe, from both ends of the same message.**
 *
 * The fixture is not invented. It is `tools/fake-venue`'s golden ExecutionReport, byte for byte — the venue
 * built to send bytes FixTool can get wrong — and it carries `58=filled|in full`, a pipe **inside a value**,
 * which is legal FIX and which this codebase has shipped twice as a truncated field.
 *
 * The venue writes that message two ways, and the difference is the whole of this class:
 *
 * - its **message log** keeps the SOH bytes, and they read — `58` comes back whole;
 * - its **stdout** renders SOH as `|` for a human, and that rendering is **lossy**. Nothing in the characters
 *   says which pipe was a delimiter. So it is refused, and the message's own `CheckSum(10)` is the evidence.
 *
 * Every SOH in here is written `\u0001`. A literal one gets eaten by tooling, and the fixture then parses as
 * a single field — which looks exactly like an alignment defect and is not one (ground rule 7).
 */
class WirePasteTest {
    private val soh = "\u0001"

    /** The venue's ExecutionReport, in the order it actually sends: 37 before 11, group mid-body, 58 with its pipe. */
    private val venueFields =
        (
            "8=FIX.4.4 9=263 35=8 34=2 49=FAKE_VENUE 52=20260714-08:12:31.017 56=FIXTOOL " +
                "37=VENUE-ORD-9 11=ORD-1 17=EXEC-1 150=2 39=2 55=EUR/USD 54=1 38=1000000 " +
                "44=1.08510 6=1.08510 14=1000000 151=0 453=2 " +
                "448=FIRMA 447=D 452=1 448=FIRMB 447=D 452=4 " +
                "58=filled|in\u00A0full 60=20260714-08:12:31.017"
        ).split(" ").map { it.replace("\u00A0", " ") }

    /** The venue's own frame: body length and checksum computed over the bytes, exactly as `fake_venue.py` does. */
    private val wire: String =
        run {
            val body = venueFields.drop(2).joinToString(soh, postfix = soh)
            val head = venueFields.take(2).joinToString(soh, postfix = soh).replace("9=263", "9=${body.length}")
            val msg = head + body
            val checksum = msg.toByteArray(Charsets.ISO_8859_1).sumOf { it.toInt() and 0xFF } % 256
            msg + "10=%03d".format(checksum) + soh
        }

    /** What the venue prints to a terminal, and what an engineer copies out of one. */
    private val piped: String = wire.replace(soh, "|")

    /** A real log line: the venue's message log writes a timestamp and a direction before the bytes. */
    private val logLine: String = "20260714-08:12:31.018 OUT $wire"

    // ------------------------------------------------------------------------------- the bytes that read

    @Test
    fun `the SOH bytes read, and the pipe stays inside the value it belongs to`() {
        val paste = WirePaste.read(wire)

        assertEquals(WirePaste.Verdict.READ, paste.verdict, paste.why)
        assertEquals("filled|in full", paste.fields.single { it.first == 58 }.second, "58 is ONE field, pipe and all")
        assertEquals(29, paste.fields.size)
        assertTrue(paste.lint.startsWith("✓"), paste.lint)
        assertTrue(paste.lint.contains("SOH delimiter"), paste.lint)
        // The good news is said out loud: the thing that would have shredded this message did not.
        assertTrue(paste.lint.contains("contains a |"), paste.lint)
    }

    /** A venue's log has a prefix. Refusing it would refuse every log line ever pasted; eating it silently is worse. */
    @Test
    fun `a log prefix is skipped, and it is reported rather than eaten`() {
        val paste = WirePaste.read(logLine)

        assertEquals(WirePaste.Verdict.READ, paste.verdict, paste.why)
        assertEquals("20260714-08:12:31.018 OUT ", paste.ignoredPrefix, "the prefix is quoted back, not eaten")
        assertEquals(8 to "FIX.4.4", paste.fields.first(), "and the message begins at its BeginString, not at a segment boundary")
        assertTrue(paste.lint.contains("agree"), "so the arithmetic can still check the reading: ${paste.lint}")
        assertEquals("filled|in full", paste.fields.single { it.first == 58 }.second)
    }

    // ------------------------------------------------------------------------------- the bytes that do not

    /**
     * **THE TRAP.** The same message, rendered with pipes. `58=filled|in full` now looks like `58=filled` and
     * a segment called `in full`, and nothing in the characters says otherwise.
     *
     * `parseFixMessage` drops that segment **silently** — which is exactly why this was invisible for as long
     * as it was, and why `GET /messages` once reported `58 = "filled"`. Here it is the evidence.
     */
    @Test
    fun `the pipe-rendered line is refused, and the segment it could not read is quoted back`() {
        val paste = WirePaste.read(piped)

        assertEquals(WirePaste.Verdict.REFUSED, paste.verdict)
        assertFalse(paste.usable, "nothing is bound from a reading the bytes themselves disprove")
        assertTrue(paste.fields.isEmpty(), "and nothing is handed on: a disproved reading binds NOTHING")
        assertNull(paste.wire)
        assertTrue(paste.why!!.contains("\"in full\""), "the evidence is quoted: ${paste.why}")
        assertTrue(paste.why!!.contains("58=filled|in full"), "and it names the shape of the problem: ${paste.why}")
        assertTrue(paste.why!!.contains("will not guess"), paste.why!!)
    }

    /**
     * And when the mangling does *not* leave an unreadable segment behind, the message's own arithmetic still
     * catches it. A Text whose value looks like a field — `58=a|11=b` — reads as a phantom `11`, and every
     * segment parses. The checksum does not.
     */
    @Test
    fun `a pipe that reads as a field is caught by the message's own checksum`() {
        // The venue's message with a Text the reader cannot tell from two fields.
        val fields = "8=FIX.4.4 9=0 35=8 49=V 56=C 58=rejected|11=PHANTOM 60=x".split(" ")
        val body = fields.drop(2).joinToString(soh, postfix = soh)
        val head = "8=FIX.4.4${soh}9=${body.length}$soh"
        val msg = head + body
        val checksum = msg.toByteArray(Charsets.ISO_8859_1).sumOf { it.toInt() and 0xFF } % 256
        val real = msg + "10=%03d".format(checksum) + soh

        val soHRead = WirePaste.read(real)
        assertEquals(WirePaste.Verdict.READ, soHRead.verdict, soHRead.why)
        assertEquals("rejected|11=PHANTOM", soHRead.fields.single { it.first == 58 }.second)

        val pipeRead = WirePaste.read(real.replace(soh, "|"))
        assertEquals(WirePaste.Verdict.REFUSED, pipeRead.verdict, "every segment parsed — and the arithmetic did not")
        assertTrue(pipeRead.why!!.contains("CheckSum(10)"), pipeRead.why!!)
        assertTrue(pipeRead.why!!.contains("123"), "and it says why a misread pipe moves the sum: ${pipeRead.why}")
    }

    /** A message read wrongly must never be *repaired* by search: the checksum says how many, never which. */
    @Test
    fun `a refused paste offers nothing to bind, however tempting the arithmetic looks`() {
        val paste = WirePaste.read(piped)

        assertNull(paste.wire, "there is no 'best effort' reading here, and there must not be one")
        assertEquals(0, paste.fields.size)
    }

    // ------------------------------------------------------------------------------- the fragment

    /**
     * A log *fragment* carries no `8=`/`10=` pair, so there is nothing in it that could confirm the delimiter.
     * That is not a refusal — it is the one case nothing can settle, and it is **said**. `PASTED` provenance
     * carries the doubt onward, to the badge and to disk.
     */
    @Test
    fun `a pipe-delimited fragment is bound, and told that nothing could verify its delimiter`() {
        val paste = WirePaste.read("35=8|11=ORD-1|150=2|39=2|")

        assertEquals(WirePaste.Verdict.UNVERIFIED, paste.verdict)
        assertTrue(paste.usable, "it is usable — a fragment is a perfectly ordinary thing to paste")
        assertEquals(4, paste.fields.size)
        assertTrue(paste.lint.contains("no CheckSum(10)"), paste.lint)
        assertTrue(paste.lint.contains("no way to tell"), paste.lint)
    }

    /** The same fragment in SOH needs no verifying: SOH cannot occur inside a value, so there is no question. */
    @Test
    fun `an SOH fragment is read, because SOH was never ambiguous`() {
        val paste = WirePaste.read("35=8${soh}11=ORD-1${soh}58=filled|in full$soh")

        assertEquals(WirePaste.Verdict.READ, paste.verdict)
        assertTrue(paste.lint.contains("unambiguous"), paste.lint)
        assertEquals("filled|in full", paste.fields.single { it.first == 58 }.second)
    }

    // ------------------------------------------------------------------------------- the anchor (S6)

    @Test
    fun `the anchor is the message's own SendingTime, and the sheet says so`() {
        val paste = WirePaste.read(wire)

        assertEquals(Instant.parse("2026-07-14T08:12:31.017Z"), paste.anchor)
        assertTrue(paste.anchorNote.contains("SendingTime(52)"), paste.anchorNote)
        assertTrue(paste.anchorNote.contains("not to the clock"), paste.anchorNote)
    }

    /** No moment in the bytes is not a broken paste. The rows go unjudged, and the sheet says that too. */
    @Test
    fun `a paste with no SendingTime is usable, and says its temporals will go unjudged`() {
        val paste = WirePaste.read("35=8|11=ORD-1|60=20260714-08:12:31|")

        assertTrue(paste.usable)
        assertNull(paste.anchor)
        assertTrue(paste.anchorNote.contains("unjudged, not failed"), paste.anchorNote)
    }

    // ------------------------------------------------------------------------------- the rest of the refusals

    @Test
    fun `two messages in the slot is a refusal, because which one is not FixTool's to choose`() {
        val paste = WirePaste.read(wire + wire)

        assertEquals(WirePaste.Verdict.REFUSED, paste.verdict)
        assertTrue(paste.why!!.contains("2 messages"), paste.why!!)
        assertTrue(paste.why!!.contains("not FixTool's to"), "and which one is not ours to pick: ${paste.why}")
    }

    @Test
    fun `a body length that disagrees with the bytes is a refusal`() {
        val paste = WirePaste.read(wire.replace("9=263", "9=999").let { corrupted -> corrupted })

        // The checksum goes first (it covers the length field too), and either way nothing is bound.
        assertEquals(WirePaste.Verdict.REFUSED, paste.verdict)
        assertNull(paste.wire)
    }

    @Test
    fun `an empty paste, and a paste with no field in it at all, are both refused in words`() {
        assertEquals(WirePaste.Verdict.REFUSED, WirePaste.read("   ").verdict)
        val prose = WirePaste.read("Hi — here's the message that failed, can you look?")
        assertEquals(WirePaste.Verdict.REFUSED, prose.verdict)
        assertTrue(prose.why!!.contains("tag=value") || prose.why!!.contains("`tag=value`"), prose.why!!)
    }
}
