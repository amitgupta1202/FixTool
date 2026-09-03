package com.knapsack.fixtool.ui

import org.junit.Test
import javax.swing.JEditorPane
import javax.swing.text.Element
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument
import kotlin.test.assertTrue

/**
 * **The user guide's own links work, and Swing can actually read it.**
 *
 * `help.html` is not rendered by a browser — it is loaded as a string into a Swing `JEditorPane`, whose
 * HTML support is roughly HTML 3.2, and jumped around by [scrollToAnchor]'s hunt for an `id` attribute in
 * the parsed document. A link that points at a section that was renamed, or an `id` the parser drops on
 * the floor, fails in the one way documentation must not: silently, in the reader's hands, printing
 * "Anchor not found" to a console nobody has open.
 *
 * So the test loads it the way the dialog does, through the real parser, and asks the real question.
 */
class HelpDocTest {
    private val html =
        HelpDocTest::class.java
            .getResourceAsStream("/help.html")!!
            .bufferedReader()
            .readText()

    /** Every `href="#…"` in the guide points at something the anchor hunt can find. */
    @Test
    fun `every internal link resolves to an id Swing can find`() {
        val ids = parsedIds()
        val targets =
            Regex("""href="#([^"]+)"""").findAll(html).map { it.groupValues[1] }.toSortedSet()
        val broken = targets.filterNot { it in ids }

        assertTrue(
            broken.isEmpty(),
            "the guide links to $broken, which no element carries as an id — the reader clicks and nothing " +
                "happens. Ids the parser did find: $ids",
        )
    }

    /**
     * The acceptor chapter, by the things it exists to say. Named individually rather than by word count:
     * each is a fact an author gets wrong at a cost, and each was undocumented until this chapter existed.
     */
    @Test
    fun `the acceptor chapter states the rules engine's load-bearing facts`() {
        val chapter = html.substringAfter("""id="acceptor-rules"""").substringBefore("""<h2 id=""")

        val claims =
            mapOf(
                "first match wins" to "first match wins",
                "conditions are ANDed" to "ANDed",
                "a step's delay is from the previous step" to "from the step before it",
                // Was "an edit needs a reconnect" until c858bae made saving reach live sessions. The
                // claim and this test agreed with each other for two releases while both were wrong,
                // which is the argument for pinning the fact rather than the sentence.
                "saving reaches a live session" to "Saving reaches a session that is already up",
                "a preset is where an empty acceptor starts" to "Starting from a preset",
                "one OrderID for the whole reply" to "\${req.uuid}",
                "an unreachable rule is named" to "never fires",
                "a step can be edited in the message editor" to "Editing a step in the message editor",
                "applying a step is not saving it" to "Applying is not saving",
            )
        val flat = chapter.flat()
        val missing = claims.filterValues { it.flat() !in flat }.keys

        assertTrue(missing.isEmpty(), "the acceptor chapter no longer says: $missing")
    }

    /**
     * The acceptor chapter's *stateful* half. The rules engine shipped stateless and grew a memory over
     * three slices; the chapter described only the stateless half for two releases, which is the drift
     * this pins. Each claim is a fact an author gets wrong at a cost.
     */
    @Test
    fun `the acceptor chapter states what the venue remembers`() {
        val chapter = html.substringAfter("""id="acceptor-rules"""").substringBefore("""<h2 id="trace"""")

        val claims =
            mapOf(
                "the book records and the rules decide" to "The book records; the rules decide",
                "an order is a fold over its own trail" to "computed on read",
                "whenOrder's four words" to "whenOrder",
                "the constraint reads the state before this message" to
                    "reads the state held <em>before</em> this message",
                "a reply carries the reason that chose it" to "carries the reason that chose it",
                "\${order.…} resolves per step as it is sent" to "resolve per step, as that step is sent",
                "a reference is never sent as an empty field" to "never sent as an empty field",
                "TargetCompID=* opts an acceptor into being a venue" to "becomes a <strong>venue</strong>",
                "refused logons are reported" to "Refused logons are reported",
                "Reply With… offers the venue's own shapes" to "Reply With&hellip;",
            )
        val flat = chapter.flat()
        val missing = claims.filterValues { it.flat() !in flat }.keys

        assertTrue(missing.isEmpty(), "the acceptor chapter no longer says: $missing")
    }

    /**
     * The scenarios chapter's multi-run half. A run set, an examples table and the batch CLI were each
     * reachable long before the guide mentioned them, and a feature nobody can find is one that did not
     * ship.
     */
    @Test
    fun `the scenarios chapter states how many runs are made and read back`() {
        val chapter = html.substringAfter("""id="assertions"""").substringBefore("""<h2 id="acceptor-rules"""")

        val claims =
            mapOf(
                "a single run is a set of one" to "A single run is a set of one",
                "the record on disk is the artifact" to "The record on disk is the artifact",
                "a record is written as each entry lands" to "as it lands",
                "focusing an entry is what publishes it" to "Clicking an entry is what publishes it",
                "entries isolate so a repeat cannot go falsely green" to "THIS_RUN",
                "the run slot is claimed once per set" to "claimed once per set",
                "a disabled menu item stays visible with its count" to "stays visible and disabled",
                "clearing the order book is the run boundary" to "Clear order book",
                "an examples column is seeded before setup runs" to "before setup runs",
                "accept-actual on an outline breaks the other rows" to "belongs to <em>all</em> the rows",
                "the batch CLI flags" to "--stop-on-failure",
                "a set is a job over the control surface" to "/scenarios/runs",
            )
        val flat = chapter.flat()
        val missing = claims.filterValues { it.flat() !in flat }.keys

        assertTrue(missing.isEmpty(), "the scenarios chapter no longer says: $missing")
    }

    /** The trace chapter, by what makes a trace different from a search box with a regex in it. */
    @Test
    fun `the trace chapter states what following an exchange does`() {
        val chapter = html.substringAfter("""id="trace"""")

        val claims =
            mapOf(
                "one click narrows every session pane" to "narrows <em>every</em> session pane",
                "the set grows as the venue mints new ids" to "grows live",
                "Esc restores each pane's own filter" to "comes back exactly as you left it",
                "the Ledger counts sessions as well as messages" to "sessions as well as messages",
                "nothing is hidden — ungrouped is counted" to "Ungrouped messages come last",
                "an undeclared venue id is why a trace stays in one session" to ".roles.json",
                "lanes put initiators and acceptors on opposite sides" to "never guessed from a CompID",
                "the venue under test is the space between the lanes" to
                    "the space between the lanes",
                "one arrow carries the hop time" to "one arrow carrying the hop time",
            )
        val flat = chapter.flat()
        val missing = claims.filterValues { it.flat() !in flat }.keys

        assertTrue(missing.isEmpty(), "the trace chapter no longer says: $missing")
    }

    /**
     * The guide is hand-wrapped at ~110 columns, so a pinned phrase is regularly split across a newline
     * and several spaces of indent. Matching on the raw text would then fail for a pure reflow — a
     * "the chapter no longer says" that is really "the chapter was re-indented", which teaches the next
     * author to weaken the claim rather than fix the wrap. Collapse runs of whitespace on both sides and
     * the assertion is about the words.
     */
    private fun String.flat(): String = replace(Regex("""\s+"""), " ")

    /** Loaded exactly as `HelpDialog` loads it, so the ids under test are the ones the app can reach. */
    private fun parsedIds(): Set<String> {
        val pane = JEditorPane("text/html", html)
        val doc = pane.document as HTMLDocument
        val found = mutableSetOf<String>()

        fun walk(element: Element) {
            element.attributes.getAttribute(HTML.Attribute.ID)?.let { found += it.toString() }
            for (i in 0 until element.elementCount) walk(element.getElement(i))
        }
        walk(doc.defaultRootElement)
        return found
    }
}
