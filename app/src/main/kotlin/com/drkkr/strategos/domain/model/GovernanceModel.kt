package com.drkkr.strategos.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

// ── Decision Lifecycle ────────────────────────────────────────────────────────

@Serializable
enum class LifecycleStage {
    DRAFT,
    MODELLED,
    CHALLENGED,          // evidence, constraints, red-team completed — ready to analyse
    ANALYSED,
    HOLD,
    READY_FOR_APPROVAL,
    APPROVED,
    IN_IMPLEMENTATION,
    MONITORING,
    PAUSED,              // stop-triggered during implementation — explicit recovery needed
    CLOSED,
    ARCHIVED;

    fun displayLabel(): String = when (this) {
        DRAFT -> "Draft"
        MODELLED -> "Modelled"
        CHALLENGED -> "Challenged"
        ANALYSED -> "Analysed"
        HOLD -> "Hold"
        READY_FOR_APPROVAL -> "Ready for Approval"
        APPROVED -> "Approved"
        IN_IMPLEMENTATION -> "In Implementation"
        MONITORING -> "Monitoring"
        PAUSED -> "Paused"
        CLOSED -> "Closed"
        ARCHIVED -> "Archived"
    }

    fun isActionable() = this in setOf(READY_FOR_APPROVAL, APPROVED, IN_IMPLEMENTATION, MONITORING, PAUSED)
    fun isTerminal() = this in setOf(CLOSED, ARCHIVED)
    fun requiresExplicitApproval() = this in setOf(READY_FOR_APPROVAL, APPROVED)
    fun isApprovedOrBeyond() = this in setOf(APPROVED, IN_IMPLEMENTATION, MONITORING, PAUSED, CLOSED, ARCHIVED)
    fun isImplementationPhase() = this in setOf(IN_IMPLEMENTATION, MONITORING, PAUSED)

    /** UI workflow stage this lifecycle stage belongs to */
    fun toWorkflowStage(): WorkflowStage = when (this) {
        DRAFT -> WorkflowStage.FRAME
        MODELLED -> WorkflowStage.MODEL
        CHALLENGED -> WorkflowStage.CHALLENGE
        HOLD -> WorkflowStage.SIMULATE                    // on hold — still in simulate
        ANALYSED -> WorkflowStage.DECIDE                  // analysis complete — decide is current
        READY_FOR_APPROVAL, APPROVED -> WorkflowStage.DECIDE
        IN_IMPLEMENTATION, MONITORING, PAUSED -> WorkflowStage.LEARN
        CLOSED, ARCHIVED -> WorkflowStage.LEARN
    }
}

/** Six-stage UI workflow — maps to ranges of LifecycleStage */
enum class WorkflowStage(val label: String, val shortLabel: String) {
    FRAME("Frame", "Frame"),
    MODEL("Model", "Model"),
    CHALLENGE("Challenge", "Challenge"),
    SIMULATE("Simulate", "Simulate"),
    DECIDE("Decide", "Decide"),
    LEARN("Learn", "Learn");

    fun cta(): String = when (this) {
        FRAME -> "Build decision model"
        MODEL -> "Review model validity"
        CHALLENGE -> "Run simulation"
        SIMULATE -> "Review decision report"
        DECIDE -> "Create implementation plan"
        LEARN -> "Review outcomes"
    }

    fun nextLifecycleStage(): LifecycleStage = when (this) {
        FRAME -> LifecycleStage.MODELLED
        MODEL -> LifecycleStage.CHALLENGED
        CHALLENGE -> LifecycleStage.ANALYSED
        SIMULATE -> LifecycleStage.READY_FOR_APPROVAL
        DECIDE -> LifecycleStage.IN_IMPLEMENTATION
        LEARN -> LifecycleStage.CLOSED
    }
}

data class TransitionResult(
    val allowed: Boolean,
    val blockers: List<String> = emptyList(),
    val requiredActions: List<String> = emptyList(),
    val nextLifecycleStage: LifecycleStage? = null
)

