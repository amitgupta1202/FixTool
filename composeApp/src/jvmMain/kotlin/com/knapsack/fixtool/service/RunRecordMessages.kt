package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import com.knapsack.fixtool.service.FixMessageHelper.toRawFixMessage
import com.knapsack.fixtool.service.FixMessageHelper.toWireFixMessage
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * **A record's messages, back as messages** — so an entry that ran an hour ago can be read on the same
 * grid, with the same detail panel and the same tag search, as the traffic that is arriving now.
 *
 * The path is the one the paste capture already walks: bytes in wire order, through the dictionary-aware
 * parser, into a `FixMessage`. Nothing about `HierarchicalGridView` knows whether its list came from a
 * session or a file — it takes a list and a tint map — which is the only reason a record can be *viewed*
 * rather than merely reported.
 *
 * A message the record could not vouch for the wire order of (`wireOrderKnown = false`) keeps its display
 * form and gets no `wireRaw`, exactly as a live message with no bytes does: the grid shows it, and the
 * surfaces that need a byte order refuse it by name rather than guessing one.
 */
object RunRecordMessages {
    /**
     * The record's messages in arrival order, and the verdicts that judged them.
     *
     * The tint comes from **the record's own `bound` map** — `stepId -> message index` — and never from the
     * live grid's. Two surfaces, two jobs: the session grid shows what is arriving and is tinted by the last
     * entry that ran on it; this shows one entry, and is tinted by what that entry decided.
     */
    fun of(record: RunRecord, dictionary: FixDictionaryAdapter?): Parsed {
        // Parsed **once**: the tint map's keys have to be the very objects the grid is handed, and a second
        // parse would produce equal-looking messages it could never match.
        val parsed = record.messages.map { parse(it, dictionary) }
        val messages = parsed.filterNotNull()
        // The record's own index -> where that message ended up, which differ only when one could not be
        // read. `bound` addresses the record, so it has to be translated rather than trusted.
        val position = mutableMapOf<Int, Int>()
        var next = 0
        parsed.forEachIndexed { i, message -> if (message != null) position[i] = next++ }
        val stepsById = record.result.steps.filter { it.stepId != null }.associateBy { it.stepId }
        val tint =
            record.bound.entries.mapNotNull { (stepId, index) ->
                val step = stepsById[stepId] ?: return@mapNotNull null
                val at = position[index] ?: return@mapNotNull null
                messages[at] to step
            }.toMap()
        return Parsed(messages, tint, messages.size)
    }

    /** The record's messages, the verdict on each that had one, and how many of them could be read. */
    data class Parsed(
        val messages: List<FixMessage>,
        val judged: Map<FixMessage, StepResult>,
        val readable: Int,
    )

    private fun parse(recorded: RecordedMessage, dictionary: FixDictionaryAdapter?): FixMessage? =
        runCatching {
            val wire = recorded.raw.toWireFixMessage()
            val quickfix =
                if (dictionary != null) wire.toQuickFixMessageManual(dictionary) else quickfix.Message(wire, false)
            FixMessage(
                timestamp = timestampOf(recorded.atMicros),
                direction = if (recorded.incoming) FixMessage.Direction.INCOMING else FixMessage.Direction.OUTGOING,
                rawMessage = recorded.raw.toRawFixMessage(),
                quickfixMessage = quickfix,
                captureTimeMicros = recorded.atMicros,
                // Only where the record says the order was actually observed. A record that had to fall back
                // to a display form must not hand it on as if it were the wire.
                wireRaw = wire.takeIf { recorded.wireOrderKnown },
            )
        }.getOrNull()

    private fun timestampOf(micros: Long): LocalDateTime =
        if (micros <= 0) {
            LocalDateTime.now()
        } else {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(micros / 1_000), ZoneId.systemDefault())
        }
}
