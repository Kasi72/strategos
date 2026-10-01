package com.drkkr.strategos.domain.framework

import com.drkkr.strategos.domain.model.Constraint
import com.drkkr.strategos.domain.model.Scenario

data class ModelIssue(
    val field: String,
    val severity: Severity,
    val message: String
) {
    enum class Severity { ERROR, WARNING, INFO }
}

data class ModelValidityReport(
    val issues: List<ModelIssue>
) {
    val blocksAnalysis: Boolean = issues.any { it.severity == ModelIssue.Severity.ERROR }
    val hasWarnings: Boolean = issues.any { it.severity == ModelIssue.Severity.WARNING }
    val isClean: Boolean = issues.isEmpty()
}

object ModelValidator {

    private val hardConstraintKinds = setOf(
        Constraint.Kind.SAFETY,
        Constraint.Kind.LEGAL,
        Constraint.Kind.ETHICAL,
        Constraint.Kind.USER_HARD
    )

    fun validate(scenario: Scenario): ModelValidityReport {
        val issues = mutableListOf<ModelIssue>()

        if (scenario.decisionOwner.isBlank()) {
            issues += ModelIssue("decisionOwner", ModelIssue.Severity.WARNING,
                "No decision owner set — results cannot be attributed.")
        }
        if (scenario.stakeholders.isEmpty()) {
            issues += ModelIssue("stakeholders", ModelIssue.Severity.ERROR,
                "No stakeholders defined — analysis cannot run.")
        }
        if (scenario.actions.isEmpty()) {
            issues += ModelIssue("actions", ModelIssue.Severity.WARNING,
                "No actions defined — game-theoretic modules will not score options.")
        }
        for (belief in scenario.beliefs) {
            if (belief.probability < 0.0 || belief.probability > 1.0) {
                issues += ModelIssue("beliefs", ModelIssue.Severity.ERROR,
                    "Probability ${belief.probability} outside [0, 1] for belief '${belief.label}'.")
            }
        }
        if (scenario.beliefs.size > 1) {
            val sum = scenario.beliefs.sumOf { it.probability }
            if (sum > 1.001) {
                issues += ModelIssue("beliefs.sum", ModelIssue.Severity.WARNING,
                    "Belief probabilities sum to ${"%.2f".format(sum)} — should be ≤ 1 if mutually exclusive.")
            }
        }
        // Duplicate payoff entries
        val payoffKeys = scenario.payoffs.map { entry ->
            "${entry.stakeholderId}|${entry.actionProfile.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }}"
        }
        if (payoffKeys.distinct().size < payoffKeys.size) {
            issues += ModelIssue("payoffs", ModelIssue.Severity.WARNING,
                "Duplicate payoff entries detected — check action profiles.")
        }
        // Actions referenced in payoffs must exist
        val actionIds = scenario.actions.map { it.id }.toSet()
        val referencedActions = scenario.payoffs.flatMap { it.actionProfile.values }.toSet()
        val missingActions = referencedActions - actionIds
        if (missingActions.isNotEmpty()) {
            issues += ModelIssue("payoffs.actions", ModelIssue.Severity.WARNING,
                "Payoff entries reference ${missingActions.size} action id(s) not in the actions list.")
        }
        // Hard constraints without assessments
        val hardConstraints = scenario.constraints.filter { it.kind in hardConstraintKinds }
        for (action in scenario.actions) {
            val assessedIds = scenario.constraintAssessments
                .filter { it.actionId == action.id }
                .map { it.constraintId }.toSet()
            val unassessed = hardConstraints.filter { it.id !in assessedIds }
            if (unassessed.isNotEmpty()) {
                issues += ModelIssue("constraints.${action.id}", ModelIssue.Severity.WARNING,
                    "Action '${action.label}' has ${unassessed.size} unassessed hard constraint(s): " +
                    unassessed.joinToString(", ") { it.label })
            }
        }

        return ModelValidityReport(issues)
    }
}