// ── Policy Option ─────────────────────────────────────────────────────────────

@Serializable
data class PolicyOption(
    val id: String = UUID.randomUUID().toString(),
    val actionId: String,
    val actionLabel: String,
    val rationale: String = "",
    val selectedAt: Long = System.currentTimeMillis(),
    val selectedBy: String = "",
    val fallbackActionId: String = "",
    val fallbackActionLabel: String = "",
    val selectedSnapshotId: String = "",
    val riskControls: List<String> = emptyList(),
    val stopCondition: String = "",
    val reversalPlan: String = ""
)

// ── Approval ──────────────────────────────────────────────────────────────────

@Serializable
enum class ApprovalStatus { PENDING, APPROVED, REJECTED, ABSTAINED, EXPIRED }

@Serializable
data class ApprovalDecision(
    val id: String = UUID.randomUUID().toString(),
    val stageId: String,
    val approverId: String,
    val approverName: String = "",
    val decision: ApprovalStatus,
    val rationale: String = "",
    val evidence: String = "",
    val signatureRef: String = "",
    val recordedAt: Long = System.currentTimeMillis(),
    val expiryDate: Long = 0L
)

@Serializable
data class ApprovalStage(
    val stageId: String = UUID.randomUUID().toString(),
    val name: String,
    val requiredStakeholderIds: List<String> = emptyList(),
    val threshold: Threshold = Threshold.SIMPLE_MAJORITY,
    val status: ApprovalStatus = ApprovalStatus.PENDING,
    val decisions: List<ApprovalDecision> = emptyList(),
    val decision: String = "",
    val rationale: String = "",
    val evidence: String = "",
    val recordedAt: Long = 0L,
    val expiryDate: Long = 0L,
    val invalidatedAt: Long = 0L
) {
    @Serializable
    enum class Threshold {
        SINGLE_EXECUTIVE, SIMPLE_MAJORITY, SUPERMAJORITY, CONSENSUS, FORMAL_VETO, REGULATOR_APPROVAL
    }

    fun effectiveStatus(now: Long = System.currentTimeMillis()): ApprovalStatus {
        if (decisions.isEmpty()) return status
        val active = decisions.filter { it.expiryDate == 0L || now <= it.expiryDate }
        val required = requiredStakeholderIds.size.coerceAtLeast(1)
        val approvals = active.count { it.decision == ApprovalStatus.APPROVED }
        val rejections = active.count { it.decision == ApprovalStatus.REJECTED }
        return when (threshold) {
            Threshold.SINGLE_EXECUTIVE -> when {
                approvals >= 1 -> ApprovalStatus.APPROVED
                rejections >= 1 -> ApprovalStatus.REJECTED
                else -> ApprovalStatus.PENDING
            }
            Threshold.SIMPLE_MAJORITY -> when {
                approvals * 2 > required -> ApprovalStatus.APPROVED
                rejections * 2 >= required -> ApprovalStatus.REJECTED
                else -> ApprovalStatus.PENDING
            }
            Threshold.SUPERMAJORITY -> when {
                approvals * 3 >= required * 2 -> ApprovalStatus.APPROVED
                rejections * 3 > required -> ApprovalStatus.REJECTED
                else -> ApprovalStatus.PENDING
            }
            Threshold.CONSENSUS -> when {
                approvals >= required && rejections == 0 -> ApprovalStatus.APPROVED
                rejections >= 1 -> ApprovalStatus.REJECTED
                else -> ApprovalStatus.PENDING
            }
            Threshold.FORMAL_VETO -> when {
                rejections >= 1 -> ApprovalStatus.REJECTED
                approvals >= 1 -> ApprovalStatus.APPROVED
                else -> ApprovalStatus.PENDING
            }
            Threshold.REGULATOR_APPROVAL -> when {
                approvals >= 1 -> ApprovalStatus.APPROVED
                rejections >= 1 -> ApprovalStatus.REJECTED
                else -> ApprovalStatus.PENDING
            }
        }
    }

    fun isValid(now: Long = System.currentTimeMillis()): Boolean {
        val effective = effectiveStatus(now)
        if (effective != ApprovalStatus.APPROVED && status != ApprovalStatus.APPROVED) return false
        if (invalidatedAt > 0L) return false
        if (expiryDate > 0L && now > expiryDate) return false
        return true
    }

    fun statusLabel(): String = when {
        invalidatedAt > 0L -> "Invalidated"
        expiryDate > 0L && System.currentTimeMillis() > expiryDate -> "Expired"
        decisions.isNotEmpty() -> effectiveStatus().name.lowercase().replaceFirstChar { it.uppercase() }
        else -> status.name.lowercase().replaceFirstChar { it.uppercase() }
    }
}

