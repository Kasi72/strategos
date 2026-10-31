package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MixedNashNxMSolverTest {

    @Test
    fun `Matching Pennies — NxM solver finds (0_5, 0_5) mixed NE`() {
        val outcome = MixedNashNxMSolver.solve(mpIr())
        assertTrue("Expected Solved, got $outcome", outcome is SolverOutcome.Solved)
        val eq = (outcome as SolverOutcome.Solved).equilibria.first()
        assertEquals(0.5, eq.mixedStrategies[0][0], 1e-6)
        assertEquals(0.5, eq.mixedStrategies[1][0], 1e-6)
    }

    @Test
    fun `PD — NxM solver finds pure NE (Defect, Defect)`() {
        val outcome = MixedNashNxMSolver.solve(pdIr())
        assertTrue("Expected Solved, got $outcome", outcome is SolverOutcome.Solved)
        val profiles = (outcome as SolverOutcome.Solved).equilibria.mapNotNull { it.pureProfile }
        assertTrue("PD pure NE should be (1,1)", profiles.any { it == listOf(1,1) })
    }

    @Test
    fun `Stag Hunt — NxM solver finds two pure NE`() {
        val outcome = MixedNashNxMSolver.solve(stagHuntIr())
        assertTrue("Expected Solved, got $outcome", outcome is SolverOutcome.Solved)
        val eqs = (outcome as SolverOutcome.Solved).equilibria
        assertTrue("Stag Hunt should have ≥2 equilibria", eqs.size >= 2)
    }

    @Test
    fun `Battle of Sexes — NxM solver finds two pure NE and one interior mixed NE`() {
        val outcome = MixedNashNxMSolver.solve(bosIr())
        assertTrue("Expected Solved, got $outcome", outcome is SolverOutcome.Solved)
        val eqs = (outcome as SolverOutcome.Solved).equilibria
        // Battle of Sexes has exactly 3 NE: (0,0), (1,1), and one interior mixed
        assertTrue("BotS should have ≥2 equilibria, got ${eqs.size}", eqs.size >= 2)
    }

    @Test
    fun `3-player game returns Unsupported`() {
        val ir = GameIR(
            scenarioId = "3p", playerIds = listOf("p1","p2","p3"),
            actionIds = listOf(listOf("a","b"), listOf("a","b"), listOf("a","b")),
            strategyCounts = listOf(2,2,2),
            utility = List(3) { DoubleArray(8) { 1.0 } },
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
        assertTrue(MixedNashNxMSolver.solve(ir) is SolverOutcome.Unsupported)
    }

    @Test
    fun `All equilibria pass EquilibriumVerifier residual check`() {
        for (ir in listOf(mpIr(), pdIr(), stagHuntIr(), bosIr())) {
            val outcome = MixedNashNxMSolver.solve(ir)
            if (outcome is SolverOutcome.Solved) {
                outcome.equilibria.forEach { eq ->
                    val r = EquilibriumVerifier.deviationGainResidual(
                        ir.utility, ir.strategyCounts, eq.mixedStrategies
                    )
                    assertTrue("Residual $r > 1e-6 for ${ir.scenarioId}", r <= 1e-6)
                }
            }
        }
    }

    // ── Game builders ──────────────────────────────────────────────────────────

    private fun mpIr() = GameIR(
        scenarioId = "mp", playerIds = listOf("p1","p2"),
        actionIds = listOf(listOf("h","t"), listOf("h","t")),
        strategyCounts = listOf(2,2),
        utility = listOf(doubleArrayOf(1.0,-1.0,-1.0,1.0), doubleArrayOf(-1.0,1.0,1.0,-1.0)),
        gameClass = GameClass.ZERO_SUM_2P, coveragePct = 100
    )

    private fun pdIr() = GameIR(
        scenarioId = "pd", playerIds = listOf("p1","p2"),
        actionIds = listOf(listOf("c","d"), listOf("c","d")),
        strategyCounts = listOf(2,2),
        utility = listOf(doubleArrayOf(3.0,0.0,5.0,1.0), doubleArrayOf(3.0,5.0,0.0,1.0)),
        gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
    )

    private fun stagHuntIr() = GameIR(
        scenarioId = "sh", playerIds = listOf("p1","p2"),
        actionIds = listOf(listOf("stag","hare"), listOf("stag","hare")),
        strategyCounts = listOf(2,2),
        utility = listOf(doubleArrayOf(3.0,0.0,1.0,1.0), doubleArrayOf(3.0,1.0,0.0,1.0)),
        gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
    )

    /** Battle of Sexes: p1 prefers (0,0)=2/1, p2 prefers (1,1)=1/2. */
    private fun bosIr() = GameIR(
        scenarioId = "bos", playerIds = listOf("p1","p2"),
        actionIds = listOf(listOf("opera","football"), listOf("opera","football")),
        strategyCounts = listOf(2,2),
        utility = listOf(doubleArrayOf(2.0,0.0,0.0,1.0), doubleArrayOf(1.0,0.0,0.0,2.0)),
        gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
    )
}
