package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.SelfModelEstimator
import com.drkkr.strategos.domain.flywheel.OutcomeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfModelEstimatorTest {

    private fun record(recommended: String, actual: String, rating: Int) =
        OutcomeRecord(
            scenarioId = "s1",
            recommendedMove = recommended,
            actualMove = actual,
            outcomeRating = rating
        )

    @Test
    fun unknown_whenFewerThan3Records() {
        val result = SelfModelEstimator.estimate(emptyList())
        assertEquals("unknown", result.inferredStrategyLabel)
        assertEquals(0.0, result.confidence, 0.001)
    }

    @Test
    fun unknown_exactly2Records() {
        val history = listOf(record("A", "A", 4), record("A", "A", 5))
        assertEquals("unknown", SelfModelEstimator.estimate(history).inferredStrategyLabel)
    }

    @Test
    fun cooperative_whenAlwaysFollows() {
        val history = (1..5).map { record("cooperate", "cooperate", 4) }
        val result = SelfModelEstimator.estimate(history)
        assertEquals("cooperative", result.inferredStrategyLabel)
        assertTrue(result.confidence >= 0.70)
    }

    @Test
    fun aggressive_whenFrequentDeviationWithHighRating() {
        // Deviates every time, opponent reacts positively
        val history = (1..5).map { record("cooperate", "assert", 5) }
        val result = SelfModelEstimator.estimate(history)
        assertEquals("aggressive", result.inferredStrategyLabel)
    }

    @Test
    fun titForTat_whenMirroringOpponent() {
        // Each record's rating drives the next record's action:
        // rating ≥ 4 (receptive)   → next turn user follows
        // rating < 4 (unreceptive) → next turn user deviates
        // All 7 transitions mirror → titForTatScore = 7/7 = 1.0 ≥ 0.70
        val history = listOf(
            record("A", "A", 5),  // seed: receptive
            record("A", "A", 2),  // follows (mirrors receptive prev) ✓; rating=2 (unreceptive)
            record("A", "B", 2),  // deviates (mirrors unreceptive prev) ✓; rating=2
            record("A", "B", 4),  // deviates (mirrors unreceptive prev) ✓; rating=4
            record("A", "A", 5),  // follows (mirrors receptive prev) ✓; rating=5
            record("A", "A", 1),  // follows (mirrors receptive prev) ✓; rating=1
            record("A", "B", 1),  // deviates (mirrors unreceptive prev) ✓; rating=1
            record("A", "B", 3)   // deviates (mirrors unreceptive prev) ✓
        )
        val result = SelfModelEstimator.estimate(history)
        assertEquals("tit-for-tat", result.inferredStrategyLabel)
    }

    @Test
    fun unpredictable_whenNoPattern() {
        // Alternating with mixed ratings — no score dominates
        val history = listOf(
            record("A", "A", 3),
            record("A", "B", 3),
            record("A", "A", 3),
            record("A", "B", 2),
            record("A", "A", 3)
        )
        val result = SelfModelEstimator.estimate(history)
        assertEquals("unpredictable", result.inferredStrategyLabel)
    }

    @Test
    fun confidence_boundedIn0to1() {
        val history = (1..10).map { record("X", "X", 5) }
        val result = SelfModelEstimator.estimate(history)
        assertTrue(result.confidence in 0.0..1.0)
    }
}
