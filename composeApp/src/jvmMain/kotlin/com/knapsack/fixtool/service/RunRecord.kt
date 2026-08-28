package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.ExampleRow
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.StepResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * **What one entry of a run set leaves behind, and it is the artifact.**
 *
 * A suite asks a question the app cannot answer from memory. Entry 2's setup calls `ClearMessages`, so
 * the moment the second scenario starts the first one's messages are gone from the grid — and even
 * without a clear, a session is a ring buffer. By the time a twelve-scenario suite lands, the grid holds
 * the last entry's traffic and nothing else, and eleven reports would point at messages that are not
 * there.
 *
 * References into the session were the other answer, and three things rule them out: the overnight suite
 * is answered in the morning, from an app that may have been closed since; a headless run has no tab to
 * hold them; and the runner keeps no snapshot between polls anyway. So the evidence goes to disk as it is
 * produced, and a tab is a **viewer over the record**.
 *
 * The shape is a serialization of things already in hand — `ScenarioReport.toJson` for the verdict,
 * `wireRaw` for the bytes, the `stepId -> message` pairing the run already reported — not a storage
 * project.
 */
data class RunRecord(
    val setId: String,
    /** 1-based position in the set, and the file's own prefix. */
    val entry: Int,
    val iteration: Int,
    /** The table row this entry ran, when it had one — so a record says what the run was given. */
    val row: ExampleRow? = null,
    val scenarioId: String,
    val scenarioName: String,
    /**
     * **The scenario as it was when this entry ran** — what was asserted, beside what came back.
     *
     * Without it a record is half the evidence. The reconcile route has to know whether the step that
     * failed is still the step that failed, and comparing today's file against today's file answers yes
     * every time: an author who edits the assertion and then clicks Reconcile would be shown the old bytes
     * against the new expectation, and "Accept actual" would write a repair for a run that never happened.
     * Null only for a record written before this existed.
     */
    val scenario: Scenario? = null,
    val startedAt: Long,
    val durationMs: Long?,
    val result: ScenarioResult,
    /** Every message the entry saw, in arrival order. */
    val messages: List<RecordedMessage>,
    /** stepId -> the index in [messages] that step judged. What a reconcile from a record re-binds off. */
    val bound: Map<String, Int>,
    /** Messages the cap removed. Reported, never silent. */
    val dropped: Int = 0,
)

/**
 * One message as the record keeps it: the bytes, and enough context to put them back on a grid.
 *
 * [raw] is `wireRaw` where there is one — the bytes as they arrived, in the order they arrived — because
 * that is what the capture-paste parser turns back into a message the grid can show, and what reconcile
 * needs. A message whose wire order was never known falls back to its display form and says so, rather
 * than passing off an order nobody observed.
 */
data class RecordedMessage(
    val index: Int,
    val session: String?,
    val incoming: Boolean,
    val atMicros: Long,
    val raw: String,
    val wireOrderKnown: Boolean = true,
)

/**
 * **Collects what the sessions show while one entry runs.**
 *
 * Fed with snapshots — a session's messages are a `StateFlow`, so the host collects it and hands each
 * emission here — and unioned by [com.knapsack.fixtool.model.AppMessage.uid]. By uid and not by the
 * message: `FixMessage` is a data class whose equality behaves like identity only because
 * `quickfix.Message` never overrode `equals`, so a `copy()` or a re-parse would silently make two of one
 * message. The uid is the identity the class actually promises.
 *
 * Whole snapshots rather than deltas is what makes conflation harmless: the buffer evicts one message per
 * arrival, so nothing is lost as long as the recorder sees one emission per buffer-length of arrivals —
 * one in a thousand, against an ingest ceiling of about a thousand a second.
 *
 * One collector per session means several threads feeding one map, so every door is synchronized. The
 * work behind each is a `putIfAbsent` per message already in a list the session handed over.
 */
class RunRecorder {
    private data class Seen(
        val session: String?,
        val message: FixMessage,
    )

    private val seen = LinkedHashMap<Long, Seen>()

    /**
     * **What was already in the logs when this entry began** — the entry's own watermark.
     *
     * The first snapshot of a session is its history, not this entry's traffic. A suite that does not
     * clear a session (a venue pane, typically — a capture clears the client leg and the book, not the
     * venue's message log) would otherwise give entry 12 a record containing all eleven earlier entries,
     * under a header that says "entry 12". The runner's own verdict already excludes what predates the
     * run; the record now agrees with it.
     */
    private val predating = mutableSetOf<Long>()
    private val started = mutableSetOf<String?>()

    @Synchronized
    fun observe(session: String?, messages: List<FixMessage>) {
        if (started.add(session)) messages.forEach { predating += it.uid }
        messages.forEach { seen.putIfAbsent(it.uid, Seen(session, it)) }
    }

    /** How many distinct messages have been seen, before any cap. */
    val size: Int @Synchronized get() = seen.size

