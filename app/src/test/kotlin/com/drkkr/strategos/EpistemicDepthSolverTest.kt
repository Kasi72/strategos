package com.drkkr.strategos

import com.drkkr.strategos.domain.game.EpistemicDepthSolver
import com.drkkr.strategos.domain.game.GameArchetype
import com.drkkr.strategos.domain.game.GameClass
import com.drkkr.strategos.domain.game.GameIR
import com.drkkr.strategos.domain.game.OpponentModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EpistemicDepthSolverTest {

    private fun makeIR(
        playerCount: Int,
        strategyCounts: List<Int>,
        utility: List<List<Double>>,
        archetype: GameArchetype = GameArchetype.GENERIC
    ) = GameIR(
        scenarioId = "test",
        playerIds = (0 until playerCount).map { "P$it" },
        actionIds = strategyCounts.mapIndexed { i, n -> (0 until n).map { "A$i$it" } },
        strategyCounts = strategyCounts,
        utility = utility.map { it.toDoubleArray() },
        archetype = archetype,
        gameClass = GameClass.FINITE_NORMAL_FORM,
        coveragePct = 100
    )

    private fun pdIR(): GameIR {
        // Prisoner's Dilemma: 2x2
        // P0 payoffs (row=P0, col=P1): CC=3, CD=0, DC=5, DD=1
        // P1 payoffs:                  CC=3, CD=5, DC=0, DD=1
        val u0 = listOf(3.0, 0.0, 5.0, 1.0)
        val u1 = listOf(3.0, 5.0, 0.0, 1.0)
        return makeIR(2, listOf(2, 2), listOf(u0, u1), GameArchetype.PRISONERS_DILEMMA)
    }

    private fun opponentModel(epsilon: Double) = OpponentModel(
        playerId = "opp",
        epsilon = epsilon,
        discountFactor = 0.5
    )

    @Test
    fun returnsNullForThreePlayers() {
        val ir = makeIR(3, listOf(2, 2, 2), listOf(
            listOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            listOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            listOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        ))
        assertNull(EpistemicDepthSolver.solve(ir, null))
    }

    @Test
    fun levelInference_highEpsilonIsLevel0() {
        val result = EpistemicDepthSolver.solve(pdIR(), opponentModel(0.80))
        assertNotNull(result)
        assertEquals(0, result!!.opponentLevel)
    }

    @Test
    fun levelInference_midEpsilonIsLevel1() {
        val result = EpistemicDepthSolver.solve(pdIR(), opponentModel(0.50))
        assertNotNull(result)
        assertEquals(1, result!!.opponentLevel)
    }

    @Test
    fun levelInference_lowEpsilonIsLevel2() {
        val result = EpistemicDepthSolver.solve(pdIR(), opponentModel(0.25))
        assertNotNull(result)
        assertEquals(2, result!!.opponentLevel)
    }

    @Test
    fun levelInference_veryLowEpsilonIsLevel3() {
        val result = EpistemicDepthSolver.solve(pdIR(), opponentModel(0.10))
        assertNotNull(result)
        assertEquals(3, result!!.opponentLevel)
    }

    @Test
    fun pdLevel0_bestResponseIsDefect() {
        // Against random opponent (level-0, 50/50), defect dominates in PD: 2.5 vs 1.5
        val result = EpistemicDepthSolver.solve(pdIR(), opponentModel(0.80))
        assertNotNull(result)
        assertEquals(1, result!!.yourBestResponse) // strategy index 1 = Defect
    }

    @Test
    fun resultContainsReasoning() {
        val result = EpistemicDepthSolver.solve(pdIR(), opponentModel(0.5))
        assertNotNull(result)
        assert(result!!.reasoning.isNotBlank())
    }

    @Test
    fun nullOpponentModelUsesDefaultEpsilon() {
        val result = EpistemicDepthSolver.solve(pdIR(), null)
        assertNotNull(result)
        // epsilon=0.5 by default → level 1
        assertEquals(1, result!!.opponentLevel)
    }

    @Test
    fun bestResponseIndexInBounds() {
        val result = EpistemicDepthSolver.solve(pdIR(), opponentModel(0.3))
        assertNotNull(result)
        assert(result!!.yourBestResponse in 0..1)
    }
}
