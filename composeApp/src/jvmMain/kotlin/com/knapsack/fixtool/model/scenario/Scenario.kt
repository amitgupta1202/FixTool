package com.knapsack.fixtool.model.scenario

/**
 * A saved, parameterized sequence of FIX sends and assertions that a deterministic runner replays
 * identically — the "author once, repeat forever, no LLM in the hot path" artifact from the
 * repeatable-scenarios design. Persisted one-file-per-scenario (see `ScenarioService`).
 *
 * `setup` runs before `steps`; `teardown` runs after `steps` (even on failure). The runner threads
 * a single persistent variable scope across every step (see the design's Decision 0).
 */
data class Scenario(
    val id: String,
    val name: String,
    val profile: String? = null,
    val setup: List<ScenarioStep> = emptyList(),
    val steps: List<ScenarioStep> = emptyList(),
    val teardown: List<ScenarioStep> = emptyList(),
    val userTags: List<String> = emptyList(),
    val version: Int = 1,
)

/** One step of a scenario. Every step targets a [session] (null = the active session). */
sealed interface ScenarioStep {
    val session: String?

    /** Send a (parameterized) message; `${...}` is resolved against the scenario scope at run time. */
    data class Send(val raw: String, override val session: String? = null) : ScenarioStep

    /** Block until a connection state is reached or a matching message arrives (no consume). */
    data class Wait(
        override val session: String? = null,
        val state: String? = null,
        val match: MatchPredicate? = null,
        val timeoutMs: Long = 10_000,
    ) : ScenarioStep

    /** Await the next not-yet-consumed matching message and assert it against an expectation. */
    data class Expect(
        override val session: String? = null,
        val direction: String = "in",
        val match: MatchPredicate? = null,
        val timeoutMs: Long = 10_000,
        val expectation: Expectation,
    ) : ScenarioStep

    /** Clear a session's observable message log (typical setup step). */
    data class ClearMessages(override val session: String? = null) : ScenarioStep

    /** Reset a session's FIX sequence numbers (typical setup step). */
    data class ResetSeqNum(
        override val session: String? = null,
        val sender: Int? = null,
        val target: Int? = null,
    ) : ScenarioStep
}

/**
 * Selects a message by message type / direction and zero or more tag=value pairs (**AND**), the
 * latter being the partial-fill discriminator (e.g. ExecType=F AND OrdStatus=2). Extends the old
 * single-tag `fixtool_wait` predicate with multi-tag matching; the runner adds a consumed cursor so
 * successive `Expect`s walk successive messages instead of re-matching the first.
 */
data class MatchPredicate(
    val messageType: String? = null,
    val direction: String? = null,
    val fields: List<TagValue> = emptyList(),
)

/** A single tag=value constraint used inside a [MatchPredicate]. */
data class TagValue(val tag: Int, val value: String)

/** The result of one step's execution. */
data class StepResult(
    val stepIndex: Int,
    val kind: String,
    val phase: String,
    val passed: Boolean,
    val detail: String? = null,
    val tags: List<TagResult> = emptyList(),
)

/** The result of a whole scenario run — drives both CI (exit code) and the in-app red/green overlay. */
data class ScenarioResult(
    val scenario: String,
    val passed: Boolean,
    val steps: List<StepResult>,
)