// ── Implementation & Monitoring ───────────────────────────────────────────────

@Serializable
data class Milestone(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val targetDate: Long = 0L,
    val completed: Boolean = false,
    val completedAt: Long = 0L
)

@Serializable
enum class MonitoringSignal { ON_TRACK, WATCH, ALERT, STOP_TRIGGERED }

@Serializable
data class MonitoringEntry(
    val id: String = UUID.randomUUID().toString(),
    val date: Long = System.currentTimeMillis(),
    val milestoneId: String = "",
    val observedOutcome: String = "",
    val expectedOutcome: String = "",
    val stakeholderResponse: String = "",
    val costVariance: Double = 0.0,   // positive = over budget
    val timeVarianceDays: Int = 0,    // positive = delayed
    val signalStatus: MonitoringSignal = MonitoringSignal.ON_TRACK,
    val evidenceNote: String = "",
    val ownerNote: String = "",
    val correctiveAction: String = ""
)

@Serializable
data class ImplementationPlan(
    val owner: String = "",
    val accountableApprover: String = "",
    val selectedActionId: String = "",
    val fallbackActionId: String = "",
    val milestones: List<Milestone> = emptyList(),
    val budget: Double = 0.0,
    val estimatedDurationDays: Int = 0,
    val dependencies: List<String> = emptyList(),
    val preconditions: List<String> = emptyList(),
    val communicationPlan: String = "",
    val stakeholderEngagementTasks: List<String> = emptyList(),
    val riskControls: List<String> = emptyList(),
    val reviewCadenceDays: Int = 30,
    val earlyWarningIndicators: List<String> = emptyList(),
    val stopCondition: String = "",
    val fallbackAction: String = "",
    val reversalPlan: String = "",
    val monitoringEntries: List<MonitoringEntry> = emptyList()
)

// ── Audit Trail ───────────────────────────────────────────────────────────────

@Serializable
data class ChangeRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val changedBy: String = "user",
    val summary: String,
    val previousVersion: Int,
    val previousLifecycleStage: LifecycleStage = LifecycleStage.DRAFT,
    val fieldChanged: String = "",
    val oldValue: String = "",
    val newValue: String = "",
    val reason: String = "",
    val invalidatedApproval: Boolean = false,
    val invalidatedSnapshot: Boolean = false
)

// ── Lifecycle Transition Validator ────────────────────────────────────────────

object LifecycleTransition {

    private val hardConstraintKinds = setOf(
        Constraint.Kind.SAFETY, Constraint.Kind.LEGAL, Constraint.Kind.ETHICAL, Constraint.Kind.USER_HARD
    )

