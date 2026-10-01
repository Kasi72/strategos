package com.drkkr.strategos.domain.framework

import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.Constraint
import com.drkkr.strategos.domain.model.ConstraintStatus
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.recommendation.DecisionCandidate
import com.drkkr.strategos.domain.recommendation.FeasibilityStatus

object AnalysisEligibilityGate {

    private val hardConstraintKinds = setOf(
        Constraint.Kind.SAFETY,
        Constraint.Kind.LEGAL,
        Constraint.Kind.ETHICAL,
        Constraint.Kind.USER_HARD
    )

    fun determine(
        scenario: Scenario,
        results: List<AnalysisResult>,
        candidates: List<DecisionCandidate>
    ): ExecutiveStatus = determineWithReason(scenario, results, candidates).status

    fun determineWithReason(
        scenario: Scenario,
        results: List<AnalysisResult>,
        candidates: List<DecisionCandidate>
    ): ExecutiveStatusReason {
        val blockers = mutableListOf<String>()
        val required = mutableListOf<String>()

        // BLOCKED_UNKNOWN_CONSTRAINT is NOT feasible — treat as blocked
        val feasible = candidates.filter { it.feasibilityStatus == FeasibilityStatus.FEASIBLE }
        val unknownBlocked = candidates.filter { it.feasibilityStatus == FeasibilityStatus.BLOCKED_UNKNOWN_CONSTRAINT }
        val hardBlocked = candidates.filter { it.feasibilityStatus == FeasibilityStatus.BLOCKED_HARD_CONSTRAINT }

        // Rule 1: All candidates blocked
        if (candidates.isNotEmpty() && feasible.isEmpty()) {
            return if (unknownBlocked.isNotEmpty()) {
                unknownBlocked.forEach { blockers += "Unknown constraint blocks: ${it.actionLabel}" }
                required += "Assess all hard constraints before proceeding"
                ExecutiveStatusReason(ExecutiveStatus.HOLD_FOR_VERIFICATION, blockers, required)
            } else {
                hardBlocked.forEach { blockers += "Hard constraint blocks: ${it.actionLabel}" }
                ExecutiveStatusReason(ExecutiveStatus.BLOCKED_CONSTRAINT, blockers, required)
            }
        }

        // Rule 2: No evidence — must collect before recommending
        if (scenario.evidenceSources.isEmpty()) {
            blockers += "No evidence sources recorded"
            required += "Add at least one evidence source before analysis"
            return ExecutiveStatusReason(ExecutiveStatus.HOLD_FOR_EVIDENCE, blockers, required)
        }

        // Rule 3: All feasible candidates tied or zero-scored
        if (feasible.isNotEmpty()) {
            val scores = feasible.map { it.score }
            val maxScore = scores.maxOrNull() ?: 0.0
            val allZero = scores.all { it < 0.001 }
            val allTied = !allZero && feasible.size > 1 &&
                scores.all { kotlin.math.abs(it - maxScore) < 0.01 }
            if (allZero || allTied) {
                blockers += if (allZero) "All candidates score zero" else "All candidates tied — cannot distinguish"
                required += "Expand payoff matrix or add distinguishing evidence"
                return ExecutiveStatusReason(ExecutiveStatus.INDETERMINATE, blockers, required)
            }
        }

        // Rule 4: Hard constraint completeness — must check BEFORE payoff/calculation checks
        // because safety violations trump exploratory status
        val hardConstraints = scenario.constraints.filter { it.kind in hardConstraintKinds }
        val unassessedConstraints = hardConstraints.filter { c ->
            scenario.constraintAssessments.none { a -> a.constraintId == c.id }
        }
        val hasUnknownConstraints = unassessedConstraints.isNotEmpty()

        // Rule 5: Risky action with unresolved hard constraints → HOLD_FOR_VERIFICATION
        // This takes priority over EXPLORATORY_ONLY because the risk of proceeding is high
        if (hasUnknownConstraints && feasible.isNotEmpty()) {
            val riskyFeasible = feasible.filter { candidate ->
                val action = scenario.actions.firstOrNull { it.id == candidate.actionId }
                isRiskyAction(action)
            }
            if (riskyFeasible.isNotEmpty()) {
                unassessedConstraints.forEach { blockers += "Unresolved constraint: ${it.label}" }
                riskyFeasible.forEach { required += "Verify constraints for irreversible/high-impact action: ${it.actionLabel}" }
                return ExecutiveStatusReason(ExecutiveStatus.HOLD_FOR_VERIFICATION, blockers, required)
            }
        }

        // Rule 6: Reversible low-impact actions with satisfied constraints may proceed as CONDITIONAL_TRIAL
        // even without a full payoff matrix — risk of being wrong is acceptably low
        val allConstraintsSatisfied = !hasUnknownConstraints &&
            feasible.all { it.constraintStatusSummary != ConstraintStatus.UNKNOWN }
        val allLowRisk = feasible.isNotEmpty() && feasible.all { candidate ->
            val action = scenario.actions.firstOrNull { it.id == candidate.actionId }
            isLowRiskAction(action)
        }
        if (allLowRisk && allConstraintsSatisfied) {
            return ExecutiveStatusReason(ExecutiveStatus.CONDITIONAL_TRIAL)
        }

        // Rule 7: No payoff matrix
        if (scenario.payoffs.isEmpty()) {
            return ExecutiveStatusReason(ExecutiveStatus.EXPLORATORY_ONLY)
        }

        // Rule 8: No calculated results and low evidence
        val hasCalculated = results.any { it.calculationStatus == CalculationStatus.CALCULATED }
        if (!hasCalculated && scenario.evidenceQuality() < 40) {
            return ExecutiveStatusReason(ExecutiveStatus.EXPLORATORY_ONLY)
        }

        val bestCandidate = feasible.firstOrNull()
        val highConfidence = (bestCandidate?.confidence ?: 0.0) >= 0.65

        return when {
            allConstraintsSatisfied && highConfidence && hasCalculated ->
                ExecutiveStatusReason(ExecutiveStatus.PROCEED)

            else -> {
                if (hasUnknownConstraints) {
                    unassessedConstraints.forEach { blockers += "Unresolved constraint: ${it.label}" }
                    required += "Assess all hard constraints"
                }
                ExecutiveStatusReason(ExecutiveStatus.HOLD_FOR_VERIFICATION, blockers, required)
            }
        }
    }

    private fun isRiskyAction(action: Action?): Boolean {
        if (action == null) return false
        return !action.reversible ||
            action.publicVisibility ||
            action.cost > 0 ||
            action.implementationDifficulty >= 4
    }

    private fun isLowRiskAction(action: Action?): Boolean {
        if (action == null) return true
        return action.reversible &&
            !action.publicVisibility &&
            action.cost <= 0 &&
            action.implementationDifficulty <= 2
    }
}
