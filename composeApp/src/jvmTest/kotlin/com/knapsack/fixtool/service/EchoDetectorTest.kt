package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.TagRole
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EchoDetector] — the ids found by watching a value come back rather than by being told.
 *
 * The two things the dictionary scan cannot do are the two things pinned hardest here: an id whose **name
 * says nothing**, and a **venue-minted** id a later send quotes back (which needs to know which reply
 * minted it, and which no amount of reading a dictionary can answer).
 */
class EchoDetectorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var n = 0

    private fun dictionary(rolesJson: String? = null): FixDictionaryAdapter {
        val stem = "echo-${n++}"
        val xml = temp.newFile("$stem.xml")
        xml.writeText(
            """
            <fix major="4" minor="4" type="FIX">
              <header><field name="BeginString" required="Y"/></header>
              <trailer><field name="CheckSum" required="Y"/></trailer>
              <messages>
                <message name="QuoteRequest" msgtype="R" msgcat="app">
                  <field name="QuoteReqID" required="N"/>
                </message>
              </messages>
              <fields>
                <field number="8" name="BeginString" type="STRING"/>
                <field number="10" name="CheckSum" type="STRING"/>
                <field number="35" name="MsgType" type="STRING"/>
                <field number="48" name="SecurityID" type="STRING"/>
                <field number="60" name="TransactTime" type="UTCTIMESTAMP"/>
                <field number="131" name="QuoteReqID" type="STRING"/>
                <field number="20013" name="LegQuoteReqID" type="STRING"/>
                <field number="20050" name="Note1" type="STRING"/>
                <field number="20099" name="VenueDealHandle" type="STRING"/>
              </fields>
            </fix>
            """.trimIndent(),
        )
        rolesJson?.let { temp.newFile("$stem.xml.roles.json").writeText(it) }
        return FixDictionaryAdapter.fromFile(xml)
    }

    private fun out(type: String, vararg f: Pair<Int, String>) = candidate(true, type, *f)

    private fun inn(type: String, vararg f: Pair<Int, String>) = candidate(false, type, *f)

    private var clock = 0

    private fun candidate(outgoing: Boolean, type: String, vararg f: Pair<Int, String>) =
        ScenarioCapture.Candidate(
            session = "Venue",
            direction = if (outgoing) FixMessage.Direction.OUTGOING else FixMessage.Direction.INCOMING,
            messageType = type,
            wire = f.joinToString("") { "${it.first}=${it.second}" },
            timestamp = LocalDateTime.of(2026, 7, 21, 12, 0, clock++),
            fields = listOf(35 to type) + f.toList(),
            source = null,
        )

    // ----- MINT: we said it first -----------------------------------------------------------------------

    @Test
    fun `a value we sent that comes back is proposed as ours to mint`() {
        val flow =
            listOf(
                out("R", 20013 to "LEG-A7F3C201"),
                inn("S", 20013 to "LEG-A7F3C201"),
            )

        val p = EchoDetector.detect(flow, dictionary()).single()

        assertEquals(EchoDetector.Kind.MINT, p.kind)
        assertEquals(TagRole.CLIENT_MINTED_ID, p.role)
        assertEquals(listOf(20013), p.tags)
        assertEquals("legQuoteReqID", p.suggestedName)
        assertTrue("35=R" in p.evidence && "35=S" in p.evidence, p.evidence)
    }

    /** The case option B cannot reach: the dictionary names it, and the name says nothing. */
    @Test
    fun `an id whose name says nothing is still found, because the value came back`() {
        val flow =
            listOf(
                out("R", 20050 to "REF-88213311"),
                inn("S", 20050 to "REF-88213311"),
            )

        val p = EchoDetector.detect(flow, dictionary()).singleOrNull()

        // `Note1` is not id-shaped, so the scan would file it under OTHER, 110 tags deep. The echo finds it.
        assertEquals(20050, p?.tags?.single())
        assertEquals(EchoDetector.Kind.MINT, p?.kind)
    }

    @Test
    fun `an echo in a different tag declares both tags`() {
        val flow =
            listOf(
                out("R", 20013 to "LEG-A7F3C201"),
                inn("S", 20099 to "LEG-A7F3C201"),
            )

        assertEquals(listOf(20013, 20099), EchoDetector.detect(flow, dictionary()).single().tags)
    }

    // ----- CAPTURE: they said it first ------------------------------------------------------------------

    @Test
    fun `a venue value we quote back is proposed as theirs to capture`() {
        val flow =
            listOf(
                inn("S", 20099 to "VENUE-DEAL-4471"),
                out("Z", 20099 to "VENUE-DEAL-4471"),
            )

        val p = EchoDetector.detect(flow, dictionary()).single()

        assertEquals(EchoDetector.Kind.CAPTURE, p.kind)
        assertEquals(TagRole.VENUE_MINTED_ID, p.role)
        assertTrue("the venue's" in p.evidence, p.evidence)
    }

    // ----- the gates ------------------------------------------------------------------------------------

    @Test
    fun `an ordinary small value is coincidence, not correlation`() {
        val flow = listOf(out("D", 20050 to "1"), inn("8", 20050 to "1"))
        assertTrue(EchoDetector.detect(flow, dictionary()).isEmpty())
    }

    @Test
    fun `a sequence-shaped number is not an id`() {
        val flow = listOf(out("D", 20050 to "10000042"), inn("8", 20050 to "10000042"))
        assertTrue(EchoDetector.detect(flow, dictionary()).isEmpty())
    }

    /** The highest-cost false positive: minting a fresh SecurityID sends an instrument that does not exist. */
    @Test
    fun `an echoed instrument id is never proposed`() {
        val flow = listOf(out("D", 48 to "US912828XG84"), inn("8", 48 to "US912828XG84"))
        assertTrue(EchoDetector.detect(flow, dictionary()).isEmpty())
    }

    /** Free text clears length and character-class checks easily; whitespace is what rules it out. */
    @Test
    fun `echoed free text is not an id`() {
        val flow =
            listOf(
                out("D", 20050 to "please book against ref 12"),
                inn("8", 20050 to "please book against ref 12"),
            )
        assertTrue(EchoDetector.detect(flow, dictionary()).isEmpty())
    }

    @Test
    fun `an echoed timestamp is not an id`() {
        val flow = listOf(out("D", 60 to "20260721-12:00:00.000"), inn("8", 60 to "20260721-12:00:00.000"))
        assertTrue(EchoDetector.detect(flow, dictionary()).isEmpty())
    }

    @Test
    fun `a tag already answered is not proposed again`() {
        // Standard: QuoteReqID is in ID_TAGS and capture already handles it.
        val standard = listOf(out("R", 131 to "QR-A7F3C201"), inn("S", 131 to "QR-A7F3C201"))
        assertTrue(EchoDetector.detect(standard, dictionary()).isEmpty())

        // Declared: the author has answered, and re-asking teaches them to dismiss the list.
        val declaredFlow = listOf(out("R", 20013 to "LEG-A7F3C201"), inn("S", 20013 to "LEG-A7F3C201"))
        assertTrue(EchoDetector.detect(declaredFlow, dictionary("""{"20013":"CLIENT_MINTED_ID"}""")).isEmpty())
    }

    @Test
    fun `a value that never comes back is not evidence of anything`() {
        val flow = listOf(out("R", 20013 to "LEG-A7F3C201"), inn("S", 20013 to "LEG-DIFFERENT99"))
        assertTrue(EchoDetector.detect(flow, dictionary()).isEmpty())
    }

    @Test
    fun `a single message can produce nothing, by construction`() {
        assertTrue(EchoDetector.detect(listOf(out("R", 20013 to "LEG-A7F3C201")), dictionary()).isEmpty())
    }

    /** A value under three tags of one message is an account or a desk, not a correlation id. */
    @Test
    fun `a repeated constant is not a correlation id`() {
        val flow =
            listOf(
                out("D", 20013 to "DESK-LONDON-1", 20050 to "DESK-LONDON-1", 20099 to "DESK-LONDON-1"),
                inn("8", 20013 to "DESK-LONDON-1"),
            )
        assertTrue(EchoDetector.detect(flow, dictionary()).isEmpty())
    }

    // ----- what accepting one produces ------------------------------------------------------------------

    /**
     * The MINT half rides entirely on machinery that already exists: declaring the role is the whole
     * change, and capture does the rest.
     */
    @Test
    fun `accepting a MINT proposal makes capture mint, reference and bind`() {
        val flow = listOf(out("R", 20050 to "REF-88213311"), inn("S", 20050 to "REF-88213311"))
        val declared = dictionary("""{"20050":"CLIENT_MINTED_ID"}""")

        val scenario = ScenarioCapture.captureFrom("x", "x", null, flow, declared)

        val send = scenario.steps.filterIsInstance<ScenarioStep.Send>().single()
        assertTrue(send.raw.contains("20050=\${note1 = uuid:20}"), send.raw)
        val expect = scenario.steps.filterIsInstance<ScenarioStep.Expect>().single()
        val row = expect.expectation.fields.single { it.tag == 20050 }
        assertEquals(Matcher.Reference("\${note1}"), row.matcher)
        val binds = expect.match?.fields ?: emptyList()
        assertEquals("\${note1}", binds.single { it.tag == 20050 }.value)
    }

    /**
     * The CAPTURE half is the one that needed new engine work. Replayed as a literal, the second run asks
     * the venue to act on an id from the day of the capture — so the reply's value is READ into scope and
     * the later send quotes *this run's*.
     */
    @Test
    fun `accepting a CAPTURE proposal reads the venue's value and sends this run's`() {
        val flow =
            listOf(
                inn("S", 20099 to "VENUE-DEAL-4471"),
                out("Z", 20099 to "VENUE-DEAL-4471"),
            )
        val declared = dictionary("""{"20099":"VENUE_MINTED_ID"}""")

        val scenario = ScenarioCapture.captureFrom("x", "x", null, flow, declared)

        val expect = scenario.steps.filterIsInstance<ScenarioStep.Expect>().single()
        val row = expect.expectation.fields.single { it.tag == 20099 }
        assertEquals("venueDealHandle", row.bindAs)
        // Reading a value is not asserting one: the matcher stays what the role seeded.
        assertEquals(Matcher.Presence, row.matcher)

        val send = scenario.steps.filterIsInstance<ScenarioStep.Send>().single()
        assertTrue(send.raw.contains("20099=\${venueDealHandle}"), send.raw)
        assertTrue("VENUE-DEAL-4471" !in send.raw, send.raw)
    }

    /** Standard FIX gets the same treatment — OrderID(37) on a cancel was replaying last week's id. */
    @Test
    fun `a standard venue id quoted back is captured too, with no overlay at all`() {
        val flow =
            listOf(
                inn("8", 37 to "VENUE-ORD-99182"),
                out("F", 37 to "VENUE-ORD-99182"),
            )

        val standard = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
        val scenario = ScenarioCapture.captureFrom("x", "x", null, flow, standard)

        val expect = scenario.steps.filterIsInstance<ScenarioStep.Expect>().single()
        val bound = expect.expectation.fields.single { it.tag == 37 }
        assertEquals("orderID", bound.bindAs)
        val send = scenario.steps.filterIsInstance<ScenarioStep.Send>().single()
        assertTrue(send.raw.contains("37=\${orderID}"), send.raw)
    }

    /** A venue id we merely receive is not a capture — nothing sends it back, so nothing needs it. */
    @Test
    fun `a venue id that is never quoted back stays a plain presence row`() {
        val flow = listOf(out("D", 20013 to "LEG-A7F3C201"), inn("8", 37 to "VENUE-ORD-99182"))

        val scenario =
            ScenarioCapture.captureFrom("x", "x", null, flow, FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4))

        val expect = scenario.steps.filterIsInstance<ScenarioStep.Expect>().single()
        val unbound = expect.expectation.fields.single { it.tag == 37 }
        assertNull(unbound.bindAs)
    }
}
