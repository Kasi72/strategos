package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.flywheel.DeceptionAnnotation
import com.drkkr.strategos.domain.flywheel.OutcomeRecord
import com.drkkr.strategos.domain.flywheel.PatternAnalysis
import com.drkkr.strategos.domain.flywheel.PatternAnalyzer
import com.drkkr.strategos.domain.flywheel.RetroDeceptionAuditor
import com.drkkr.strategos.domain.game.GameIR
import com.drkkr.strategos.domain.game.OpponentModel
import com.drkkr.strategos.domain.model.PayoffDimension

/**
 * Phase 5a — Calibration Flywheel.
 * Pure domain: no android.* imports. Runs when an arc reaches OUTCOME_KNOWN and
 * updates the opponent's rationality profile from the recorded outcomes.
 */
object RetrospectiveEngine {

    data class Calibration(
        val updatedModel: OpponentModel,
        val sampleSize: Int,
        val summary: String,
        val deceptionAnnotations: List<DeceptionAnnotation> = emptyList(),
        val patternAnalysis: PatternAnalysis? = null
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
    fun run(model: OpponentModel, outcomes: List<OutcomeRecord>, ir: GameIR? = null): Calibration? {
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

        val annotations = if (ir != null) RetroDeceptionAuditor.audit(ir, updated, outcomes) else emptyList()
        val patternAnalysis = if (outcomes.size >= 10) PatternAnalyzer.analyze(outcomes) else null

        return Calibration(updated, n, summary, annotations, patternAnalysis)
    }

    /**
     * Inverse utility inference: gradient descent over observed opponent actions and outcome
     * ratings to estimate which [PayoffDimension]s the opponent weights most.
     *
     * Signal model:
     *  - `observedOpponentActionIndex == 0` (first / "cooperative") + high rating → opponent
     *    weights RELATIONSHIPS, WELLBEING, ETHICS.
     *  - `observedOpponentActionIndex > 0` (non-cooperative) + low rating → opponent weights
     *    COST, EFFICIENCY, REPUTATION.
     *  - Gradient is applied proportionally to deviation from a uniform prior.
     *
     * Returns null when fewer than 3 outcomes have a known opponent action index.
     */
    fun inferOpponentUtility(outcomes: List<OutcomeRecord>): Map<PayoffDimension, Double>? {
        val usable = outcomes.filter { it.observedOpponentActionIndex != null }
        if (usable.size < 3) return null

        // Start from a uniform prior over all dimensions.
        val weights = PayoffDimension.entries.associateWith { 1.0 / PayoffDimension.entries.size }.toMutableMap()

        val learningRate = 0.15
        for (obs in usable) {
            val actionIdx = obs.observedOpponentActionIndex!!
            // Cooperation signal: index 0 = most cooperative; rating 1-5 normalised to -1..+1.
            val cooperationSignal = if (actionIdx == 0) 1.0 else -1.0
            val satisfactionSignal = (obs.outcomeRating - 3.0) / 2.0  // -1..+1

            // Gradient: cooperative + satisfied → push relational dims up; non-coop + dissatisfied → push cost dims up.
            val gradient = cooperationSignal * satisfactionSignal
            for (dim in PayoffDimension.entries) {
                val isRelational = dim in setOf(PayoffDimension.RELATIONSHIPS, PayoffDimension.WELLBEING,
                    PayoffDimension.ETHICS, PayoffDimension.REPUTATION, PayoffDimension.LONG_TERM_VALUE)
                val signal = if (isRelational) gradient else -gradient
                weights[dim] = (weights.getValue(dim) + learningRate * signal).coerceAtLeast(0.0)
            }
        }

        // Normalise so weights sum to 1.
        val total = weights.values.sum().takeIf { it > 0.0 } ?: return null
        return weights.mapValues { (_, v) -> v / total }
    }
}
