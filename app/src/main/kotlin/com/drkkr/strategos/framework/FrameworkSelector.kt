package com.drkkr.strategos.framework

import com.drkkr.strategos.domain.framework.FrameworkCategory
import com.drkkr.strategos.domain.framework.FrameworkModule
import com.drkkr.strategos.domain.model.Scenario

data class FrameworkSelection(
    val moduleId: String,
    val moduleName: String,
    val category: FrameworkCategory,
    val reason: String
)

data class AnalysisPlan(
    val core: List<FrameworkSelection>,
    val supporting: List<FrameworkSelection>,
    val exploratory: List<FrameworkSelection>,
    val excluded: List<FrameworkSelection>
) {
    fun selected() = core + supporting + exploratory

    fun summary(): String = buildString {
        appendLine("Selected analysis plan")
        appendLine("Core models: ${if (core.isEmpty()) "none" else core.joinToString(", ") { it.moduleName }}")
        appendLine("Supporting models: ${if (supporting.isEmpty()) "none" else supporting.joinToString(", ") { it.moduleName }}")
        appendLine("Exploratory models: ${if (exploratory.isEmpty()) "none" else exploratory.joinToString(", ") { it.moduleName }}")
        if (excluded.isNotEmpty()) {
            appendLine("Excluded: ${excluded.joinToString(", ") { it.moduleName }}")
            appendLine("Reason: ${excluded.firstOrNull()?.reason ?: ""}")
        }
    }
}

/**
 * Classifies framework modules as Core / Supporting / Exploratory / Excluded
 * for a given scenario, using applicability scores and structural signals.
 *
 * Rules:
 *  CORE        : applicability ≥ 0.70  AND  module is structurally required
 *  SUPPORTING  : applicability ≥ 0.45  OR   module provides useful secondary perspective
 *  EXPLORATORY : applicability ≥ 0.25  but dependent on weak/incomplete data
 *  EXCLUDED    : applicability < 0.25  OR   hard constraint violation
 */
object FrameworkSelector {

    // Module IDs that are structurally core for most decision scenarios
    private val alwaysCoreIds = setOf(
        "robust-frontier",   // Robust Decision Frontier — always relevant
        "red-team"           // Red-team — always relevant
    )

    // Module IDs that are supporting by default unless data elevates them
    private val defaultSupportingIds = setOf(
        "sensitivity",
        "pareto",
        "expected-utility",
        "bayesian-updating",
        "value-of-information",
        "behavioral-response",
        "scenario-ensemble"
    )

    fun classify(scenario: Scenario, modules: List<FrameworkModule>): AnalysisPlan {
        val core = mutableListOf<FrameworkSelection>()
        val supporting = mutableListOf<FrameworkSelection>()
        val exploratory = mutableListOf<FrameworkSelection>()
        val excluded = mutableListOf<FrameworkSelection>()

        val hasPayoffs = scenario.payoffs.isNotEmpty()
        val hasTree = scenario.gameTree != null
        val hasBeliefs = scenario.beliefs.isNotEmpty()
        val hasTwoPlayers = scenario.players().size == 2
        val hasMultiPlayers = scenario.players().size >= 3
        val text = "${scenario.title} ${scenario.description}".lowercase()

        for (module in modules) {
            val app = module.canAnalyze(scenario)
            val score = app.score
            val id = module.id

            val selection = when {
                // Hard exclude
                score < 0.15 -> FrameworkSelection(
                    id, module.name, FrameworkCategory.EXCLUDED,
                    app.reasons.firstOrNull() ?: "Applicability score ${(score * 100).toInt()}% — below threshold"
                )

                // Structural always-core modules
                id in alwaysCoreIds && score >= 0.50 -> FrameworkSelection(
                    id, module.name, FrameworkCategory.CORE,
                    "Required for every decision analysis: ${app.reasons.firstOrNull() ?: ""}"
                )

                // Nash equilibrium — core when payoffs exist with 2+ players
                (id == "pure-nash" || id == "dominant-strategy") && hasPayoffs && hasTwoPlayers ->
                    FrameworkSelection(id, module.name, FrameworkCategory.CORE,
                        "Payoff matrix present with 2 players — Nash equilibrium is structurally required")

                (id == "pure-nash" || id == "dominant-strategy") && hasPayoffs && hasMultiPlayers ->
                    FrameworkSelection(id, module.name, FrameworkCategory.CORE,
                        "Payoff matrix with ${ scenario.stakeholders.size } players — Nash analysis required")

                // Backward induction — core when game tree exists
                id == "backward-induction" && hasTree ->
                    FrameworkSelection(id, module.name, FrameworkCategory.CORE,
                        "Sequential game tree present — backward induction is required")

                // Coalition analysis — core when 3+ stakeholders with authority
                id == "coalition-analysis" && hasMultiPlayers ->
                    FrameworkSelection(id, module.name, FrameworkCategory.CORE,
                        "${scenario.stakeholders.size} stakeholders with decision authority — coalition analysis required")

                // Minimax — core in zero-sum or adversarial scenarios
                id == "minimax" && (scenario.zeroSum || text.contains("adversar") || text.contains("compet")) ->
                    FrameworkSelection(id, module.name, FrameworkCategory.CORE,
                        "Zero-sum or adversarial context — minimax is structurally required")

                // High applicability → core or supporting
                score >= 0.75 -> FrameworkSelection(
                    id, module.name, FrameworkCategory.CORE,
                    "High applicability (${(score * 100).toInt()}%): ${app.reasons.firstOrNull() ?: ""}"
                )

                score >= 0.55 && id !in defaultSupportingIds -> FrameworkSelection(
                    id, module.name, FrameworkCategory.SUPPORTING,
                    "Moderate applicability (${(score * 100).toInt()}%): ${app.reasons.firstOrNull() ?: ""}"
                )

                id in defaultSupportingIds && score >= 0.40 -> FrameworkSelection(
                    id, module.name, FrameworkCategory.SUPPORTING,
                    "Secondary analytical perspective: ${app.reasons.firstOrNull() ?: ""}"
                )

                // VOI — supporting when beliefs are incomplete
                id == "value-of-information" && !hasBeliefs -> FrameworkSelection(
                    id, module.name, FrameworkCategory.SUPPORTING,
                    "No probability beliefs entered — VOI identifies highest-value information to collect"
                )

                // Bayesian — supporting when beliefs exist
                id == "bayesian-updating" && hasBeliefs -> FrameworkSelection(
                    id, module.name, FrameworkCategory.SUPPORTING,
                    "Probability beliefs present — Bayesian updating is applicable"
                )

                score >= 0.25 -> FrameworkSelection(
                    id, module.name, FrameworkCategory.EXPLORATORY,
                    "Weak applicability (${(score * 100).toInt()}%) — dependent on assumptions: ${app.reasons.firstOrNull() ?: ""}"
                )

                else -> FrameworkSelection(
                    id, module.name, FrameworkCategory.EXCLUDED,
                    app.reasons.firstOrNull() ?: "Applicability score ${(score * 100).toInt()}% — insufficient data"
                )
            }

            when (selection.category) {
                FrameworkCategory.CORE -> core += selection
                FrameworkCategory.SUPPORTING -> supporting += selection
                FrameworkCategory.EXPLORATORY -> exploratory += selection
                FrameworkCategory.EXCLUDED -> excluded += selection
            }
        }

        return AnalysisPlan(core, supporting, exploratory, excluded)
    }
}
