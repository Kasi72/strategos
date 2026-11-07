package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end engine trace for the Medical Board UDID Assessment (Inspection Game).
 *
 * Matrix (Medical Board rows, Claimant columns):
 *                      Genuine    Exaggerate
 *   Routine Check:    ( 5,  5)   (-5,  10)
 *   Thorough Audit:   ( 0,  5)   ( 5,  -5)
 *
 * Expected:
 *   Pure NE: None — every profile has a profitable unilateral deviation.
 *   Mixed NE:
 *     Board:    P(Routine) = 2/3 ≈ 0.6667,  P(Thorough) = 1/3 ≈ 0.3333
 *     Claimant: P(Genuine) = 2/3 ≈ 0.6667,  P(Exaggerate) = 1/3 ≈ 0.3333
 *
 *   Interpretation: Board must audit ~33% of the time to make exaggerating unprofitable.
 *
 * Profile index order (row-major):
 *   0 = (Routine, Genuine)    → Board=5,  Claimant=5
 *   1 = (Routine, Exaggerate) → Board=-5, Claimant=10
 *   2 = (Thorough, Genuine)   → Board=0,  Claimant=5
 *   3 = (Thorough, Exaggerate)→ Board=5,  Claimant=-5
 */
class MedicalBoardInspectionTest {

