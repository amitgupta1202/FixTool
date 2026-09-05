package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.service.SocketStamp
import quickfix.Message
import quickfix.SessionID

/**
 * **The world a load run needs**, in the same spirit as `ScenarioHost`: sessions by profile, the evaluator
 * for the once-per-lane expressions, and a clock. Two implementations, one over a headless process and one
 * over the app's live sessions, so the runner is written once.
 */
interface LoadHost {
    /**
     * Brings up every lane of the issuing profile, with [override] applied to each lane's config when given,
     * waits for logon, and returns the lanes that reached LOGGED_ON, numbered by profile slot. A shortfall
     * is returned rather than refused: 38 lanes of 50 is still a load test and zero is not.
     */
    fun openLanes(profileId: String, override: StoreAndLogOverride?): List<LoadLane>

    /** One session per listen-only profile. They take part in matching and never issue. */
    fun openListeners(profileIds: List<String>, override: StoreAndLogOverride?): List<LoadLane>

    /** Evaluates one field's template through the full evaluator, once, for [lane], with [scope] as its variables. */
    fun resolveOnce(template: String, scope: Map<String, String>, lane: LoadLane): String

    fun dictionary(): FixDictionaryAdapter

    /** Puts back whatever [openLanes] and [openListeners] did: closes headless sessions, restores live ones. */
    fun release()

    fun now(): Long = System.currentTimeMillis()

    fun sleep(ms: Long) = Thread.sleep(ms)
}

/** One participating session as the runner sees it. An interface so a test can run the runner over fakes. */
interface LoadLane {
    val lane: Lane

    /** The engine's id for this session, or null before it has one. */
    val sessionId: SessionID?

    fun send(message: Message): Boolean

    /** The pane's discarded counter now. The runner reads it before and after. */
    fun discarded(): Long

    fun addStampListener(listener: (SocketStamp) -> Unit): AutoCloseable
}

/** A live [FixMessageSession] as a lane. */
class SessionLoadLane(
    override val lane: Lane,
    val session: FixMessageSession,
) : LoadLane {
    override val sessionId: SessionID? get() = session.sessionId()

    override fun send(message: Message): Boolean = session.sendPrepared(message)

    override fun discarded(): Long = session.discarded.value

    override fun addStampListener(listener: (SocketStamp) -> Unit): AutoCloseable = session.addStampListener(listener)
}

/** A plan that cannot run at all, with the reason. The command exits 2 on it, the app shows it. */
class LoadRefused(
    message: String,
) : RuntimeException(message)
