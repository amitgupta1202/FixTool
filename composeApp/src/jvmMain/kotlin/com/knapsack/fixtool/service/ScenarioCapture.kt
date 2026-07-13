package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Expectation
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
    private val TRANSPORT_TAGS = SessionTags.TRANSPORT

    /** Client-originated correlation id tags: parameterized on send, echo-matched on responses. */
    private val ID_TAGS = setOf(11, 41, 131, 526, 583)

    /** A session's title + the messages observed on it. */
    data class CapturedSession(val title: String, val messages: List<FixMessage>)

    /**
     * One business message that capture *would* turn into a step: the row unit of the capture-review
     * screen, where the author curates the selection before anything is saved.
     */
    data class Candidate(val session: String, val message: FixMessage, val fields: List<Pair<Int, String>>)

    /** All business messages across [sessions], merged chronologically — the capture-review rows. */
    fun candidates(sessions: List<CapturedSession>): List<Candidate> =
        sessions
            .flatMap { s ->
                s.messages
                    .filter { it.messageType !in ADMIN_MSG_TYPES }
                    .map { Candidate(s.title, it, FixMessageHelper.parseFixMessage(it.rawMessage)) }
            }
            .sortedBy { it.message.timestamp }

    fun capture(
        id: String,
        name: String,
        profile: String?,
        sessions: List<CapturedSession>,
        dictionary: FixDictionaryAdapter?,
    ): Scenario = captureFrom(id, name, profile, candidates(sessions), dictionary)

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
        val seeded = ExpectationSeeder.seed(entry.fields, dictionary)
        // Override any top-level tag whose captured value echoes something we sent → verify by reference.
        val correlated = seeded.fields.map { fe ->
            if (fe.path != null) return@map fe
            val captured = entry.fields.firstOrNull { it.first == fe.tag }?.second
            val ref = captured?.let { refByValue[it] }
            if (ref != null) fe.copy(matcher = Matcher.Reference(ref)) else fe
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
                mode = seeded.mode,
                golden = entry.message.rawMessage,
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
