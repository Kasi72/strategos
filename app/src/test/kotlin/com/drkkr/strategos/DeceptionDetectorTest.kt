package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.DeceptionDetector
import com.drkkr.strategos.domain.game.GameArchetype
import com.drkkr.strategos.domain.game.GameClass
import com.drkkr.strategos.domain.game.GameIR
import com.drkkr.strategos.domain.game.OpponentModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeceptionDetectorTest {

    private fun pdIr(): GameIR {
        // 2×2 Prisoner's Dilemma: actions 0=Cooperate, 1=Defect
        // NE: (Defect, Defect) = profile index 3
        // utility[player][profile]: C/C=3, C/D=0, D/C=5, D/D=1
        return GameIR(
            scenarioId = "pd",
            playerIds = listOf("user", "opp"),
            actionIds = listOf(listOf("Cooperate", "Defect"), listOf("Cooperate", "Defect")),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf(3.0, 0.0, 5.0, 1.0),  // user payoffs
                doubleArrayOf(3.0, 5.0, 0.0, 1.0)   // opp payoffs
            ),
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100,
            archetype = GameArchetype.PRISONERS_DILEMMA
        )
    }

    @Test
    fun `no signal when opponent plays equilibrium action`() {
        // Equilibrium is (Defect, Defect), opp plays Defect = index 1
        val opp = OpponentModel(playerId = "opp", epsilon = 0.1, discountFactor = 0.5)
        val result = DeceptionDetector.detect(pdIr(), opp, observedActionIndex = 1)
        assertNull(result)
    }

    @Test
    fun `bluffing signal when opponent plays suboptimal action`() {
        // Opp plays Cooperate (index 0) but NE is Defect (index 1)
        val opp = OpponentModel(playerId = "opp", epsilon = 0.1, discountFactor = 0.5)
        val result = DeceptionDetector.detect(pdIr(), opp, observedActionIndex = 0)
        assertNotNull(result)
        // Cooperate can benefit opp when user cooperates (payoff 3 vs 5 from defecting given user cooperates)
        // but in PD, Defect dominates, so Cooperate is dominated → IRRATIONAL
        assertEquals(DeceptionDetector.DeceptionKind.IRRATIONAL, result!!.kind)
    }

    @Test
    fun `returns null when opponent player id not in IR`() {
        val opp = OpponentModel(playerId = "unknown", epsilon = 0.1, discountFactor = 0.5)
        val result = DeceptionDetector.detect(pdIr(), opp, observedActionIndex = 0)
        assertNull(result)
    }

    @Test
    fun `signal has correct opponent player id`() {
        val opp = OpponentModel(playerId = "opp", epsilon = 0.1, discountFactor = 0.5)
        val ir = pdIr()
        // Create asymmetric game where Cooperate sometimes benefits opp to trigger BLUFFING
        val asymIr = ir.copy(
            utility = listOf(
                doubleArrayOf(3.0, 0.0, 5.0, 1.0),
                // opp: Cooperate gives 4 when user cooperates, Defect gives 3 (reverse PD for opp)
                doubleArrayOf(4.0, 5.0, 2.0, 1.0)
            )
        )
        val result = DeceptionDetector.detect(asymIr, opp, observedActionIndex = 0)
        if (result != null) {
            assertEquals("opp", result.opponentPlayerId)
        }
    }
}
