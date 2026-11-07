package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end engine trace for the Resident Ward Cover scenario (Coordination/Chicken Game).
 *
 * Matrix (Resident 1 rows, Resident 2 columns):
 *                      Cover Ward    Prepare SDL
 *   Cover Ward:        ( 3,  3)      ( 4,  8)
 *   Prepare SDL:       ( 8,  4)      (-10, -10)
 *
 * Profile index order (row-major):
 *   0 = (Cover,   Cover)   → R1= 3, R2= 3
 *   1 = (Cover,   Prepare) → R1= 4, R2= 8   ← NE1
 *   2 = (Prepare, Cover)   → R1= 8, R2= 4   ← NE2
 *   3 = (Prepare, Prepare) → R1=-10, R2=-10
 *
 * Expected:
 *   Pure NE: (Cover Ward, Prepare SDL) = (4,8) and (Prepare SDL, Cover Ward) = (8,4).
 *   IEDS:    no strategies eliminated (no dominant strategies).
 *   Archetype: CHICKEN (two anti-diagonal pure NE, no dominant strategies).
 *   Mixed NE: each plays Cover with prob 14/19 ≈ 0.7368, Prepare with 5/19 ≈ 0.2632.
 */
class ResidentWardCoverTest {

    private val ir = GameIR(
        scenarioId     = "resident-ward-cover",
        playerIds      = listOf("Resident 1", "Resident 2"),
        actionIds      = listOf(
            listOf("Cover Ward", "Prepare SDL"),
            listOf("Cover Ward", "Prepare SDL")
        ),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf( 3.0,  4.0,  8.0, -10.0),   // Resident 1 payoffs
            doubleArrayOf( 3.0,  8.0,  4.0, -10.0)    // Resident 2 payoffs
        ),
        gameClass    = GameClass.FINITE_NORMAL_FORM,
        coveragePct  = 100
    )

    // ── Pure NE: two equilibria at off-diagonal profiles ────────────────────

    @Test
    fun `two pure Nash equilibria found`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)

        assertTrue("PureNash should find equilibria, got $outcome", outcome is SolverOutcome.Solved)

        val profiles = (outcome as SolverOutcome.Solved).equilibria.mapNotNull { it.pureProfile }
        assertEquals("Exactly two pure NE", 2, profiles.size)
    }

    @Test
    fun `pure NE1 is (Cover Ward, Prepare SDL) — profile (0,1)`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("NE (Cover, Prepare) = (0,1) exists", profiles.any { it == listOf(0, 1) })
    }

    @Test
    fun `pure NE2 is (Prepare SDL, Cover Ward) — profile (1,0)`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("NE (Prepare, Cover) = (1,0) exists", profiles.any { it == listOf(1, 0) })
    }

    @Test
    fun `NE1 payoffs are (4, 8)`() {
        val idx = ir.profileIndex(intArrayOf(0, 1))
        assertEquals("R1 payoff at NE1",  4.0, ir.utility[0][idx], 1e-9)
        assertEquals("R2 payoff at NE1",  8.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `NE2 payoffs are (8, 4)`() {
        val idx = ir.profileIndex(intArrayOf(1, 0))
        assertEquals("R1 payoff at NE2",  8.0, ir.utility[0][idx], 1e-9)
        assertEquals("R2 payoff at NE2",  4.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `(Cover, Cover) is NOT a Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 0.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertFalse("(Cover, Cover) has profitable deviations — not a NE",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol)
        )
    }

    @Test
    fun `(Prepare, Prepare) is NOT a Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertFalse("(Prepare, Prepare) has profitable deviations — not a NE",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol)
        )
    }

    // ── IEDS: no dominant strategies ────────────────────────────────────────

    @Test
    fun `IEDS strict — no strategies eliminated`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = false)
        assertFalse("No strictly dominated strategies in this game", result.anyEliminated)
        assertEquals("Game unchanged", listOf(2, 2), result.ir.strategyCounts)
        assertEquals("Zero IEDS rounds", 0, result.rounds)
    }

    // ── Archetype: CHICKEN (two anti-diagonal NE, no dominant strategies) ───

    @Test
    fun `archetype detected as CHICKEN`() {
        val archetype = GameArchetypeDetector.detect(ir)
        assertEquals(
            "Two anti-diagonal NE with no dominant strategies = CHICKEN",
            GameArchetype.CHICKEN, archetype
        )
    }

    // ── MixedNashNxMSolver finds both pure NE and interior mixed NE ─────────

    @Test
    fun `MixedNashNxMSolver finds at least two equilibria`() {
        val outcome = MixedNashNxMSolver.solve(ir)
        assertTrue("Expected Solved for 2-player game, got $outcome", outcome is SolverOutcome.Solved)

        val eqs = (outcome as SolverOutcome.Solved).equilibria
        assertTrue("At least two equilibria (the two pure NE)", eqs.size >= 2)
    }

    @Test
    fun `MixedNashNxMSolver finds both pure NE profiles`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("Pure NE (0,1) found by MixedNash", profiles.any { it == listOf(0, 1) })
        assertTrue("Pure NE (1,0) found by MixedNash", profiles.any { it == listOf(1, 0) })
    }

    @Test
    fun `interior mixed NE exists with P(Cover) ≈ 14÷19 for each player`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved

        // Mixed NE indifference: R1 indifferent iff R2 plays Cover w/ p = 14/19.
        val mixed = outcome.equilibria.firstOrNull { eq ->
            eq.pureProfile == null &&
            eq.mixedStrategies[0][0] > 0.01 && eq.mixedStrategies[0][0] < 0.99 &&
            eq.mixedStrategies[1][0] > 0.01 && eq.mixedStrategies[1][0] < 0.99
        }
        assertNotNull("Interior mixed-strategy NE must exist for Chicken game", mixed)

        val p1Cover = mixed!!.mixedStrategies[0][0]
        val p2Cover = mixed.mixedStrategies[1][0]

        assertEquals("R1 P(Cover Ward) = 14/19", 14.0 / 19.0, p1Cover, 1e-4)
        assertEquals("R2 P(Cover Ward) = 14/19", 14.0 / 19.0, p2Cover, 1e-4)
        assertEquals("R1 P(Prepare SDL) = 5/19", 5.0 / 19.0, 1.0 - p1Cover,  1e-4)
        assertEquals("R2 P(Prepare SDL) = 5/19", 5.0 / 19.0, 1.0 - p2Cover,  1e-4)
    }

    // ── EquilibriumVerifier confirms both pure NE ────────────────────────────

    @Test
    fun `EquilibriumVerifier adaptive tolerance is 1e-6 (max payoff = 10)`() {
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertEquals("Adaptive tolerance = max(1e-9, 10*1e-7)", 10.0 * 1e-7, tol, 1e-15)
    }

    @Test
    fun `EquilibriumVerifier confirms NE1 (Cover, Prepare)`() {
        val mixed = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("(Cover, Prepare) is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol)
        )
    }

    @Test
    fun `EquilibriumVerifier confirms NE2 (Prepare, Cover)`() {
        val mixed = listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0, 0.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("(Prepare, Cover) is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol)
        )
    }

    @Test
    fun `deviation gain residual near-zero at mixed NE`() {
        val pCover   = 14.0 / 19.0
        val pPrepare = 5.0  / 19.0
        val mixed = listOf(
            doubleArrayOf(pCover, pPrepare),
            doubleArrayOf(pCover, pPrepare)
        )
        val residual = EquilibriumVerifier.deviationGainResidual(ir.utility, ir.strategyCounts, mixed)
        assertTrue("Residual at mixed NE must be < 1e-6, got $residual", residual < 1e-6)
    }

    // ── Full scenario summary ────────────────────────────────────────────────

    @Test
    fun `full scenario summary — two pure NE at off-diagonal, archetype CHICKEN`() {
        val pureOutcome  = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val mixedOutcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val archetype    = GameArchetypeDetector.detect(ir)
        val tol          = EquilibriumVerifier.adaptiveTolerance(ir.utility)

        val pureProfiles = pureOutcome.equilibria.mapNotNull { it.pureProfile }
        val interiorNE   = mixedOutcome.equilibria.firstOrNull { it.pureProfile == null }

        // Assertions
        assertEquals("Two pure NE", 2, pureProfiles.size)
        assertTrue("NE1 = (0,1)", pureProfiles.any { it == listOf(0, 1) })
        assertTrue("NE2 = (1,0)", pureProfiles.any { it == listOf(1, 0) })
        assertEquals("Archetype", GameArchetype.CHICKEN, archetype)
        assertNotNull("Interior mixed NE exists", interiorNE)

        val p1Cover = interiorNE!!.mixedStrategies[0][0]
        val p2Cover = interiorNE.mixedStrategies[1][0]

        println("""
            ═══════════════════════════════════════════════════════
             Resident Ward Cover — Engine Trace
            ═══════════════════════════════════════════════════════
             Scenario:   Two residents sharing on-call burden
             Matrix:     Cover/Prepare × Cover/Prepare
                         ( 3, 3) | ( 4, 8)
                         ( 8, 4) | (-10,-10)

             Archetype:  ${archetype.name}

             IEDS (strict): no strategies eliminated (no dominant strategies)

             Pure Nash Equilibria (2 found):  ✓
               NE1: (Cover Ward, Prepare SDL) → payoffs (4, 8)
               NE2: (Prepare SDL, Cover Ward) → payoffs (8, 4)

               Conflict: R1 prefers NE2 (gets 8), R2 prefers NE1 (gets 8).
               Anti-coordination structure — both prefer the other covers.

             Mixed Strategy Nash Equilibrium:  ✓
               Resident 1: P(Cover Ward) = ${"%.4f".format(p1Cover)} ≈ 14/19
                           P(Prepare SDL) = ${"%.4f".format(1.0 - p1Cover)} ≈ 5/19
               Resident 2: P(Cover Ward) = ${"%.4f".format(p2Cover)} ≈ 14/19
                           P(Prepare SDL) = ${"%.4f".format(1.0 - p2Cover)} ≈ 5/19

             Key insight: Each must cover ~74% of the time to make the other
             indifferent. The 'prepare' option tempts defection but mutual
             preparation is catastrophic — classic Chicken structure.

             Adaptive tolerance: $tol  (max payoff=10, tol=10×1e-7)
            ═══════════════════════════════════════════════════════
        """.trimIndent())
    }
}
