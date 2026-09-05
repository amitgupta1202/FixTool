package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.WireDirection
import com.knapsack.fixtool.model.load.LoadMatch
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
class StampMatcher(
    private val match: LoadMatch,
    private val requestType: String,
    private val issuing: Set<SessionID>,
    private val laneOf: (SessionID) -> Int = { 0 },
    private val specimenLimit: Int = DEFAULT_SPECIMENS,
) {
    private class Pending(
        val sentMicros: Long,
        val laneSlot: Int,
        val wire: String,
    )

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

    /** Everything the matcher has to say once the run is over. */
    data class Result(
        val counts: Counts,
        /** Every matched round trip in microseconds, sorted ascending. */
        val roundTripsSorted: LongArray,
        val perSecond: List<SecondBucket>,
        val unmatched: List<Unmatched>,
        val specimens: List<Specimen>,
        val pendingPeak: Int,
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    private val matchedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
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
    private val sampleSeconds = IntList()
    private val issuedSeconds = IntList()
    private val specimens = ArrayList<Specimen>()

    /** Any thread, any session, every stamp. Does nothing with a message that is not the run's business. */
    fun onStamp(stamp: SocketStamp) {
        val sessionId = stamp.sessionId ?: return
        val type = WireTags.msgType(stamp.wire) ?: return
        when (stamp.direction) {
            WireDirection.SEND -> onSend(sessionId, type, stamp)
            WireDirection.RECEIVE -> onReceive(type, stamp)
        }
    }

    private fun onSend(sessionId: SessionID, type: String, stamp: SocketStamp) {
        if (sessionId !in issuing || type != requestType) return
        val id = WireTags.tagValue(stamp.wire, match.requestTag) ?: return
        pending[id] = Pending(stamp.micros, laneOf(sessionId), stamp.wire)
        leftSocket.incrementAndGet()
        val now = outstanding.incrementAndGet()
        if (now > pendingPeak) pendingPeak = now
        synchronized(samples) {
            if (firstSendMicros == NONE) firstSendMicros = stamp.micros
            if (stamp.micros > lastSendMicros) lastSendMicros = stamp.micros
            issuedSeconds.add(secondOf(stamp.micros))
        }
    }

    private fun onReceive(type: String, stamp: SocketStamp) {
        if (WireTags.isAdmin(type)) return
        if (match.replyType != null && type != match.replyType) return
        val id = WireTags.tagValue(stamp.wire, match.replyTag) ?: return
        val request = pending.remove(id)
        when {
            request != null -> {
                outstanding.decrementAndGet()
                matchedIds += id
                if (settleClosed) {
                    late.incrementAndGet()
                } else {
                    matched.incrementAndGet()
                    record(request, stamp)
                }
            }
            id in matchedIds -> duplicates.incrementAndGet()
            else -> strays.incrementAndGet()
        }
    }

    private fun record(request: Pending, reply: SocketStamp) {
        val rtt = (reply.micros - request.sentMicros).coerceAtLeast(0)
        synchronized(samples) {
            samples.add(rtt)
            sampleSeconds.add(secondOf(reply.micros))
            if (reply.micros > lastMatchedMicros) lastMatchedMicros = reply.micros
            if (specimens.size < specimenLimit) specimens += Specimen(request.wire, reply.wire, rtt)
        }
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

    /** Everything, once. Closes the settle window if nobody has. */
    fun finish(): Result {
        val unmatched = closeSettle()
        synchronized(samples) {
            val sorted = samples.toArray().also { it.sort() }
            return Result(
                counts = snapshot(),
                roundTripsSorted = sorted,
                perSecond = buckets(),
                unmatched = unmatched,
                specimens = specimens.toList(),
                pendingPeak = pendingPeak,
            )
        }
    }

    /** Per-second issued and matched counts, and the p95 of the round trips that landed in each second. */
    private fun buckets(): List<SecondBucket> {
        val issued = issuedSeconds.toArray()
        val matchedAt = sampleSeconds.toArray()
        val rtts = samples.toArray()
        val last = maxOf(issued.maxOrNull() ?: -1, matchedAt.maxOrNull() ?: -1)
        if (last < 0) return emptyList()
        val issuedPer = IntArray(last + 1)
        for (s in issued) issuedPer[s]++
        val matchedPer = IntArray(last + 1)
        val perSecondRtts = Array(last + 1) { LongList() }
        for (i in matchedAt.indices) {
            matchedPer[matchedAt[i]]++
            perSecondRtts[matchedAt[i]].add(rtts[i])
        }
        return (0..last).map { s ->
            val bucket = perSecondRtts[s].toArray().also { it.sort() }
            SecondBucket(s, issuedPer[s], matchedPer[s], bucket.takeIf { it.isNotEmpty() }?.let { nearestRank(it, P95) })
        }
    }

    /** A growable primitive long array, so three hundred thousand round trips are not three hundred thousand boxes. */
    private class LongList {
        private var data = LongArray(INITIAL)
        private var size = 0

        fun add(v: Long) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        fun toArray(): LongArray = data.copyOf(size)
    }

    private class IntList {
        private var data = IntArray(INITIAL)
        private var size = 0

        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        fun toArray(): IntArray = data.copyOf(size)
    }

    companion object {
        const val DEFAULT_SPECIMENS = 50
        private const val NONE = Long.MIN_VALUE
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val INITIAL = 1_024
        private const val P95 = 0.95

        /** The sample at position ⌈p·n⌉ of a sorted array, a measurement that actually happened. */
        fun nearestRank(sorted: LongArray, percentile: Double): Long {
            val rank = Math.ceil(percentile * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}
