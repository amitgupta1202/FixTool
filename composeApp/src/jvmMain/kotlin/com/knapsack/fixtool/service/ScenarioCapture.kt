package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.TagValue

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
     * One business message that capture *would* turn into a step: the row unit of the capture-review
     * screen, where the author curates the selection before anything is saved.
     */
    data class Candidate(val session: String, val message: FixMessage, val fields: List<Pair<Int, String>>)

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
            if (fields == null) unreadable += message else candidates += Candidate(title, message, fields)
        }
        return Scan(candidates, unreadable)
    }

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

        for (candidate in selection) {
            if (candidate.message.direction == FixMessage.Direction.OUTGOING) {
                steps += sendStep(candidate, dictionary, refByValue)
            } else {
                steps += expectStep(candidate, dictionary, refByValue)
            }
        }

        val setup = selection.map { it.session }.distinct().map { ScenarioStep.ClearMessages(it) }
        return Scenario(id = id, name = name, profile = profile, setup = setup, steps = steps)
    }

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
        return ScenarioStep.Send(raw.toString(), entry.session)
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
            val ref = refByValue[sf.capturedValue]
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
            session = entry.session,
            direction = "in",
            match = MatchPredicate(messageType = entry.message.messageType, fields = bindConstraints),
            timeoutMs = DEFAULT_TIMEOUT_MS,
            expectation = Expectation(
                fields = correlated,
                messageType = entry.message.messageType,
                mode = MatchMode.OPEN,
                // The venue's bytes, not the display string. The rows above were seeded from `wireFields`
                // (SOH, unsubstituted) while the golden used to be stored as the '|'-substituted string —
                // two decoders of one message, and they disagreed about it: the editor previews the seeded
                // rows *against the golden*, so a reply carrying `58=Rejected|insufficient margin` seeded a
                // row for the whole value and then previewed it against a golden that had been split into
                // `58=Rejected` plus a phantom. The row went red against the very message it was captured
                // from, and the author was invited to "fix" a matcher that was already right.
                // Non-null by construction: `scan` does not offer a candidate whose wire bytes are missing.
                golden = entry.message.wireRaw ?: entry.message.rawMessage,
            ),
        )
    }

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
