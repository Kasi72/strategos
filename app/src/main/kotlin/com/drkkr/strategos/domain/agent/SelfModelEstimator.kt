package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.flywheel.OutcomeRecord

/**
 * Infers what strategy pattern the opponent has observed in the user, based on outcome history.
 *
 * Strategy labels:
 *   "tit-for-tat"    — user mirrors opponent: cooperative when opponent was receptive, assertive when not
 *   "cooperative"    — user consistently follows cooperative recommendations
 *   "aggressive"     — user consistently deviates toward assertive moves regardless of opponent's reaction
 *   "unpredictable"  — no consistent pattern across ≥4 records
 *   "unknown"        — insufficient history (< 3 records)
 */
object SelfModelEstimator {

    private const val MIN_RECORDS = 3
    private const val DOMINANCE_THRESHOLD = 0.70

    fun estimate(history: List<OutcomeRecord>): SelfModel {
        if (history.size < MIN_RECORDS) {
            return SelfModel(inferredStrategyLabel = "unknown", confidence = 0.0)
        }

        val n = history.size.toDouble()
        val followRate = history.count { it.followed }.toDouble() / n

        // Cooperative: user mostly followed recommendations
        val cooperativeScore = followRate

        // Aggressive: user mostly deviated AND opponent reactions were positive (high rating despite deviation)
        val deviatedWithHighRating = history.count { !it.followed && it.outcomeRating >= 4 }.toDouble() / n
        val aggressiveScore = (1.0 - followRate) * 0.6 + deviatedWithHighRating * 0.4

        // Tit-for-tat: user mirrors — followed when opponent was receptive, deviated when not
        val titForTatScore = computeTitForTatScore(history)

        // Unpredictable: low variance explanation
        val scores = mapOf(
            "cooperative" to cooperativeScore,
            "aggressive" to aggressiveScore,
            "tit-for-tat" to titForTatScore
        )
        val best = scores.maxByOrNull { it.value }!!
        return if (best.value >= DOMINANCE_THRESHOLD) {
            SelfModel(inferredStrategyLabel = best.key, confidence = best.value)
        } else {
            SelfModel(inferredStrategyLabel = "unpredictable", confidence = 1.0 - best.value)
        }
    }

    private fun computeTitForTatScore(history: List<OutcomeRecord>): Double {
        if (history.size < 2) return 0.0
        var mirrors = 0
        for (i in 1 until history.size) {
            val prev = history[i - 1]
            val curr = history[i]
            // opponent "receptive" = high previous rating; user mirrors by following next turn
            val prevReceptive = (prev.outcomeRating >= 4)
            val userMirrored = (prevReceptive && curr.followed) || (!prevReceptive && !curr.followed)
            if (userMirrored) mirrors++
        }
        return mirrors.toDouble() / (history.size - 1)
    }
}