    /**
     * The record's evidence: messages in arrival order, the `stepId -> index` map, and what the cap took.
     *
     * **The cap never drops what the report points at.** Every message some step judged — a bound reply,
     * a stray a strict verdict named — is kept whatever the cap says; the cap falls on the unbound
     * remainder, **oldest first**, because the newest unbound traffic is the traffic nearest whatever the
     * reader is about to ask about. A repeat over a quote stream is the case this exists for.
     */
    @Synchronized
    fun build(judged: Map<FixMessage, StepResult>, cap: Int): Evidence {
        // Arrival order across sessions: the capture stamp is the only common clock, and an unstamped
        // message (0) keeps its insertion position, which for one session is already chronological.
        val judgedUids = judged.keys.mapTo(mutableSetOf()) { it.uid }
        // History is dropped — except where a verdict pointed at it. Under the permissive binding scope an
        // expect may bind a message older than the run, and a record whose `bound` index pointed at a
        // message it had discarded would be evidence with the evidence taken out.
        val ordered =
            seen.values
                .filterNot { it.message.uid in predating && it.message.uid !in judgedUids }
                .sortedBy { it.message.captureTimeMicros }
        val kept =
            if (cap <= 0 || ordered.size <= cap) {
                ordered
            } else {
                val spare = (cap - judgedUids.count { uid -> ordered.any { it.message.uid == uid } }).coerceAtLeast(0)
                val fill =
                    ordered
                        .filterNot { it.message.uid in judgedUids }
                        .takeLast(spare)
                        .mapTo(mutableSetOf()) { it.message.uid }
                ordered.filter { it.message.uid in judgedUids || it.message.uid in fill }
            }
        val messages =
            kept.mapIndexed { i, s ->
                RecordedMessage(
                    index = i,
                    session = s.session,
                    incoming = s.message.direction == FixMessage.Direction.INCOMING,
                    atMicros = s.message.captureTimeMicros,
                    raw = s.message.wireRaw ?: s.message.rawMessage,
                    wireOrderKnown = s.message.wireRaw != null,
                )
            }
        val positionOf = kept.withIndex().associate { (i, s) -> s.message.uid to i }
        val bound =
            judged.entries
                .mapNotNull { (message, step) ->
                    val stepId = step.stepId ?: return@mapNotNull null
                    positionOf[message.uid]?.let { stepId to it }
                }.toMap()
        return Evidence(messages, bound, ordered.size - kept.size)
    }

    /** What one entry's record carries: the messages kept, what judged them, and what the cap took. */
    data class Evidence(
        val messages: List<RecordedMessage>,
        val bound: Map<String, Int>,
        val dropped: Int,
    )
}

/** The record as JSON and back. The `result` block is [ScenarioReport.toJson] verbatim. */
object RunRecordCodec {
    fun toJson(record: RunRecord): JsonObject =
        buildJsonObject {
            put("set", record.setId)
            put("entry", record.entry)
            put("iteration", record.iteration)
            record.row?.let { row ->
                put(
                    "row",
                    buildJsonObject {
                        put("name", row.name)
                        put("values", buildJsonObject { row.values.forEach { (k, v) -> put(k, v) } })
                    },
                )
            }
            put(
                "scenario",
                buildJsonObject {
                    put("id", record.scenarioId)
                    put("name", record.scenarioName)
                    // The definition, in the same shape a scenario file has — so a reader of the record can
                    // see what was asserted, and the reconcile gate can tell an edit from a re-run.
                    record.scenario?.let { put("definition", ScenarioCodec.toJson(it)) }
                },
            )
            put("startedAt", record.startedAt)
            record.durationMs?.let { put("durationMs", it) }
            put("result", ScenarioReport.toJson(record.result))
            put(
                "messages",
                buildJsonArray {
                    record.messages.forEach { m ->
                        add(
                            buildJsonObject {
                                put("i", m.index)
                                m.session?.let { put("session", it) }
                                put("dir", if (m.incoming) "in" else "out")
                                put("at", m.atMicros)
                                put("raw", m.raw)
                                if (!m.wireOrderKnown) put("wireOrderKnown", false)
                            },
                        )
                    }
                },
            )
            put("bound", buildJsonObject { record.bound.forEach { (stepId, at) -> put(stepId, at) } })
            put("dropped", record.dropped)
        }

    fun fromJson(obj: JsonObject): RunRecord =
        RunRecord(
            setId = obj["set"]?.jsonPrimitive?.content.orEmpty(),
            entry = obj["entry"]?.jsonPrimitive?.int ?: 0,
            iteration = obj["iteration"]?.jsonPrimitive?.int ?: 1,
            row =
                obj["row"]?.jsonObject?.let { row ->
                    ExampleRow(
                        name = row["name"]?.jsonPrimitive?.content.orEmpty(),
                        values = row["values"]?.jsonObject.orEmpty().mapValues { (_, v) -> v.jsonPrimitive.content },
                    )
                },
            scenarioId =
                obj["scenario"]
                    ?.jsonObject
                    ?.get("id")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty(),
            scenarioName =
                obj["scenario"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty(),
            scenario =
                obj["scenario"]?.jsonObject?.get("definition")?.jsonObject?.let {
                    runCatching { ScenarioCodec.fromJson(it) }.getOrNull()
                },
            startedAt = obj["startedAt"]?.jsonPrimitive?.long ?: 0L,
            durationMs = obj["durationMs"]?.jsonPrimitive?.long,
            result = ScenarioReport.fromJson(obj["result"]!!.jsonObject),
            messages =
                obj["messages"]?.jsonArray.orEmpty().map { it.jsonObject }.map { m ->
                    RecordedMessage(
                        index = m["i"]!!.jsonPrimitive.int,
                        session = m["session"]?.jsonPrimitive?.content,
                        incoming = m["dir"]?.jsonPrimitive?.content == "in",
                        atMicros = m["at"]?.jsonPrimitive?.long ?: 0L,
                        raw = m["raw"]!!.jsonPrimitive.content,
                        wireOrderKnown = m["wireOrderKnown"]?.jsonPrimitive?.content != "false",
                    )
                },
            bound = obj["bound"]?.jsonObject.orEmpty().mapValues { (_, v) -> v.jsonPrimitive.int },
            dropped = obj["dropped"]?.jsonPrimitive?.int ?: 0,
        )
}
