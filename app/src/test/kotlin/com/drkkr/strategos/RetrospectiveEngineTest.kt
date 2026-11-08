package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.RetrospectiveEngine
import com.drkkr.strategos.domain.flywheel.OutcomeRecord
import com.drkkr.strategos.domain.game.OpponentModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrospectiveEngineTest {

    private val base = OpponentModel(
        playerId = "opp1",
        epsilon = 0.4,
        discountFactor = 0.6,
        cooperationIndex = null
    )

    private fun outcome(recommended: String, actual: String, rating: Int) = OutcomeRecord(
        scenarioId = "s1",
        recommendedMove = recommended,
        actualMove = actual,
        outcomeRating = rating
    )

    @Test fun returnsNullForEmptyOutcomes() {
        assertNull(RetrospectiveEngine.run(base, emptyList()))
    }

    @Test fun decreasesEpsilonWhenUserFollowsRecommendations() {
        val outcomes = listOf(
            outcome("Accept", "Accept", 4),
            outcome("Wait",   "Wait",   4),
            outcome("Push",   "Push",   5)
        )
        val cal = RetrospectiveEngine.run(base, outcomes)!!
        assertTrue("epsilon should decrease on 100% follow-through", cal.updatedModel.epsilon < base.epsilon)
    }

    @Test fun increasesEpsilonWhenUserIgnoresRecommendations() {
        val outcomes = listOf(
            outcome("Accept", "Refuse", 2),
            outcome("Wait",   "Push",   2)
        )
        val cal = RetrospectiveEngine.run(base, outcomes)!!
        assertTrue("epsilon should increase on 0% follow-through", cal.updatedModel.epsilon > base.epsilon)
    }

    @Test fun setsCooperationIndexFromRatings() {
        val outcomes = listOf(outcome("A", "A", 5))
        val cal = RetrospectiveEngine.run(base, outcomes)!!
        assertNotNull(cal.updatedModel.cooperationIndex)
        assertTrue(cal.updatedModel.cooperationIndex!! > 0.5)
    }

    @Test fun blendsExistingCooperationIndex() {
        val modelWithCoop = base.copy(cooperationIndex = 0.2)
        val outcomes = listOf(outcome("A", "A", 5))
        val cal = RetrospectiveEngine.run(modelWithCoop, outcomes)!!
        // Should be a blend of 0.2 and 1.0, so between both
        val coop = cal.updatedModel.cooperationIndex!!
        assertTrue(coop > 0.2 && coop < 1.0)
    }

    @Test fun sampleSizeMatchesOutcomeCount() {
        val outcomes = listOf(
            outcome("A", "A", 3),
            outcome("B", "B", 3),
            outcome("C", "X", 3)
        )
        val cal = RetrospectiveEngine.run(base, outcomes)!!
        assertEquals(3, cal.sampleSize)
    }

    @Test fun summaryContainsSampleSize() {
        val outcomes = listOf(outcome("A", "A", 4))
        val cal = RetrospectiveEngine.run(base, outcomes)!!
        assertTrue(cal.summary.contains("1 outcome"))
    }

    @Test fun epsilonStaysInRange() {
        val outcomes = (1..10).map { outcome("A", "B", 1) }
        val cal = RetrospectiveEngine.run(base, outcomes)!!
        assertTrue(cal.updatedModel.epsilon in 0.0..1.0)
    }

    @Test fun discountFactorUnchanged() {
        val outcomes = listOf(outcome("A", "A", 5))
        val cal = RetrospectiveEngine.run(base, outcomes)!!
        assertEquals(base.discountFactor, cal.updatedModel.discountFactor, 0.0)
    }
}
