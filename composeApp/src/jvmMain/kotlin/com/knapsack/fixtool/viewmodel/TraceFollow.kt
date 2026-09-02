package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.AppMessage
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.Traces
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **The cross-session grouping as the app currently holds it**, kept whole so nobody computes it twice.
 *
 * [snapshots] is positional and is what every [com.knapsack.fixtool.service.Located] in [grouping]
 * addresses: session *s* is `snapshots[s]`, named by `sessionTitles[s]`. Both are captured together at
 * the moment the grouping was computed, because a `Located` is only meaningful against the lists it was
 * derived from — a session closed a tick later would otherwise turn every index into a lie.
 *
 * FIX messages only: a session's own notices are [AppMessage]s that carry no wire fields and so can
 * belong to no exchange. Dropping them here is what makes `snapshots[session][index]` line up with what
 * `Traces.group` was handed.
 */
data class TraceIndex(
    val snapshots: List<List<FixMessage>>,
    val sessionTitles: List<String>,
    val grouping: Traces.Grouping,
)

/**
 * **The one exchange the whole app is following**, resolved against the current [TraceIndex].
 *
 * [uids] is the answer every pane asks for, and it is a set of [FixMessage.uid] rather than of indices
 * on purpose: a pane republishes its snapshot on its own drain tick, so at any instant a pane may hold
 * one tick more than the grouping saw. A uid is stable for the life of a message and outside
 * `FixMessage.equals`, so membership survives that skew; an index would silently point at a neighbour.
 *
 * [anchorId] is what the user actually clicked — the value, not the trace. It is kept because a trace
 * can grow a new label, be merged into a larger component, or not exist yet: an id minted three hops
 * into a venue's flow is followed the moment it is clicked and joins a trace when the reply arrives.
 * Until then this carries empty [uids] and zero counts rather than un-following itself.
 *
 * [truncatedSessionTitles] is [truncatedSessions] already resolved against the index that produced it.
 * The chip has to say *which pane* lost history, and pairing two independently published flows to build
 * one sentence is how a reader ends up with a session name from the wrong generation.
 */
data class FollowedTrace(
    val anchorId: String,
    val uids: Set<Long>,
    val sessionCount: Int,
    val messageCount: Int,
    val label: String,
    val truncatedSessions: List<Int>,
    val truncatedSessionTitles: List<String> = emptyList(),
) {
    /** Nothing in the current index carries [anchorId] — followed, but not yet arrived. */
    val pending: Boolean get() = uids.isEmpty()
}

/**
 * **Follow one exchange through every pane at once.**
 *
 * **Why this is app-level when grouping is per-pane.** Grouping and its collapse set live on
 * `FixMessageSession` because they are ways of looking at ONE pane, and a both-sides test puts the same
 * conversation label in two panes — folding `STREAM-A` in the venue pane must not fold the client's
 * (commit 55b5da8). Follow is the opposite kind of thing by definition: the question it answers is
 * *what happened to RFQ-A1 across every session*, and an answer held per pane is the regex-in-each-box
 * workflow this feature exists to replace — typed n times, stale in n places, and silently different in
 * each. So there is exactly one followed trace, and every pane derives its narrowing from it.
 *
 * **Nothing here writes into session state.** A pane narrows because [FollowedTrace.uids] is ANDed with
 * its own filters at render time, so stopping restores the pane exactly — its regex, its direction
 * boxes and its message types were never touched. That is the whole difference between this and the
 * global filter box, which used to overwrite each pane's own filter to say the same thing.
 *
 * **Recompute is memoised on snapshot identity.** Each session publishes an immutable list on its drain
 * tick and replaces it wholesale, so `===` per session is an exact test for "this pane changed", and a
 * quiet app regroups nothing however often it is asked. It is also gated on being *wanted*: with
 * nothing followed and the panel shut, the index is dropped rather than maintained, because a grouping
 * nobody is reading is pure cost on the 100 ms tick.
 *
 * Not thread-safe by design: one caller drives [refresh] from one coroutine, and the results are
 * published through `StateFlow`s that any thread may read.
 */
class TraceFollow {
    /**
     * One session on its way into a regroup: what it is called, what it is holding, what it has lost.
     *
     * [lostIds] is passed by reference and never copied. `FixMessageSession.lostCorrelationIds` is a
     * live concurrent set that may hold ten thousand values, and `Traces.group` only probes it — copying
     * it ten times a second per pane to gain nothing is exactly the cost this class is memoised to avoid.
     */
    data class Input(
        val title: String,
        val messages: List<AppMessage>,
        val lostIds: Set<String> = emptySet(),
    )

    private val _followedTrace = MutableStateFlow<FollowedTrace?>(null)
    val followedTrace: StateFlow<FollowedTrace?> = _followedTrace.asStateFlow()

