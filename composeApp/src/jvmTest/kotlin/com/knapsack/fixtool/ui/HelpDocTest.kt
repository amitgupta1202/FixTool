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
                // The list is read far more often than it is written, and everything below is
                // about reading it: the number a card is addressed by everywhere else, the one line it
                // folds to, and the mark that answers "which rule answered me?".
                "a card prints the number every other surface names it by" to "sent by rule 7",
                "a closed card is the rule in a line" to "3 steps over 500ms",
                "a rule with no conditions is the catch-all" to "any 35=D",
                "the rule that just answered marks itself" to "fired 09:14:22.418",
                "the mark is withheld when it could be wrong" to "withheld rather than guessed",
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
     * The acceptor chapter's *quote* half, pinned the same way and for the same reason: the venue grew a
     * second memory, and each claim below is a fact whose absence turns a venue back into a message echo.
     */
    @Test
    fun `the acceptor chapter states what the venue remembers about its quotes`() {
        val chapter = html.substringAfter("""id="acceptor-rules"""").substringBefore("""<h2 id="trace"""")

        val claims =
            mapOf(
                "a quote is born by a message the venue sends" to "born by a message the client sends",
                "expiry is a clock comparison, not a stored flag" to "a clock comparison, made when a rule asks",
                "the venue's answer echoes 693, not 117" to "<strong>693 and not 117</strong>",
                "a booked quote hit is kept off the order book" to "not</strong> offered to the order book",
                "clearing the book clears both" to "empties both",
                "whenQuote's four words" to "whenQuote",
                "nothing on the message tells the four cases apart" to
                    "Nothing on the incoming message distinguishes these four cases",
                "quoteField compares against the quote's own value" to "quoteField",
                "an unresolvable quoteField is false" to "<strong>false</strong>",
                "quoteField is refused in a scenario" to "refused in a scenario by name",
                "\${quote.…} is never sent as an empty field" to "never sent as an empty field",
                "a reply reading the quote needs a whenQuote" to "will not validate",
                "no trigger can mint the quote it reads" to "no trigger can mint the quote it reads",
                "the dry run takes a quote state" to "quoteState",
            )
        val flat = chapter.flat()
        val missing = claims.filterValues { it.flat() !in flat }.keys

        assertTrue(missing.isEmpty(), "the acceptor chapter no longer says: $missing")
    }

    /**
     * The RFQ example, by the four things a reader has to know before they can drive it. Every one of
     * them was a different fact in the first slice of that venue, which is why they are pinned rather
     * than left to whoever last edited the chapter.
     */
    @Test
    fun `the RFQ example chapter states what the venue does now, not what it used to`() {
        val chapter = html.substringAfter("""id="rfq-venue"""").substringBefore("""<h3>Closing the Workspace""")

        val claims =
            mapOf(
                "the QuoteID is opaque" to "The QuoteID is opaque",
                "each side is drawn from a band" to "drawn per quote from its own band",
                "no draw can invert the quote" to "no draw can put a bid at or above an offer",
                "a quote stands for thirty seconds" to "thirty seconds",
                "the memory answers first" to "the venue's memory answering",
                "the shipped set is named" to "RFQ round trip",
                "the set is run by name from the CLI" to "--set rfq-round-trip",
                "the capture names are the ones the templates read" to "\${quoteId}",
                "an uncaptured name is refused before the run starts" to "refused before the run starts",
                "the generators are why this venue can be loaded" to "render natively",
            )
        val flat = chapter.flat()
        val missing = claims.filterValues { it.flat() !in flat }.keys

        assertTrue(missing.isEmpty(), "the RFQ example chapter no longer says: $missing")
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

    /**
     * The load-sets chapter's *reactive* half, pinned the way the acceptor chapter's is: by the facts an
     * author gets wrong at a cost, not by the sentences that carry them.
     *
     * The costly ones are all about expectation. Somebody who reads "reactive" as "faster" will run one to
     * shorten a soak and find it took exactly as long. Somebody who reads a cap as a rate will read every
     * healthy run as red. And a phase 1 that cannot be reactive is the first thing anybody tries.
     */
    @Test
    fun `the load sets chapter states what a reactive phase is for`() {
        val chapter = html.substringAfter("""id="load-sets"""").substringBefore("""<h3 id="quick-connect"""")

        val claims =
            mapOf(
                "a reactive phase fires per reply, as the reply lands" to
                    "one message for each reply the phase it reacts to is given, as that reply lands",
                "it is not a way to make a set faster" to "Reactive does not make a set faster",
                "what it buys is the chain, measured end to end" to "the round trip leg by leg",
                "the count and the indices are the trigger's" to "Its count and its message indices",
                "phase 1 can never be reactive" to "Phase 1 can never be reactive",
                "a sibling's captures are out of reach" to "never a sibling's captures",
                "a trigger that never fired leaves a message unsent" to "unaddressable",
                // The one that turns every healthy reactive run red if it is read as a rate.
                "a cap is a ceiling and being under it is not a shortfall" to "never a shortfall",
                "so a capped phase gets its own verdict" to "CAPPED",
                "the third option is on the Shape segment, from phase 2 on" to "from the second",
                "the picker names a phase the way the refusals do" to "1 · Ask for a quote",
                "the file spells it triggered, beside after" to "\"kind\": \"triggered\"",
                "the CLI needs no flag for it" to "runs it with no flag of its own",
            )
        val flat = chapter.flat()
        val missing = claims.filterValues { it.flat() !in flat }.keys

        assertTrue(missing.isEmpty(), "the load sets chapter no longer says: $missing")
    }

    /**
     * **What a run does about the sessions it needs**, pinned as facts because every one of them is a thing
     * a reader would otherwise do by hand, or wait for and not understand.
     *
     * The costly misreadings are all about scope. Somebody who thinks Run connects *everything* will not
     * trust it near a production box; somebody who thinks it reconnects a profile that is already up will
     * not either. And a run that seems to hang for ten seconds against a dead venue is a bug report unless
     * the wait is written down.
     */
    @Test
    fun `the load sets chapter states what a run brings up before it dials`() {
        val chapter = html.substringAfter("""id="load-sets"""").substringBefore("""<h3 id="quick-connect"""")

        val claims =
            mapOf(
                "a run connects the profiles it names" to "brings up the sessions it names",
                "issuing and listening profiles both" to "issues from or listens on",
                "acceptors are bound before anything dials them" to "acceptors first",
                "the wait is bounded, and how long" to "up to ten seconds",
                "it only touches what would have been refused" to "only where the run would have been refused",
                "a profile already up is left alone" to "left exactly as it is",
                "a parked phase's profile is not opened" to "neither opened nor waited for",
                "a venue that never answers is still a refusal" to "reached LOGGED_ON",
                "the menu row says what it will connect" to "which of them pressing it will connect",
                "the lanes stay up afterwards" to "left up afterwards",
                // The example workspaces are the case: the set names the client and the client dials us.
                "our own venue comes up too, and first" to "when the venue is one of ours",
            )
        val flat = chapter.flat()
        val missing = claims.filterValues { it.flat() !in flat }.keys

        assertTrue(missing.isEmpty(), "the load sets chapter no longer says: $missing")
    }

    /**
     * **Every number the report prints has a definition somewhere.**
     *
     * The run document has no tooltips — a figure there is a label and a number, and nothing in the
     * product says what it means. So the guide is the only place `peak outstanding` or `strays` is
     * defined, and a reader looking at a finished report has nowhere else to go. Each claim below is one
     * that changes what somebody concludes from a run: whether a figure failed it, what it was measured
     * between, and which of two figures that sound alike they are looking at.
     */
    @Test
    fun `the load report chapter defines every figure it prints`() {
        val chapter = html.substringAfter("""id="load-report"""").substringBefore("""id="load-sets"""")

        val claims =
            mapOf(
                "answered is the first reply, and what the round trip is measured to" to
                    "That first reply is also what the round trip is measured to",
                "unanswered is the bar" to "anything above zero fails the run",
                "unaddressable is the tool never asking, not the venue not replying" to
                    "the tool never asked",
                "issued is three numbers" to "Three numbers, and it stays three numbers",
                "and completeness is judged over the last of them" to "judged over the last of the three",
                "a duplicate is a second reply for an id already matched" to "had <em>already</em> been matched",
                "and is never judged, because one order draws several reports" to
                    "several ExecutionReports",
                "late is after the settle window, and answers nothing" to "its request stays unanswered",
                "a stray is somebody else's traffic on a listening session" to "another client's traffic",
                "peak outstanding is what the venue was asked to hold at once" to "waiting for an answer at any one moment",
                "the round trip is socket to socket" to "socket send stamp to socket\n        receive stamp",
                "drain is the venue still working after the tool stopped" to "after the tool stopped asking",
                "the lane table exists to find the one bad lane" to "one lane is much worse than the rest",
                "the tool's own figures fail the run" to "Any of these\n        above zero fails the run",
                "and what to do about discards" to "Raise the session buffer",
                "a cap is never a failure" to "CAPPED is never a failure",
                "a rate shortfall only fails on request" to "strict-rate",
                "exit 2 means it never started" to "the run never started",
            )
        val flat = chapter.flat()
        val missing = claims.filterValues { it.flat() !in flat }.keys

        assertTrue(missing.isEmpty(), "the load report chapter no longer says: $missing")
    }

    /**
     * **A figure is explained on the report and in the guide, or in neither.**
     *
     * The two exist for different readers: the tooltip is for somebody looking at a number now, the guide
     * for somebody deciding what to measure or reading a record months later. A figure that has one and
     * not the other leaves one of them stranded — which is exactly the state the report was in, with a
     * chapter of prose and not a single tooltip.
     */
    @Test
    fun `every figure the report explains on hover is explained in the guide too`() {
        val chapter = html.substringAfter("""id="load-report"""").substringBefore("""id="load-sets"""").lowercase()

        val missing = LoadGlossary.terms.filterNot { it.lowercase() in chapter }

        assertTrue(
            missing.isEmpty(),
            "the report defines $missing on hover, and the guide's report chapter never names them",
        )
    }

    /**
     * **Every field the run dialog asks for is written down, with the flag that answers it headless.**
     *
     * A parameter a reader cannot look up is one they leave at its default for ever. The two that decide
     * whether the numbers mean anything — the settle window and the store — are the ones worth pinning
     * hardest, because both are wrong by default for measuring a venue rather than a disk.
     */
    @Test
    fun `the load parameters chapter names every field and its command-line flag`() {
        val chapter = html.substringAfter("""id="load-parameters"""").substringBefore("""id="load-report"""")

        val claims =
            mapOf(
                "the template is required" to "The message to issue",
                "the profile that issues, and what a lane is" to "Every session it\n                has logged on is a lane",
                "a one-session profile is one lane" to "opens one session is one lane",
                "burst and rate" to "issues a count as fast as the lanes carry it",
                "the settle window, by that name" to "settle window",
                "nothing is given up before it closes" to "Nothing is given up on before it closes",
                "the match pair, and that a template with no id is refused" to "carries none is refused",
                "listening sessions match as fully as issuing ones" to "matched wherever it\n                lands",
                "capture is what makes a set work against a venue that mints ids" to "mints its own ids",
                "the seed is rendered once" to "rendered once when the run starts",
                "the store is why the numbers measure the venue" to "rather than FixTool's file appends",
                "and that memory needs reset on logon" to "memory store needs Reset on Logon",
                "the flags that exist only on the command line" to "--strict-rate",
            )
        val flat = chapter.flat()
        val missing = claims.filterValues { it.flat() !in flat }.keys

        assertTrue(missing.isEmpty(), "the load parameters chapter no longer says: $missing")
    }

    /**
     * **The two whole-window session buttons, and the one difference between them.**
     *
     * A reader who takes Close all for a louder Disconnect all loses a pane's messages finding out. The
     * facts that stop that are what it takes away, that it asks, and that the asking is the button itself
     * rather than a dialog — a second click nobody expects is worse than no confirmation at all.
     */
    @Test
    fun `the toolbar chapter states what Close all takes away and that it asks`() {
        val chapter = html.substringAfter("""id="quick-connect"""").substringBefore("""<h3 id="workspaces"""")

        val claims =
            mapOf(
                "disconnect all asks nothing, because nothing is lost" to "It asks no confirmation",
                "close all closes the panes" to "closes every session pane",
                "and disconnects them on the way" to "disconnecting them on the way",
                "it counts panes, not connections" to "counts panes rather than connections",
                "close all does ask" to "This one does ask",
                "and the second click is the confirmation" to "the second closes them",
                "what it costs is the messages" to "nothing returns a pane's messages",
                "and nothing else" to "run records and load reports are untouched",
                "a live run refuses both" to "refuses it in the same words",
                "and there is a door for a script" to "POST /sessions/close",
            )
        val flat = chapter.flat()
        val missing = claims.filterValues { it.flat() !in flat }.keys

        assertTrue(missing.isEmpty(), "the toolbar chapter no longer says: $missing")
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
     * The workspace chapter, by the facts that decide whether a reader loses work.
     *
     * A workspace is where a user's profiles and scenarios now live, so every claim here is one that
     * costs something to get wrong: that Default is the old `~/.fixtool` and nothing moved on upgrade,
     * that opening an example twice is not how you get a clean one, that Reset renames rather than
     * deletes, and that Close leaves the copy on disk. This chapter shipped with the feature; the pin
     * is so it stays shipped. See the acceptor chapter's note on pinning facts rather than sentences.
     */
    @Test
    fun `the settings chapter states what a workspace is and what it takes with it`() {
        val chapter = html.substringAfter("""id="workspaces"""").substringBefore("""<h2 id="viewing-messages"""")

        val claims =
            mapOf(
                "a workspace is a folder, and there is one kind" to "There is one",
                "New makes an empty one and Open opens a folder or an example" to
                    "<strong>New</strong> makes an empty one",
                "the switcher is at the top left, not in Quick Connect" to "switcher at the top left",
                "Default is ~/.fixtool and nothing moved on upgrade" to "nothing had to move on upgrade",
                "Default cannot be closed and is not in Recent" to "never appears in Recent",
                "an example is copied out because the bundle cannot be edited in place" to
                    "editing the installed app",
                // The bug d3b7255 fixed, and the one a reader is most likely to walk into: a second
                // Open is not how you get a pristine copy, and believing it silently abandons the first.
                "the copy happens once, so opening it again is not a clean one" to
                    "The copy happens <strong>once</strong>",
                "provenance is the .fixtool-origin file" to ".fixtool-origin",
                "Reset renames the old copy rather than deleting it" to
                    "<em>renamed</em> rather than deleted",
                "preferences stay with the person, not the workspace" to "not a fresh install",
                "passwords are in secrets.json, and that is separation not encryption" to
                    "This is a separation, not encryption",
                "FIXTOOL_WORKSPACE moves the installation" to "FIXTOOL_WORKSPACE",
                "an environment is where a counterparty is, not who it is" to
                    "as distinct from who it is",
                // Load-bearing: two environments on one counterparty would otherwise share a
                // sequence-number store, which is a corruption a user cannot see coming.
                "the session qualifier is the environment's name" to
                    "The session qualifier is the environment's name",
                "environments are off until Extract environments is used" to "Extract environments",
            )
        val flat = chapter.flat()
        val missing = claims.filterValues { it.flat() !in flat }.keys

        assertTrue(missing.isEmpty(), "the workspace/environments chapter no longer says: $missing")
    }

    /**
     * The example chapter, by what a first-run reader has to be told.
     *
     * It is the chapter a new user reads first and a demo uses exclusively, and it names UI the release
     * can rename underneath it: the guide told readers to look for an "Open example…" menu item for a
     * release after that item was folded into Open workspace, which is precisely the failure this pins.
     */
    @Test
    fun `the example chapter states how to open it and what it lands as`() {
        val chapter = html.substringAfter("""id="demo-server"""").substringBefore("""<h2 id="connections"""")

        val claims =
            mapOf(
                "the example is listed under Open workspace" to "<strong>Open workspace</strong>",
                "the empty-state button is named as the app names it" to "Open FX Venue example",
                "it lands in workspaces/fx-venue" to "~/.fixtool/workspaces/fx-venue",
                "opening it again returns the copy with your edits" to "the copy happens once",
                "a pristine one means renaming or deleting the folder" to "rename or delete the folder",
                "nothing is mixed in, so there is nothing to uninstall" to "nothing to uninstall",
                "the venue is open to any CompID on 19876" to "open to any CompID",
                "the venue's own pane starts minimized" to "starts <strong>minimized</strong>",
                "a venue pane holds no traffic of its own" to "It holds no traffic of its own",
                // Minimizing must never retarget a composed order: the chip says where Send goes.
                "a minimized session stays connected and stays the send target" to "&rarr; editor",
                "minimizing is not closing" to "This is not closing",
                "a healthy venue's chip stays quiet" to "stays quiet while the venue is healthy",
            )
        val flat = chapter.flat()
        val missing = claims.filterValues { it.flat() !in flat }.keys

        assertTrue(missing.isEmpty(), "the example chapter no longer says: $missing")
    }

    /**
     * The two corrections that actively mislead when wrong, rather than merely being absent.
     *
     * The guide told authors to filter on `ClOrdID=…`, which cannot match — the pattern is run against
     * the raw message, where fields are tag numbers. And the offset table stopped at `h`/`d`/`w`/`m`/`y`,
     * so `${now+5m}` reads as five minutes to anyone who has not read the expander: it is five months.
     * Both produce a wrong result that looks like a working feature, which is why they are pinned.
     */
    @Test
    fun `the guide does not mislead about filters or time units`() {
        val flat = html.flat()

        val claims =
            mapOf(
                "the filter matches tag numbers, not field names" to "tag <em>numbers</em>, not",
                "the wrong spelling is named" to "<code>ClOrdID=ORDER.*</code> matches nothing",
                "a bare m is months" to "is months, not minutes",
                "minutes are spelled min" to "\${now+5min}",
                "utcnow exists for UTCTimestamp fields" to "\${utcnow}",
                "and why local time is wrong there" to "TransactTime(60)",
                // Each of these is a generator an author cannot use without being told it exists, and
                // each shipped before the guide named it.
                "a trimmed uuid" to "\${uuid:12}",
                "seconds, which a quote's validity needs" to "\${utcnow+30s}",
                "a clock in a pattern of your own" to "\${utcnow:yyyyMMdd}",
                "a drawn number, for a venue that prices its own quotes" to "\${random:1.09010:1.09019:5}",
            )
        val missing = claims.filterValues { it.flat() !in flat }.keys

        assertTrue(missing.isEmpty(), "the guide no longer says: $missing")
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
