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
        /**
         * The scenario's Examples table supplies this name. No step writes it and none needs to — which is
         * what stops it being reported as never-minted, and what lets the editor say where it comes from.
         */
        val seeded: Boolean = false,
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
    fun sites(steps: List<ScenarioStep>, columns: List<String> = emptyList()): Map<String, VarSites> {
        val writes = steps.flatMapIndexed { i, s -> mintedIn(s).map { it to i } }
        val refs = steps.flatMapIndexed { i, s -> referencedIn(s).map { it to i } }
        // Columns are named even when no step reads one: "a column the scenario never reads" is a lint the
        // editor can only draw if the name reaches it.
        return (writes + refs).map { it.first }.plus(columns).distinct().associateWith { name ->
            val writtenAt = writes.filter { it.first == name }.map { it.second }.distinct()
            VarSites(
                // A Send mint chose the value; an Expect's `bindAs` read it off the venue's reply.
                mintedAt = writtenAt.filterNot { steps[it] is ScenarioStep.Expect },
                capturedAt = writtenAt.filter { steps[it] is ScenarioStep.Expect },
                referencedAt = refs.filter { it.first == name }.map { it.second }.distinct(),
                allWritesMuted = writtenAt.isNotEmpty() && writtenAt.all { steps[it].muted },
                seeded = name in columns,
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
    fun unminted(steps: List<ScenarioStep>, columns: List<String> = emptyList()): List<String> {
        // A column IS a mint — the row writes it before the first step runs. Without this, every outline
        // would report every one of its own columns as a typo, which is a lint that has cried wolf.
        val minted = steps.flatMap { mintedIn(it) }.toSet() + columns
        return steps.flatMap { referencedIn(it) }.filter { it !in minted }.distinct()
    }

    /**
     * Columns the scenario never reads — the other half of the outline's lint.
     *
     * Not an error: it is what a half-finished table looks like, and an author adding columns before the
     * steps that use them is doing it in the order that makes sense. But neither should it be discovered by
     * watching a run pass while proving nothing about the column.
     */
    fun unreadColumns(steps: List<ScenarioStep>, columns: List<String>): List<String> {
        val read = steps.flatMap { referencedIn(it) }.toSet()
        return columns.filter { it !in read }
    }

    private fun mintedIn(step: ScenarioStep): List<String> =
        when (step) {
            // The WIRE view, not the authored one: an excluded field is not sent, so it does not mint.
            // Same judgement `allWritesMuted` makes one level up — a write that will not happen on a run
            // is not a write. Exclude the field that mints `${id}` and its badge goes out while every
            // downstream `${id}` turns up in `unminted()`, which is exactly the warning that case wants.
            is ScenarioStep.Send -> MINT.findAll(SendFields.wire(step.raw)).map { it.groupValues[1] }.toList()
            // A `bindAs` row mints too — from the venue's side of the wire. The Expect that captures
            // `QuoteReqID` into `${qr}` wears the same filled badge a minting Send does, and a later
            // send's `${qr}` is a working reference, not a never-minted warning.
            is ScenarioStep.Expect -> step.expectation.fields.mapNotNull { it.bindAs }
            else -> emptyList()
        }

    private fun referencedIn(step: ScenarioStep): List<String> =
        when (step) {
            is ScenarioStep.Send -> REF.findAll(SendFields.wire(step.raw)).map { it.groupValues[1] }.toList()
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
