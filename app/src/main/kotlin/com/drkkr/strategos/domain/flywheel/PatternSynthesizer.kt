package com.drkkr.strategos.domain.flywheel

object PatternSynthesizer {
    data class OpponentPattern(
        val opponentId: String,
        val dominantArchetype: String?,
        val avgRating: Double,
        val followRate: Double,
        val sampleSize: Int,
        val insight: String
    )

    fun synthesize(records: List<OutcomeRecord>, opponentId: String): OpponentPattern? {
        if (opponentId.isBlank()) return null
        val filtered = records.filter { it.opponentReaction != null || it.archetypeName != null }
        if (filtered.size < 2) return null
        val avgRating = filtered.map { it.outcomeRating }.average()
        val followRate = filtered.count { it.followed }.toDouble() / filtered.size
        val archCounts = filtered.mapNotNull { it.archetypeName }.groupingBy { it }.eachCount()
        val dominantArchetype = archCounts.maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.key
        val insight = buildInsight(opponentId, avgRating, followRate, dominantArchetype, filtered.size)
        return OpponentPattern(opponentId, dominantArchetype, avgRating, followRate, filtered.size, insight)
    }

    fun synthesizeAll(records: List<OutcomeRecord>): List<OpponentPattern> {
        val byOpponent = records.groupBy { it.opponentReaction?.take(40) ?: "unknown" }
        return byOpponent.mapNotNull { (opponentId, recs) -> synthesize(recs, opponentId) }
    }

    private fun buildInsight(opponentId: String, avgRating: Double, followRate: Double, archetype: String?, sampleSize: Int): String {
        val ratingNote = when {
            avgRating >= 4.0 -> "interactions tend to go well"
            avgRating <= 2.5 -> "outcomes have been poor"
            else -> "outcomes are mixed"
        }
        val followNote = when {
            followRate >= 0.7 -> "following engine advice has worked"
            followRate <= 0.3 -> "deviating from engine advice is common"
            else -> "advice adherence is inconsistent"
        }
        val archetypeNote = archetype?.let { " Dominant pattern: $it." } ?: ""
        return "Based on $sampleSize past interactions: $ratingNote, $followNote.$archetypeNote"
    }
}
