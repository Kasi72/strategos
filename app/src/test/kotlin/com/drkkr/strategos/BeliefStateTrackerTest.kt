package com.drkkr.strategos

import com.drkkr.strategos.domain.game.BeliefStateTracker
import com.drkkr.strategos.domain.game.GameArchetype
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BeliefStateTrackerTest {

    @Test
    fun uniformPrior_allEqual() {
        val prior = BeliefStateTracker.uniformPrior()
        val expected = 1.0 / GameArchetype.entries.size
        prior.archetypeProbabilities.values.forEach { p ->
            assertEquals(expected, p, 1e-10)
        }
        assertEquals(GameArchetype.entries.size, prior.archetypeProbabilities.size)
    }

    @Test
    fun uniformPrior_sumsToOne() {
        val sum = BeliefStateTracker.uniformPrior().archetypeProbabilities.values.sum()
        assertEquals(1.0, sum, 1e-10)
    }

    @Test
    fun update_highCoopRaisesPureCoordination() {
        val prior = BeliefStateTracker.uniformPrior()
        val updated = BeliefStateTracker.update(prior, 0.90)
        val pCoord = updated.archetypeProbabilities[GameArchetype.PURE_COORDINATION]!!
        val pZeroSum = updated.archetypeProbabilities[GameArchetype.ZERO_SUM]!!
        assertTrue("PURE_COORDINATION should dominate at high coop rate", pCoord > pZeroSum)
    }

    @Test
    fun update_lowCoopRaisesZeroSum() {
        val prior = BeliefStateTracker.uniformPrior()
        val updated = BeliefStateTracker.update(prior, 0.05)
        val pZeroSum = updated.archetypeProbabilities[GameArchetype.ZERO_SUM]!!
        val pHarmony = updated.archetypeProbabilities[GameArchetype.HARMONY]!!
        assertTrue("ZERO_SUM should dominate at low coop rate", pZeroSum > pHarmony)
    }

    @Test
    fun update_posteriorSumsToOne() {
        val updated = BeliefStateTracker.update(BeliefStateTracker.uniformPrior(), 0.5)
        val sum = updated.archetypeProbabilities.values.sum()
        assertEquals(1.0, sum, 1e-10)
    }

    @Test
    fun updateAll_sequentialEqualsChained() {
        val prior = BeliefStateTracker.uniformPrior()
        val rates = listOf(0.3, 0.7, 0.2)
        val sequential = BeliefStateTracker.updateAll(prior, rates)
        var chained = prior
        rates.forEach { chained = BeliefStateTracker.update(chained, it) }
        sequential.archetypeProbabilities.forEach { (k, v) ->
            assertEquals(v, chained.archetypeProbabilities[k]!!, 1e-10)
        }
    }

    @Test
    fun beliefState_mostLikely_returnsHighestProb() {
        val prior = BeliefStateTracker.uniformPrior()
        val updated = BeliefStateTracker.update(prior, 0.95)
        val mostLikely = updated.mostLikely
        val maxP = updated.archetypeProbabilities.values.max()
        assertEquals(maxP, updated.archetypeProbabilities[mostLikely]!!, 1e-10)
    }

    @Test
    fun beliefState_entropy_uniformMaximum() {
        val uniform = BeliefStateTracker.uniformPrior()
        val peaked = BeliefStateTracker.update(uniform, 0.95)
        assertTrue("Uniform entropy should exceed peaked entropy", uniform.entropy > peaked.entropy)
    }

    @Test
    fun beliefState_entropy_nonNegative() {
        val updated = BeliefStateTracker.update(BeliefStateTracker.uniformPrior(), 0.5)
        assertTrue(updated.entropy >= 0.0)
    }
}
