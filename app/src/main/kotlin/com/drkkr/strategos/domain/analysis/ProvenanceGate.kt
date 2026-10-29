package com.drkkr.strategos.domain.analysis

import com.drkkr.strategos.domain.framework.ExecutiveStatus
import com.drkkr.strategos.domain.framework.ExecutiveStatusReason
import com.drkkr.strategos.domain.recommendation.DecisionReport
import kotlin.math.roundToInt

/** Can only lower an executive status, never raise it. Only PROCEED and CONDITIONAL_TRIAL are ever touched. */
object ProvenanceGate {
    enum class Outcome { NONE, CAPPED_TO_CONDITIONAL, CAPPED_TO_EXPLORATORY }

    fun apply(report: DecisionReport, stats: ProvenanceStats): Pair<DecisionReport, Outcome> {
        val share = stats.aiPayoffShare
        val current = report.executiveStatus
        if (current != ExecutiveStatus.PROCEED && current != ExecutiveStatus.CONDITIONAL_TRIAL) return report to Outcome.NONE

        val (target, outcome) = when {
            share >= 0.5 -> ExecutiveStatus.EXPLORATORY_ONLY to Outcome.CAPPED_TO_EXPLORATORY
            share > 0.0 && current == ExecutiveStatus.PROCEED -> ExecutiveStatus.CONDITIONAL_TRIAL to Outcome.CAPPED_TO_CONDITIONAL
            else -> return report to Outcome.NONE
        }

        val pct = (share * 100).roundToInt()
        val reason = report.executiveStatusReason ?: ExecutiveStatusReason(status = current)
        val capped = reason.copy(
            status = target,
            blockingReasons = reason.blockingReasons +
                "$pct% of payoffs are AI-inferred and unreviewed. Review them in Model → Review AI inputs.",
            requiredActions = reason.requiredActions + "Review AI-inferred payoffs"
        )
        return report.copy(executiveStatus = target, executiveStatusReason = capped) to outcome
    }
}