    fun canTransition(scenario: Scenario, to: LifecycleStage): TransitionResult {
        val from = scenario.lifecycleStage
        val blockers = mutableListOf<String>()

        if (from == to) {
            return TransitionResult(false, listOf("Already in ${to.displayLabel()}"))
        }

        when (to) {
            LifecycleStage.MODELLED -> {
                val allowed = from in setOf(LifecycleStage.DRAFT, LifecycleStage.HOLD, LifecycleStage.ANALYSED, LifecycleStage.CHALLENGED)
                if (!allowed) blockers += "Can only enter Model from Draft, Hold, or Analysed (current: ${from.displayLabel()})"
                if (scenario.title.isBlank()) blockers += "Decision title required"
                if (scenario.decisionOwner.isBlank()) blockers += "Decision owner required"
                if (scenario.stakeholders.size < 2) blockers += "At least 2 stakeholders required"
            }
            LifecycleStage.CHALLENGED -> {
                if (from !in setOf(LifecycleStage.MODELLED, LifecycleStage.HOLD))
                    blockers += "Must be in Model before Challenge (current: ${from.displayLabel()})"
                if (scenario.actions.isEmpty()) blockers += "At least one action required"
                val actionsWithoutOwner = scenario.actions.filter { it.stakeholderId.isBlank() }
                if (actionsWithoutOwner.isNotEmpty())
                    blockers += "${actionsWithoutOwner.size} action(s) missing an owner"
                if (scenario.constraints.isEmpty()) blockers += "At least one constraint must be logged"
                if (scenario.payoffs.isEmpty()) blockers += "Payoff structure required"
            }
            LifecycleStage.ANALYSED -> {
                val allowed = from in setOf(LifecycleStage.CHALLENGED, LifecycleStage.MODELLED, LifecycleStage.HOLD)
                if (!allowed) blockers += "Must be in Challenge or Hold before Analyse (current: ${from.displayLabel()})"
                // Analysis is triggered by the analyse action — evidence/constraint checks done by AnalysisEligibilityGate
            }
            LifecycleStage.READY_FOR_APPROVAL -> {
                if (from !in setOf(LifecycleStage.ANALYSED, LifecycleStage.HOLD))
                    blockers += "Must be Analysed or Hold before Ready for Approval (current: ${from.displayLabel()})"
                val unassessedHard = scenario.constraints
                    .filter { it.kind in hardConstraintKinds }
                    .filter { c -> scenario.constraintAssessments.none { a -> a.constraintId == c.id } }
                if (unassessedHard.isNotEmpty())
                    blockers += "Unresolved hard constraints: ${unassessedHard.joinToString(", ") { it.label }}"
                if (scenario.decisionOwner.isBlank())
                    blockers += "Decision owner required for approval routing"
                if (scenario.selectedPolicyOption == null)
                    blockers += "Policy option must be selected before requesting approval"
            }
            LifecycleStage.APPROVED -> {
                if (from != LifecycleStage.READY_FOR_APPROVAL)
                    blockers += "Must be Ready for Approval before Approved (current: ${from.displayLabel()})"
                if (scenario.approvalStages.isEmpty())
                    blockers += "No approval stages defined — approval cannot be inferred from authority score"
                val invalid = scenario.approvalStages.filter { !it.isValid() }
                if (invalid.isNotEmpty())
                    blockers += "Incomplete or invalidated stages: ${invalid.joinToString(", ") { it.name }}"
            }
            LifecycleStage.IN_IMPLEMENTATION -> {
                if (from != LifecycleStage.APPROVED)
                    blockers += "Must be Approved before In Implementation (current: ${from.displayLabel()})"
                if (scenario.implementationPlan == null)
                    blockers += "Implementation plan required before implementation begins"
                scenario.implementationPlan?.let { plan ->
                    if (plan.owner.isBlank()) blockers += "Implementation plan owner required"
                    if (plan.stopCondition.isBlank()) blockers += "Stop condition required in implementation plan"
                }
            }
            LifecycleStage.MONITORING -> {
                if (from != LifecycleStage.IN_IMPLEMENTATION)
                    blockers += "Must be In Implementation before Monitoring (current: ${from.displayLabel()})"
                if (scenario.implementationPlan?.earlyWarningIndicators.isNullOrEmpty())
                    blockers += "Early-warning indicators required before monitoring"
            }
            LifecycleStage.CLOSED -> {
                val allowedFrom = setOf(
                    LifecycleStage.MONITORING, LifecycleStage.IN_IMPLEMENTATION,
                    LifecycleStage.APPROVED, LifecycleStage.HOLD
                )
                if (from !in allowedFrom)
                    blockers += "Cannot close from ${from.displayLabel()}"
            }
            LifecycleStage.ARCHIVED -> {
                val allowedFrom = setOf(
                    LifecycleStage.CLOSED, LifecycleStage.DRAFT,
                    LifecycleStage.HOLD, LifecycleStage.CHALLENGED
                )
                if (from !in allowedFrom)
                    blockers += "Cannot archive from ${from.displayLabel()}"
            }
            LifecycleStage.PAUSED -> {
                val allowedFrom = setOf(
                    LifecycleStage.IN_IMPLEMENTATION, LifecycleStage.MONITORING
                )
                if (from !in allowedFrom)
                    blockers += "Can only pause from In Implementation or Monitoring (current: ${from.displayLabel()})"
            }
            LifecycleStage.HOLD -> {
                if (from.isTerminal())
                    blockers += "Cannot hold a terminal decision — it is ${from.displayLabel()}"
                if (from == LifecycleStage.PAUSED)
                    blockers += "Paused scenario must resume or close — use Resume or Close"
            }
            LifecycleStage.DRAFT -> {
                blockers += "Cannot revert to Draft — create a new scenario instead"
            }
        }

        val next = when {
            blockers.isEmpty() -> to
            else -> null
        }
        return TransitionResult(blockers.isEmpty(), blockers, nextLifecycleStage = next)
    }
}

