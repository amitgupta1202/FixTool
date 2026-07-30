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
        val missing = claims.filterValues { it !in chapter }.keys

        assertTrue(missing.isEmpty(), "the acceptor chapter no longer says: $missing")
    }

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
