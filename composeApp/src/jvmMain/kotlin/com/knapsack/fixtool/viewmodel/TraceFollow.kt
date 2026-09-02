package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.AppMessage
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.LaneRole
import com.knapsack.fixtool.service.TraceKey
import com.knapsack.fixtool.service.TraceRows
import com.knapsack.fixtool.service.Traces
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    /**
     * Which side of the wire each pane holds, captured at the same instant as [snapshots].
     *
     * Positional like everything else here, and gathered with the snapshots rather than read off the
     * sessions when Lanes wants it, for the reason this whole type exists: the list of panes is rebuilt
     * every tick, so a role read one tick later than the snapshot it labels would put an initiator's
     * word above an acceptor's column the first time a pane is opened or closed. Empty means nobody
     * asked, which `TraceLanes.build` reads as [LaneRole.UNKNOWN] rather than as a guess.
     */
    val sessionRoles: List<LaneRole> = emptyList(),
)

/** Which drawing of the trace the panel is showing. One panel, two renderings of the same rows. */
enum class TraceRendering {
    LEDGER,
    LANES,
}

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
 * **Two threads reach [refresh], so the mutating path is synchronized.** The ticker calls it on
 * `Dispatchers.Default` every 100 ms, and a click calls it straight through `follow()` /
 * `openTracePanel()` on the UI thread so the panes narrow on the click rather than on the next tick —
 * which means both can be inside it at once. The memo's fields are plain vars, and the damage is worse
 * than a wasted regroup: interleave the two and [sources] can end up recording one call's snapshots
 * while [traceIndex] holds the other's, after which the identity check passes forever and the index
 * never updates again. `@Synchronized` on the three methods that write them costs an uncontended lock
 * per tick and removes the whole class of it. Reads go on being lock-free: results are published
 * through `StateFlow`s that any thread may collect.
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
        /** Read off the pane's profile — see [LaneRole]. Defaulted so a test that has no profile says so. */
        val role: LaneRole = LaneRole.UNKNOWN,
    )

    private val _followedTrace = MutableStateFlow<FollowedTrace?>(null)
    val followedTrace: StateFlow<FollowedTrace?> = _followedTrace.asStateFlow()

    private val _traceIndex = MutableStateFlow<TraceIndex?>(null)
    val traceIndex: StateFlow<TraceIndex?> = _traceIndex.asStateFlow()

    private val _tracePanelOpen = MutableStateFlow(false)
    val tracePanelOpen: StateFlow<Boolean> = _tracePanelOpen.asStateFlow()

    /**
     * **Which traces the Ledger is showing the messages of.**
     *
     * Collapsed is the default and expansion is the exception, because the panel's first job is the
     * question a QA cannot answer today — *which exchanges crossed more than one session* — and that is
     * read off the headers. A Ledger that opened every trace would bury its own headers under a few
     * thousand rows the moment it appeared.
     *
     * App-level and keyed by [TraceKey], not per pane: this is one view of one grouping, so there is one
     * fold set. See [TraceKey] for why the key names the opener's session by title.
     */
    private val _expandedTraces = MutableStateFlow<Set<TraceKey>>(emptySet())
    val expandedTraces: StateFlow<Set<TraceKey>> = _expandedTraces.asStateFlow()

    /**
     * The ungrouped bucket's own fold, separate because it is not a trace and has no [TraceKey].
     *
     * Collapsed by default like everything else, and *present* by default like nothing else: it is what
     * the grouping could not explain, so it is counted on screen whether or not anyone opens it.
     */
    private val _ungroupedExpanded = MutableStateFlow(false)
    val ungroupedExpanded: StateFlow<Boolean> = _ungroupedExpanded.asStateFlow()

    /**
     * **Which of the panel's two drawings is on screen.**
     *
     * App-level beside the fold set, because it is one property of one panel. [TraceRendering.LEDGER] is
     * the default and has to be: the Ledger answers *what is running* over every trace at once, which is
     * the question a reader has before they have chosen anything, while Lanes draws exactly one exchange
     * and has nothing to show until one is followed. Opening on Lanes would open on an empty picture.
     *
     * Deliberately not cleared by [unfollow]. A reader who was reading Lanes and stopped following is
     * still reading Lanes, and switching the panel out from under them would be the view second-guessing
     * a gesture that was about the filter, not about the drawing.
     */
    private val _traceRendering = MutableStateFlow(TraceRendering.LEDGER)
    val traceRendering: StateFlow<TraceRendering> = _traceRendering.asStateFlow()

    /** The id the user clicked. The trace is derived; this is the thing that was asked for. */
    private var anchorId: String? = null

    /**
     * The anchor whose trace has already been auto-expanded once.
     *
     * Following opens the trace it followed — that is the gesture and the view being one thought — but
     * only the first time it resolves. Re-expanding it on every tick would make the fold chevron a
     * control that undoes itself a tenth of a second later, and the reader is entitled to collapse the
     * thing they are following and go on following it.
     */
    private var seededAnchor: String? = null

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
    @Synchronized
    fun follow(id: String) {
        anchorId = id
        // A new anchor has not been auto-expanded yet, and following the same id twice deliberately
        // re-opens it: the second press is a reader asking to see it again.
        seededAnchor = null
    }

    @Synchronized
    fun unfollow() {
        anchorId = null
        seededAnchor = null
        _followedTrace.value = null
    }

    /** Fold or unfold one trace. The reader's choice outranks everything, including [follow]'s. */
    fun toggleTrace(key: TraceKey) {
        _expandedTraces.update { if (key in it) it - key else it + key }
    }

    fun toggleUngrouped() {
        _ungroupedExpanded.value = !_ungroupedExpanded.value
    }

    fun setTraceRendering(rendering: TraceRendering) {
        _traceRendering.value = rendering
    }

    /**
     * Open everything the panel is currently showing, the bucket included.
     *
     * [keys] rather than "all traces I know of", because the caller is the panel and what it is showing
     * is the list it just built — expanding traces that scrolled out of a stale index would be a fold
     * set describing a view nobody has.
     */
    fun expandAll(keys: Collection<TraceKey>) {
        _expandedTraces.value = keys.toSet()
        _ungroupedExpanded.value = true
    }

    fun collapseAll() {
        _expandedTraces.value = emptySet()
        _ungroupedExpanded.value = false
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
    @Synchronized
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
                    sessionRoles = inputs.map { it.role },
                )
            sources = incoming
            sourceDictionary = dictionary
        }
        val id = anchorId
        val index = _traceIndex.value
        val trace = if (id == null) null else index?.grouping?.traces?.firstOrNull { id in it.ids }
        _followedTrace.value = id?.let { resolve(it, index, trace) }
        // The gesture and the view are one thought: what you followed is what the Ledger opens on. Once
        // — see [seededAnchor] — and only when there is something to open, so an anchor the venue has
        // not echoed yet expands the moment it arrives rather than never.
        if (id != null && trace != null && index != null && id != seededAnchor) {
            seededAnchor = id
            val key = TraceRows.keyOf(trace, index.sessionTitles)
            _expandedTraces.update { it + key }
        }
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
        trace: Traces.Trace?,
    ): FollowedTrace {
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
