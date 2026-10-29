package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.analysis.PipelineResult
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

enum class StressVerdict { ROBUST, FRAGILE, NOT_APPLICABLE }
enum class StressDirection { IMPROVE, WORSEN }

data class StressFlip(val stakeholder: String, val profileLabel: String, val direction: StressDirection, val newRecommended: String?)

data class StressResult(
    val verdict: StressVerdict,
    val runs: Int,
    val flips: List<StressFlip>,
    val flipCount: Int,
    val holdsIn: Int,
    val partial: Boolean
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("verdict", verdict.name)
        put("runs", runs)
        putJsonArray("flips") {
            flips.forEach { f ->
                addJsonObject {
                    put("stakeholder", f.stakeholder); put("profile_label", f.profileLabel)
                    put("direction", f.direction.name); put("new_recommended", f.newRecommended)
                }
            }
        }
        put("holds_in", holdsIn)
        put("partial", partial)
    }
}

/**
 * Nudges each AI-inferred payoff up and down, one at a time, and checks whether the recommended action flips (blueprint §8.7).
 * Pure and synchronous; callers run it in Dispatchers.Default.
 */
object InferredStressTest {
    private const val MAX_FLIPS = 5
    private const val RECOMMENDED = "Recommended"

    fun run(
        scenario: Scenario,
        perturbation: Double = 0.25,
        pipeline: AnalysisPipeline,
        maxEntries: Int = 20,
        budgetMs: Long = 8_000,
        now: () -> Long = System::currentTimeMillis
    ): StressResult {
        val targets = scenario.payoffs.withIndex().filter { it.value.factStatus == FactStatus.AI_INFERRED }.take(maxEntries)
        if (targets.isEmpty()) return StressResult(StressVerdict.NOT_APPLICABLE, 0, emptyList(), 0, 0, false)

        val start = now()
        val baseline = recommended(pipeline.run(scenario))
        val delta = perturbation.coerceIn(0.1, 0.5) * 10
        val flips = mutableListOf<StressFlip>()
        var runs = 0
        var flipCount = 0
        var partial = false

        loop@ for ((index, entry) in targets) {
            for (direction in StressDirection.entries) {
                if (now() - start > budgetMs) {
                    partial = true
                    break@loop
                }
                val nudged = MultiPayoff(entry.payoff.values.mapValues { (dim, v) ->
                    val up = (direction == StressDirection.IMPROVE) == dim.higherIsBetter
                    (if (up) v + delta else v - delta).coerceIn(0.0, 10.0)
                })
                val variant = scenario.copy(payoffs = scenario.payoffs.toMutableList().also { it[index] = entry.copy(payoff = nudged) })
                val result = recommended(pipeline.run(variant))
                runs++
                if (result?.first != baseline?.first) {
                    flipCount++
                    if (flips.size < MAX_FLIPS) {
                        flips += StressFlip(
                            stakeholder = scenario.stakeholders.firstOrNull { it.id == entry.stakeholderId }?.name ?: entry.stakeholderId,
                            profileLabel = profileLabel(scenario, entry.actionProfile),
                            direction = direction,
                            newRecommended = result?.second
                        )
                    }
                }
            }
        }
        val verdict = if (flipCount > 0) StressVerdict.FRAGILE else StressVerdict.ROBUST
        return StressResult(verdict, runs, flips, flipCount, runs - flipCount, partial)
    }

    /** (actionId, label) of the Recommended pathway, or null when there is none. */
    private fun recommended(pr: PipelineResult): Pair<String?, String>? =
        pr.report?.pathways?.firstOrNull { it.label == RECOMMENDED }?.let { it.actionId to it.action }

    private fun profileLabel(s: Scenario, profile: Map<String, String>): String =
        s.players().mapNotNull { p -> profile[p.id] }.ifEmpty { profile.values.toList() }
            .joinToString(" / ") { id -> s.actions.firstOrNull { it.id == id }?.label ?: id }
}