// ── Capability Functions ──────────────────────────────────────────────────────

data class CapabilityResult(
    val allowed: Boolean,
    val blockers: List<String> = emptyList(),
    val requiredActions: List<String> = emptyList(),
    val nextLifecycleStage: LifecycleStage? = null
)

object ScenarioCapabilities {

    private val hardKinds = setOf(
        Constraint.Kind.SAFETY, Constraint.Kind.LEGAL,
        Constraint.Kind.ETHICAL, Constraint.Kind.USER_HARD
    )

    fun canAnalyse(scenario: Scenario): CapabilityResult {
        val blockers = mutableListOf<String>()
        val required = mutableListOf<String>()
        if (scenario.lifecycleStage !in setOf(
                LifecycleStage.CHALLENGED, LifecycleStage.MODELLED, LifecycleStage.HOLD
            )) blockers += "Must be in Challenge, Model, or Hold to analyse (current: ${scenario.lifecycleStage.displayLabel()})"
        if (scenario.actions.isEmpty()) { blockers += "No actions defined"; required += "Add at least one action" }
        if (scenario.payoffs.isEmpty()) { blockers += "No payoff structure defined"; required += "Add payoff dimensions" }
        return CapabilityResult(blockers.isEmpty(), blockers, required, LifecycleStage.ANALYSED)
    }

    fun canSubmitForApproval(scenario: Scenario): CapabilityResult {
        val blockers = mutableListOf<String>()
        val required = mutableListOf<String>()
        if (scenario.lifecycleStage !in setOf(LifecycleStage.ANALYSED, LifecycleStage.HOLD))
            blockers += "Must be Analysed to submit for approval (current: ${scenario.lifecycleStage.displayLabel()})"
        if (scenario.selectedPolicyOption == null) { blockers += "No policy option selected"; required += "Select a policy option in Report tab" }
        if (scenario.decisionOwner.isBlank()) { blockers += "Decision owner required"; required += "Set decision owner" }
        val unassessedHard = scenario.constraints.filter { it.kind in hardKinds }
            .filter { c -> scenario.constraintAssessments.none { a -> a.constraintId == c.id } }
        if (unassessedHard.isNotEmpty()) { blockers += "${unassessedHard.size} hard constraint(s) unassessed"; required += "Assess all hard constraints" }
        return CapabilityResult(blockers.isEmpty(), blockers, required, LifecycleStage.READY_FOR_APPROVAL)
    }

