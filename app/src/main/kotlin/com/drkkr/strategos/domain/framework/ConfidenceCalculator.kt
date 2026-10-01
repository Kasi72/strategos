package com.drkkr.strategos.domain.framework

import com.drkkr.strategos.domain.model.Constraint
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario

object ConfidenceCalculator {

    fun compute(scenario: Scenario, results: List<AnalysisResult>): ConfidenceProfile {

        // ── Evidence quality ────────────────────────────────────────────────
        val evidenceQ = scenario.evidenceQuality() / 100.0
        val evidenceExp = when {
            scenario.evidenceSources.isEmpty() -> "No evidence sources entered — all analysis is proxy-only"
            evidenceQ < 0.4 -> "Evidence quality low (${(evidenceQ * 100).toInt()}%) — mostly expert opinion or internal data"
            evidenceQ < 0.7 -> "Evidence quality moderate (${(evidenceQ * 100).toInt()}%) — mix of strong and weak sources"
            else -> "Evidence quality good (${(evidenceQ * 100).toInt()}%)"
        }

        // ── Model completeness ──────────────────────────────────────────────
        val completeness = scenario.completenessScore() / 100.0
        val unknownBeliefs = scenario.beliefs.count { it.status == FactStatus.UNKNOWN }
        val userEstimateBeliefs = scenario.beliefs.count { it.status == FactStatus.USER_ESTIMATE }
        val completenessAdj = (completeness - unknownBeliefs * 0.04 - userEstimateBeliefs * 0.02).coerceAtLeast(0.05)
        val completenessExp = buildString {
            append("Model completeness ${(completenessAdj * 100).toInt()}%")
            if (scenario.payoffs.isEmpty()) append(" — no payoff matrix")
            if (scenario.beliefs.isEmpty()) append(" — no probability beliefs")
            if (unknownBeliefs > 0) append(" — $unknownBeliefs unknown belief(s)")
        }

        // ── Analytical robustness ───────────────────────────────────────────
        val coreResults = results.filter { it.frameworkCategory == FrameworkCategory.CORE }
        val robustness = if (coreResults.isEmpty()) {
            0.35
        } else {
            // Check if top recommended action is consistent across core modules
            val topActions = coreResults.flatMap { r ->
                r.recommendedActions.filter { it.lens == RecommendedAction.Lens.EXPECTED }.map { it.label }
            }
            val consistency = if (topActions.isEmpty()) 0.4
            else {
                val modeCount = topActions.groupBy { it.lowercase().trim() }.values.maxOfOrNull { it.size } ?: 0
                modeCount.toDouble() / topActions.size
            }
            val avgApplicability = coreResults.map { it.applicability.score }.average()
            (consistency * 0.6 + avgApplicability * 0.4).coerceIn(0.1, 0.95)
        }
        val robustnessExp = when {
            coreResults.isEmpty() -> "No core models run — robustness not established"
            robustness >= 0.70 -> "Leading option consistent across ${coreResults.size} core model(s)"
            robustness >= 0.50 -> "Partial agreement across core models — verify assumptions"
            else -> "Core models disagree — recommendation is sensitive to model choice"
        }

        // ── Implementation readiness ────────────────────────────────────────
        val vetoPlayers = scenario.stakeholders.filter { it.canVeto }
        val confirmedVeto = vetoPlayers.count { s ->
            s.behavioralAssumptions.any { it.status == FactStatus.CONFIRMED }
        }
        val hardConstraints = scenario.constraints.filter {
            it.kind == Constraint.Kind.SAFETY || it.kind == Constraint.Kind.LEGAL || it.kind == Constraint.Kind.ETHICAL
        }
        val topAction = results.flatMap { it.recommendedActions }
            .filter { it.lens == RecommendedAction.Lens.EXPECTED }
            .groupBy { it.label.lowercase().trim() }
            .maxByOrNull { it.value.size }?.value?.firstOrNull()
        val reversible = scenario.actions.firstOrNull { a ->
            topAction?.label?.contains(a.label, ignoreCase = true) == true
        }?.reversible ?: true

        val implBase = when {
            vetoPlayers.isEmpty() -> 0.70
            confirmedVeto == vetoPlayers.size -> 0.80
            confirmedVeto > 0 -> 0.55
            else -> 0.35
        }
        val implAdj = implBase - (hardConstraints.size * 0.03) + (if (reversible) 0.05 else -0.08)
        val implReadiness = implAdj.coerceIn(0.05, 0.95)
        val implExp = buildString {
            if (vetoPlayers.isNotEmpty()) {
                append("${confirmedVeto}/${vetoPlayers.size} veto-holder commitment(s) confirmed")
            } else {
                append("No formal veto players identified")
            }
            if (hardConstraints.isNotEmpty()) append(" — ${hardConstraints.size} hard constraint(s) active")
            if (!reversible) append(" — leading action is irreversible")
        }

        return ConfidenceProfile(
            evidenceQuality = evidenceQ,
            modelCompleteness = completenessAdj,
            analyticalRobustness = robustness,
            implementationReadiness = implReadiness,
            explanations = mapOf(
                "evidenceQuality" to evidenceExp,
                "modelCompleteness" to completenessExp,
                "analyticalRobustness" to robustnessExp,
                "implementationReadiness" to implExp
            )
        )
    }
}
