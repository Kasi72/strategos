package com.drkkr.strategos.domain.model

data class PolicySafetyResult(
    val selectedActionId: String,
    val policyOptionId: String,
    val eligible: Boolean,
    val blockers: List<String>,
    val warnings: List<String>,
    val assessedConstraints: List<ConstraintAssessment>,
    val expiredAssessments: List<ConstraintAssessment>,
    val violatedConstraints: List<ConstraintAssessment>,
    val missingConstraints: List<Constraint>
)

object PolicySafetyEvaluator {

    private val hardKinds = setOf(
        Constraint.Kind.SAFETY, Constraint.Kind.LEGAL,
        Constraint.Kind.ETHICAL, Constraint.Kind.USER_HARD
    )

    fun evaluate(scenario: Scenario): PolicySafetyResult {
        val policy = scenario.selectedPolicyOption
        val actionId = policy?.actionId ?: ""
        val policyOptionId = policy?.id ?: ""
        val hardConstraints = scenario.constraints.filter { it.kind in hardKinds }
        val now = System.currentTimeMillis()

        // Assessments scoped to the selected action and optionally this policyOption
        val relevant = scenario.constraintAssessments.filter { a ->
            a.actionId == actionId &&
            (a.policyOptionId.isEmpty() || a.policyOptionId == policyOptionId)
        }
        val byConstraint = relevant.associateBy { it.constraintId }

        val expired  = relevant.filter { it.expiryDate > 0 && now > it.expiryDate }
        val violated = relevant.filter { it.status == ConstraintStatus.VIOLATED }
        val missing  = hardConstraints.filter { c -> byConstraint[c.id] == null }
        val unclear  = relevant.filter { it.status == ConstraintStatus.UNKNOWN }

        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (actionId.isEmpty()) {
            blockers += "No policy action selected"
        }
        violated.forEach { a ->
            val label = hardConstraints.find { it.id == a.constraintId }?.label ?: a.constraintId
            blockers += "Constraint violated: $label"
        }
        missing.forEach { c -> blockers += "No assessment for hard constraint: ${c.label}" }
        expired.forEach { a ->
            val label = hardConstraints.find { it.id == a.constraintId }?.label ?: a.constraintId
            blockers += "Assessment expired: $label"
        }
        unclear.forEach { a ->
            val label = hardConstraints.find { it.id == a.constraintId }?.label ?: a.constraintId
            warnings += "Assessment unclear: $label"
        }

        return PolicySafetyResult(
            selectedActionId = actionId,
            policyOptionId = policyOptionId,
            eligible = blockers.isEmpty(),
            blockers = blockers,
            warnings = warnings,
            assessedConstraints = relevant,
            expiredAssessments = expired,
            violatedConstraints = violated,
            missingConstraints = missing
        )
    }

    fun hardConstraintStatuses(scenario: Scenario, actionId: String): List<Pair<Constraint, ConstraintAssessment?>> {
        val hard = scenario.constraints.filter { it.kind in hardKinds }
        val policy = scenario.selectedPolicyOption
        val policyOptionId = policy?.id ?: ""
        val relevant = scenario.constraintAssessments.filter { a ->
            a.actionId == actionId &&
            (a.policyOptionId.isEmpty() || a.policyOptionId == policyOptionId)
        }.associateBy { it.constraintId }
        return hard.map { c -> c to relevant[c.id] }
    }
}
