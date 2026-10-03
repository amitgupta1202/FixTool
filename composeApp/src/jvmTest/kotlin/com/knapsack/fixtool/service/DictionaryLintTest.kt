package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dictionary-mismatch lint: tags a message carries that the loaded dictionary does not define
 * for its message type are named locally — instead of surfacing minutes later as a cryptic
 * counterparty reject (a venue-dialect-vs-standard-FIX44 QuoteRequest confusion seen live).
 */
class DictionaryLintTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    @Test
    fun `a well-formed grouped message has no unknown tags`() {
        val fields = listOf(
            35 to "R",
            131 to "QR-1",
            146 to "1",
            55 to "EUR/USD", // group-internal: known via the group's dictionary
            54 to "1",
            38 to "250000",
        )
        assertEquals(emptyList(), DictionaryLint.unknownTags(fields, dictionary))
    }

    @Test
    fun `a tag the dictionary does not define for the message type is named`() {
        // ExecType(150) belongs to ExecutionReport, not QuoteRequest.
        val fields = listOf(35 to "R", 131 to "QR-1", 150 to "2")
        val unknown = DictionaryLint.unknownTags(fields, dictionary)
        assertEquals(listOf(150), unknown)
        val text = DictionaryLint.describe(unknown, fields, dictionary)
        assertTrue(text.contains("150"), text)
        assertTrue(text.contains("in the loaded dictionary"), text)
    }

    @Test
    fun `header and trailer tags are never flagged`() {
        val fields = listOf(8 to "FIX.4.4", 9 to "100", 35 to "R", 34 to "2", 49 to "A", 56 to "B", 131 to "QR-1", 10 to "000")
        assertEquals(emptyList(), DictionaryLint.unknownTags(fields, dictionary))
    }

    /**
     * A FIX 5.0 application dictionary has an empty `<header/>` and `<trailer/>`: the session fields live in
     * the FIXT transport dictionary. Asking only the application dictionary called every one of them "not
     * defined", on every send.
     */
    @Test
    fun `header and trailer tags are never flagged on FIX 5 either`() {
        val raw =
            "8=FIXT.1.1|9=80|35=D|49=C|56=V|34=2|52=20260928-10:00:00|1128=9|11=ORD-1|55=IBM|54=1|38=100|40=1|" +
                "60=20260928-10:00:00|10=000|"
        val fields = FixMessageHelper.parseFixMessage(raw)

        assertEquals(emptyList(), DictionaryLint.unknownTags(fields, FixDictionaryAdapter.forVersion(FixVersion.FIX_5_0_SP2)))
    }

    /** A venue's own header field, declared in its FIXT file, is a header field to the lint and to the builder. */
    @Test
    fun `a header field the transport dictionary declares is neither flagged nor built into the body`() {
        val dir = Files.createTempDirectory("fixtool-lint-fixt").toFile()
        try {
            val app = File(dir, "FIX50SP2.xml").apply { writeText(resource("FIX50SP2.xml")) }
            val transport =
                File(dir, "FIXT11.xml").apply {
                    writeText(
                        resource("FIXT11.xml")
                            .replaceFirst("  </header>", "    <field name=\"VenueRoute\" required=\"N\"/>\n  </header>")
                            .replaceFirst("  </fields>", "    <field number=\"9999\" name=\"VenueRoute\" type=\"STRING\"/>\n  </fields>"),
                    )
                }
            val venue = FixDictionaryAdapter.fromFiles(app, transport)
            val raw = "35=D|9999=DESK-7|11=ORD-1|55=IBM|54=1|38=100|40=1|60=20260928-10:00:00|"

            assertEquals(emptyList(), DictionaryLint.unknownTags(FixMessageHelper.parseFixMessage(raw), venue))
            val message = raw.toQuickFixMessageManual(venue)
            assertEquals("DESK-7", message.header.getString(9999), "the venue's header field goes in the header")
            assertFalse(message.isSetField(9999), "and not in the body")
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/dictionaries/$name")!!.use { it.reader().readText() }

    @Test
    fun `no dictionary or unknown message type stays silent`() {
        val fields = listOf(35 to "ZZ", 9999 to "x")
        assertEquals(emptyList(), DictionaryLint.unknownTags(fields, null))
        assertEquals(emptyList(), DictionaryLint.unknownTags(fields, dictionary))
    }

    // ------------------------------------------------------- suppressing the validator's echo

    @Test
    fun `a complaint about the tags the lint already named is not repeated`() {
        val problem = "Validation error: Tag not defined for this message type, field=150"
        assertTrue(DictionaryLint.alreadyNamed(problem, listOf(150)))
    }

    @Test
    fun `an unknown tag does not swallow a complaint about a tag whose number starts with it`() {
        // The lint named tag 20. The validator is complaining about tag 200 — a different tag, and
        // the only thing wrong with the message that the send would have told anyone about.
        val problem = "Validation error: Tag not defined for this message type, field=200"
        assertFalse(
            DictionaryLint.alreadyNamed(problem, listOf(20)),
            "tag 20 swallowed a complaint about tag 200 — the send reported no problem at all",
        )
    }

    @Test
    fun `a complaint naming a tag the lint did not name survives`() {
        val problem = "Validation error: Required tag missing, field=44"
        assertFalse(DictionaryLint.alreadyNamed(problem, listOf(150, 20)))
    }

    @Test
    fun `a complaint naming no tag at all survives`() {
        val problem = "Validation error: Invalid message type"
        assertFalse(DictionaryLint.alreadyNamed(problem, listOf(150)))
    }
}
