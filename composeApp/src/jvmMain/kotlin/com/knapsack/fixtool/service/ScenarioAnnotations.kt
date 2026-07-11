package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioStep

/**
 * Derives, per step, which scenario variables the step **mints** (`${x = ...}` in a Send) and which
 * it **references** (`${x}` in a later Send, or an Expect `Reference` matcher checking an echo).
 * Drives the correlation badges in the capture-review and editor flow views, making cross-step —
 * and cross-*session* — id correlation visible instead of implied.
 */
object ScenarioAnnotations {
    /** The variables one step mints/references; parallel to the step list passed to [annotate]. */
    data class StepVars(val minted: List<String>, val referenced: List<String>)

    private val MINT = Regex("\\$\\{\\s*(\\w+)\\s*=")
    private val REF = Regex("\\$\\{\\s*(\\w+)\\s*}")

    fun annotate(steps: List<ScenarioStep>): List<StepVars> {
        val minted = steps.flatMap { mintedIn(it) }.toSet()
        return steps.map { step ->
            StepVars(
                minted = mintedIn(step),
                // Only names minted somewhere in this scenario count as correlation references —
                // this keeps engine expressions like ${LocalDateTime.now()...} out of the badges.
                referenced = referencedIn(step).filter { it in minted }.distinct(),
            )
        }
    }

    private fun mintedIn(step: ScenarioStep): List<String> =
        when (step) {
            is ScenarioStep.Send -> MINT.findAll(step.raw).map { it.groupValues[1] }.toList()
            else -> emptyList()
        }

    private fun referencedIn(step: ScenarioStep): List<String> =
        when (step) {
            is ScenarioStep.Send -> REF.findAll(step.raw).map { it.groupValues[1] }.toList()
            is ScenarioStep.Expect ->
                step.expectation.fields
                    .mapNotNull { (it.matcher as? Matcher.Reference)?.expression }
                    .flatMap { expr -> REF.findAll(expr).map { it.groupValues[1] } }
            else -> emptyList()
        }
}