    private val _traceIndex = MutableStateFlow<TraceIndex?>(null)
    val traceIndex: StateFlow<TraceIndex?> = _traceIndex.asStateFlow()

    private val _tracePanelOpen = MutableStateFlow(false)
    val tracePanelOpen: StateFlow<Boolean> = _tracePanelOpen.asStateFlow()

    /** The id the user clicked. The trace is derived; this is the thing that was asked for. */
    private var anchorId: String? = null

    /** The snapshot list identities the current [traceIndex] was built from. Compared by `===`. */
    private var sources: List<List<AppMessage>> = emptyList()
    private var sourceDictionary: FixDictionaryAdapter? = null

    /** How many times a grouping was actually computed — the memo's behaviour, made testable. */
    var regroupCount: Long = 0L
        private set

    /** Whether anyone is reading the index: something followed, or the panel open. */
    val wanted: Boolean get() = anchorId != null || _tracePanelOpen.value

    /**
     * Follow [id]. There is one followed trace, so this replaces whatever was followed before.
     *
     * The set does not become visible until the next [refresh]; the caller pumps one immediately so a
     * click narrows the panes on the click rather than on the next tick.
     */
    fun follow(id: String) {
        anchorId = id
    }

    fun unfollow() {
        anchorId = null
        _followedTrace.value = null
    }

    fun openTracePanel() {
        _tracePanelOpen.value = true
    }

    /** Closing the panel does not unfollow: the panes stay narrowed, which is what the chip says. */
    fun closeTracePanel() {
        _tracePanelOpen.value = false
    }

    fun toggleTracePanel() {
        _tracePanelOpen.value = !_tracePanelOpen.value
    }

    /**
     * Bring the index and the followed set up to date with what the sessions are holding now.
     *
     * Cheap and safe to call on every tick: it regroups only when some session's published snapshot is a
     * different object than the one it last grouped (or the dictionary changed underneath, which changes
     * which tags draw edges at all), and does nothing whatever while nothing is [wanted].
     *
     * [inputs] is read positionally and never retained beyond this call's memo key, so sessions added or
     * closed between ticks are simply a different list — no `Located` from a previous generation is ever
     * consulted, which is why closing a pane cannot leave the followed set pointing into it.
     */
    fun refresh(
        inputs: List<Input>,
        dictionary: FixDictionaryAdapter?,
    ) {
        if (!wanted) {
            // Drop the index rather than keep maintaining it: with nothing followed and the panel shut,
            // it is a few thousand positions nobody can see.
            sources = emptyList()
            sourceDictionary = null
            _traceIndex.value = null
            _followedTrace.value = null
            return
        }
        val incoming = inputs.map { it.messages }
        if (_traceIndex.value == null || dictionary !== sourceDictionary || !sameSnapshots(incoming)) {
            val snapshots = inputs.map { input -> input.messages.filterIsInstance<FixMessage>() }
            regroupCount++
            _traceIndex.value =
                TraceIndex(
                    snapshots = snapshots,
                    sessionTitles = inputs.map { it.title },
                    grouping = Traces.group(snapshots, dictionary, inputs.map { it.lostIds }),
                )
            sources = incoming
            sourceDictionary = dictionary
        }
        _followedTrace.value = anchorId?.let { id -> resolve(id, _traceIndex.value) }
    }

    /** Identity, element by element — the snapshots are immutable, so this is the whole question. */
    private fun sameSnapshots(incoming: List<List<AppMessage>>): Boolean {
        if (incoming.size != sources.size) return false
        for (index in incoming.indices) if (incoming[index] !== sources[index]) return false
        return true
    }

    /**
     * The trace carrying [id], as a followed set.
     *
     * A missing trace is not a failure to follow. The anchor may be an id the venue has not echoed yet,
     * or one whose only message has just been cleared; either way the honest answer is "following, with
     * nothing to show", and un-following on the user's behalf would lose the gesture they made.
     */
    private fun resolve(
        id: String,
        index: TraceIndex?,
    ): FollowedTrace {
        val trace = index?.grouping?.traces?.firstOrNull { id in it.ids }
        if (index == null || trace == null) {
            return FollowedTrace(
                anchorId = id,
                uids = emptySet(),
                sessionCount = 0,
                messageCount = 0,
                label = id,
                truncatedSessions = emptyList(),
            )
        }
        val uids = HashSet<Long>(trace.members.size * 2)
        for (member in trace.members) {
            val message =
                index.snapshots
                    .getOrNull(member.session)
                    ?.getOrNull(member.index)
            if (message != null) uids += message.uid
        }
        return FollowedTrace(
            anchorId = id,
            uids = uids,
            sessionCount = trace.sessions.size,
            messageCount = trace.members.size,
            label = trace.label,
            truncatedSessions = trace.truncatedSessions,
            truncatedSessionTitles = trace.truncatedSessions.mapNotNull { index.sessionTitles.getOrNull(it) },
        )
    }
}
