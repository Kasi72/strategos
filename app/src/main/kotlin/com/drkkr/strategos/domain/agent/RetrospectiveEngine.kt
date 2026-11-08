package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.flywheel.OutcomeRecord
import com.drkkr.strategos.domain.game.OpponentModel

/**
 * Phase 5a — Calibration Flywheel.
 * Pure domain: no android.* imports. Runs when an arc reaches OUTCOME_KNOWN and
 * updates the opponent's rationality profile from the recorded outcomes.
 */
object RetrospectiveEngine {

    data class Calibration(
        val updatedModel: OpponentModel,
        val sampleSize: Int,
        val summary: String
    )

    /**
     * Derives a calibration delta from [outcomes] and merges it into [model].
     * Returns null when [outcomes] is empty (nothing to learn from).
     *
     * Update rules:
     *  - epsilon: blended toward (1 - follow-through rate) with weight 0.3.
     *    Following recommendations correlates with low randomness/irrationality.
     *  - cooperationIndex: blended toward normalised average rating with weight 0.3.
     *    High outcome ratings signal cooperative or at least accommodating counterpart behaviour.
     *  - discountFactor: unchanged — requires multi-round observation outside this engine's scope.
     */
    fun run(model: OpponentModel, outcomes: List<OutcomeRecord>): Calibration? {
        if (outcomes.isEmpty()) return null

        val n = outcomes.size
        val followRate = outcomes.count { it.followed }.toDouble() / n
        // High follow-through = user trusts engine = epsilon stays low
        val newEpsilon = (model.epsilon * 0.7 + (1.0 - followRate) * 0.3).coerceIn(0.0, 1.0)

        val avgRating = outcomes.map { it.outcomeRating }.average()
        val cooperationSignal = (avgRating - 1.0) / 4.0   // normalise 1..5 → 0..1
        val prevCoop = model.cooperationIndex ?: cooperationSignal
        val newCoop = (prevCoop * 0.7 + cooperationSignal * 0.3).coerceIn(0.0, 1.0)

        val updated = model.copy(epsilon = newEpsilon, cooperationIndex = newCoop)

        val summary = buildString {
            append("Calibration ($n outcome${if (n == 1) "" else "s"}): ")
            append("follow-through ${(followRate * 100).toInt()}%, ")
            append("avg rating ${"%.1f".format(avgRating)}/5. ")
            append("epsilon ${"%.2f".format(newEpsilon)} (was ${"%.2f".format(model.epsilon)}), ")
            append("cooperation ${"%.2f".format(newCoop)}.")
        }

        return Calibration(updated, n, summary)
    }
}
