package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.WireDirection
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.service.SocketStamp
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.LockSupport

/**
 * **A load set, start to verdict: several runs on lanes that outlive any one of them.**
 *
 * Everything inside a phase is [LoadRunner] exactly as it is. The set owns the four things one run never
 * had to:
 *
 * - **Lanes that outlive a phase.** Opened once, before phase 1, for every profile any phase names, with
 *   the store override applied once. Opening lazily per phase would put a logon inside the set's clock and
 *   draw it on the timeline as phase time.
 * - **A seed that outlives a phase.** Rendered before this by [LoadSet.plan], and carried by every phase's
 *   plan, so phase 2 addresses the ids phase 1 minted without anything being passed between them.
 * - **The routing of a reply to the phase that asked for it.** See [Router]. Against a matching venue this
 *   is not a corner case: phase 1's orders keep drawing fills while phase 2 cancels them.
 * - **When each phase may start.** See [Conductor]. Every phase runs on a thread of its own, so a phase
 *   that reacts to another can run beside it rather than after it.
 *
 * **What "strictly in order" now means.** A phase that names no trigger waits for every earlier phase and
 * for everything that reacts to one, transitively, so a set of paced phases runs exactly as it always did:
 * one at a time, in the order it was written. A phase that names a trigger starts when that phase starts,
 * and issues one message for each of that phase's requests as the venue answers it, which is what turns a
 * three-phase RFQ set from three blocks into a chain per message.
 *
 * The record is written as the set goes, so a set killed at phase three leaves phases one and two complete
 * on disk, and the morning after an overnight set the answer is in `loads/<id>/load.json` whether or not
 * the app is still open.
 */
