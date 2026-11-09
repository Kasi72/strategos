package com.drkkr.strategos.domain.flywheel

import com.drkkr.strategos.domain.agent.DeceptionDetector

/**
 * Retro-deception finding attached to a single OutcomeRecord.
 * Produced by RetroDeceptionAuditor when the record's observedOpponentActionIndex is set.
 */
data class DeceptionAnnotation(
    val scenarioId: String,
    val kind: DeceptionDetector.DeceptionKind,
    val equilibriumActionIndex: Int,
    val observedActionIndex: Int,
    val explanation: String
)
