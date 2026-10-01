package com.drkkr.strategos.domain.model

// ── Navigation target identifiers ────────────────────────────────────────────

enum class NavigationTarget(val tabId: String, val section: String = "") {
    FRAME("model", "frame"),
    MODEL_ACTIONS("model", "actions"),
    MODEL_CONSTRAINTS("model", "constraints"),
    MODEL_EVIDENCE("model", "evidence"),
    ANALYSIS("analysis", ""),
    REPORT_POLICY("report", "policy"),
    REPORT_APPROVALS("report", "approvals"),
    REPORT_CONSTRAINTS("report", "constraints"),
    REPORT_SNAPSHOT("report", "snapshot"),
    IMPLEMENTATION_PLAN("implementation", "plan"),
    IMPLEMENTATION_MONITORING("implementation", "monitoring"),
    IMPLEMENTATION_APPROVALS("implementation", "approvals"),
    SCENARIOS("scenarios", "")
}

// ── Directive produced by the orchestrator ────────────────────────────────────

data class ActionDirective(
    val ctaLabel: String,
    val description: String,
    val target: NavigationTarget,
    val blockers: List<String> = emptyList(),
    val requiredActions: List<String> = emptyList(),
    val isBlocked: Boolean = false
)

// ── Per-stage display state for the workflow strip ────────────────────────────

data class WorkflowStageState(
    val stage: WorkflowStage,
    val status: StageStatus,
    val blockers: List<String> = emptyList()
)

enum class StageStatus { COMPLETED, CURRENT, CURRENT_BLOCKED, FUTURE }

// ── Central orchestrator ──────────────────────────────────────────────────────

object WorkflowOrchestrator {

    private val hardKinds = setOf(
        Constraint.Kind.SAFETY, Constraint.Kind.LEGAL,
        Constraint.Kind.ETHICAL, Constraint.Kind.USER_HARD
    )

    /**
     * Returns the single most-important action the user must take next.
     * Never shows a future CTA when an earlier blocker exists.
     */
    fun nextAction(scenario: Scenario): ActionDirective {
        val stage = scenario.lifecycleStage
        val policy = scenario.selectedPolicyOption

        return when (stage) {
            LifecycleStage.DRAFT -> {
                val blockers = mutableListOf<String>()
                if (scenario.title.isBlank()) blockers += "Decision title required"
                if (scenario.decisionOwner.isBlank()) blockers += "Decision owner required"
                if (scenario.stakeholders.size < 2) blockers += "At least 2 stakeholders required"
                if (blockers.isNotEmpty())
                    ActionDirective("Complete decision frame", "Fill in title, owner, and stakeholders.", NavigationTarget.FRAME, blockers, isBlocked = true)
                else
                    ActionDirective("Build decision model", "Add actions, payoffs, and constraints.", NavigationTarget.MODEL_ACTIONS)
            }

            LifecycleStage.MODELLED -> {
                val blockers = mutableListOf<String>()
                if (scenario.actions.isEmpty()) blockers += "At least one action required"
                if (scenario.constraints.isEmpty()) blockers += "At least one constraint required"
                if (scenario.payoffs.isEmpty()) blockers += "Payoff structure required"
                if (blockers.isNotEmpty())
                    ActionDirective("Complete model", "Model is incomplete.", NavigationTarget.MODEL_ACTIONS, blockers, isBlocked = true)
                else
                    ActionDirective("Start challenge review", "Validate evidence, constraints, and assumptions.", NavigationTarget.MODEL_CONSTRAINTS)
            }

            LifecycleStage.CHALLENGED -> {
                ActionDirective("Run analysis", "Execute game-theory frameworks on validated model.", NavigationTarget.ANALYSIS)
            }

            LifecycleStage.ANALYSED -> {
                when {
                    policy == null ->
                        ActionDirective("Select policy option", "Choose the institutional option for execution.", NavigationTarget.REPORT_POLICY)
                    unresolvedHardConstraints(scenario, policy.actionId).isNotEmpty() -> {
                        val unresolved = unresolvedHardConstraints(scenario, policy.actionId)
                        ActionDirective(
                            "Assess hard constraints",
                            "${unresolved.size} hard constraint(s) require assessment for '${policy.actionLabel}'.",
                            NavigationTarget.MODEL_CONSTRAINTS,
                            unresolved.map { "Constraint '${it.label}' unassessed for selected policy" },
                            isBlocked = true
                        )
                    }
                    scenario.approvalStages.isEmpty() ->
                        ActionDirective("Define approval route", "No approval stages configured.", NavigationTarget.REPORT_APPROVALS)
                    else ->
                        ActionDirective("Submit for approval", "All prerequisites met.", NavigationTarget.REPORT_APPROVALS)
                }
            }

            LifecycleStage.READY_FOR_APPROVAL -> {
                val pending = scenario.approvalStages.filter { !it.isValid() }
                if (pending.isNotEmpty())
                    ActionDirective("Record approval decision", "${pending.size} stage(s) pending.", NavigationTarget.REPORT_APPROVALS)
                else
                    ActionDirective("Finalize approval", "All stages approved.", NavigationTarget.REPORT_APPROVALS)
            }

            LifecycleStage.APPROVED -> {
                val plan = scenario.implementationPlan
                when {
                    plan == null ->
                        ActionDirective("Create implementation plan", "Define owner, milestones, stop condition, and reversal plan.", NavigationTarget.IMPLEMENTATION_PLAN)
                    plan.owner.isBlank() ->
                        ActionDirective("Assign plan owner", "Implementation plan needs an owner.", NavigationTarget.IMPLEMENTATION_PLAN, listOf("Owner required"), isBlocked = true)
                    plan.stopCondition.isBlank() ->
                        ActionDirective("Define stop condition", "Stop condition required before implementation begins.", NavigationTarget.IMPLEMENTATION_PLAN, listOf("Stop condition required"), isBlocked = true)
                    else ->
                        ActionDirective("Begin implementation", "Plan complete — start implementation.", NavigationTarget.IMPLEMENTATION_PLAN)
                }
            }

            LifecycleStage.IN_IMPLEMENTATION -> {
                val entries = scenario.implementationPlan?.monitoringEntries ?: emptyList()
                if (entries.isEmpty())
                    ActionDirective("Log first monitoring update", "No monitoring entries recorded yet.", NavigationTarget.IMPLEMENTATION_MONITORING)
                else
                    ActionDirective("Log monitoring update", "Track progress against milestones and indicators.", NavigationTarget.IMPLEMENTATION_MONITORING)
            }

            LifecycleStage.MONITORING ->
                ActionDirective("Log monitoring update", "Track outcomes and update early-warning indicators.", NavigationTarget.IMPLEMENTATION_MONITORING)

            LifecycleStage.PAUSED ->
                ActionDirective("Review pause decision", "Stop triggered — decide to resume, revert, or close.", NavigationTarget.IMPLEMENTATION_MONITORING, listOf("Implementation paused — stop signal triggered"), isBlocked = true)

            LifecycleStage.HOLD ->
                ActionDirective("Re-analyse after hold", "Material change — re-run analysis and obtain fresh approvals.", NavigationTarget.ANALYSIS, listOf("Material change detected — approvals invalidated"), isBlocked = true)

            LifecycleStage.CLOSED ->
                ActionDirective("Archive scenario", "Decision closed. Archive or review for learning.", NavigationTarget.SCENARIOS)

            LifecycleStage.ARCHIVED ->
                ActionDirective("View archived scenario", "This scenario is archived.", NavigationTarget.SCENARIOS)
        }
    }

