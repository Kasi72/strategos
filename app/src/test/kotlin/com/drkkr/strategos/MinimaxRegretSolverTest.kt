package com.drkkr.strategos

import com.drkkr.strategos.domain.game.GameClass
import com.drkkr.strategos.domain.game.GameIR
import com.drkkr.strategos.domain.game.MinimaxRegretSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MinimaxRegretSolverTest {

    private fun ir2x2(u0: DoubleArray, u1: DoubleArray = DoubleArray(4) { 0.0 }) = GameIR(
        scenarioId = "test",
        playerIds = listOf("p0", "p1"),
        actionIds = listOf(listOf("a0", "a1"), listOf("b0", "b1")),
        strategyCounts = listOf(2, 2),
        utility = listOf(u0, u1),
        gameClass = GameClass.FINITE_NORMAL_FORM,
        coveragePct = 100
    )

    @Test
    fun returnsNullForThreePlayers() {
        val ir = GameIR(
            scenarioId = "test",
            playerIds = listOf("p0", "p1", "p2"),
            actionIds = listOf(listOf("a0"), listOf("b0"), listOf("c0")),
            strategyCounts = listOf(1, 1, 1),
            utility = listOf(doubleArrayOf(1.0), doubleArrayOf(1.0), doubleArrayOf(1.0)),
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100
        )
        assertNull(MinimaxRegretSolver.solve(ir))
    }

    @Test
    fun returnsNullForEmptyStrategyCounts() {
        val ir = GameIR(
            scenarioId = "test",
            playerIds = listOf("p0", "p1"),
            actionIds = listOf(emptyList(), listOf("b0")),
            strategyCounts = listOf(0, 1),
            utility = listOf(doubleArrayOf(), doubleArrayOf(1.0)),
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 0
        )
        assertNull(MinimaxRegretSolver.solve(ir))
    }

    @Test
    fun solvesSymmetricPrisonersDilemma() {
        // Canonical PD: CC=3, CD=0, DC=5, DD=1  (row player = player 0)
        // Regret matrix:
        //   col=C: best=5, so regret(C)=5-3=2, regret(D)=5-5=0
        //   col=D: best=1, so regret(C)=1-0=1, regret(D)=1-1=0
        // worst-case regret: strategy C → max(2,1)=2;  strategy D → max(0,0)=0
        // minimax regret → Defect (index 1), worst-case regret = 0, gap = 2
        val result = MinimaxRegretSolver.solve(ir2x2(doubleArrayOf(3.0, 0.0, 5.0, 1.0)))
        assertNotNull(result)
        assertEquals(1, result!!.strategy)
        assertEquals(0.0, result.worstCaseRegret, 1e-9)
        assertEquals(2.0, result.regretGap, 1e-9)
    }

    @Test
    fun solvesCoordinationGame() {
        // Coordination: CC=2, CD=0, DC=0, DD=2
        // col=C: best=2; regret(C)=0, regret(D)=2
        // col=D: best=2; regret(C)=2, regret(D)=0
        // worst-case: C→2, D→2 — tie, solver picks first (index 0)
        val result = MinimaxRegretSolver.solve(ir2x2(doubleArrayOf(2.0, 0.0, 0.0, 2.0)))
        assertNotNull(result)
        assertEquals(0, result!!.strategy)
        assertEquals(2.0, result.worstCaseRegret, 1e-9)
        assertEquals(0.0, result.regretGap, 1e-9)
    }

    @Test
    fun solvesDominantStrategyGame() {
        // Player 0 always prefers row 1: 10 > 1 regardless of column
        // u0 = [1, 1, 10, 10] (row0: 1,1; row1: 10,10)
        // col=C: best=10; regret(row0)=9, regret(row1)=0
        // col=D: best=10; regret(row0)=9, regret(row1)=0
        // worst-case: row0→9, row1→0
        val result = MinimaxRegretSolver.solve(ir2x2(doubleArrayOf(1.0, 1.0, 10.0, 10.0)))
        assertNotNull(result)
        assertEquals(1, result!!.strategy)
        assertEquals(0.0, result.worstCaseRegret, 1e-9)
        assertEquals(9.0, result.regretGap, 1e-9)
    }

    @Test
    fun solves3x2Game() {
        // 3 strategies for player 0, 2 for player 1
        // u0 = [2,0, 1,1, 0,2]  (row-major: s0t0,s0t1, s1t0,s1t1, s2t0,s2t1)
        val ir = GameIR(
            scenarioId = "test",
            playerIds = listOf("p0", "p1"),
            actionIds = listOf(listOf("a0", "a1", "a2"), listOf("b0", "b1")),
            strategyCounts = listOf(3, 2),
            utility = listOf(doubleArrayOf(2.0, 0.0, 1.0, 1.0, 0.0, 2.0), doubleArrayOf(0.0, 2.0, 1.0, 1.0, 2.0, 0.0)),
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100
        )
        val result = MinimaxRegretSolver.solve(ir)
        assertNotNull(result)
        // Middle strategy (index 1) has worst-case regret of 1.0 from both columns
        assertEquals(1, result!!.strategy)
        assertEquals(1.0, result.worstCaseRegret, 1e-9)
    }
}
