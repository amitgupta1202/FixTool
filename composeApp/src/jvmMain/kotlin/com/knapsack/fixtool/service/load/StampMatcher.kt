package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.WireDirection
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.RoundTripHistogram
import com.knapsack.fixtool.service.SocketStamp
import com.knapsack.fixtool.service.WireTags
import quickfix.SessionID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * **Pairs every reply with the request it answers, across the whole population, from the socket stamps.**
 *
 * One object for the run, fed every stamp from every participating session: the lanes that issue and the
 * sessions that only listen. That is what makes the counts exact however many messages a pane retains, what
 * lets a reply landing on a drop-copy session match a request that left lane 12, and what keeps the round
 * trip on the same clock the latency panel uses. The pane path still runs beside this, for the grid and for
 * the `discarded` counter the report's tool block reads. It is never the count.
 *
 * A request is `pending` from its SEND stamp until the first reply carrying its id arrives on any session,
 * which is the match and the round trip. A second arrival of the same id is a duplicate: reported, not
 * judged, because an order legitimately draws several ExecutionReports. A reply carrying the tag that
 * matches nothing issued is a stray: another client's traffic seen on a listen-only session. Nothing is
 * aged out of `pending` before [closeSettle], and a reply after that is late rather than matched.
 *
 * Round trips are kept as a primitive array and sorted once at the end, because the percentiles of a
 * 300,000-message run over a 10,000-sample ring would be percentiles of the last thirty seconds. The
 * request's wire is kept while it is pending, so the record can hold the bytes of what went unanswered
 * (D10). The working set is therefore bounded by the number outstanding at once, which the settle window
 * bounds. The set of matched ids, kept for duplicate detection, grows with the run.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class StampMatcher(
    private val match: LoadMatch,
    private val requestType: String,
    private val issuing: Set<SessionID>,
    private val laneOf: (SessionID) -> Int = { 0 },
    private val specimenLimit: Int = DEFAULT_SPECIMENS,
    /** Names to tags, read off each matched reply and kept at that request's own message index. */
    private val captures: List<Pair<String, Int>> = emptyList(),
    /** Where they are kept. The set owns it, because a later phase reads what an earlier one filled. */
    private val table: CaptureTable? = null,
) {
    private class Pending(
        val sentMicros: Long,
        val laneSlot: Int,
        val wire: String,
        /** Which message this was, so a captured value lands where a later phase will look for it. */
        val messageIndex: Int,
    )

    /**
     * **Named values kept per message index, for the whole set.**
     *
     * `Array<Array<String?>>` and not a map of maps: 4,000 messages × 2 names is 8,000 slots either way,
     * and an array indexed by the thing a later phase already has — its own `${'$'}{messageIndex}` — needs no
     * boxing and no lookup. Written on the I/O threads under the matcher's own lock, read on the render-ahead
     * threads of a later phase, by which time the phase that filled it has finished.
     */
    class CaptureTable(
        val names: List<String>,
        /** One more than the highest index any phase of the set will use. */
        size: Int,
    ) {
        private val values: Array<Array<String?>> = Array(names.size) { arrayOfNulls<String?>(size.coerceAtLeast(1)) }

        val size: Int get() = if (values.isEmpty()) 0 else values[0].size

        fun put(nameIndex: Int, messageIndex: Int, value: String?) {
            if (nameIndex !in values.indices || messageIndex !in values[nameIndex].indices) return
            values[nameIndex][messageIndex] = value
        }

        /** The value kept for [name] at [messageIndex], or null when nothing was. */
        operator fun get(name: String, messageIndex: Int): String? {
            val i = names.indexOf(name)
            if (i < 0 || messageIndex !in values[i].indices) return null
            return values[i][messageIndex]
        }

        /** How many indices carry a value for each name. What the report's `captured` block says. */
        fun counts(): Map<String, Int> =
            names.withIndex().associate { (i, name) -> name to values[i].count { v -> v != null } }

        /** Every index that carries at least one value, with its values, for the evidence file. */
        fun rows(): List<Pair<Int, List<Pair<String, String>>>> =
            (0 until size).mapNotNull { index ->
                val row = names.withIndex().mapNotNull { (i, name) -> values[i][index]?.let { name to it } }
                if (row.isEmpty()) null else index to row
            }

        /** A per-message lookup for one name, which is what a later phase's renderer is handed. */
        fun lookup(name: String): (Int) -> String? = { index -> get(name, index) }
    }

    /** A request that went unanswered within the settle window. */
    data class Unmatched(
        val id: String,
        val laneSlot: Int,
        val sentMicros: Long,
        val wire: String,
    )

    /** One matched pair kept whole, so a reader can see what a good exchange looked like. */
    data class Specimen(
        val request: String,
        val reply: String,
        val roundTripMicros: Long,
    )

    /** What happened in one second of the run, counted from the first send. */
    data class SecondBucket(
        val second: Int,
        val issued: Int,
        val matched: Int,
        val p95Micros: Long?,
    )

    /** The counts at one moment. What the progress line prints. */
    data class Counts(
        val leftSocket: Long,
        val matched: Long,
        val pendingNow: Int,
        val duplicates: Long,
        val late: Long,
        val strays: Long,
        val firstSendMicros: Long?,
        val lastSendMicros: Long?,
        val lastMatchedMicros: Long?,
    )

    /**
     * What one lane matched, missed and repeated, and how long its round trips took.
     *
     * The latency half is only honest because each lane renders ahead of its own sends ([RenderAhead]).
     * While one pacer loop rendered and sent every lane round-robin, lane N left systematically later than
     * lane 1 and would have looked worse forever — a false finding with a lane number on it.
     *
     * The percentiles come from the lane's own [RoundTripHistogram], not from kept samples: a per-lane
     * sample array is the run's whole round-trip set a second time, and the question a lane table answers
     * is "is any lane much worse than the rest", which a log bucket answers exactly well enough.
     */
    data class LaneCounts(
        val slot: Int,
        val matched: Long,
        val unanswered: Long,
        val duplicates: Long,
        /** Nearest-rank over the lane's histogram, as the lower edge of the bucket it lands in. Null with no samples. */
        val p50Micros: Long? = null,
        val p95Micros: Long? = null,
    )

    /** Everything the matcher has to say once the run is over. */
    data class Result(
        val counts: Counts,
        /** Every matched round trip in microseconds, sorted ascending. */
        val roundTripsSorted: LongArray,
        val perSecond: List<SecondBucket>,
        /** [RoundTripHistogram.BUCKETS] counts, log-spaced. What the charts are drawn from. */
        val histogram: List<Int>,
        val perLane: List<LaneCounts>,
        val unmatched: List<Unmatched>,
        val specimens: List<Specimen>,
        val pendingPeak: Int,
        /** How many indices carry a value for each captured name. Empty when the phase captures nothing. */
        val captured: Map<String, Int> = emptyMap(),
    )

    /**
     * One second of the run, accumulated as stamps arrive rather than rebuilt at the end.
     *
     * [buckets] used to walk every sample and every send to reconstruct these, which meant the live
     * document had nothing to draw until the run was over: `perSecond` was `emptyList()` for its whole
     * length, because the final report was the only place a `Result` existed. The p95 stays exact — the
     * second's own round trips, sorted, nearest rank — and [p95] caches it so a progress tick that
     * touches six hundred seconds re-sorts only the one that moved.
     */
    private class SecondAccumulator {
        var issued = 0
        var matched = 0
        val rtts = LongList()
        private var p95 = -1L

        fun p95Micros(): Long? {
            if (rtts.size == 0) return null
            if (p95 < 0) p95 = nearestRank(rtts.toArray().also { it.sort() }, P95)
            return p95
        }

        fun add(rtt: Long) {
            rtts.add(rtt)
            p95 = -1L
        }
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    /**
     * Id to the message index it was issued for, filled by [issued] and taken out by the SEND stamp.
     *
     * The stamp carries the wire and nothing else, so the index has to be handed over on the way past. The
     * SEND stamp always follows the `send` call, so the map is filled before it is read.
     */
    private val issuedIndex = ConcurrentHashMap<String, Int>()

    /**
     * Id to the lane that issued it, kept for the run.
     *
     * A set until per-lane counts wanted it: a duplicate arrives after its request has left [pending], so
     * the lane is only knowable if the match remembered it. The value is a small `Int`, and every lane
     * slot a run has is inside `Integer`'s own cache, so the map holds the same boxes it started with.
     */
    private val matchedIds = ConcurrentHashMap<String, Int>()
    private val outstanding = AtomicInteger()
    private val leftSocket = AtomicLong()
    private val matched = AtomicLong()
    private val duplicates = AtomicLong()
    private val late = AtomicLong()
    private val strays = AtomicLong()

    @Volatile private var pendingPeak = 0

    @Volatile private var firstSendMicros: Long = NONE

    @Volatile private var lastSendMicros: Long = NONE

    @Volatile private var lastMatchedMicros: Long = NONE

    @Volatile private var settleClosed = false

    @Volatile private var unmatchedAtClose: List<Unmatched>? = null

    // Guarded by `samples`: matches arrive on several I/O threads, one per MINA processor.
    private val samples = LongList()
    private val seconds = ArrayList<SecondAccumulator>()
    private val histogram = IntArray(RoundTripHistogram.BUCKETS)
    private val laneMatched = HashMap<Int, Long>()
    private val laneDuplicates = HashMap<Int, Long>()
    private val laneHistograms = HashMap<Int, IntArray>()
    private val specimens = ArrayList<Specimen>()

    /**
     * **What one matcher made of one stamp.**
     *
     * Three answers and not two, because a set has to tell "the wrong reply type, so try the phase
     * before this one" apart from "a reply of exactly the shape I wait for, carrying an id I never
     * issued". The first is nothing at all; the second is the stray count the "nothing matched"
     * diagnosis reads, and it belongs to the phase that was running when it arrived.
     */
    enum class Claim {
        /** Its own: a send it issued, a match, a late reply, or a duplicate of one it matched. */
        MINE,

        /** Nothing to do with this matcher and evidence of nothing: admin, the wrong type, no id. */
        NOT_A_REPLY,

        /** A reply of the shape this matcher waits for, carrying an id nothing here issued. */
        UNKNOWN,
    }

    /** Any thread, any session, every stamp. Does nothing with a message that is not the run's business. */
    fun onStamp(stamp: SocketStamp) {
        if (offer(stamp) == Claim.UNKNOWN) countStray()
    }

    /**
     * **Offer [stamp] to this matcher without deciding whose stray it is.**
     *
     * What a set's reply router calls. Every reply is offered to the live phase first, then to each
     * finished phase newest first, and only a reply nobody issued is counted as a stray, on the phase that
     * was running when it came. Against a matching venue that is not a corner case: phase 1's orders keep
     * drawing fills while phase 2 cancels them, and one matcher per run would have counted every one of
     * those fills as a phase 2 stray.
     */
    fun offer(stamp: SocketStamp): Claim {
        val sessionId = stamp.sessionId ?: return Claim.NOT_A_REPLY
        val type = WireTags.msgType(stamp.wire) ?: return Claim.NOT_A_REPLY
        return when (stamp.direction) {
            WireDirection.SEND -> onSend(sessionId, type, stamp)
            WireDirection.RECEIVE -> onReceive(type, stamp)
        }
    }

    /** A reply nobody issued, counted against this matcher. The router decides which phase that is. */
    fun countStray() {
        strays.incrementAndGet()
    }

    /**
     * **This id is about to be sent, as message [messageIndex].** Called on the pacer thread before `send`.
     *
     * Without it the matcher would know a request's id and its lane and not which message it was, and a
     * captured value would have nowhere to land: a later phase looks its captures up by the index its own
     * ids are built from.
     */
    fun issued(id: String, messageIndex: Int) {
        if (captures.isNotEmpty()) issuedIndex[id] = messageIndex
    }

    private fun onSend(sessionId: SessionID, type: String, stamp: SocketStamp): Claim {
        if (sessionId !in issuing || type != requestType) return Claim.NOT_A_REPLY
        val id = WireTags.tagValue(stamp.wire, match.requestTag) ?: return Claim.NOT_A_REPLY
        pending[id] = Pending(stamp.micros, laneOf(sessionId), stamp.wire, issuedIndex.remove(id) ?: 0)
        leftSocket.incrementAndGet()
        val now = outstanding.incrementAndGet()
        if (now > pendingPeak) pendingPeak = now
        synchronized(samples) {
            if (firstSendMicros == NONE) firstSendMicros = stamp.micros
            if (stamp.micros > lastSendMicros) lastSendMicros = stamp.micros
            secondAt(secondOf(stamp.micros)).issued++
        }
        return Claim.MINE
    }

    @Suppress("ReturnCount")
    private fun onReceive(type: String, stamp: SocketStamp): Claim {
        if (WireTags.isAdmin(type)) return Claim.NOT_A_REPLY
        if (match.replyType != null && type != match.replyType) return Claim.NOT_A_REPLY
        val id = WireTags.tagValue(stamp.wire, match.replyTag) ?: return Claim.NOT_A_REPLY
        val request = pending.remove(id)
        return when {
            request != null -> {
                outstanding.decrementAndGet()
                matchedIds[id] = request.laneSlot
                if (settleClosed) {
                    late.incrementAndGet()
                } else {
                    matched.incrementAndGet()
                    record(request, stamp)
                }
                Claim.MINE
            }
            matchedIds.containsKey(id) -> {
                duplicates.incrementAndGet()
                val lane = matchedIds[id]
                if (lane != null) synchronized(samples) { laneDuplicates.merge(lane, 1L, Long::plus) }
                Claim.MINE
            }
            else -> Claim.UNKNOWN
        }
    }

    private fun record(request: Pending, reply: SocketStamp) {
        val rtt = (reply.micros - request.sentMicros).coerceAtLeast(0)
        synchronized(samples) {
            samples.add(rtt)
            val bucket = RoundTripHistogram.indexOf(rtt)
            histogram[bucket]++
            laneMatched.merge(request.laneSlot, 1L, Long::plus)
            laneHistograms.getOrPut(request.laneSlot) { IntArray(RoundTripHistogram.BUCKETS) }[bucket]++
            secondAt(secondOf(reply.micros)).let {
                it.matched++
                it.add(rtt)
            }
            if (reply.micros > lastMatchedMicros) lastMatchedMicros = reply.micros
            if (specimens.size < specimenLimit) specimens += Specimen(request.wire, reply.wire, rtt)
            // The first reply only: a duplicate must not overwrite the value the match already kept, or a
            // later phase would address whichever ExecutionReport happened to arrive last.
            captures.forEachIndexed { i, (_, tag) ->
                table?.put(i, request.messageIndex, WireTags.tagValue(reply.wire, tag))
            }
        }
    }

    /** The accumulator for one second, growing the list to reach it. Callers hold the `samples` lock. */
    private fun secondAt(second: Int): SecondAccumulator {
        while (seconds.size <= second) seconds.add(SecondAccumulator())
        return seconds[second]
    }

    private fun secondOf(micros: Long): Int {
        val first = firstSendMicros
        return if (first == NONE) 0 else ((micros - first) / MICROS_PER_SECOND).toInt().coerceAtLeast(0)
    }

    /** The counts right now, for a progress line. */
    fun snapshot(): Counts =
        Counts(
            leftSocket = leftSocket.get(),
            matched = matched.get(),
            pendingNow = pending.size,
            duplicates = duplicates.get(),
            late = late.get(),
            strays = strays.get(),
            firstSendMicros = firstSendMicros.takeIf { it != NONE },
            lastSendMicros = lastSendMicros.takeIf { it != NONE },
            lastMatchedMicros = lastMatchedMicros.takeIf { it != NONE },
        )

    /**
     * **The settle window is over.** What is still pending is the run's unmatched set, frozen here, and any
     * reply that arrives from now on is late rather than matched. Idempotent: the first close decides.
     */
    fun closeSettle(): List<Unmatched> {
        unmatchedAtClose?.let { return it }
        synchronized(samples) {
            unmatchedAtClose?.let { return it }
            settleClosed = true
            val frozen =
                pending.entries
                    .map { (id, p) -> Unmatched(id, p.laneSlot, p.sentMicros, p.wire) }
                    .sortedBy { it.sentMicros }
            unmatchedAtClose = frozen
            return frozen
        }
    }

    /** The round trips so far, sorted, for a progress line's distribution. A copy: the run keeps writing. */
    fun roundTripsSoFar(): LongArray = synchronized(samples) { samples.toArray().also { it.sort() } }

    /** The per-second story so far, so the live document has one. Beside [roundTripsSoFar], by design. */
    fun bucketsSoFar(): List<SecondBucket> = synchronized(samples) { buckets() }

    /** The round-trip histogram so far. Thirty counts, whatever the run's size. */
    fun histogramSoFar(): List<Int> = synchronized(samples) { histogram.toList() }

    /** The most requests outstanding at once so far. Counted all along; it just never reached a live report. */
    fun pendingPeakSoFar(): Int = pendingPeak

    /** Per-lane completeness so far. Unanswered is only known once the settle window has closed. */
    fun perLaneSoFar(): List<LaneCounts> = synchronized(samples) { perLane(unmatchedAtClose.orEmpty()) }

    /** Everything, once. Closes the settle window if nobody has. */
    fun finish(): Result {
        val unmatched = closeSettle()
        synchronized(samples) {
            val sorted = samples.toArray().also { it.sort() }
            return Result(
                counts = snapshot(),
                roundTripsSorted = sorted,
                perSecond = buckets(),
                histogram = histogram.toList(),
                perLane = perLane(unmatched),
                unmatched = unmatched,
                specimens = specimens.toList(),
                pendingPeak = pendingPeak,
                captured = table?.counts().orEmpty(),
            )
        }
    }

    /** Per-second issued and matched counts, and the p95 of the round trips that landed in each second. */
    private fun buckets(): List<SecondBucket> = seconds.mapIndexed { s, a -> SecondBucket(s, a.issued, a.matched, a.p95Micros()) }

    /**
     * Completeness per lane: what it matched, what went unanswered, what came back twice.
     *
     * Completeness only, and deliberately. Per-lane *latency* would partly measure FixTool: one pacer loop
     * renders and sends every lane round-robin, so lane N leaves systematically later than lane 1 by about
     * (N−1) times the per-message cost. Small against a 3ms p50, systematic per lane, and therefore the
     * exact shape of a false finding. Completeness is unaffected by the ordering.
     */
    private fun perLane(unmatched: List<Unmatched>): List<LaneCounts> {
        val unansweredPer = unmatched.groupingBy { it.laneSlot }.eachCount()
        val slots = (laneMatched.keys + laneDuplicates.keys + unansweredPer.keys).sorted()
        return slots.map { slot ->
            val lane = laneHistograms[slot]
            LaneCounts(
                slot = slot,
                matched = laneMatched[slot] ?: 0,
                unanswered = (unansweredPer[slot] ?: 0).toLong(),
                duplicates = laneDuplicates[slot] ?: 0,
                p50Micros = lane?.let { percentileOf(it, P50) },
                p95Micros = lane?.let { percentileOf(it, P95) },
            )
        }
    }

    /**
     * The percentile of a log histogram, as the lower edge of the bucket the rank falls in.
     *
     * A bucket edge and not an interpolated value on purpose: an interpolation is a number nothing
     * measured, and the point of a lane table is which lane is out of line, not what its p95 was to
     * three figures. The aggregate percentiles are still exact, off the sorted samples.
     */
    private fun percentileOf(histogram: IntArray, percentile: Double): Long? {
        val total = histogram.sumOf { it.toLong() }
        if (total == 0L) return null
        val rank = Math.ceil(percentile * total).toLong().coerceIn(1, total)
        var seen = 0L
        histogram.forEachIndexed { index, count ->
            seen += count
            if (seen >= rank) return RoundTripHistogram.lowerMicros(index)
        }
        return RoundTripHistogram.lowerMicros(histogram.size - 1)
    }

    /** A growable primitive long array, so three hundred thousand round trips are not three hundred thousand boxes. */
    private class LongList {
        private var data = LongArray(INITIAL)
        var size = 0
            private set

        fun add(v: Long) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        fun toArray(): LongArray = data.copyOf(size)
    }

    companion object {
        const val DEFAULT_SPECIMENS = 50
        private const val NONE = Long.MIN_VALUE
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val INITIAL = 1_024
        private const val P50 = 0.50
        private const val P95 = 0.95

        /** The sample at position ⌈p·n⌉ of a sorted array, a measurement that actually happened. */
        fun nearestRank(sorted: LongArray, percentile: Double): Long {
            val rank = Math.ceil(percentile * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}
