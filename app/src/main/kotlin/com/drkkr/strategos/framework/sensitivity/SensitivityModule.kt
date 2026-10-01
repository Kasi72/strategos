package com.drkkr.strategos.framework.sensitivity

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport

class SensitivityModule : FrameworkModule {
    override val id = "sensitivity"
    override val name = "Sensitivity Analysis"
    override val version = "1.0"
    override val category = "Robustness"

    override fun canAnalyze(model: Scenario): Applicability {
        val sf = MatrixSupport.build(model)
        return if (sf != null) Applicability(0.7, listOf("Perturbs each weight dimension ±10% independently and tracks ranking changes."))
        else Applicability(0.0, listOf("Need payoff matrix"))
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val sf = MatrixSupport.build(model)!!
        val focal = 0
        val strats = sf.strategies[sf.players[focal]]!!

        val delta = 0.1
        val sensData = mutableListOf<Map<String, Any>>()

        for (dim in PayoffDimension.values()) {
            val baseWeights = model.weights.toMutableMap()
            val baseW = baseWeights[dim] ?: 0.0
            if (baseW == 0.0) continue
            for (factor in listOf(1.0 - delta, 1.0 + delta)) {
                val perturbed = baseWeights.toMutableMap().also { it[dim] = baseW * factor }
                val sfP = MatrixSupport.build(model, perturbed)!!
                // Best action for focal under perturbed weights
                val euP = DoubleArray(strats.size) { aIdx ->
                    sfP.utility[focal].indices.filter { sfP.profileFor(it)[focal] == aIdx }
                        .map { sfP.utility[focal][it] }.average()
                }
                val bestP = strats[euP.indices.maxBy { euP[it] }]
                sensData += mapOf("dim" to dim.name, "factor" to factor, "bestAction" to bestP)
            }
        }

        // Critical dimensions: those where a 10% change flips the best action
        val baseline = bestAction(sf, focal, strats)
        val critical = sensData.filter { it["bestAction"] != baseline }.map { "${it["dim"]} @ ${it["factor"]}" }

        val findings = listOf(
            Finding("Baseline best action", labelOf(model, baseline)),
            if (critical.isEmpty()) Finding("Robust ranking", "Best action stable across ±10% weight perturbations.")
            else Finding("Sensitive to", critical.joinToString(", "), Finding.Severity.WARNING)
        )
        val viz = VizSpec(VizSpec.Kind.TABLE, "Sensitivity table", mapOf("rows" to sensData))
        return AnalysisResult(
            id, name, canAnalyze(model), findings, emptyList(),
            assumptions = listOf(Assumption("±10% perturbation of each dimension weight independently")),
            calculations = mapOf("critical" to critical, "sensTable" to sensData),
            confidence = 0.6,
            visualizations = listOf(viz),
            explanation = if (critical.isEmpty()) "Recommendation is robust to ±10% weight changes."
            else "Recommendation changes under ${critical.size} weight perturbation(s) — verify those assumptions."
        )
    }

    private fun bestAction(sf: MatrixSupport.StrategicForm, focal: Int, strats: List<String>): String {
        val eu = DoubleArray(strats.size) { aIdx ->
            sf.utility[focal].indices.filter { sf.profileFor(it)[focal] == aIdx }
                .map { sf.utility[focal][it] }.average()
        }
        return strats[eu.indices.maxBy { eu[it] }]
    }

    private fun labelOf(s: Scenario, aid: String) = s.actions.first { it.id == aid }.label
}
