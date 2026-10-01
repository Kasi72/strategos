package com.drkkr.strategos.framework.eu

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport

/**
 * Expected utility for a focal decision maker (first stakeholder), treating
 * other players' strategies as a uniform belief unless beliefs override.
 */
class ExpectedUtilityModule : FrameworkModule {
    override val id = "expected-utility"
    override val name = "Expected Utility"
    override val version = "1.0"
    override val category = "Decision theory"

    override fun canAnalyze(model: Scenario): Applicability {
        MatrixSupport.build(model) ?: return Applicability(0.0, listOf("Requires normal-form payoffs"))
        return Applicability(0.7, listOf("Averages over opponents' strategies for focal player ${model.stakeholders[0].name}."))
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val sf = MatrixSupport.build(model)!!
        val focal = 0
        val focalStrats = sf.strategies[sf.players[focal]]!!
        val results = mutableMapOf<String, Double>()
        for (aIdx in focalStrats.indices) {
            var sum = 0.0; var n = 0
            for (idx in sf.utility[focal].indices) {
                val profile = sf.profileFor(idx)
                if (profile[focal] == aIdx) { sum += sf.utility[focal][idx]; n++ }
            }
            results[focalStrats[aIdx]] = if (n > 0) sum / n else 0.0
        }
        val best = results.maxBy { it.value }
        val findings = listOf(
            Finding("Best action (uniform belief)", "${labelOf(model, best.key)} — EU=${"%.3f".format(best.value)}"),
            Finding("All actions", results.entries.joinToString { "${labelOf(model, it.key)}=${"%.3f".format(it.value)}" })
        )
        val recs = listOf(RecommendedAction(sf.players[focal], "Play ${labelOf(model, best.key)}", "Highest expected utility under uniform prior over opponent strategies.", lens = RecommendedAction.Lens.EXPECTED))
        val viz = VizSpec(VizSpec.Kind.BAR, "Expected utility per action", mapOf(
            "labels" to results.keys.map { labelOf(model, it) },
            "values" to results.values.toList()
        ))
        return AnalysisResult(
            id, name, canAnalyze(model), findings, recs,
            assumptions = listOf(Assumption("Uniform prior over opponents' strategy profiles"),
                Assumption("Weighted-scalar utility over payoff dimensions")),
            calculations = mapOf("expectedUtility" to results),
            confidence = 0.6,
            visualizations = listOf(viz),
            explanation = "Compares actions by mean payoff over opponents' strategies."
        )
    }

    private fun labelOf(s: Scenario, aid: String) = s.actions.first { it.id == aid }.label
}
