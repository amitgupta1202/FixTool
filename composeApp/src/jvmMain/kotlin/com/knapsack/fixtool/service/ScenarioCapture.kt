package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.MatchOp
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepOrigin
import com.knapsack.fixtool.model.scenario.TagValue
import com.knapsack.fixtool.service.compare.WirePaste

/**
 * Records a live session flow into a replayable [Scenario]. The flow can span **multiple sessions**
 * (e.g. an RFQ: QuoteRequest out, Quote back — possibly correlated across a quote and a trade
 * session): messages are merged chronologically and each step keeps its own `session`.
 *
 * - Outgoing application messages become [ScenarioStep.Send] steps; volatile fields are
 *   parameterized so each replay is fresh: `TransactTime` → `${...now...}`, and correlation IDs
 *   (ClOrdID, QuoteReqID, …) → a fresh **scenario variable** (`${id0 = UUID.randomUUID()}`).
 * - Incoming application messages become [ScenarioStep.Expect] steps with a **smart-seeded**
 *   expectation (timestamps→temporal, prices→numeric, IDs→presence, exact otherwise), and any tag
 *   whose captured value echoes a value we sent is auto-wired to a **reference matcher** on that
 *   scenario variable (`${id0}`) — so the echo is verified against each run's fresh id, **even when
 *   the response comes back on a different session** (the variable scope is scenario-wide).
 * - Admin messages (Logon/Heartbeat/…) are skipped; `setup` clears each session for a clean start.
 */
object ScenarioCapture {
    /** Session/admin message types that are not part of the business flow. */
    private val ADMIN_MSG_TYPES = setOf("0", "1", "2", "3", "4", "5", "A")

    /** Transport/session header+trailer tags the framework re-stamps on send; dropped from Send raw. */
    private val TRANSPORT_TAGS = SessionTags.REWRITTEN_ON_SEND

    /** Client-originated correlation id tags: parameterized on send, echo-matched on responses. */
    private val ID_TAGS = setOf(11, 41, 131, 526, 583)

    /** A session's title + the messages observed on it. */
    data class CapturedSession(val title: String, val messages: List<FixMessage>)

