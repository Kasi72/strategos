package com.drkkr.strategos.domain.framework

import com.drkkr.strategos.domain.model.NavigationTarget

enum class AnalysisRunStatus {
    NOT_RUN,
    PARTIAL,       // all required modules succeeded; some optional failed
    COMPLETE,      // all selected modules succeeded
    FAILED         // at least one required module failed
}

enum class BlockerSeverity { CRITICAL, HIGH, MEDIUM, LOW }

data class BlockerAction(
    val severity: BlockerSeverity,
    val title: String,
    val impact: String,
    val currentState: String,
    val fixLabel: String,
    val target: NavigationTarget,
    val technicalDetail: String? = null
)

/**
 * Canonical single source of truth for decision readiness.
 * Passed to Analysis, Report, Workflow, and Implementation screens.
 * Never independently reconstructed per-screen.
 */
data class DecisionReadinessResult(
    val primaryStatus: ExecutiveStatus,
    val summary: String,
    val blockers: List<BlockerAction>,         // deduplicated, priority-ordered
    val requiredActions: List<String>,          // deduplicated
    val policyEligible: Boolean,
    val snapshotStatus: SnapshotStatus,
    val lifecycleStatus: String,
    val analysisRunStatus: AnalysisRunStatus,
    val structuralCompleteness: Int,           // 0–100
    val evidenceQuality: Int,                  // 0–100
    val moduleRunResults: List<ModuleRunResult>
) {
    enum class SnapshotStatus { VALID, STALE, MISSING }

    fun isReadyForPolicy(): Boolean =
        policyEligible &&
        snapshotStatus == SnapshotStatus.VALID &&
        analysisRunStatus != AnalysisRunStatus.FAILED &&
        analysisRunStatus != AnalysisRunStatus.NOT_RUN

    companion object {
        /** Priority order: safety violation > hard constraint > stale snapshot >
         *  missing policy > missing evidence > missing approvals > missing impl plan */
        fun priorityOf(b: BlockerAction): Int = when (b.severity) {
            BlockerSeverity.CRITICAL -> 0
            BlockerSeverity.HIGH     -> 1
            BlockerSeverity.MEDIUM   -> 2
            BlockerSeverity.LOW      -> 3
        }

        fun notRun(structuralCompleteness: Int, evidenceQuality: Int) = DecisionReadinessResult(
            primaryStatus = ExecutiveStatus.INDETERMINATE,
            summary = "Analysis not yet run.",
            blockers = emptyList(),
            requiredActions = listOf("Run analysis to assess decision readiness."),
            policyEligible = false,
            snapshotStatus = SnapshotStatus.MISSING,
            lifecycleStatus = "DRAFT",
            analysisRunStatus = AnalysisRunStatus.NOT_RUN,
            structuralCompleteness = structuralCompleteness,
            evidenceQuality = evidenceQuality,
            moduleRunResults = emptyList()
        )
    }
}
