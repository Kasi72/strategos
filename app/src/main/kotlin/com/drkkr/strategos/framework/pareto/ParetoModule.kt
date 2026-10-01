package com.drkkr.strategos.framework.pareto

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario

data class ScoredAction(val stakeholderId: String, val actionId: String, val scores: Map<PayoffDimension, Double>)

class ParetoModule : FrameworkModule {
    override val id = "pareto"
    override val name = "Multi-Objective Pareto Analysis"
    override val version = "1.0"
    override val category = "Multi-objective"

    override fun canAnalyze(model: Scenario): Applicability {
        val dims = model.payoffs.flatMap { it.payoff.values.keys }.distinct()
        return if (dims.size >= 2) Applicability(0.85, listOf("${dims.size} payoff dimensions — Pareto frontier applicable."))
        else Applicability(0.0, listOf("Need ≥2 payoff dimensions"))
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val focal = model.stakeholders.firstOrNull() ?: return emptyResult(model)
        val focalPayoffs = model.payoffs.filter { it.stakeholderId == focal.id }
        if (focalPayoffs.isEmpty()) return emptyResult(model)

        val dims = focalPayoffs.flatMap { it.payoff.values.keys }.distinct()
        val scored: List<ScoredAction> = focalPayoffs.map { entry ->
            val profileLabel = entry.actionProfile.values.map { aid -> model.actions.firstOrNull { it.id == aid }?.label ?: aid }.joinToString("+")
            ScoredAction(focal.id, profileLabel, entry.payoff.values)
        }

        val pareto = scored.filter { a -> scored.none { b -> b !== a && dominates(b, a, dims) } }
        val dominated = scored - pareto.toSet()

        val findings = mutableListOf<Finding>()
        findings += Finding("Pareto-optimal profiles", "${pareto.size} of ${scored.size} profiles are non-dominated.")
        pareto.forEach { a ->
            val detail = dims.joinToString(", ") { d -> "${d.name}=${"%.2f".format(a.scores[d] ?: 0.0)}" }
            findings += Finding("Pareto-optimal: ${a.actionId}", detail)
        }
        if (dominated.isNotEmpty()) findings += Finding("Dominated", dominated.joinToString { it.actionId })

        val viz = VizSpec(VizSpec.Kind.PARETO, "Pareto frontier", mapOf(
            "dims" to dims.map { it.name },
            "profiles" to scored.map { mapOf("label" to it.actionId, "scores" to it.scores.mapKeys { e -> e.key.name }, "pareto" to (it in pareto)) }
        ))
        val recs = pareto.map { a ->
            RecommendedAction(focal.id, "Consider: ${a.actionId}", "Pareto-optimal across ${dims.size} dimensions.", lens = RecommendedAction.Lens.EXPECTED)
        }
        return AnalysisResult(
            id, name, canAnalyze(model), findings, recs,
            assumptions = listOf(Assumption("Higher = better for all dims except COST")),
            calculations = mapOf("pareto" to pareto.map { it.actionId }, "dominated" to dominated.map { it.actionId }),
            confidence = 0.8,
            visualizations = listOf(viz),
            explanation = "${pareto.size} Pareto-efficient option(s). No dominated option can improve any dimension without worsening another."
        )
    }

    private fun dominates(a: ScoredAction, b: ScoredAction, dims: List<PayoffDimension>): Boolean {
        val betterOrEqual = dims.all { d ->
            val av = a.scores[d] ?: 0.0; val bv = b.scores[d] ?: 0.0
            if (d.higherIsBetter) av >= bv else av <= bv
        }
        val strictlyBetter = dims.any { d ->
            val av = a.scores[d] ?: 0.0; val bv = b.scores[d] ?: 0.0
            if (d.higherIsBetter) av > bv + 1e-9 else av < bv - 1e-9
        }
        return betterOrEqual && strictlyBetter
    }

    private fun emptyResult(model: Scenario) = AnalysisResult(
        id, name, Applicability(0.0, listOf("No focal payoffs for '${model.title}'")), emptyList(), emptyList(),
        emptyList(), emptyMap(), 0.0, emptyList(), "Insufficient payoff data for scenario '${model.title}'."
    )
}
