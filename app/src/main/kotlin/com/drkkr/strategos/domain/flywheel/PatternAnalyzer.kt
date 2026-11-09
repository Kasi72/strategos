package com.drkkr.strategos.domain.flywheel

/**
 * Phase 5 — Cross-Scenario Learning.
 * Mines OutcomeRecord history for reusable heuristics:
 *   - win rates per archetype and per strategy (follow vs deviate)
 *   - Shapley delta: marginal value of following the engine's recommendation
 *   - timing correlation: whether outcomes trend up or down over time
 *
 * Pure domain — no android.* or androidx.* imports.
 */
object PatternAnalyzer {

    const val MIN_RECORDS = 10
    private const val MIN_GROUP_SIZE = 3

    fun analyze(outcomes: List<OutcomeRecord>): PatternAnalysis? {
        if (outcomes.size < MIN_RECORDS) return null
        val heuristics = mutableListOf<Heuristic>()

        // Win rates by game archetype
        outcomes.filter { it.archetypeName != null }
            .groupBy { it.archetypeName!! }
            .forEach { (archetype, records) ->
                if (records.size >= MIN_GROUP_SIZE) {
                    heuristics += Heuristic(
                        condition = "archetype=$archetype",
                        successRate = records.count { it.outcomeRating >= 4 }.toDouble() / records.size,
                        sampleSize = records.size
                    )
                }
            }

        // Win rates by strategy choice
        val followed = outcomes.filter { it.followed }
        val deviated = outcomes.filter { !it.followed }
        if (followed.size >= MIN_GROUP_SIZE) {
            heuristics += Heuristic(
                condition = "strategy=follow_recommendation",
                successRate = followed.count { it.outcomeRating >= 4 }.toDouble() / followed.size,
                sampleSize = followed.size
            )
        }
        if (deviated.size >= MIN_GROUP_SIZE) {
            heuristics += Heuristic(
                condition = "strategy=deviate_from_recommendation",
                successRate = deviated.count { it.outcomeRating >= 4 }.toDouble() / deviated.size,
                sampleSize = deviated.size
            )
        }

        // Shapley delta: marginal value of following vs deviating (0.5 = neutral)
        if (followed.size >= MIN_GROUP_SIZE && deviated.size >= MIN_GROUP_SIZE) {
            val followAvg = followed.map { it.outcomeRating }.average()
            val deviateAvg = deviated.map { it.outcomeRating }.average()
            val delta = (followAvg - deviateAvg) / 4.0  // -1..1
            heuristics += Heuristic(
                condition = "shapley_delta=follow_minus_deviate",
                successRate = ((delta + 1.0) / 2.0).coerceIn(0.0, 1.0),
                sampleSize = followed.size + deviated.size
            )
        }

        // Timing correlation: recent half vs early half (0.5 = no trend, >0.5 = improving)
        val sorted = outcomes.sortedBy { it.recordedAt }
        val half = sorted.size / 2
        val earlyAvg = sorted.take(half).map { it.outcomeRating }.average()
        val lateAvg = sorted.drop(half).map { it.outcomeRating }.average()
        heuristics += Heuristic(
            condition = "timing_trend=recent_vs_early",
            successRate = ((lateAvg - earlyAvg) / 4.0 / 2.0 + 0.5).coerceIn(0.0, 1.0),
            sampleSize = outcomes.size
        )

        return PatternAnalysis(heuristics)
    }
}