    fun canApprove(scenario: Scenario): CapabilityResult {
        val blockers = mutableListOf<String>()
        val required = mutableListOf<String>()
        if (scenario.lifecycleStage != LifecycleStage.READY_FOR_APPROVAL)
            blockers += "Must be Ready for Approval (current: ${scenario.lifecycleStage.displayLabel()})"
        if (scenario.approvalStages.isEmpty()) { blockers += "No approval stages defined"; required += "Define approval stages" }
        val invalid = scenario.approvalStages.filter { !it.isValid() }
        if (invalid.isNotEmpty()) { required += "Complete ${invalid.size} pending approval stage(s)" }
        return CapabilityResult(blockers.isEmpty() && invalid.isEmpty(), blockers + invalid.map { "Stage '${it.name}' not yet approved" }, required, LifecycleStage.APPROVED)
    }

    fun canStartImplementation(scenario: Scenario): CapabilityResult {
        val blockers = mutableListOf<String>()
        val required = mutableListOf<String>()
        if (scenario.lifecycleStage != LifecycleStage.APPROVED)
            blockers += "Must be Approved before starting implementation (current: ${scenario.lifecycleStage.displayLabel()})"
        val plan = scenario.implementationPlan
        if (plan == null) { blockers += "No implementation plan"; required += "Create an implementation plan" }
        else {
            if (plan.owner.isBlank()) { blockers += "Plan owner required"; required += "Assign plan owner" }
            if (plan.stopCondition.isBlank()) { blockers += "Stop condition required"; required += "Define stop condition" }
        }
        return CapabilityResult(blockers.isEmpty(), blockers, required, LifecycleStage.IN_IMPLEMENTATION)
    }

    fun canEnterMonitoring(scenario: Scenario): CapabilityResult {
        val blockers = mutableListOf<String>()
        val required = mutableListOf<String>()
        if (scenario.lifecycleStage != LifecycleStage.IN_IMPLEMENTATION)
            blockers += "Must be In Implementation to enter Monitoring (current: ${scenario.lifecycleStage.displayLabel()})"
        if (scenario.implementationPlan?.earlyWarningIndicators.isNullOrEmpty()) {
            blockers += "No early-warning indicators defined"
            required += "Add early-warning indicators to plan"
        }
        return CapabilityResult(blockers.isEmpty(), blockers, required, LifecycleStage.MONITORING)
    }

    fun canPause(scenario: Scenario): CapabilityResult {
        val blockers = mutableListOf<String>()
        if (!scenario.lifecycleStage.isImplementationPhase())
            blockers += "Can only pause during implementation or monitoring (current: ${scenario.lifecycleStage.displayLabel()})"
        return CapabilityResult(blockers.isEmpty(), blockers, nextLifecycleStage = LifecycleStage.PAUSED)
    }

    fun canResume(scenario: Scenario): CapabilityResult {
        val blockers = mutableListOf<String>()
        val required = mutableListOf<String>()
        if (scenario.lifecycleStage != LifecycleStage.PAUSED)
            blockers += "Can only resume from Paused (current: ${scenario.lifecycleStage.displayLabel()})"
        required += "Review stop-triggered signal and confirm it is safe to resume"
        return CapabilityResult(blockers.isEmpty(), blockers, required, LifecycleStage.IN_IMPLEMENTATION)
    }

    fun canClose(scenario: Scenario): CapabilityResult {
        val allowed = setOf(
            LifecycleStage.MONITORING, LifecycleStage.IN_IMPLEMENTATION,
            LifecycleStage.APPROVED, LifecycleStage.HOLD, LifecycleStage.PAUSED,
            LifecycleStage.ANALYSED, LifecycleStage.DRAFT
        )
        val blockers = mutableListOf<String>()
        if (scenario.lifecycleStage !in allowed)
            blockers += "Cannot close from ${scenario.lifecycleStage.displayLabel()}"
        return CapabilityResult(blockers.isEmpty(), blockers, nextLifecycleStage = LifecycleStage.CLOSED)
    }
}
