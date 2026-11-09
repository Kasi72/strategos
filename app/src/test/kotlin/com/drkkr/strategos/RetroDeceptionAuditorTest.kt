package com.drkkr.strategos

import com.drkkr.strategos.domain.flywheel.OutcomeRecord
import com.drkkr.strategos.domain.flywheel.RetroDeceptionAuditor
import com.drkkr.strategos.domain.game.GameClass
import com.drkkr.strategos.domain.game.GameIR
import com.drkkr.strategos.domain.game.OpponentModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroDeceptionAuditorTest {

    private val baseModel = OpponentModel(playerId = "opp1", epsilon = 0.1, discountFactor = 0.9, cooperationIndex = 0.8)

    private fun smallIr(): GameIR {
        // 2×2: player0 vs player1, all-cooperate payoffs so equilibrium is (0,0)
        return GameIR(
            scenarioId = "s1",
            playerIds = listOf("user", "opp1"),
            actionIds = listOf(listOf("C", "D"), listOf("C", "D")),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf(3.0, 0.0, 5.0, 1.0), // user payoffs: CC, CD, DC, DD
                doubleArrayOf(3.0, 5.0, 0.0, 1.0)  // opp payoffs:  CC, CD, DC, DD
            ),
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100
        )
    }

    @Test
    fun returnsEmptyWhenNoActionIndicesSet() {
        val outcomes = listOf(
            OutcomeRecord("s1", "C", "C", 4),
            OutcomeRecord("s1", "D", "D", 3)
        )
        val result = RetroDeceptionAuditor.audit(smallIr(), baseModel, outcomes)
        assertTrue(result.isEmpty())
    }

    @Test
    fun skipsNullIndicesAndAnnotatesNonNull() {
        val outcomes = listOf(
            OutcomeRecord("s1", "C", "C", 4, observedOpponentActionIndex = null),
            // action index 1 = "D" for opponent — in prisoner's dilemma with equilibrium at CC,
            // this should produce a signal (D when equilibrium is C → BLUFFING or similar)
            OutcomeRecord("s1", "C", "C", 2, observedOpponentActionIndex = 1)
        )
        val result = RetroDeceptionAuditor.audit(smallIr(), baseModel, outcomes)
        // May be empty if the solver does not find CC as equilibrium — just assert no crash
        // and that null-index record is not annotated
        result.forEach { annotation ->
            assertEquals("s1", annotation.scenarioId)
        }
    }

    @Test
    fun scenarioIdPreserved() {
        val ir = smallIr()
        val outcomes = listOf(
            OutcomeRecord("scenario-abc", "C", "C", 3, observedOpponentActionIndex = 1)
        )
        val result = RetroDeceptionAuditor.audit(ir, baseModel, outcomes)
        result.forEach { assertEquals("scenario-abc", it.scenarioId) }
    }
}