    /** Stage display states for the 6-node workflow strip. */
    fun stageStates(scenario: Scenario): List<WorkflowStageState> {
        val currentWorkflow = scenario.lifecycleStage.toWorkflowStage()
        val currentIdx = WorkflowStage.values().indexOf(currentWorkflow)
        val directive = nextAction(scenario)
        return WorkflowStage.values().mapIndexed { idx, stage ->
            when {
                idx < currentIdx -> WorkflowStageState(stage, StageStatus.COMPLETED)
                idx == currentIdx ->
                    if (directive.isBlocked)
                        WorkflowStageState(stage, StageStatus.CURRENT_BLOCKED, directive.blockers)
                    else
                        WorkflowStageState(stage, StageStatus.CURRENT)
                else -> WorkflowStageState(stage, StageStatus.FUTURE)
            }
        }
    }

    private fun unresolvedHardConstraints(scenario: Scenario, actionId: String): List<Constraint> {
        val hard = scenario.constraints.filter { it.kind in hardKinds }
        val assessed = scenario.constraintAssessments
            .filter { it.actionId == actionId }
            .map { it.constraintId }.toSet()
        return hard.filter { it.id !in assessed }
    }

    /** Validate that no implementation, approval, or monitoring objects reference a different action. */
    fun validatePolicyConsistency(scenario: Scenario): List<String> {
        val issues = mutableListOf<String>()
        val policyActionId = scenario.selectedPolicyOption?.actionId ?: return issues
        scenario.implementationPlan?.let { plan ->
            if (plan.selectedActionId.isNotBlank() && plan.selectedActionId != policyActionId)
                issues += "Implementation plan references action '${plan.selectedActionId}' — does not match selected policy '${policyActionId}'"
        }
        scenario.approvalStages.forEach { stage ->
            if (stage.decisions.any { it.stageId != stage.stageId })
                issues += "Approval stage '${stage.name}' has mismatched decision records"
        }
        return issues
    }
}