    /**
     * The name a capture wears until the author gives it one. Timestamped, because the fixed string this
     * used to be ("Captured scenario") made every unnamed capture a twin of the last — a store full of
     * files nothing but a step count could tell apart.
     */
    fun defaultName(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String =
        "Capture " + now.format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm:ss"))

    /**
     * One business message that capture *would* turn into a step: the row unit of the capture-review screen,
     * where the author curates the selection before anything is saved.
     *
     * **It carries its own direction, type and bytes** rather than reading them off a [FixMessage], because
     * capture has a second source now and a paste has no `FixMessage` behind it. Synthesising one to fill the
     * field would put a message in the grid's selection that never arrived on any wire — the same lie in a
     * smaller font — so [source] is simply **null for a paste**, and a pasted row has no source to highlight.
     */
    data class Candidate(
        val session: String,
        /**
         * **Null means the bytes do not say, and nobody has said either.**
         *
         * A live capture knows: it arrived on a wire, in a direction. A paste does not, and it may not be
         * *guessed* — a reply mis-marked as a Send becomes a step that **asserts nothing**, the scenario sends
         * the venue's own ExecutionReport back at it, and the step "passes" (a Send always does). That is a
         * false green by omission. So an undirected row is refused a save, by name. See [directionFrom].
         */
        val direction: FixMessage.Direction?,
        val messageType: String,
        /** The bytes, SOH-delimited: a live message's `wireRaw`, or a paste's, **as read** (never guessed). */
        val wire: String,
        val timestamp: java.time.LocalDateTime,
        val fields: List<Pair<Int, String>>,
        /** The grid row this came from. **Null for a paste**, which has no source message to highlight. */
        val source: FixMessage? = null,
    ) {
        val pasted: Boolean get() = source == null

        val outgoing: Boolean get() = direction == FixMessage.Direction.OUTGOING
    }

    /**
     * What a capture found: the rows it can offer, and the messages it had to leave out.
     *
     * [unreadable] exists so that leaving a message out is a thing the author is *told*, not a thing that
     * happens. A capture is a claim about coverage — "these are the messages in your flow" — and a silent
     * omission turns a scenario that checks four of five replies into one that looks complete.
     */
    data class Scan(
        val candidates: List<Candidate>,
        val unreadable: List<FixMessage>,
    )

    /**
     * All business messages across [sessions], merged chronologically — the capture-review rows.
     *
     * A message whose **wire order is unknown** cannot be a row. Every seeded expectation is an ordered
     * list whose order is half of what it asserts, so seeding one from a field list we had to guess bakes
     * a fabricated order into the golden — and it would then go red, forever, against the venue's real
     * one. See [FixMessage.wireRaw]; in practice this is empty, because QuickFIX/J retains the bytes it
     * parsed.
     */
    fun scan(sessions: List<CapturedSession>): Scan {
        val business =
            sessions
                .flatMap { s -> s.messages.filter { it.messageType !in ADMIN_MSG_TYPES }.map { s.title to it } }
                .sortedBy { it.second.timestamp }
        val candidates = mutableListOf<Candidate>()
        val unreadable = mutableListOf<FixMessage>()
        for ((title, message) in business) {
            val fields = FixMessageHelper.wireFields(message)
            if (fields == null) {
                unreadable += message
            } else {
                candidates +=
                    Candidate(
                        session = title,
                        direction = message.direction,
                        messageType = message.messageType,
                        wire = message.wireRaw ?: message.rawMessage,
                        timestamp = message.timestamp,
                        fields = fields,
                        source = message,
                    )
            }
        }
        return Scan(candidates, unreadable)
    }

    /**
     * **Capture's second source: pasted wire.** One message per line — a server log fragment, an email — read
     * by the same reader the paste sheet uses, so the two cannot come to disagree about what a message is.
     *
     * A line whose reading the bytes themselves **disprove** is not a candidate. It is *reported*, in the same
     * place the unreadable live messages already are ([Scan.unreadable] is a list of sentences here), because
     * leaving a message out has to be a thing the author is told rather than a thing that happens.
     *
     * Direction is **not** guessed: [directionFrom] reads it off `SenderCompID(49)` where the assigned session
     * settles it, and leaves it null where nothing does. See [Candidate.direction] for what a guess costs.
     */
    fun fromPaste(
        text: String,
        session: String,
        senderCompId: String? = null,
        targetCompId: String? = null,
        at: java.time.LocalDateTime = java.time.LocalDateTime.now(),
    ): PastedScan {
        val candidates = mutableListOf<Candidate>()
        val refused = mutableListOf<String>()
        text.lines().map { it.trim() }.filter { it.isNotBlank() }.forEachIndexed { index, line ->
            val read = WirePaste.read(line)
            val wire = read.wire
            if (!read.usable || wire == null) {
                refused += "line ${index + 1}: ${read.why ?: read.lint}"
                return@forEachIndexed
            }
            val type = read.fields.firstOrNull { it.first == 35 }?.second
            if (type == null) {
                refused += "line ${index + 1}: no MsgType(35) — this is not a message FixTool can replay"
                return@forEachIndexed
            }
            candidates +=
                Candidate(
                    session = session,
                    direction = directionFrom(read.fields, senderCompId, targetCompId),
                    messageType = type,
                    wire = wire,
                    // A paste's own moment where it has one: its SendingTime(52) — three rows all stamped
                    // with the instant of the paste tell the author nothing. Where 52 is absent or
                    // unreadable, the paste moment (nudged per line, so the sort stays stable) stands in.
                    timestamp = sendingTimeOf(read.fields) ?: at.plusNanos(index.toLong()),
                    fields = read.fields,
                    source = null,
                )
        }
        return PastedScan(candidates, refused)
    }

    /** UTCTIMESTAMP formats a SendingTime(52) legitimately arrives in. */
    private val SENDING_TIME_PATTERNS =
        listOf(
            "yyyyMMdd-HH:mm:ss.SSSSSSSSS",
            "yyyyMMdd-HH:mm:ss.SSSSSS",
            "yyyyMMdd-HH:mm:ss.SSS",
            "yyyyMMdd-HH:mm:ss",
        ).map { java.time.format.DateTimeFormatter.ofPattern(it) }

    /** The message's own SendingTime(52), when it carries a readable one. */
    private fun sendingTimeOf(fields: List<Pair<Int, String>>): java.time.LocalDateTime? {
        val value = fields.firstOrNull { it.first == 52 }?.second ?: return null
        return SENDING_TIME_PATTERNS.firstNotNullOfOrNull { fmt ->
            runCatching { java.time.LocalDateTime.parse(value, fmt) }.getOrNull()
        }
    }

    /** What a paste produced, and what it could not — the sentences the review prints. */
    data class PastedScan(val candidates: List<Candidate>, val refused: List<String>)

    /** The rows only. Callers that must report what was left out use [scan]. */
    fun candidates(sessions: List<CapturedSession>): List<Candidate> = scan(sessions).candidates

    fun capture(
        id: String,
        name: String,
        profile: String?,
        sessions: List<CapturedSession>,
        dictionary: FixDictionaryAdapter?,
    ): Scenario = captureFrom(id, name, profile, candidates(sessions), dictionary)

    // There is no `unassertable` any more, and nothing to warn the author about. It existed to name the
    // groups whose entries shared an identity, which the old model could not assert and therefore
    // skipped — a hole in the coverage that the capture-review screen had to apologise for. Position is
    // the identity now, so those groups are asserted like any other, entry by entry.

    /** Builds the scenario from an already-curated [selection] (capture-review's Save). */
    fun captureFrom(
        id: String,
        name: String,
        profile: String?,
        selection: List<Candidate>,
        dictionary: FixDictionaryAdapter?,
    ): Scenario {
        // capturedValue -> "${varName}" reference, scenario-wide (so a response on any session can echo it).
        val refByValue = mutableMapOf<String, String>()
        val steps = mutableListOf<ScenarioStep>()
        // Each Expect step paired with the message it was seeded from, so the post-pass below can compare the
        // bytes of two same-type replies and seed a discriminator that tells them apart.
        val expectCandidates = mutableListOf<Pair<Int, Candidate>>()

        for (candidate in selection) {
            // An undirected row cannot become a step: it would become a Send that asserts nothing, silently.
            // The review refuses the save before this, by name; this is the engine keeping the same rule.
            if (candidate.direction == null) continue
            if (candidate.outgoing) {
                steps += sendStep(candidate, dictionary, refByValue)
            } else {
                expectCandidates += steps.size to candidate
                steps += expectStep(candidate, dictionary, refByValue)
            }
        }
        disambiguateSameType(steps, expectCandidates)

        val setup = selection.map { it.session }.distinct().map { ScenarioStep.ClearMessages(it) }
        return Scenario(id = id, name = name, profile = profile, setup = setup, steps = steps)
    }

    /** Business fields whose value is stable across a replay (unlike ids/timestamps/seqnums). */
    private val STABLE_VALUE_DISCRIMINATORS = listOf(150, 39) // ExecType, OrdStatus

    /**
     * **Two same-type replies the seeded bind constraints cannot tell apart get a discriminator here.**
     *
     * Capture seeds bind constraints only from echoed correlation ids ([ID_TAGS]), which are identical across
     * every fill of one order — so two `ExecutionReport`s for the same `ClOrdID` bind by arrival order alone,
     * and a step that means "the terminal one" silently grabs the ack. This post-pass groups the Expects by
     * `(session, messageType)` and, for each group its existing constraints do not already separate, seeds the
     * *minimal* distinguisher, preferring intent over position:
     *
     * 1. a **value** discriminator on a stable business tag (ExecType/OrdStatus) whose value differs across all
     *    members — reorder-proof and self-documenting;
     * 2. else, for a pair, a **presence** discriminator on a tag one carries and the other does not (e.g. the
     *    terminal report's `QuoteReqID`) — value-agnostic, so replay-safe for id tags;
     * 3. else **occurrence** ordinals by arrival order — always separates, but a bare count, so it is the last
     *    resort.
     */
    private fun disambiguateSameType(steps: MutableList<ScenarioStep>, expects: List<Pair<Int, Candidate>>) {
        val groups = expects.groupBy { (_, c) -> c.session to c.messageType }
        for ((_, members) in groups) {
            if (members.size < 2) continue
            // Already separable by the constraints capture seeded (distinct echoed ids)? Arrival order + the
            // consumed cursor then suffice, and adding anything would only be noise.
            if (members.map { (idx, _) -> bindSignature(steps[idx]) }.toSet().size == members.size) continue
            if (seedValueDiscriminator(steps, members)) continue
            if (members.size == 2 && seedPresenceDiscriminator(steps, members)) continue
            seedOrdinals(steps, members)
        }
    }

    /** A comparable key for what an Expect's bind predicate already constrains — fields (op+value) and ordinal. */
    private fun bindSignature(step: ScenarioStep): String {
        val m = (step as? ScenarioStep.Expect)?.match ?: return ""
        return m.fields.sortedBy { it.tag }.joinToString(",") { "${it.tag}:${it.op}:${it.value}" } + "|occ=${m.occurrence}"
    }

    private fun seedValueDiscriminator(steps: MutableList<ScenarioStep>, members: List<Pair<Int, Candidate>>): Boolean {
        for (tag in STABLE_VALUE_DISCRIMINATORS) {
            val values = members.map { (_, c) -> firstValue(c, tag) }
            if (values.any { it == null }) continue // the tag must be on every member
            if (values.toSet().size != members.size) continue // and pairwise distinct, so it separates all
            members.forEach { (idx, c) -> addConstraint(steps, idx, TagValue(tag, firstValue(c, tag)!!, MatchOp.EQ)) }
            return true
        }
        return false
    }

    private fun seedPresenceDiscriminator(steps: MutableList<ScenarioStep>, members: List<Pair<Int, Candidate>>): Boolean {
        val (a, b) = members
        val aTags = a.second.fields.mapTo(mutableSetOf()) { it.first }
        val bTags = b.second.fields.mapTo(mutableSetOf()) { it.first }
        // A tag on exactly one of the two separates them. Prefer a correlation id (the QuoteReqID case) — it is
        // the field an author would themselves reach for — then the lowest-numbered tag for a stable choice.
        val distinguishing = (aTags - bTags) + (bTags - aTags)
        val tag = distinguishing.filter { it in ID_TAGS }.minOrNull() ?: distinguishing.minOrNull() ?: return false
        val presentInA = tag in aTags
        addConstraint(steps, a.first, TagValue(tag, "", if (presentInA) MatchOp.PRESENT else MatchOp.ABSENT))
        addConstraint(steps, b.first, TagValue(tag, "", if (presentInA) MatchOp.ABSENT else MatchOp.PRESENT))
        return true
    }

    private fun seedOrdinals(steps: MutableList<ScenarioStep>, members: List<Pair<Int, Candidate>>) {
        // The k-th same-type reply, in arrival order, is occurrence k. The members are already chronological
        // (selection is), but sort defensively so the ordinal cannot depend on grouping order.
        members.sortedBy { (_, c) -> c.timestamp }.forEachIndexed { k, (idx, _) -> setOccurrence(steps, idx, k + 1) }
    }

    private fun firstValue(c: Candidate, tag: Int): String? {
        val hit = c.fields.firstOrNull { it.first == tag } ?: return null
        return hit.second.takeIf { it.isNotBlank() }
    }

    private fun addConstraint(steps: MutableList<ScenarioStep>, idx: Int, tv: TagValue) {
        val e = steps[idx] as? ScenarioStep.Expect ?: return
        val m = e.match ?: MatchPredicate(messageType = e.expectation.messageType)
        if (m.fields.any { it.tag == tv.tag }) return // never double up on a tag we already constrain
        steps[idx] = e.copy(match = m.copy(fields = m.fields + tv))
    }

    private fun setOccurrence(steps: MutableList<ScenarioStep>, idx: Int, n: Int) {
        val e = steps[idx] as? ScenarioStep.Expect ?: return
        val m = e.match ?: MatchPredicate(messageType = e.expectation.messageType)
        steps[idx] = e.copy(match = m.copy(occurrence = n))
    }

    /**
     * **The direction, read off the bytes where the bytes decide it.**
     *
     * Once a session is assigned, `SenderCompID(49)` equal to that session's own sender means the message went
     * **out**; equal to its target means it came **in**. Where the bytes do not decide — no session config, no
     * `49`, a log from a third party — the answer is **null**, and the author is asked. It is never guessed:
     * see [Candidate.direction] for what a guess costs.
     */
    fun directionFrom(
        fields: List<Pair<Int, String>>,
        senderCompId: String?,
        targetCompId: String?,
    ): FixMessage.Direction? {
        val sender = fields.firstOrNull { it.first == 49 }?.second ?: return null
        return when (sender) {
            senderCompId?.takeIf { it.isNotBlank() } -> FixMessage.Direction.OUTGOING
            targetCompId?.takeIf { it.isNotBlank() } -> FixMessage.Direction.INCOMING
            else -> null
        }
    }

    /** The included rows whose direction nobody has settled. A save is refused while there are any. */
    fun undirected(selection: List<Candidate>): List<Candidate> = selection.filter { it.direction == null }

    private fun sendStep(
        entry: Candidate,
        dictionary: FixDictionaryAdapter?,
        refByValue: MutableMap<String, String>,
    ): ScenarioStep.Send {
        val raw = StringBuilder()
        for ((tag, value) in entry.fields) {
            if (tag in TRANSPORT_TAGS) continue
            val out = when {
                tag == 35 -> value
                isTimestamp(tag, dictionary) -> NOW_EXPR
                tag in ID_TAGS && value.isNotBlank() -> idExpr(value, refByValue)
                else -> value
            }
            raw.append(tag).append('=').append(out).append('|')
        }
        return ScenarioStep.Send(raw.toString(), entry.session, origin = entry.originOfStep())
    }

    /** First send of a value mints a fresh scenario variable; a re-send references the same one. */
    private fun idExpr(value: String, refByValue: MutableMap<String, String>): String {
        val existing = refByValue[value]
        if (existing != null) return existing
        val varName = "id${refByValue.size}"
        refByValue[value] = "\${$varName}"
        return "\${$varName = UUID.randomUUID()}"
    }

    private fun expectStep(
        entry: Candidate,
        dictionary: FixDictionaryAdapter?,
        refByValue: Map<String, String>,
    ): ScenarioStep.Expect {
        // Each seeded row carries the value it was seeded from, so a row that echoes something we sent
        // becomes a reference check. Rows must be correlated by *their own* captured value, not by
        // looking the tag up in the message: `firstOrNull { it.first == tag }` answers with the first
        // occurrence, so on a two-leg order the second leg's ClOrdID row would have been correlated
        // against the first leg's value — an assertion pointing at a field it does not describe.
        val seeded = ExpectationSeeder.seedDetailed(entry.fields, dictionary)
        val correlated = seeded.map { sf ->
            // Correlated by TAG as well as by value. Keying on the raw value alone rewrote every seeded row
            // whose captured value merely *equalled* a sent id — across every tag in the message. A tester
            // whose NewOrderSingle carries the utterly ordinary `11=1` therefore had Side(54)=1,
            // OrdStatus(39)=1 and ExecType(150)=1 all rewritten to Reference("${id0}"); on replay ${id0} is a
            // fresh uuid, so the scenario asserted that Side equals a uuid and was permanently red for a
            // reason pointing at nothing the author wrote. An echo comes back in a correlation-id field —
            // which is precisely the filter the bind constraints below already apply.
            val ref = if (sf.field.tag in ID_TAGS) refByValue[sf.capturedValue] else null
            if (ref != null) sf.field.copy(matcher = Matcher.Reference(ref)) else sf.field
        }
        // Echoed correlation ids also become bind constraints, so on a busy session this step binds
        // to *the response to this run's ids* — not merely the first message of the same type. The
        // ${idN} values are resolved against the scenario scope by the runner.
        val bindConstraints = entry.fields
            .filter { (tag, value) -> tag in ID_TAGS && refByValue.containsKey(value) }
            .map { (tag, value) -> TagValue(tag, refByValue.getValue(value)) }
            .distinctBy { it.tag }
        return ScenarioStep.Expect(
            origin = entry.originOfStep(),
            session = entry.session,
            direction = "in",
            match = MatchPredicate(messageType = entry.messageType, fields = bindConstraints),
            timeoutMs = DEFAULT_TIMEOUT_MS,
            expectation = Expectation(
                fields = correlated,
                messageType = entry.messageType,
                mode = MatchMode.OPEN,
                // The venue's bytes, not the display string. The rows above were seeded from `wireFields`
                // (SOH, unsubstituted) while the golden used to be stored as the '|'-substituted string —
                // two decoders of one message, and they disagreed about it: the editor previews the seeded
                // rows *against the golden*, so a reply carrying `58=Rejected|insufficient margin` seeded a
                // row for the whole value and then previewed it against a golden that had been split into
                // `58=Rejected` plus a phantom. The row went red against the very message it was captured
                // from, and the author was invited to "fix" a matcher that was already right.
                // Non-null by construction: `scan` does not offer a candidate whose wire bytes are missing.
                golden = entry.wire,
            ),
        )
    }

    /** Everything a paste makes is badged, because FixTool did not watch these bytes arrive (S4). */
    private fun Candidate.originOfStep(): StepOrigin = if (pasted) StepOrigin.PASTED else StepOrigin.LIVE

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun isTimestamp(tag: Int, dictionary: FixDictionaryAdapter?): Boolean {
        if (tag == 60) return true
        val type = try {
            dictionary?.getDataDictionary()?.getFieldType(tag)?.name
        } catch (e: Exception) {
            null
        }
        return type == "UTCTIMESTAMP" || type == "TZTIMESTAMP"
    }

    private const val DEFAULT_TIMEOUT_MS = 10_000L
    /**
     * UTCTimestamp fields are UTC by definition, and a replay stamps them fresh at send time. Taking
     * the system clock's local time put a London capture an hour ahead in summer — a TransactTime the
     * counterparty may reject, and one that fails any temporal assertion echoing it back.
     */
    private const val NOW_EXPR =
        "\${LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}"
}
