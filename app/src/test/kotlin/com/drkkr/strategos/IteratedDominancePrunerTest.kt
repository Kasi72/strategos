package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

class IteratedDominancePrunerTest {

    @Test
    fun `PD — Defect dominates Cooperate for both players — game reduces to 1x1`() {
        val result = IteratedDominancePruner.prune(pdIr())
        assertEquals(1, result.ir.strategyCounts[0])
        assertEquals(1, result.ir.strategyCounts[1])
        assertEquals("d", result.ir.actionIds[0][0])
        assertEquals("d", result.ir.actionIds[1][0])
    }

    @Test
    fun `PD — eliminated actions correctly logged`() {
        val result = IteratedDominancePruner.prune(pdIr())
        assertTrue(result.eliminated["p1"]!!.contains("c"))
        assertTrue(result.eliminated["p2"]!!.contains("c"))
    }

    @Test
    fun `Matching Pennies — no dominated strategy — game unchanged`() {
        val result = IteratedDominancePruner.prune(mpIr())
        assertFalse(result.anyEliminated)
        assertEquals(2, result.ir.strategyCounts[0])
        assertEquals(2, result.ir.strategyCounts[1])
        assertEquals(0, result.rounds)
    }

    @Test
    fun `3x2 game with one dominated row — reduces to 2x2`() {
        // Player 1 has 3 actions; action 2 is strictly dominated by action 0 for all player 2 responses
        // Payoffs player 1: (0,0)=3,(0,1)=3,(1,0)=2,(1,1)=2,(2,0)=1,(2,1)=1
        // Payoffs player 2: symmetric
        val u1 = doubleArrayOf(3.0, 3.0, 2.0, 2.0, 1.0, 1.0)
        val u2 = doubleArrayOf(3.0, 2.0, 3.0, 2.0, 3.0, 2.0)
        val ir = GameIR(
            scenarioId = "3x2", playerIds = listOf("p1","p2"),
            actionIds = listOf(listOf("a0","a1","a2"), listOf("b0","b1")),
            strategyCounts = listOf(3,2), utility = listOf(u1, u2),
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
        val result = IteratedDominancePruner.prune(ir)
        // a2 dominated by a0; a1 dominated by a0 → only a0 survives for player 1
        assertTrue(result.anyEliminated)
        assertTrue(result.ir.strategyCounts[0] < 3)
    }

    @Test
    fun `Stag Hunt — no dominated strategy — game unchanged`() {
        val result = IteratedDominancePruner.prune(stagHuntIr())
        assertFalse(result.anyEliminated)
        assertEquals(2, result.ir.strategyCounts[0])
        assertEquals(2, result.ir.strategyCounts[1])
    }

    // ── Game builders ──────────────────────────────────────────────────────────

    private fun pdIr() = GameIR(
        scenarioId = "pd", playerIds = listOf("p1","p2"),
        actionIds = listOf(listOf("c","d"), listOf("c","d")),
        strategyCounts = listOf(2,2),
        utility = listOf(doubleArrayOf(3.0,0.0,5.0,1.0), doubleArrayOf(3.0,5.0,0.0,1.0)),
        gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
    )

    private fun mpIr() = GameIR(
        scenarioId = "mp", playerIds = listOf("p1","p2"),
        actionIds = listOf(listOf("h","t"), listOf("h","t")),
        strategyCounts = listOf(2,2),
        utility = listOf(doubleArrayOf(1.0,-1.0,-1.0,1.0), doubleArrayOf(-1.0,1.0,1.0,-1.0)),
        gameClass = GameClass.ZERO_SUM_2P, coveragePct = 100
    )

    private fun stagHuntIr() = GameIR(
        scenarioId = "sh", playerIds = listOf("p1","p2"),
        actionIds = listOf(listOf("stag","hare"), listOf("stag","hare")),
        strategyCounts = listOf(2,2),
        utility = listOf(doubleArrayOf(3.0,0.0,1.0,1.0), doubleArrayOf(3.0,1.0,0.0,1.0)),
        gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
    )
}
