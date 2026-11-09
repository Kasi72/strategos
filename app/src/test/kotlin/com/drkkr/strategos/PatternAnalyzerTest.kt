package com.drkkr.strategos

import com.drkkr.strategos.domain.flywheel.OutcomeRecord
import com.drkkr.strategos.domain.flywheel.PatternAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternAnalyzerTest {

    private fun record(
        recommended: String,
        actual: String,
        rating: Int,
        archetype: String? = null,
        recordedAt: Long = System.currentTimeMillis()
    ) = OutcomeRecord(
        scenarioId = "s1",
        recommendedMove = recommended,
        actualMove = actual,
        outcomeRating = rating,
        archetypeName = archetype,
        recordedAt = recordedAt
    )

    private fun tenRecords(
        recommended: String = "A",
        actual: String = "A",
        rating: Int = 4,
        archetype: String? = null
    ) = (1..10).map { record(recommended, actual, rating, archetype, it.toLong()) }

    @Test
    fun returnsNull_fewerThan10Records() {
        val history = (1..9).map { record("A", "A", 4) }
        assertNull(PatternAnalyzer.analyze(history))
    }

    @Test
    fun returnsNull_exactly9Records() {
        assertNull(PatternAnalyzer.analyze((1..9).map { record("A", "A", 3) }))
    }

    @Test
    fun returnsNonNull_exactly10Records() {
        assertNotNull(PatternAnalyzer.analyze(tenRecords()))
    }

    @Test
    fun archetypeHeuristic_appearsWhenGroupSizeMet() {
        // 5 PD + 5 SH, each group >= MIN_GROUP_SIZE(3)
        val history = (1..5).map { record("A", "A", 5, "PRISONERS_DILEMMA", it.toLong()) } +
                      (6..10).map { record("A", "A", 2, "STAG_HUNT", it.toLong()) }
        val analysis = PatternAnalyzer.analyze(history)!!
        val archetypes = analysis.heuristics.filter { it.condition.startsWith("archetype=") }
            .map { it.condition }
        assertTrue("archetype=PRISONERS_DILEMMA" in archetypes)
        assertTrue("archetype=STAG_HUNT" in archetypes)
    }

    @Test
    fun followHeuristic_successRateMatchesRatings() {
        // 5 follow records with rating 5, 5 follow records with rating 1 → successRate = 0.5
        val history = (1..5).map { record("A", "A", 5, recordedAt = it.toLong()) } +
                      (6..10).map { record("A", "A", 1, recordedAt = it.toLong()) }
        val analysis = PatternAnalyzer.analyze(history)!!
        val h = analysis.heuristics.first { it.condition == "strategy=follow_recommendation" }
        assertEquals(0.5, h.successRate, 0.01)
        assertEquals(10, h.sampleSize)
    }

    @Test
    fun deviateHeuristic_appearsWhenGroupSizeMet() {
        // 7 follow + 3 deviate — deviate group exactly at MIN_GROUP_SIZE
        val history = (1..7).map { record("A", "A", 4, recordedAt = it.toLong()) } +
                      (8..10).map { record("A", "B", 4, recordedAt = it.toLong()) }
        val analysis = PatternAnalyzer.analyze(history)!!
        assertTrue(analysis.heuristics.any { it.condition == "strategy=deviate_from_recommendation" })
    }

    @Test
    fun shapleyDelta_neutralWhenFollowAndDeviateEqual() {
        // 5 follow rating=4 + 5 deviate rating=4 → delta = 0 → successRate = 0.5
        val history = (1..5).map { record("A", "A", 4, recordedAt = it.toLong()) } +
                      (6..10).map { record("A", "B", 4, recordedAt = it.toLong()) }
        val analysis = PatternAnalyzer.analyze(history)!!
        val h = analysis.heuristics.firstOrNull { it.condition == "shapley_delta=follow_minus_deviate" }
        assertNotNull(h)
        assertEquals(0.5, h!!.successRate, 0.01)
    }

    @Test
    fun timingTrend_improvingWhenRecentRatingsHigher() {
        // early 5 records rating=1, recent 5 records rating=5 → successRate > 0.5
        val history = (1..5).map { record("A", "A", 1, recordedAt = it.toLong()) } +
                      (6..10).map { record("A", "A", 5, recordedAt = (it + 100).toLong()) }
        val analysis = PatternAnalyzer.analyze(history)!!
        val h = analysis.heuristics.first { it.condition == "timing_trend=recent_vs_early" }
        assertTrue(h.successRate > 0.5)
    }

    @Test
    fun timingTrend_decliningWhenRecentRatingsLower() {
        // early rating=5, recent rating=1
        val history = (1..5).map { record("A", "A", 5, recordedAt = it.toLong()) } +
                      (6..10).map { record("A", "A", 1, recordedAt = (it + 100).toLong()) }
        val analysis = PatternAnalyzer.analyze(history)!!
        val h = analysis.heuristics.first { it.condition == "timing_trend=recent_vs_early" }
        assertTrue(h.successRate < 0.5)
    }

    @Test
    fun successRates_allBoundedIn0to1() {
        val history = (1..10).map { record("A", if (it % 3 == 0) "B" else "A", (it % 5) + 1, "PD", it.toLong()) }
        val analysis = PatternAnalyzer.analyze(history)!!
        analysis.heuristics.forEach { h ->
            assertTrue("${h.condition} successRate out of bounds: ${h.successRate}", h.successRate in 0.0..1.0)
        }
    }

    @Test
    fun sampleSizes_arePositive() {
        val history = tenRecords()
        val analysis = PatternAnalyzer.analyze(history)!!
        analysis.heuristics.forEach { h ->
            assertTrue("${h.condition} sampleSize <= 0", h.sampleSize > 0)
        }
    }
}
