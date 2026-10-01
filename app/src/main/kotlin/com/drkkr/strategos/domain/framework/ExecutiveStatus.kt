package com.drkkr.strategos.domain.framework

enum class ExecutiveStatus {
    PROCEED,
    CONDITIONAL_TRIAL,
    HOLD_FOR_EVIDENCE,
    HOLD_FOR_VERIFICATION,
    EXPLORATORY_ONLY,
    INDETERMINATE,
    BLOCKED_CONSTRAINT;

    fun displayLabel(): String = when (this) {
        PROCEED -> "Proceed"
        CONDITIONAL_TRIAL -> "Conditional Trial"
        HOLD_FOR_EVIDENCE -> "Hold — Collect Evidence First"
        HOLD_FOR_VERIFICATION -> "Hold — Verify Constraints First"
        EXPLORATORY_ONLY -> "Exploratory Only"
        INDETERMINATE -> "Indeterminate — Options Not Distinguishable"
        BLOCKED_CONSTRAINT -> "Blocked — Constraint Violation"
    }

    fun requiresHold(): Boolean = this in setOf(
        HOLD_FOR_EVIDENCE, HOLD_FOR_VERIFICATION, EXPLORATORY_ONLY, INDETERMINATE, BLOCKED_CONSTRAINT
    )

    fun isApprovalSafe(): Boolean = this == PROCEED || this == CONDITIONAL_TRIAL
}
