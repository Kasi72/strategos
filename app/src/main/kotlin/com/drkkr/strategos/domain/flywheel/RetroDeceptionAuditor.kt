package com.drkkr.strategos.domain.flywheel

import com.drkkr.strategos.domain.agent.DeceptionDetector
import com.drkkr.strategos.domain.game.GameIR
import com.drkkr.strategos.domain.game.OpponentModel

/**
 * Retrospective deception audit: runs DeceptionDetector against each OutcomeRecord that
 * has an observedOpponentActionIndex set, using the final GameIR for the scenario.
 * Records where the index is null are silently skipped.
 *
 * Pure domain — no android.* or androidx.* imports.
 */
object RetroDeceptionAuditor {

    fun audit(
        ir: GameIR,
        opponentModel: OpponentModel,
        outcomes: List<OutcomeRecord>
    ): List<DeceptionAnnotation> = outcomes.mapNotNull { record ->
        val idx = record.observedOpponentActionIndex ?: return@mapNotNull null
        val signal = DeceptionDetector.detect(ir, opponentModel, idx) ?: return@mapNotNull null
        DeceptionAnnotation(
            scenarioId = record.scenarioId,
            kind = signal.kind,
            equilibriumActionIndex = signal.equilibriumActionIndex,
            observedActionIndex = signal.observedActionIndex,
            explanation = signal.explanation
        )
    }
}