class LoadSetRunner(
    private val host: LoadHost,
    private val store: LoadRecordStore? = null,
    private val clock: Pacer.Clock = Pacer.Clock.SYSTEM,
) {
    /**
     * **Handed the set's trigger buffers once, before any phase starts.**
     *
     * `internal` because a test watches them close, which is the half of the deadlock nothing else can
     * see: no phase reads a buffer yet, so the record says a dependant was skipped and says nothing at all
     * about what would have fired it. See [TriggerBuffer].
     */
    internal var onTriggerBuffers: (List<TriggerBuffer>) -> Unit = {}

    /**
     * **How a phase's thread is made**, so a test can hand back one that refuses to start.
     *
     * `internal` for the reason [onTriggerBuffers] is: the failure that matters most about asking a
     * machine for a thread per phase cannot be staged from outside. A machine with none left answers
     * `Thread.start()` with an `OutOfMemoryError` rather than with a thread, and what the set does about
     * that is the difference between a phase that ends and a phase parked at a gate nobody will open.
     */
    internal var newThread: (String, () -> Unit) -> Thread = { name, body -> Thread(body, name) }

    /**
     * **The reply router: one listener per session for the whole set, and every stamp to the phase that
     * issued it.**
     *
     * A phase owns a stamp when it can name it: a reply carrying an id it has outstanding or has already
     * matched, a send carrying an id it handed over on its way to the socket. The router asks each
     * registered phase in turn, newest first, and offers the stamp only to the one that says yes. Asking
     * rather than reading is the point of it. Two phases of one set can wait on different tags of
     * different message types, so there is no single id the router could pull off a stamp and hold up to
     * everybody: only a matcher knows how a matcher reads an id. See [StampMatcher.owns].
     *
     * A phase stays registered after its run ends, because a reply to something it issued is still its own
     * however long after it ended it arrives. A fill drawn by one of phase 1's orders and landing while
     * phase 2 cancels is phase 1's late reply, not a phase 2 stray.
     *
     * **A send goes to its issuer or nowhere.** Two phases can legitimately issue the same MsgType on the
     * same lanes, and a phase offered a send it never made would take it into its own `pending` and its
     * own per-second buckets, quietly corrupting numbers it had already reported.
     *
     * **A reply nobody issued is a stray**, charged once to the newest phase waiting for a reply of that
     * shape, which is the phase whose "nothing matched" diagnosis has to explain it. Once and not once per
     * phase: the offers stop at the first phase that recognises the shape.
     */
    private class Router {
        /** One phase's matcher and the number the whole set knows that phase by. */
        private class Registered(
            val phase: Int,
            val matcher: StampMatcher,
        )

        /**
         * Newest phase first, which is the order a reply is most likely to belong to and the phase a stray
         * belongs to. Ordered by phase number rather than by arrival, because a phase's place in the set is
         * what "newest" means and phases will not always start in that order.
         */
        private val registered = CopyOnWriteArrayList<Registered>()

        fun onStamp(stamp: SocketStamp) {
            val owner = registered.firstOrNull { it.matcher.owns(stamp) }
            if (owner != null) {
                owner.matcher.offer(stamp)
                return
            }
            // Nobody issued it. A send stops here: only its issuer may count it. A reply is somebody's
            // stray, and whose is decided by the first phase that recognises the shape it arrived in.
            if (stamp.direction == WireDirection.SEND) return
            registered.firstOrNull { it.matcher.offer(stamp) == StampMatcher.Claim.UNKNOWN }?.matcher?.countStray()
        }

        /**
         * Puts [matcher] in as phase [phase]. Closing stops nothing: the phase keeps its stamps for the
         * length of the set, which is what a late reply needs and what the record re-reads for it.
         *
         * **Under one lock, because finding the place and putting it there are two calls.** A
         * `CopyOnWriteArrayList` makes each of them atomic and neither of them atomic together, so two
         * phases registering at the same moment can both read the same place and leave the list ordered
         * `[2, 3, 1]` where the whole point of it is `[3, 2, 1]`. What that costs is a stray charged to
         * the wrong phase, and where two phases legitimately reuse an id, one phase's fill handed to the
         * other as a duplicate. Contended once per phase and read without a lock, as it was.
         */
        @Synchronized
        fun register(phase: Int, matcher: StampMatcher): AutoCloseable {
            val at = registered.indexOfFirst { it.phase < phase }
            registered.add(if (at < 0) registered.size else at, Registered(phase, matcher))
            return AutoCloseable { }
        }
    }

    /**
     * The set's own [LoadHost], handed to each phase: the lanes are already open and `release()` does
     * nothing, because the set releases after the last phase.
     */
    private class HeldLanes(
        private val delegate: LoadHost,
        private val byProfile: Map<String, List<LoadLane>>,
    ) : LoadHost {
        override fun openLanes(profileId: String, override: StoreAndLogOverride?): List<LoadLane> =
            byProfile[profileId].orEmpty()

        /**
         * A phase listening on a profile that also issues somewhere gets that profile's whole lane list,
         * because the set holds one set of sessions per profile and there is no second one to hand back.
         */
        override fun openListeners(profileIds: List<String>, override: StoreAndLogOverride?): List<LoadLane> =
            profileIds.flatMap { byProfile[it].orEmpty() }

        override fun resolveOnce(template: String, scope: Map<String, String>, lane: LoadLane): String =
            delegate.resolveOnce(template, scope, lane)

        override fun dictionary(): FixDictionaryAdapter = delegate.dictionary()

        /** The set releases, once, after the last phase. A phase releasing would log the lanes out mid-set. */
        override fun release() = Unit

        override fun now(): Long = delegate.now()

        override fun sleep(ms: Long) = delegate.sleep(ms)
    }

    /**
     * **An interrupt that lands on the thread running the set, held until the set has ended.**
     *
     * Stopping a set is a polled flag and never an interrupt (R19), so an interrupt arriving on this
     * thread is somebody else's and is theirs to be told about. Telling them by leaving the flag set part
     * way through the ending takes the rest of the ending away, because `Thread.join` is interruptible and
     * throws the moment it is called: the record writer would never be joined, the last record would never
     * be written, and the caller would be handed an `InterruptedException` where a record was due.
     *
     * So every join the set does swallows it, and the last line of [run] hands it back, once, with
     * nothing left behind it to skip.
     */
    internal class Interrupts {
        @Volatile private var caught = false

        /** Joins [thread] however often this one is interrupted while it waits. */
        @Suppress("SwallowedException")
        fun join(thread: Thread) {
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (e: InterruptedException) {
                    caught = true
                }
            }
        }

        /** The last line of the set, where whoever interrupted it is told, once and in one place. */
        fun handBack() {
            if (caught) Thread.currentThread().interrupt()
        }
    }

    /**
     * **The record on disk, written by one thread, and always the latest one there is.**
     *
     * A phase publishes about four times a second while it is issuing and once a poll while it is
     * settling, and every one of those writes used to happen on the phase's own thread, in the middle of
     * its schedule. With phases running beside each other that is the write rate multiplied by the phase
     * count, all of it inside one lock the phases would then be queueing for, and the file could end up
     * holding an older picture than one already written.
     *
     * So a phase hands a finished record over and goes back to its schedule, and one thread writes
     * whichever record is the latest when it gets there. A tick nobody had time to write is a tick the
     * next one replaces, which is the right answer: the record is a picture of now and not a log of every
     * now there was, and every reader of it re-reads the file anyway.
     */
    internal class RecordWriter(
        /**
         * **What a record is written with**, null when the set keeps none, which is every test that reads
         * the answer rather than the file. The write and not the store, because what this owns is "the
         * latest one there is, once" and not where a record lives, and a test can then hand it one that
         * takes its time.
         */
        private val write: ((LoadRecord) -> Unit)?,
        private val interrupts: Interrupts = Interrupts(),
    ) : AutoCloseable {
        private val latest = AtomicReference<LoadRecord?>()

        /** False from the first [close] on. Atomic, so the drain below it happens once and not twice. */
        private val open = AtomicBoolean(true)

        private val thread =
            write?.let {
                Thread(::drain, "fixtool-load-record").apply {
                    isDaemon = true
                    start()
                }
            }

        /**
         * Never blocks the phase that called it, and never for a disk.
         *
         * **A record offered after the close is dropped.** The set has written the record it finished
         * with, and an older picture landing on top of that is the one thing this exists to prevent.
         * Nothing offers there today, because every phase is joined before the first close.
         */
        fun offer(record: LoadRecord) {
            val writer = thread ?: return
            if (!open.get()) return
            latest.set(record)
            LockSupport.unpark(writer)
        }

        private fun drain() {
            while (open.get()) {
                val next = latest.getAndSet(null)
                if (next == null) LockSupport.park(this) else write?.invoke(next)
            }
        }

        /**
         * Nothing more is coming, and whatever the writer was doing is finished before this returns, so
         * the record the set writes last is the record left on disk. Idempotent, because it is called
         * once where the phases end and once more in the teardown that runs whatever happened, and both
         * of those are the set's own thread.
         *
         * The join is [Interrupts]' and not a plain one, because a plain join on a thread whose interrupt
         * flag is set throws at once: the writer would be left running, the record it was holding would
         * never be written, and the caller would be handed an `InterruptedException` where a record was
         * due. Every join the set does is that join, and the interrupt goes back at the end of [run].
         */
        override fun close() {
            if (!open.getAndSet(false)) return
            thread?.let {
                LockSupport.unpark(it)
                interrupts.join(it)
            }
            // Whatever was offered and never written, so a set that threw still leaves its last picture.
            latest.getAndSet(null)?.let { write?.invoke(it) }
        }
    }

    /**
     * **What the phases of a set write between them, behind one lock.**
     *
     * Three things are written by every phase and read by the record: the phase reports, the matchers a
     * finished phase's late replies keep arriving at, and the lowest-numbered phase that did not pass. A
     * set that ran its phases one at a time could hold them in plain lists. With two phases at once,
     * `reports[index] = …` from two threads is a lost write, and a record assembled while another phase is
     * halfway through writing its own is a picture of a moment that never happened.
     *
     * So one lock covers all three and the publish that reads them. It also serialises `onProgress`, which
     * is what the CLI's narrator and the app's view state were written against: they are handed records
     * one at a time, in the order the records were made. The disk write is the one thing kept out from
     * under it, because it is the one thing that waits on something. See [RecordWriter].
     */
    private class Board(
        private val planned: LoadSet.Planned,
        stubs: List<LoadReport>,
        private val startedAt: Long,
        private val now: () -> Long,
        private val writer: RecordWriter,
        private val onProgress: (LoadRecord) -> Unit,
    ) {
        private val lock = Any()
        private val reports = stubs.toMutableList()
        private val matchers = arrayOfNulls<StampMatcher>(stubs.size)
        private var lowestFailure: Int? = null

        /** The lowest-numbered phase that did not pass, or null while none has. */
        val failedPhase: Int? get() = synchronized(lock) { lowestFailure }

        /** Phase [index]'s matcher, kept for the set: a finished phase's late count keeps growing. */
        fun register(index: Int, matcher: StampMatcher) = synchronized(lock) { matchers[index] = matcher }

        /** Phase [index] is here now. Published, because the live document is drawn from these. */
        fun update(index: Int, report: LoadReport) =
            synchronized(lock) {
                reports[index] = report
                publishLocked()
            }

        /**
         * **Phase [index] has a verdict**, which is where a failure the set stops on comes from.
         *
         * The **lowest-numbered** failure and not the first to finish. With phases overlapping those are
         * different phases, and `skipNote` and [LoadRecord.verdict] both name the lowest.
         */
        fun judged(index: Int, report: LoadReport) =
            synchronized(lock) {
                reports[index] = report
                val n = index + 1
                val lowest = lowestFailure
                if (report.verdict.exitCode != LoadReport.EXIT_PASSED && (lowest == null || n < lowest)) {
                    lowestFailure = n
                }
                publishLocked()
            }

        /** Phase [index] is not going to run, for the reason [note] gives. */
        fun skipped(index: Int, note: String) =
            synchronized(lock) {
                reports[index] = reports[index].copy(status = LoadStatus.SKIPPED, note = note, finishedAt = now())
                publishLocked()
            }

        /**
         * **The chain blocks, once every phase has ended and its times are settled.**
         *
         * On the last phase of each chain, because a chain has one end. Published like everything else,
         * so the record on disk and the document on screen both hold the block the set finished with.
         */
        fun attachChains(chains: Map<Int, LoadReport.Chain>) =
            synchronized(lock) {
                if (chains.isEmpty()) return@synchronized
                chains.forEach { (index, chain) -> reports[index] = reports[index].copy(chain = chain) }
                publishLocked()
            }

        fun publish() = synchronized(lock) { publishLocked() }

        /** The record as it stands, which is what the set hands back. */
        fun record(): LoadRecord = synchronized(lock) { recordLocked() }

        private fun publishLocked() {
            val current = recordLocked()
            writer.offer(current)
            onProgress(current)
        }

        private fun recordLocked(): LoadRecord =
            LoadRecord(
                id = planned.id,
                label = planned.label,
                startedAt = startedAt,
                finishedAt = if (reports.any { it.status.isLive }) null else now(),
                phases = reports.mapIndexed { i, r -> withLate(r, matchers[i]) },
                set = LoadRecord.SetInfo(planned.name, planned.onFailure),
                seed = planned.seed,
            )

        /** A finished phase's late count, re-read from its matcher, because a reply to it can still arrive. */
        private fun withLate(report: LoadReport, matcher: StampMatcher?): LoadReport {
            if (matcher == null || report.status != LoadStatus.DONE) return report
            val late = matcher.snapshot().late
            return if (late == report.replies.late) report else report.copy(replies = report.replies.copy(late = late))
        }
    }

    /**
     * **The phases of one set, each on a thread of its own, each held at its own gate.**
     *
     * **A thread per phase and not a pool.** The pacer parks and then spins to its mark, which is a thread
     * doing nothing anybody else could use, so a phase borrowing a shared dispatcher's thread would hold
     * it for the length of the phase. A thread of its own also gets a name, and `fixtool-load-phase-2` in
     * a thread dump of a stalled set is the difference between a diagnosis and a guess.
     *
     * **The gate, and what "strictly in order" means now.** A phase that names no trigger waits for every
     * earlier phase and for everything that reacts to one, transitively, so a set of paced phases runs
     * exactly as it always did. A phase that names a trigger waits only for that phase to **start**, which
     * is what a phase answering another's replies needs: waiting for the trigger to finish is the block it
     * exists to break up. From then on its own messages are released one at a time by [releasesOf], as
     * that phase's requests are answered, and it ends when that phase's settle window closes.
     *
     * **Nothing is ever interrupted (R19).** Stopping is the polled flag it always was, passed to each
     * phase and read by its pacer and its settle loop. An interrupt would come out of `RenderAhead.next`,
     * `LoadHost.sleep` or `Clock.awaitUntil` as an `InterruptedException` from the middle of
     * `LoadRunner.run`, past the STOPPED verdict it would otherwise reach, leaving the phase with no
     * report at all.
     *
     * **Every phase is joined before anything is re-thrown.** The teardown after this closes every stamp
     * listener and releases the host, which in the app begins a disconnect and a reconnect. A phase still
     * issuing while that happens loses every stamp it had left and has its sessions cycled underneath it,
     * so a phase that threw waits for its neighbours before its exception goes anywhere.
     */
    @Suppress("LongParameterList")
    private inner class Conductor(
        private val planned: LoadSet.Planned,
        private val board: Board,
        private val held: LoadHost,
        private val router: Router,
        private val table: StampMatcher.CaptureTable?,
        private val times: ChainTimes?,
        private val triggers: List<TriggerBuffer>,
        private val cancelled: () -> Boolean,
        private val interrupts: Interrupts,
    ) {
        private val size = planned.phases.size

        /** Counted down when a phase begins, which is what a phase reacting to it waits for. */
        private val started = List(size) { CountDownLatch(1) }

        /** Counted down when a phase is over, however it ended, which is what a paced phase waits for. */
        private val finished = List(size) { CountDownLatch(1) }

        /**
         * What each phase threw, or null. An atomic array rather than a plain one because a phase reads
         * whether its neighbours are still standing without having waited on a latch of theirs.
         */
        private val failures = AtomicReferenceArray<Throwable>(size)

        /**
         * Whether each phase got as far as issuing, which is what a phase reacting to it has to know: a
         * trigger that was skipped is a trigger that will never fire. Written before [started] is counted
         * down, which is the edge that makes it readable by the phase waiting on it.
         */
        private val ran = BooleanArray(size)

        /** Every phase a paced phase waits for: the ones before it, and everything rooted in one. */
        private val blockers: List<List<Int>> =
            (0 until size).map { index -> (0 until size).filter { it != index && rootOf(it) < index } }

        fun conduct() {
            // A muted phase never runs, so nothing may ever wait on it and nothing it would have fired
            // will be. Opened here rather than on a thread of its own, because it has no thread.
            planned.phases.forEachIndexed { index, plan ->
                if (!plan.muted) return@forEachIndexed
                started[index].countDown()
                closeDependants(index)
                finished[index].countDown()
            }
            val threads =
                planned.phases.mapIndexedNotNull { index, plan ->
                    if (plan.muted) {
                        null
                    } else {
                        index to newThread("fixtool-load-phase-${index + 1}") { phase(index) }.apply { isDaemon = true }
                    }
                }
            try {
                startAll(threads)
            } finally {
                joinAll(threads.map { it.second })
            }
            (0 until size).firstNotNullOfOrNull { failures.get(it) }?.let { throw it }
        }

        /**
         * **Starting a thread is the one thing here that can fail before any phase has run.**
         *
         * Fifty lanes over three phases is a hundred and fifty-three threads, and a machine with none
         * left answers `start()` with an `OutOfMemoryError`. Letting that out of here would skip the
         * joins beneath it and run the teardown, which closes every stamp listener and releases the
         * host, under the phases that did start: in the app, a disconnect and a reconnect beneath a
         * phase still issuing. It would also leave the unstarted phase's `finished` uncounted, and a
         * phase gated on that one waits at its gate for the rest of the process.
         *
         * So the failure is recorded against the phase that could not start and against every phase
         * behind it, and each of them is counted out by hand exactly as its own thread's `finally`
         * would have counted it. Behind it as well as itself, because a machine that could not make
         * this thread will not make the next one either. Nothing is published from here: the throwable
         * goes back to the caller whatever happens, and a machine that has just run out of threads is a
         * poor place to build a record.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun startAll(threads: List<Pair<Int, Thread>>) {
            threads.forEachIndexed { at, (_, thread) ->
                try {
                    thread.start()
                } catch (t: Throwable) {
                    threads.drop(at).forEach { (index, _) -> abandon(index, t) }
                    return
                }
            }
        }

        /** A phase that never got a thread, counted out of the set exactly as its own thread would have. */
        private fun abandon(index: Int, cause: Throwable) {
            failures.set(index, cause)
            started[index].countDown()
            closeDependants(index)
            finished[index].countDown()
        }

        /**
         * **Every phase joined, whatever happened to any of them and whatever happens to this thread.**
         *
         * Uninterruptibly, because an interrupt on the thread that started the set would otherwise skip
         * the joins and let the teardown cycle the sessions under a phase that is still issuing. Stopping
         * a set is a polled flag and never an interrupt, so an interrupt arriving here is somebody else's.
         * It is handed back at the end of [run] and not here: the next thing this thread does is join the
         * record writer, and handing it back here would take that join and the last record with it. See
         * [Interrupts].
         */
        private fun joinAll(threads: List<Thread>) = threads.forEach { interrupts.join(it) }

        @Suppress("TooGenericExceptionCaught")
        private fun phase(index: Int) {
            try {
                awaitGate(index)
                val skip = skipNote(index)
                // Before the latch below, so a phase waiting on this one's start reads a settled answer.
                if (skip == null) ran[index] = true
                started[index].countDown()
                if (skip != null) {
                    board.skipped(index, skip)
                    return
                }
                issue(index)
            } catch (t: Throwable) {
                // Every Throwable, and not LoadRefused: in a set none of LoadRunner's refusals is
                // reachable, because templates, lanes and unseeded names are all judged before phase 1
                // dials. What escapes here is a render error, a script error or an InvalidMessage.
                failures.set(index, t)
            } finally {
                started[index].countDown()
                closeDependants(index)
                finished[index].countDown()
            }
        }

        /** Waits until this phase may start. See the class note for what each kind of phase waits for. */
        private fun awaitGate(index: Int) {
            val trigger = triggerOf(index)
            if (trigger != null) started[trigger].await() else blockers[index].forEach { finished[it].await() }
        }

        /** Why this phase will not run, or null when it will. Asked the moment its gate opens. */
        private fun skipNote(index: Int): String? {
            val trigger = triggerOf(index)
            val broken = (0 until size).firstOrNull { failures.get(it) != null }?.plus(1)
            val failed = board.failedPhase
            return when {
                cancelled() -> LoadRecord.STOPPED_NOTE
                trigger != null && !ran[trigger] ->
                    "phase ${trigger + 1} did not run, so nothing would have fired this one"
                broken != null -> "phase $broken could not run and the set stops there"
                failed != null && planned.onFailure == OnFailure.STOP ->
                    "phase $failed did not pass and the set stops on failure"
                else -> null
            }
        }

        private fun issue(index: Int) {
            val n = index + 1
            val runner =
                LoadRunner(
                    held,
                    store = null,
                    clock = clock,
                    fires = releasesOf(index),
                    listen = { matcher, _ ->
                        board.register(index, matcher)
                        router.register(n, matcher)
                    },
                    chain = times?.get(index),
                )
            val outcome =
                runner.run(
                    planned.phases[index],
                    phase = n,
                    captures = capturesFor(index),
                    firedBy = triggers[index].takeIf { planned.phases[index].shape is LoadShape.Triggered },
                    cancelled = cancelled,
                ) { progress -> board.update(index, progress) }
            outcome.report.evidence?.let { files ->
                store?.writeEvidence(planned.id, files, outcome.unmatched, outcome.specimens, outcome.captured)
            }
            board.judged(index, outcome.report)
        }

        /**
         * **What this phase's matches release**: the buffer of every reactive phase that reacts to it.
         *
         * Null when nothing does, which is most phases and every set that has no reactive phase at all,
         * so the matcher of a set that could never overlap is exactly the matcher it always was.
         *
         * Only a reactive phase is wired up. A paced phase can carry an `after` when a plan reaches the
         * runner without being validated, and it waits on that phase's start and nothing else: it reads
         * no buffer, so filling one for it is work nobody would ever collect.
         *
         * Several phases can react to one, and each gets its own copy of every index, because two
         * phases answering the same trigger both answer for every message it issued.
         */
        private fun releasesOf(index: Int): StampMatcher.Trigger? {
            val n = index + 1
            val waiting =
                planned.phases.indices
                    .filter { planned.phases[it].reactsTo(n) }
                    .map { triggers[it] }
            if (waiting.isEmpty()) return null
            return object : StampMatcher.Trigger {
                override fun fired(messageIndex: Int) = waiting.forEach { it.post(messageIndex) }

                override fun done() =
                    waiting.forEach { it.close("phase $n's settle window closed, so nothing more will fire this one") }
            }
        }

        private fun capturesFor(index: Int): LoadRunner.Captures =
            LoadRunner.Captures(
                lookups = table?.let { t -> readable(index).associateWith { t.lookup(it) } }.orEmpty(),
                table = table.takeIf { planned.phases[index].capture.isNotEmpty() },
            )

        /**
         * **Every capture name this phase may read**, which for a phase that reacts to another is not
         * every earlier one.
         *
         * A paced phase reads what every earlier live phase kept, because it runs after all of them. A
         * phase that reacts to another reads what its trigger kept and what its trigger could itself read,
         * and R17 says so for a reason that is a race and not a rule: two phases reacting to phase 1 are
         * fired for message 7 at the same instant, so the sibling's reply for 7 has not landed and its
         * capture for 7 is not there. `LoadSet.problems()` refuses that before a lane opens; this is the
         * same rule where the reading happens, because a set handed straight to the runner never met it.
         */
        private fun readable(index: Int): List<String> {
            val trigger = triggerOf(index) ?: return everyEarlierLivePhaseKeeps(index)
            val fires = planned.phases[trigger]
            val kept = if (fires.muted) emptyList() else fires.capture.keys.toList()
            return kept + readable(trigger)
        }

        /** What a paced phase reads: every name an earlier phase kept and was not parked while keeping. */
        private fun everyEarlierLivePhaseKeeps(index: Int): List<String> =
            planned.phases
                .take(index)
                .filterNot { it.muted }
                .flatMap { it.capture.keys }

        /**
         * **Nothing this phase would have fired will be fired now**, on every way out of it, and the
         * note says which way out it was.
         *
         * Three ways and not two. `ran` is set before the phase issues rather than after it, so it says
         * "this phase got as far as issuing" and not "this phase finished": a phase that threw half way
         * through would otherwise tell the phases waiting on it that it had finished, which is a note
         * that reaches a record and sends whoever reads it looking for the wrong thing.
         */
        private fun closeDependants(index: Int) {
            val n = index + 1
            val reason =
                when {
                    !ran[index] -> "phase $n did not run, so nothing would have fired this one"
                    failures.get(index) != null -> "phase $n could not finish, so nothing more will fire this one"
                    else -> "phase $n has finished, so nothing more will fire this one"
                }
            planned.phases.forEachIndexed { i, plan -> if (plan.after == n) triggers[i].close(reason) }
        }

        /** The 0-based phase this one reacts to, or null when it runs on a schedule of its own. */
        private fun triggerOf(index: Int): Int? {
            val after = planned.phases[index].after ?: return null
            return (after - 1).takeIf { it in 0 until index }
        }

        /**
         * The phase a chain of triggers starts at, which is the phase whose place in the set decides
         * where everything rooted in it runs. Its own index for a phase that reacts to nothing.
         */
        private fun rootOf(index: Int): Int {
            var at = index
            repeat(size) {
                at = triggerOf(at) ?: return at
            }
            return at
        }
    }

    /**
     * Runs [planned] to its verdict, writing the record as it goes.
     *
     * Throws [LoadRefused] when the set cannot start: a template that will not compile, a name nothing
     * seeds, or a phase whose issuing profile has no lane logged on. Nothing dials for phase 1 until every
     * phase has been looked at, so a set of six never fails on phase five for something that could have
     * been said before phase one.
     *
     * Anything a phase throws once it is running is joined back here and re-thrown after every other phase
     * has ended, and the phase that threw is the lowest-numbered one that did.
     */
    fun run(
        planned: LoadSet.Planned,
        cancelled: () -> Boolean = { false },
        onProgress: (LoadRecord) -> Unit = {},
    ): LoadRecord {
        val startedAt = host.now()
        val compiled = planned.phases.map { plan -> compile(plan) }
        val byProfile = openEveryLane(planned)
        // The phases that will actually run. A muted phase's lanes, captures and indices are none of the
        // set's business, and it keeps its position in every list that is indexed by phase number.
        val live = planned.phases.filterNot { it.muted }

        val router = Router()
        val held = HeldLanes(host, byProfile)
        // One table for the whole set, sized by the highest index any phase will reach, because a phase
        // that counts from 2,001 reads what phase 1 filled at 2,001. Absent when nothing is captured.
        val names = live.flatMap { it.capture.keys }
        // One past the highest index any live phase will reach, which is what both the table and the chain
        // times are sized by. The formula and never the table's existence: a set can chain without
        // capturing anything, and a set can capture without chaining at all.
        val highest = (live.maxOfOrNull { it.indexTo }?.plus(1) ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val table = names.takeIf { it.isNotEmpty() }?.let { StampMatcher.CaptureTable(it, highest) }
        // Arrays only for the phases of a chain, so a set of paced phases and every single run there has
        // ever been allocate nothing at all and carry no chain block. See Chains.
        val times = Chains.participants(planned).takeIf { it.isNotEmpty() }?.let { ChainTimes(highest, it) }
        val triggers = triggerBuffers(planned).also { onTriggerBuffers(it) }
        val sessions = byProfile.values.flatten().distinct()
        val handles = sessions.map { it.addStampListener(router::onStamp) }
        val stubs = planned.phases.mapIndexed { index, plan -> stub(plan, compiled[index], byProfile, startedAt) }

        val interrupts = Interrupts()
        val write: ((LoadRecord) -> Unit)? = store?.let { records -> { record -> records.write(record) } }
        val writer = RecordWriter(write, interrupts)
        val board = Board(planned, stubs, startedAt, host::now, writer, onProgress)
        try {
            board.publish()
            try {
                Conductor(planned, board, held, router, table, times, triggers, cancelled, interrupts).conduct()
            } finally {
                // Every phase has been joined by now, however the set went, so the times are settled and
                // visible to this thread. In the finally because a set that threw still measured whatever
                // it measured, and the record it leaves behind is written either way.
                times?.let { board.attachChains(Chains.of(planned, it)) }
            }
            // Before the last record rather than after it, so nothing the writer was still holding can
            // land on top of the one the set finished with.
            writer.close()
            return board.record().also {
                store?.write(it)
                onProgress(it)
            }
        } finally {
            triggers.forEach { it.close("the set ended, so nothing more will fire this one") }
            writer.close()
            handles.forEach { it.close() }
            host.release()
            // Last, because everything above owes the caller something and half of it is interruptible.
            interrupts.handBack()
        }
    }

    /**
     * **One buffer per phase, before any phase starts**, for the same reason the capture table is made
     * here: the thing that closes it is a different phase on a different thread, and a buffer made when a
     * phase starts is a buffer nobody could close if that phase never did. See [TriggerBuffer].
     *
     * A phase that reacts to nothing is closed here and now, because nothing was ever going to fire it.
     */
    private fun triggerBuffers(planned: LoadSet.Planned): List<TriggerBuffer> =
        planned.phases.mapIndexed { index, plan ->
            // The phase that fills it, by number and label, so the phase reading it can say what did not
            // happen: an index nothing ever released is reported as a message this phase never sent, and
            // a phase that captures nothing has no other name to give for what was missing.
            val fills =
                plan.after?.let { after ->
                    planned.phases.getOrNull(after - 1)?.let { "phase $after · ${it.label}" }
                }
            TriggerBuffer(index + 1, firedBy = fills).also {
                if (plan.after == null) it.close("it runs on its own schedule, so nothing fires it")
            }
        }

    /**
     * The report a phase carries before it has run: what it would do, and whether it is going to.
     *
     * **A muted phase is skipped from the FIRST record** rather than when a thread reaches it, so the live
     * document draws it as parked from the first tick instead of as queued.
     */
    private fun stub(
        plan: LoadPlan,
        template: LoadReport.TemplateInfo,
        byProfile: Map<String, List<LoadLane>>,
        startedAt: Long,
    ): LoadReport =
        if (plan.muted) {
            LoadReport
                .stub(
                    plan,
                    LoadStatus.SKIPPED,
                    lanes = 0,
                    template = template,
                    startedAt = startedAt,
                    note = LoadRecord.MUTED_NOTE,
                ).copy(finishedAt = startedAt)
        } else {
            LoadReport.stub(plan, LoadStatus.PENDING, byProfile[plan.profileId]?.size ?: 0, template, startedAt)
        }

    /**
     * **Every lane the set will ever need, open before phase 1 dials, with the store override applied once.**
     *
     * **Issuers first.** A profile that issues in *any* phase is opened as lanes, so it gets **all** of
     * them, with the 1-based slots every other door hands out. Walking the phases in order and opening
     * whichever door that phase happened to name put a profile that listens in phase 1 and issues in phase
     * 2 into the map as a listener, which is one session wrapped as slot 0. Phase 2 then issued from that
     * single lane whatever `sessionCount` said, and `${sessionIndex}` rendered 0 on every message of it.
     *
     * **Listeners second, and only for a profile that never issues.** One session is all matching needs
     * from a profile nothing is sent on. A profile that does both listens on its issuing lanes, which is
     * right because [Router] puts a stamp listener on every session the set holds for the whole set,
     * rather than on the lanes of the phase that happens to be live.
     *
     * A phase whose issuing profile has no lane logged on is refused here, before phase 1 dials, with the
     * sentence a single run gives. Anything already open is released on the way out, so a set refused on
     * phase four does not leave phase one's lanes up.
     *
     * **A muted phase's profile is neither opened nor judged**, which is what "the venue leg this phase
     * issues on is down" needs from muting it.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun openEveryLane(planned: LoadSet.Planned): Map<String, List<LoadLane>> {
        // Indexed, because the refusal names the phase by its place in the whole set and a muted phase
        // keeps its number: phase 3 of a set whose phase 2 is parked is still phase 3.
        val live = planned.phases.withIndex().filterNot { it.value.muted }
        val byProfile = linkedMapOf<String, List<LoadLane>>()
        try {
            live.forEach { (_, plan) ->
                byProfile.getOrPut(plan.profileId) { host.openLanes(plan.profileId, planned.storeAndLog) }
            }
            live.forEach { (_, plan) ->
                plan.listenProfileIds.forEach { id ->
                    byProfile.getOrPut(id) { host.openListeners(listOf(id), planned.storeAndLog) }
                }
            }
            live.forEach { (index, plan) ->
                if (byProfile[plan.profileId].isNullOrEmpty()) {
                    throw LoadRefused(
                        "phase ${index + 1}: no session of '${plan.profileName}' reached LOGGED_ON, " +
                            "so there is nothing to issue on",
                    )
                }
            }
        } catch (e: Exception) {
            host.release()
            throw e
        }
        return byProfile
    }

    /**
     * The template's shape for the record, or a refusal for the whole set when it will not compile.
     *
     * **Except for a muted phase**, whose template is never rendered: its stub carries the name and the
     * MsgType and moves on, because a phase parked while its message is half written must not be the
     * reason the two phases either side of it cannot run.
     */
    @Suppress("SwallowedException")
    private fun compile(plan: LoadPlan): LoadReport.TemplateInfo {
        val compiled =
            try {
                CompiledTemplate.compile(plan.template)
            } catch (e: IllegalArgumentException) {
                if (plan.muted) {
                    return LoadReport.TemplateInfo(
                        plan.template.name,
                        plan.template.msgType ?: "",
                        emptyList(),
                        emptyList(),
                        emptyList(),
                    )
                }
                throw LoadRefused(e.message ?: "the template '${plan.template.name}' cannot be compiled")
            }
        return LoadReport.TemplateInfo(
            plan.template.name,
            compiled.msgType,
            compiled.perMessageTags,
            compiled.fixedTags,
            compiled.onceTags,
        )
    }
}
