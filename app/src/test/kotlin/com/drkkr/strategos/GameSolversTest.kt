package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [PureNashSolver] and [MixedNash2x2Solver] via the [GameSolver] interface.
 *
 * All games built from manually-known payoffs; never from solver output.
 */
class GameSolversTest {

    // ── PureNashSolver ────────────────────────────────────────────────────────

    @Test
    fun `PD has exactly one pure NE at (Defect, Defect)`() {
        val outcome = PureNashSolver.solve(pdIr())
        assertTrue(outcome is SolverOutcome.Solved)
        val solved = outcome as SolverOutcome.Solved
        assertEquals(1, solved.equilibria.size)
        assertEquals(listOf(1, 1), solved.equilibria.first().pureProfile)
    }

    @Test
    fun `Stag Hunt has two pure NE`() {
        val outcome = PureNashSolver.solve(stagHuntIr())
        assertTrue(outcome is SolverOutcome.Solved)
        val solved = outcome as SolverOutcome.Solved
        assertEquals(2, solved.equilibria.size)
        val profiles = solved.equilibria.map { it.pureProfile!! }.sortedWith(compareBy({ it[0] }, { it[1] }))
        assertEquals(listOf(0, 0), profiles[0])   // (Stag, Stag)
        assertEquals(listOf(1, 1), profiles[1])   // (Hare, Hare)
    }

    @Test
    fun `Matching Pennies has no pure NE — returns Incomplete`() {
        val outcome = PureNashSolver.solve(mpIr())
        assertTrue("Expected Incomplete, got $outcome", outcome is SolverOutcome.Incomplete)
    }

    @Test
    fun `PureNashSolver returns Solved with diagnostics completion COMPLETE`() {
        val outcome = PureNashSolver.solve(pdIr())
        val diagnostics = (outcome as SolverOutcome.Solved).diagnostics
        assertEquals(SolverCompletion.COMPLETE, diagnostics.completion)
        assertEquals(0.0, diagnostics.residual ?: -1.0, 1e-12)
    }

    @Test
    fun `PureNashSolver returns InvalidModel for single-player IR`() {
        val ir = GameIR(
            scenarioId = "x", playerIds = listOf("p1"),
            actionIds = listOf(listOf("a1", "a2")),
            strategyCounts = listOf(2),
            utility = listOf(doubleArrayOf(1.0, 0.0)),
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100
        )
        val outcome = PureNashSolver.solve(ir)
        assertTrue(outcome is SolverOutcome.InvalidModel)
    }

    @Test
    fun `PureNashSolver capabilities advertise no mixed support`() {
        val caps = PureNashSolver.capabilities()
        assertFalse(caps.supportsMixedStrategies)
        assertTrue(GameClass.FINITE_NORMAL_FORM in caps.supportedClasses)
    }

    // ── MixedNash2x2Solver ────────────────────────────────────────────────────

    @Test
    fun `Matching Pennies mixed NE is (0_5, 0_5) for both players`() {
        val outcome = MixedNash2x2Solver.solve(mpIr())
        assertTrue("Expected Solved, got $outcome", outcome is SolverOutcome.Solved)
        val mixed = (outcome as SolverOutcome.Solved).equilibria.first().mixedStrategies
        assertEquals(2, mixed.size)
        assertEquals(0.5, mixed[0][0], 1e-9)
        assertEquals(0.5, mixed[1][0], 1e-9)
    }

    @Test
    fun `MixedNash2x2Solver on PD returns pure NE (Defect, Defect)`() {
        // PD has a dominant strategy; indifference eqs give p outside [0,1].
        // Solver falls back to PureNashSolver → Solved.
        val outcome = MixedNash2x2Solver.solve(pdIr())
        assertTrue("Expected Solved, got $outcome", outcome is SolverOutcome.Solved)
        val pureProfile = (outcome as SolverOutcome.Solved).equilibria.first().pureProfile
        assertEquals(listOf(1, 1), pureProfile)
    }

    @Test
    fun `MixedNash2x2Solver returns Unsupported for 3-player game`() {
        val ir3 = GameIR(
            scenarioId = "x", playerIds = listOf("p1", "p2", "p3"),
            actionIds = listOf(listOf("a","b"), listOf("a","b"), listOf("a","b")),
            strategyCounts = listOf(2, 2, 2),
            utility = List(3) { DoubleArray(8) { 1.0 } },
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
        val outcome = MixedNash2x2Solver.solve(ir3)
        assertTrue(outcome is SolverOutcome.Unsupported)
    }

    @Test
    fun `MixedNash2x2Solver residual verified by EquilibriumVerifier`() {
        val outcome = MixedNash2x2Solver.solve(mpIr()) as SolverOutcome.Solved
        val residual = EquilibriumVerifier.deviationGainResidual(
            mpIr().utility,
            mpIr().strategyCounts,
            outcome.equilibria.first().mixedStrategies
        )
        assertTrue("Residual $residual should be ≤ 1e-9", residual <= 1e-9)
    }

    // ── Game builders ─────────────────────────────────────────────────────────

    /** Prisoner's Dilemma: (C,C)=3/3, (C,D)=0/5, (D,C)=5/0, (D,D)=1/1. Dominant strategy: Defect. */
    private fun pdIr(): GameIR {
        // profiles: [0,0]=CC, [0,1]=CD, [1,0]=DC, [1,1]=DD
        val u1 = doubleArrayOf(3.0, 0.0, 5.0, 1.0)
        val u2 = doubleArrayOf(3.0, 5.0, 0.0, 1.0)
        return GameIR(
            scenarioId = "pd", playerIds = listOf("p1", "p2"),
            actionIds = listOf(listOf("c1","d1"), listOf("c2","d2")),
            strategyCounts = listOf(2, 2),
            utility = listOf(u1, u2),
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
    }

    /** Matching Pennies: zero-sum; unique mixed NE at (0.5, 0.5). */
    private fun mpIr(): GameIR {
        val u1 = doubleArrayOf(1.0, -1.0, -1.0, 1.0)
        val u2 = doubleArrayOf(-1.0, 1.0, 1.0, -1.0)
        return GameIR(
            scenarioId = "mp", playerIds = listOf("p1", "p2"),
            actionIds = listOf(listOf("h1","t1"), listOf("h2","t2")),
            strategyCounts = listOf(2, 2),
            utility = listOf(u1, u2),
            gameClass = GameClass.ZERO_SUM_2P, coveragePct = 100
        )
    }

    /** Stag Hunt: pure NE at (Stag,Stag)=3/3 and (Hare,Hare)=1/1. */
    private fun stagHuntIr(): GameIR {
        // profiles: [0,0]=SS, [0,1]=SH, [1,0]=HS, [1,1]=HH
        val u1 = doubleArrayOf(3.0, 0.0, 1.0, 1.0)
        val u2 = doubleArrayOf(3.0, 1.0, 0.0, 1.0)
        return GameIR(
            scenarioId = "sh", playerIds = listOf("p1", "p2"),
            actionIds = listOf(listOf("stag","hare"), listOf("stag","hare")),
            strategyCounts = listOf(2, 2),
            utility = listOf(u1, u2),
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
    }
}
