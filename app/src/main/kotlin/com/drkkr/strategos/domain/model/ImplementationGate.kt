package com.drkkr.strategos.domain.model

enum class GateStatus { PASS, AMBER, BLOCKED, NOT_APPLICABLE }

data class GateCheck(
    val name: String,
    val status: GateStatus,
    val detail: String = ""
)

enum class ImplementationGateOverallStatus(val label: String) {
    BLOCKED("Blocked"),
    HOLD_FOR_VERIFICATION("Hold for Verification"),
    READY_FOR_APPROVAL("Ready for Approval"),
    APPROVED_NOT_STARTED("Approved — Not Started"),
    IN_IMPLEMENTATION("In Implementation"),
    MONITORING("Monitoring"),
    PAUSED("Paused — Stop Triggered"),
    CLOSED("Closed")
}

data class ImplementationGateResult(
    val overallStatus: ImplementationGateOverallStatus,
    val gates: List<GateCheck>,
    val blockingReasons: List<String>,
    val nextRequiredAction: String
)

object ImplementationGate {

    private val hardKinds = setOf(
        Constraint.Kind.SAFETY, Constraint.Kind.LEGAL,
        Constraint.Kind.ETHICAL, Constraint.Kind.USER_HARD
    )

    fun evaluate(scenario: Scenario): ImplementationGateResult {
        val gates = mutableListOf<GateCheck>()
        val blockers = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val plan = scenario.implementationPlan

        // 1. Approved decision snapshot
        val hasApproved = scenario.lifecycleStage.isApprovedOrBeyond()
        gates += gate("Approved decision snapshot", hasApproved, !hasApproved, "No approved decision snapshot")
        if (!hasApproved) blockers += "No approved decision snapshot"

        // 2. Selected policy option
        val policyOption = scenario.selectedPolicyOption
        val hasPolicyOption = policyOption != null
        gates += GateCheck(
            "Selected policy option",
            if (hasPolicyOption) GateStatus.PASS else GateStatus.BLOCKED,
            policyOption?.actionLabel ?: "None selected"
        )
        if (!hasPolicyOption) blockers += "No policy option selected"

        // 3. Hard constraints gate — scoped to selected policy option's action only
        val scopedActionId = policyOption?.actionId
        val hardConstraints = scenario.constraints.filter { it.kind in hardKinds }
        val scopedAssessments = if (scopedActionId != null)
            scenario.constraintAssessments.filter { it.actionId == scopedActionId }
        else
            scenario.constraintAssessments
        val unassessed = hardConstraints.filter { c ->
            scopedAssessments.none { a -> a.constraintId == c.id }
        }
        val violated = scopedAssessments.filter { it.status == ConstraintStatus.VIOLATED }
        val hardGateStatus = when {
            violated.isNotEmpty() -> GateStatus.BLOCKED
            unassessed.isNotEmpty() -> GateStatus.AMBER
            else -> GateStatus.PASS
        }
        gates += GateCheck(
            "Hard constraints clear", hardGateStatus,
            when {
                violated.isNotEmpty() -> "${violated.size} violated"
                unassessed.isNotEmpty() -> "${unassessed.size} unassessed"
                hardConstraints.isEmpty() -> "No hard constraints recorded"
                else -> "All clear"
            }
        )
        if (hardGateStatus == GateStatus.BLOCKED) blockers += "${violated.size} hard constraint(s) violated"
        if (hardGateStatus == GateStatus.AMBER) blockers += "${unassessed.size} hard constraint(s) unassessed"

        // 4. Approval gate
        val pendingApprovals = scenario.approvalStages.filter { !it.isValid(now) }
        val approvalGateStatus = when {
            scenario.approvalStages.isEmpty() -> GateStatus.BLOCKED
            pendingApprovals.isNotEmpty() -> GateStatus.AMBER
            else -> GateStatus.PASS
        }
        gates += GateCheck(
            "Required approvals complete", approvalGateStatus,
            when {
                scenario.approvalStages.isEmpty() -> "No approval stages defined"
                pendingApprovals.isNotEmpty() -> "${pendingApprovals.size} pending/invalid"
                else -> "All ${scenario.approvalStages.size} stage(s) approved"
            }
        )
        if (approvalGateStatus == GateStatus.BLOCKED) blockers += "No approval stages defined"
        if (approvalGateStatus == GateStatus.AMBER) blockers += "${pendingApprovals.size} approval(s) pending or expired"

        // 5. Implementation owner
        val hasOwner = plan?.owner?.isNotBlank() == true
        gates += gate("Named implementation owner", hasOwner, !hasOwner,
            if (!hasOwner) "No owner assigned" else plan!!.owner)
        if (!hasOwner) blockers += "No implementation owner assigned"

        // 6. Milestones
        val hasMilestones = plan?.milestones?.isNotEmpty() == true
        gates += GateCheck(
            "Implementation milestones",
            if (hasMilestones) GateStatus.PASS else GateStatus.AMBER,
            if (!hasMilestones) "No milestones defined" else "${plan!!.milestones.size} milestone(s)"
        )

        // 7. Preconditions
        val hasPreconditions = plan?.preconditions?.isNotEmpty() == true
        gates += GateCheck(
            "Preconditions defined",
            if (hasPreconditions) GateStatus.PASS else GateStatus.AMBER,
            if (!hasPreconditions) "No preconditions listed" else "${plan!!.preconditions.size} precondition(s)"
        )

        // 8. Resources / budget
        val hasResources = (plan?.budget ?: 0.0) > 0.0
        gates += GateCheck(
            "Resources / budget confirmed",
            if (hasResources) GateStatus.PASS else GateStatus.AMBER,
            if (!hasResources) "No budget estimate" else "Budget: ${plan!!.budget}"
        )

        // 9. Monitoring indicators
        val hasMonitoring = plan?.earlyWarningIndicators?.isNotEmpty() == true
        gates += gate("Monitoring indicators defined", hasMonitoring, !hasMonitoring,
            if (!hasMonitoring) "No early warning indicators" else "${plan!!.earlyWarningIndicators.size} indicator(s)")
        if (!hasMonitoring) blockers += "No monitoring indicators defined"

        // 10. Stop condition and reversal plan
        val hasStop = plan?.stopCondition?.isNotBlank() == true
        val hasReversal = plan?.reversalPlan?.isNotBlank() == true
        val stopGate = when {
            hasStop && hasReversal -> GateStatus.PASS
            hasStop || hasReversal -> GateStatus.AMBER
            else -> GateStatus.BLOCKED
        }
        gates += GateCheck(
            "Stop condition and reversal plan", stopGate,
            when {
                !hasStop && !hasReversal -> "Neither defined"
                !hasStop -> "Stop condition missing"
                !hasReversal -> "Reversal plan missing"
                else -> "Both defined"
            }
        )
        if (stopGate == GateStatus.BLOCKED) blockers += "No stop condition or reversal plan defined"

        // Overall status
        val hasStopTriggered = plan?.monitoringEntries
            ?.any { it.signalStatus == MonitoringSignal.STOP_TRIGGERED } == true

        val overall = when {
            scenario.lifecycleStage == LifecycleStage.PAUSED -> ImplementationGateOverallStatus.PAUSED
            hasStopTriggered -> ImplementationGateOverallStatus.PAUSED
            scenario.lifecycleStage == LifecycleStage.CLOSED -> ImplementationGateOverallStatus.CLOSED
            scenario.lifecycleStage == LifecycleStage.MONITORING -> ImplementationGateOverallStatus.MONITORING
            scenario.lifecycleStage == LifecycleStage.IN_IMPLEMENTATION -> ImplementationGateOverallStatus.IN_IMPLEMENTATION
            // Gate-based status: check blockers BEFORE granting stage-derived status
            hardGateStatus == GateStatus.AMBER && blockers.isNotEmpty() -> ImplementationGateOverallStatus.HOLD_FOR_VERIFICATION
            blockers.isNotEmpty() -> ImplementationGateOverallStatus.BLOCKED
            scenario.lifecycleStage == LifecycleStage.APPROVED -> ImplementationGateOverallStatus.APPROVED_NOT_STARTED
            scenario.lifecycleStage == LifecycleStage.READY_FOR_APPROVAL -> ImplementationGateOverallStatus.READY_FOR_APPROVAL
            else -> ImplementationGateOverallStatus.READY_FOR_APPROVAL
        }

        val nextAction = when (overall) {
            ImplementationGateOverallStatus.BLOCKED -> "Resolve all blocked gates before proceeding"
            ImplementationGateOverallStatus.HOLD_FOR_VERIFICATION -> "Assess unresolved constraints and obtain missing approvals"
            ImplementationGateOverallStatus.READY_FOR_APPROVAL -> "Submit for approval and complete approval route"
            ImplementationGateOverallStatus.APPROVED_NOT_STARTED -> "Create implementation plan with owner, milestones, and stop condition"
            ImplementationGateOverallStatus.IN_IMPLEMENTATION -> "Track milestones and monitor early warning indicators"
            ImplementationGateOverallStatus.MONITORING -> "Review monitoring entries and update outcome observations"
            ImplementationGateOverallStatus.PAUSED -> "Review stop-triggered signal and decide: resume or revert"
            ImplementationGateOverallStatus.CLOSED -> "Decision closed. Archive or review for learning."
        }

        return ImplementationGateResult(overall, gates, blockers, nextAction)
    }

    private fun gate(name: String, pass: Boolean, blocked: Boolean, detail: String): GateCheck =
        GateCheck(name, if (pass) GateStatus.PASS else if (blocked) GateStatus.BLOCKED else GateStatus.AMBER, detail)
}
