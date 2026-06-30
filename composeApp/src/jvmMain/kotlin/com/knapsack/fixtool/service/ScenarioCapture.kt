package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep

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
    private val TRANSPORT_TAGS = setOf(8, 9, 34, 49, 50, 52, 56, 57, 115, 128, 142, 143, 144, 145, 10, 43, 97, 122, 369)

    /** Client-originated correlation id tags: parameterized on send, echo-matched on responses. */
    private val ID_TAGS = setOf(11, 41, 131, 526, 583)

    /** A session's title + the messages observed on it. */
    data class CapturedSession(val title: String, val messages: List<FixMessage>)

    private data class Entry(val session: String, val message: FixMessage, val fields: List<Pair<Int, String>>)

    fun capture(
        id: String,
        name: String,
        profile: String?,
        sessions: List<CapturedSession>,
        dictionary: FixDictionaryAdapter?,
    ): Scenario {
        val entries = sessions
            .flatMap { s ->
                s.messages
                    .filter { it.messageType !in ADMIN_MSG_TYPES }
                    .map { Entry(s.title, it, FixMessageHelper.parseFixMessage(it.rawMessage)) }
            }
            .sortedBy { it.message.timestamp }

        // capturedValue -> "${varName}" reference, scenario-wide (so a response on any session can echo it).
        val refByValue = mutableMapOf<String, String>()
        val steps = mutableListOf<ScenarioStep>()

        for (entry in entries) {
            if (entry.message.direction == FixMessage.Direction.OUTGOING) {
                steps += sendStep(entry, dictionary, refByValue)
            } else {
                steps += expectStep(entry, dictionary, refByValue)
            }
        }

        val setup = sessions.map { it.title }.distinct().map { ScenarioStep.ClearMessages(it) }
        return Scenario(id = id, name = name, profile = profile, setup = setup, steps = steps)
    }

    private fun sendStep(
        entry: Entry,
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
        entry: Entry,
        dictionary: FixDictionaryAdapter?,
        refByValue: Map<String, String>,
    ): ScenarioStep.Expect {
        val seeded = ExpectationSeeder.seed(entry.fields, dictionary)
        // Override any tag whose captured value echoes something we sent → verify the echo by reference.
        val correlated = seeded.fields.map { fe ->
            val captured = entry.fields.firstOrNull { it.first == fe.tag }?.second
            val ref = captured?.let { refByValue[it] }
            if (ref != null) fe.copy(matcher = Matcher.Reference(ref)) else fe
        }
        return ScenarioStep.Expect(
            session = entry.session,
            direction = "in",
            match = MatchPredicate(messageType = entry.message.messageType),
            timeoutMs = DEFAULT_TIMEOUT_MS,
            expectation = Expectation(fields = correlated, messageType = entry.message.messageType, mode = seeded.mode),
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
    private const val NOW_EXPR =
        "\${LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss.SSS\"))}"
}
