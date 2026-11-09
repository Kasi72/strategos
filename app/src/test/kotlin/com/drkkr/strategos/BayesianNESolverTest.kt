package com.drkkr.strategos

import com.drkkr.strategos.domain.game.BayesianNESolver
import com.drkkr.strategos.domain.game.GameClass
import com.drkkr.strategos.domain.game.GameIR
import com.drkkr.strategos.domain.game.OpponentModel
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BayesianNESolverTest {

    private fun ir2x2(gameClass: GameClass = GameClass.FINITE_NORMAL_FORM, coveragePct: Int = 20): GameIR {
        // 2×2 PD-style payoffs: cooperate=0, defect=1
        // utility[0] = user: CC=3, CD=0, DC=5, DD=1
        // utility[1] = opp:  CC=3, CD=5, DC=0, DD=1
        return GameIR(
            scenarioId = "test",
            playerIds = listOf("user", "opp"),
            actionIds = listOf(listOf("cooperate", "defect"), listOf("cooperate", "defect")),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf(3.0, 0.0, 5.0, 1.0),
                doubleArrayOf(3.0, 5.0, 0.0, 1.0)
            ),
            gameClass = gameClass,
            coveragePct = coveragePct,
            archetype = com.drkkr.strategos.domain.game.GameArchetype.PRISONERS_DILEMMA
        )
    }

    @Test
    fun returnsNull_whenScoreBelowThreshold() {
        val ir = ir2x2(coveragePct = 90)   // low asymmetry
        val score = BayesianNESolver.computeAsymmetryScore(ir)
        assertTrue(score <= BayesianNESolver.THRESHOLD)
        assertNull(BayesianNESolver.solve(ir, null, score))
    }

    @Test
    fun returnsNull_forScoreExactlyAtThreshold() {
        assertNull(BayesianNESolver.solve(ir2x2(), null, BayesianNESolver.THRESHOLD))
    }

    @Test
    fun returnsResult_whenScoreAboveThreshold() {
        val ir = ir2x2(coveragePct = 10)
        val score = BayesianNESolver.computeAsymmetryScore(ir)
        assertTrue(score > BayesianNESolver.THRESHOLD)
        val result = BayesianNESolver.solve(ir, null, score)
        assertNotNull(result)
    }

    @Test
    fun strategyIndex_withinBounds() {
        val ir = ir2x2(coveragePct = 10)
        val score = BayesianNESolver.computeAsymmetryScore(ir)
        val result = BayesianNESolver.solve(ir, null, score)!!
        assertTrue(result.strategy in 0 until 2)
    }

    @Test
    fun typePosteriors_sumToApproximately1() {
        val ir = ir2x2(coveragePct = 10)
        val score = BayesianNESolver.computeAsymmetryScore(ir)
        val result = BayesianNESolver.solve(ir, null, score)!!
        val sum = result.typePosteriors.values.sum()
        assertTrue(kotlin.math.abs(sum - 1.0) < 0.001)
    }

    @Test
    fun cooperativeOpponent_shiftsTowardCooperation() {
        // Use a coordination (Stag Hunt) game where mutual cooperation (index 0) gives 4,4
        // and defection gives only 1,1. With a 90% cooperative opponent the BNE recommends cooperate.
        val irStag = GameIR(
            scenarioId = "stag",
            playerIds = listOf("user", "opp"),
            actionIds = listOf(listOf("stag", "hare"), listOf("stag", "hare")),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf(4.0, 0.0, 1.0, 1.0),
                doubleArrayOf(4.0, 1.0, 0.0, 1.0)
            ),
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 10,
            archetype = com.drkkr.strategos.domain.game.GameArchetype.STAG_HUNT
        )
        val score = BayesianNESolver.computeAsymmetryScore(irStag)
        val cooperativeOpponent = OpponentModel(
            playerId = "opp", epsilon = 0.1, discountFactor = 0.9,
            cooperationIndex = 0.9
        )
        val result = BayesianNESolver.solve(irStag, cooperativeOpponent, score)!!
        assertTrue(result.strategy == 0)
    }

    @Test
    fun returnsNull_forSinglePlayer() {
        val ir = GameIR(
            scenarioId = "test",
            playerIds = listOf("user"),
            actionIds = listOf(listOf("A", "B", "C")),
            strategyCounts = listOf(3),
            utility = listOf(doubleArrayOf(1.0, 2.0, 3.0)),
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 10,
            archetype = com.drkkr.strategos.domain.game.GameArchetype.GENERIC
        )
        assertNull(BayesianNESolver.solve(ir, null, 0.9))
    }

    @Test
    fun computeAsymmetryScore_bayesianGameClass_addsBonus() {
        val irBayesian = ir2x2(GameClass.BAYESIAN, coveragePct = 60)
        val irGeneral  = ir2x2(GameClass.FINITE_NORMAL_FORM, coveragePct = 60)
        assertTrue(
            BayesianNESolver.computeAsymmetryScore(irBayesian) >
            BayesianNESolver.computeAsymmetryScore(irGeneral)
        )
    }

    @Test
    fun computeAsymmetryScore_boundedIn0to1() {
        listOf(0, 50, 100).forEach { cov ->
            val score = BayesianNESolver.computeAsymmetryScore(ir2x2(coveragePct = cov))
            assertTrue(score in 0.0..1.0)
        }
    }
}