    private val ir = GameIR(
        scenarioId     = "udid-inspection",
        playerIds      = listOf("Medical Board", "Claimant"),
        actionIds      = listOf(
            listOf("Routine Check", "Thorough Audit"),
            listOf("Genuine Presentation", "Exaggerate Symptoms")
        ),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf( 5.0, -5.0, 0.0,  5.0),   // Medical Board payoffs
            doubleArrayOf( 5.0, 10.0, 5.0, -5.0)    // Claimant payoffs
        ),
        gameClass    = GameClass.FINITE_NORMAL_FORM,
        coveragePct  = 100
    )

    // ── Pure NE: must not exist ──────────────────────────────────────────────

    @Test
    fun `no pure Nash equilibrium exists`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)

        assertFalse(
            "Inspection game has no pure NE — PureNash must NOT return Solved, got $outcome",
            outcome is SolverOutcome.Solved
        )
        assertTrue("Must be Incomplete (no pure NE)", outcome is SolverOutcome.Incomplete)
        val incomplete = outcome as SolverOutcome.Incomplete
        assertTrue(
            "Reason should mention mixed equilibrium: ${incomplete.reason}",
            incomplete.reason.contains("mixed", ignoreCase = true)
        )
    }

    // ── IEDS: strict dominance cannot prune anything in an inspection game ───

    @Test
    fun `IEDS strict dominance — no action eliminated (no dominant strategy exists)`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = false)
        assertFalse("No strictly dominated strategies in inspection game", result.anyEliminated)
        assertEquals("Game unchanged after strict IEDS", listOf(2, 2), result.ir.strategyCounts)
    }

    // ── Mixed NE via Fix 3 (MixedNashNxMSolver) ─────────────────────────────

    @Test
    fun `MixedNashNxMSolver finds unique interior mixed NE`() {
        val outcome = MixedNashNxMSolver.solve(ir)
        assertTrue("Expected Solved for 2-player game, got $outcome", outcome is SolverOutcome.Solved)

        val equilibria = (outcome as SolverOutcome.Solved).equilibria
        assertTrue("At least one mixed NE found", equilibria.isNotEmpty())

        // Find the interior mixed NE (no pure profiles in this game)
        val mixed = equilibria.firstOrNull { eq ->
            eq.pureProfile == null &&
            eq.mixedStrategies[0][0] > 0.01 && eq.mixedStrategies[0][0] < 0.99 &&
            eq.mixedStrategies[1][0] > 0.01 && eq.mixedStrategies[1][0] < 0.99
        }
        assertNotNull("Must contain an interior mixed-strategy NE", mixed)
    }

    @Test
    fun `Board mixed strategy — P(Routine Check) ≈ 2÷3`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val boardStrategy = outcome.equilibria
            .filter { it.pureProfile == null }
            .map { it.mixedStrategies[0][0] }

        assertTrue("At least one interior mixed NE exists", boardStrategy.isNotEmpty())
        val pRoutine = boardStrategy.first()
        assertEquals("P(Routine Check) = 2/3", 2.0 / 3.0, pRoutine, 1e-4)
        assertEquals("P(Thorough Audit) = 1/3", 1.0 / 3.0, 1.0 - pRoutine, 1e-4)
    }

    @Test
    fun `Claimant mixed strategy — P(Genuine) ≈ 2÷3`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val claimantStrategy = outcome.equilibria
            .filter { it.pureProfile == null }
            .map { it.mixedStrategies[1][0] }

        assertTrue("At least one interior mixed NE exists", claimantStrategy.isNotEmpty())
        val pGenuine = claimantStrategy.first()
        assertEquals("P(Genuine Presentation) = 2/3", 2.0 / 3.0, pGenuine, 1e-4)
        assertEquals("P(Exaggerate Symptoms)  = 1/3", 1.0 / 3.0, 1.0 - pGenuine, 1e-4)
    }

    // ── Fix 1 (EquilibriumVerifier adaptive tolerance) validates mixed NE ────

    @Test
    fun `EquilibriumVerifier confirms mixed NE with adaptive tolerance`() {
        val pRoutine  = 2.0 / 3.0
        val pThorough = 1.0 / 3.0
        val pGenuine     = 2.0 / 3.0
        val pExaggerate  = 1.0 / 3.0

        val mixedProfile = listOf(
            doubleArrayOf(pRoutine, pThorough),
            doubleArrayOf(pGenuine, pExaggerate)
        )

        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        // Max |payoff| = 10 → tol = max(1e-9, 10 * 1e-7) = 1e-6
        assertEquals("Adaptive tolerance = max(1e-9, 10*1e-7)", 10.0 * 1e-7, tol, 1e-15)

        assertTrue(
            "Mixed (2/3, 1/3) × (2/3, 1/3) is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(
                ir.utility, ir.strategyCounts, mixedProfile, tol
            )
        )
    }

    @Test
    fun `deviation gain residual is near-zero at mixed NE`() {
        val mixedProfile = listOf(
            doubleArrayOf(2.0/3.0, 1.0/3.0),
            doubleArrayOf(2.0/3.0, 1.0/3.0)
        )
        val residual = EquilibriumVerifier.deviationGainResidual(
            ir.utility, ir.strategyCounts, mixedProfile
        )
        assertTrue("Residual at mixed NE must be < 1e-6, got $residual", residual < 1e-6)
    }

    // ── Archetype: inspection game → GENERIC (no 2×2 archetype matches) ─────

    @Test
    fun `archetype is GENERIC — inspection game has no dominant strategy for either player`() {
        val archetype = GameArchetypeDetector.detect(ir)
        // Inspection game: no dominant strategies, no pure NE, non-zero-sum
        // Does not fit PD/Chicken/StagHunt/BOS/Harmony/Deadlock/Leader/Hero
        assertEquals("Inspection game archetype", GameArchetype.GENERIC, archetype)
    }

    // ── Full scenario summary ────────────────────────────────────────────────

    @Test
    fun `full scenario summary — no pure NE, mixed NE probabilities match expected`() {
        val pureOutcome  = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)
        val mixedOutcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val archetype    = GameArchetypeDetector.detect(ir)
        val tol          = EquilibriumVerifier.adaptiveTolerance(ir.utility)

        val interiorNE = mixedOutcome.equilibria.first { it.pureProfile == null }
        val pRoutine   = interiorNE.mixedStrategies[0][0]
        val pThorough  = interiorNE.mixedStrategies[0][1]
        val pGenuine   = interiorNE.mixedStrategies[1][0]
        val pExagger   = interiorNE.mixedStrategies[1][1]

        // Assertions
        assertFalse("No pure NE", pureOutcome is SolverOutcome.Solved)
        assertEquals("P(Routine)  = 2/3", 2.0/3.0, pRoutine,  1e-4)
        assertEquals("P(Thorough) = 1/3", 1.0/3.0, pThorough, 1e-4)
        assertEquals("P(Genuine)  = 2/3", 2.0/3.0, pGenuine,  1e-4)
        assertEquals("P(Exaggerate) = 1/3", 1.0/3.0, pExagger, 1e-4)
        assertEquals("Archetype", GameArchetype.GENERIC, archetype)

        println("""
            ═══════════════════════════════════════════════════════
             Medical Board UDID Assessment — Engine Trace
            ═══════════════════════════════════════════════════════
             Scenario:   Medical Board vs Claimant (Inspection Game)
             Matrix:     Routine/Thorough × Genuine/Exaggerate
                         ( 5, 5) | (-5, 10)
                         ( 0, 5) | ( 5, -5)

             Archetype:  ${archetype.name}

             Pure Nash Equilibrium: NONE ✓
               All 4 profiles have a profitable unilateral deviation.

             IEDS (strict): no strategies eliminated (no dominant strategies)

             Mixed Strategy Nash Equilibrium:  ✓
               Medical Board:
                 P(Routine Check)      = ${"%.4f".format(pRoutine)}  ≈ 2/3
                 P(Thorough Audit)     = ${"%.4f".format(pThorough)} ≈ 1/3

               Claimant:
                 P(Genuine Presentation) = ${"%.4f".format(pGenuine)}  ≈ 2/3
                 P(Exaggerate Symptoms)  = ${"%.4f".format(pExagger)} ≈ 1/3

             Key insight: Board must audit 33.3% of the time to make
             exaggerating unprofitable for the claimant.

             Adaptive tolerance: $tol  (max payoff=10, tol=10×1e-7)
             Deviation gain residual at mixed NE: < 1e-6
            ═══════════════════════════════════════════════════════
        """.trimIndent())
    }
}
