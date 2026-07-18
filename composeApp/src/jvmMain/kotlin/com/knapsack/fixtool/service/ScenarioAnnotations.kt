package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioStep

/**
 * Derives, per step, which scenario variables the step **mints** (`${x = ...}` in a Send) and which
 * it **references** (`${x}` in a later Send, or an Expect `Reference` matcher checking an echo).
 * Drives the correlation badges in the capture-review and editor flow views, making cross-step —
 * and cross-*session* — id correlation visible instead of implied.
 */
object ScenarioAnnotations {
    /**
     * The variables one step writes/references; parallel to the step list passed to [annotate].
     *
     * `minted` is every write, either kind. [fromReply] is which kind: a Send *mints* a value we chose
     * and puts it on the wire; an Expect's `bindAs` *captures* one the venue chose off its reply. One
     * step's writes are all the same kind — it is a Send or it is an Expect — so one flag says it.
     */
    data class StepVars(
        val minted: List<String>,
        val referenced: List<String>,
        val fromReply: Boolean = false,
    )

    /**
     * One variable's whole life across the scenario: the steps that mint it, the steps that capture it,
     * the steps that read it, and whether every write it has is parked. A badge knows its own half of
     * the correlation — this is the other half, which is what a reader actually wants when deciding
     * whether a step is safe to delete or mute.
     */
    data class VarSites(
        val mintedAt: List<Int>,
        val capturedAt: List<Int>,
        val referencedAt: List<Int>,
        val allWritesMuted: Boolean,
    ) {
        /** Every step that puts a value in this variable, either way, in step order. */
        val writtenAt: List<Int> get() = (mintedAt + capturedAt).sorted()
    }

    /**
     * [VarSites] for every variable the scenario writes or references, keyed by name — 0-based step
     * indices, in step order.
     *
     * `allWritesMuted` follows the same judgement the variables strip makes: a name written only by
     * parked steps does not get written on a run, so a live reference to it is the leaves-a-literal
     * problem, not a working correlation.
     */
    fun sites(steps: List<ScenarioStep>): Map<String, VarSites> {
        val writes = steps.flatMapIndexed { i, s -> mintedIn(s).map { it to i } }
        val refs = steps.flatMapIndexed { i, s -> referencedIn(s).map { it to i } }
        return (writes + refs).map { it.first }.distinct().associateWith { name ->
            val writtenAt = writes.filter { it.first == name }.map { it.second }.distinct()
            VarSites(
                // A Send mint chose the value; an Expect's `bindAs` read it off the venue's reply.
                mintedAt = writtenAt.filterNot { steps[it] is ScenarioStep.Expect },
                capturedAt = writtenAt.filter { steps[it] is ScenarioStep.Expect },
                referencedAt = refs.filter { it.first == name }.map { it.second }.distinct(),
                allWritesMuted = writtenAt.isNotEmpty() && writtenAt.all { steps[it].muted },
            )
        }
    }

    private val MINT = Regex("\\$\\{\\s*(\\w+)\\s*=")
    private val REF = Regex("\\$\\{\\s*(\\w+)\\s*}")

    fun annotate(steps: List<ScenarioStep>): List<StepVars> {
        val minted = steps.flatMap { mintedIn(it) }.toSet()
        return steps.map { step ->
            val mints = mintedIn(step)
            StepVars(
                minted = mints,
                // Only names minted somewhere in this scenario count as correlation references —
                // this keeps engine expressions like ${LocalDateTime.now()...} out of the badges.
                referenced = referencedIn(step).filter { it in minted }.distinct(),
                fromReply = mints.isNotEmpty() && step is ScenarioStep.Expect,
            )
        }
    }

    /**
     * Bare-name references (`${x}`) no step ever mints — in mint-order of appearance.
     *
     * The engine deliberately leaves an unknown `${name}` **literal** on the wire (an error would make
     * every non-variable `${...}` a gamble), which turns a typo into a silent one: the send carries the
     * ten characters `${idO}` and the venue rejects — or worse, accepts — a message nobody meant. This is
     * the authoring-time warning for that. Engine expressions never appear here: `${LocalDateTime.now()}`
     * is not a bare name, and a bare name that IS minted somewhere is a working reference, not a warning.
     */
    fun unminted(steps: List<ScenarioStep>): List<String> {
        val minted = steps.flatMap { mintedIn(it) }.toSet()
        return steps.flatMap { referencedIn(it) }.filter { it !in minted }.distinct()
    }

    private fun mintedIn(step: ScenarioStep): List<String> =
        when (step) {
            is ScenarioStep.Send -> MINT.findAll(step.raw).map { it.groupValues[1] }.toList()
            // A `bindAs` row mints too — from the venue's side of the wire. The Expect that captures
            // `QuoteReqID` into `${qr}` wears the same filled badge a minting Send does, and a later
            // send's `${qr}` is a working reference, not a never-minted warning.
            is ScenarioStep.Expect -> step.expectation.fields.mapNotNull { it.bindAs }
            else -> emptyList()
        }

    private fun referencedIn(step: ScenarioStep): List<String> =
        when (step) {
            is ScenarioStep.Send -> REF.findAll(step.raw).map { it.groupValues[1] }.toList()
            // Both halves of an Expect can reference: the assertion rows (a `Reference` matcher checking an
            // echo) AND the bind predicate (`11=${id0}` steering which message the step consumes). The
            // predicate was invisible here, so a step that binds by a minted id but does not assert it
            // showed no badge — the correlation was working and the flow view denied it existed.
            is ScenarioStep.Expect ->
                step.expectation.fields
                    .mapNotNull { (it.matcher as? Matcher.Reference)?.expression }
                    .flatMap { expr -> REF.findAll(expr).map { it.groupValues[1] } } +
                    referencedIn(step.match)
            is ScenarioStep.Wait -> referencedIn(step.match)
            else -> emptyList()
        }

    private fun referencedIn(match: MatchPredicate?): List<String> =
        match?.fields.orEmpty().flatMap { REF.findAll(it.value).map { m -> m.groupValues[1] } }
}
