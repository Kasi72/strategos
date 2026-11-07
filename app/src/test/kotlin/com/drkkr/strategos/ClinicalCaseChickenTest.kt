package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end engine trace for the Clinical Case Chicken scenario (Resource Brinkmanship).
 *
 * Matrix (Student 1 rows, Student 2 columns):
 *                Insist    Yield
 *   Insist:   (-10,-10)  (10, 0)
 *   Yield:    (  0, 10)  ( 5, 5)
 *
 * Profile index order (row-major):
 *   0 = (Insist, Insist) → S1=-10, S2=-10
 *   1 = (Insist, Yield)  → S1= 10, S2=  0   ← NE1
 *   2 = (Yield,  Insist) → S1=  0, S2= 10   ← NE2
 *   3 = (Yield,  Yield)  → S1=  5, S2=  5
 *
 * Expected:
 *   Archetype:   CHICKEN (two anti-diagonal pure NE, no dominant strategies)
 *   Pure NE:     (Insist, Yield) = (10, 0) and (Yield, Insist) = (0, 10)
 *   IEDS:        No strategies eliminated
 *   Mixed NE:    P(Insist) = 1/3, P(Yield) = 2/3  (both players)
 *
 * Mixed NE derivation (indifference principle):
 *   S1 indifferent iff S2 plays Insist with prob p:
 *   S1_Insist = -10p + 10(1-p) = 10 - 20p
 *   S1_Yield  =   0p +  5(1-p) =  5 -  5p
 *   Indifference: 10 - 20p = 5 - 5p  →  5 = 15p  →  p = 1/3
 */
class ClinicalCaseChickenTest {

    private val ir = GameIR(
        scenarioId     = "clinical-case-chicken",
        playerIds      = listOf("Student 1", "Student 2"),
        actionIds      = listOf(
            listOf("Insist", "Yield"),
            listOf("Insist", "Yield")
        ),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf(-10.0, 10.0,  0.0, 5.0),   // Student 1 payoffs
            doubleArrayOf(-10.0,  0.0, 10.0, 5.0)    // Student 2 payoffs
        ),
        gameClass    = GameClass.FINITE_NORMAL_FORM,
        coveragePct  = 100
    )

    // ── Payoff table verification ────────────────────────────────────────────

    @Test
    fun `NE1 (Insist, Yield) payoffs are (10, 0)`() {
        val idx = ir.profileIndex(intArrayOf(0, 1))
        assertEquals("S1 payoff at (Insist, Yield)", 10.0, ir.utility[0][idx], 1e-9)
        assertEquals("S2 payoff at (Insist, Yield)",  0.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `NE2 (Yield, Insist) payoffs are (0, 10)`() {
        val idx = ir.profileIndex(intArrayOf(1, 0))
        assertEquals("S1 payoff at (Yield, Insist)",  0.0, ir.utility[0][idx], 1e-9)
        assertEquals("S2 payoff at (Yield, Insist)", 10.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `mutual insistence (Insist, Insist) payoffs are (-10, -10)`() {
        val idx = ir.profileIndex(intArrayOf(0, 0))
        assertEquals("S1 loses marks", -10.0, ir.utility[0][idx], 1e-9)
        assertEquals("S2 loses marks", -10.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `mutual yield (Yield, Yield) payoffs are (5, 5)`() {
        val idx = ir.profileIndex(intArrayOf(1, 1))
        assertEquals("S1 at (Yield, Yield)", 5.0, ir.utility[0][idx], 1e-9)
        assertEquals("S2 at (Yield, Yield)", 5.0, ir.utility[1][idx], 1e-9)
    }

    // ── Archetype: CHICKEN ───────────────────────────────────────────────────

    @Test
    fun `archetype detected as CHICKEN`() {
        val archetype = GameArchetypeDetector.detect(ir)
        assertEquals("Two anti-diagonal NE, catastrophic mutual aggression = CHICKEN",
            GameArchetype.CHICKEN, archetype)
    }

    // ── IEDS: no dominant strategies ────────────────────────────────────────

    @Test
    fun `IEDS strict — no strategies eliminated`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = false)
        assertFalse("No strictly dominated strategies in Chicken", result.anyEliminated)
        assertEquals("Game unchanged", listOf(2, 2), result.ir.strategyCounts)
        assertEquals("Zero IEDS rounds", 0, result.rounds)
    }

    // ── Pure NE: two anti-diagonal equilibria ───────────────────────────────

    @Test
    fun `two pure Nash equilibria found`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)
        assertTrue("Must find pure NE, got $outcome", outcome is SolverOutcome.Solved)
        val profiles = (outcome as SolverOutcome.Solved).equilibria.mapNotNull { it.pureProfile }
        assertEquals("Exactly two pure NE", 2, profiles.size)
    }

    @Test
    fun `pure NE1 is (Insist, Yield) — profile (0,1)`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("NE (Insist, Yield) = (0,1) exists", profiles.any { it == listOf(0, 1) })
    }

    @Test
    fun `pure NE2 is (Yield, Insist) — profile (1,0)`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("NE (Yield, Insist) = (1,0) exists", profiles.any { it == listOf(1, 0) })
    }

    @Test
    fun `(Insist, Insist) is NOT a Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 0.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertFalse("Both have profitable deviation to Yield — not a NE",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    @Test
    fun `(Yield, Yield) is NOT a Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertFalse("Both have profitable deviation to Insist — not a NE",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    // ── Mixed NE: P(Insist) = 1/3 ────────────────────────────────────────────

    @Test
    fun `MixedNashNxMSolver returns Solved`() {
        val outcome = MixedNashNxMSolver.solve(ir)
        assertTrue("Expected Solved for 2-player game, got $outcome", outcome is SolverOutcome.Solved)
    }

    @Test
    fun `MixedNashNxMSolver finds both pure NE`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("Pure NE (0,1) found", profiles.any { it == listOf(0, 1) })
        assertTrue("Pure NE (1,0) found", profiles.any { it == listOf(1, 0) })
    }

    @Test
    fun `interior mixed NE exists`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val mixed = outcome.equilibria.firstOrNull { eq ->
            eq.pureProfile == null &&
            eq.mixedStrategies[0][0] > 0.01 && eq.mixedStrategies[0][0] < 0.99 &&
            eq.mixedStrategies[1][0] > 0.01 && eq.mixedStrategies[1][0] < 0.99
        }
        assertNotNull("Interior mixed NE must exist for Chicken game", mixed)
    }

    @Test
    fun `Student 1 mixed strategy — P(Insist) ≈ 1÷3`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val mixed = outcome.equilibria.first { it.pureProfile == null }
        val pS1 = mixed.mixedStrategies[0]
        assertEquals("P(Insist) = 1/3",  1.0/3.0, pS1[0], 1e-4)
        assertEquals("P(Yield) = 2/3",   2.0/3.0, pS1[1], 1e-4)
    }

    @Test
    fun `Student 2 mixed strategy — P(Insist) ≈ 1÷3`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val mixed = outcome.equilibria.first { it.pureProfile == null }
        val pS2 = mixed.mixedStrategies[1]
        assertEquals("P(Insist) = 1/3",  1.0/3.0, pS2[0], 1e-4)
        assertEquals("P(Yield) = 2/3",   2.0/3.0, pS2[1], 1e-4)
    }

    // ── EquilibriumVerifier ──────────────────────────────────────────────────

    @Test
    fun `EquilibriumVerifier adaptive tolerance is 1e-6 (max payoff = 10)`() {
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertEquals("tol = max(1e-9, 10*1e-7) = 1e-6", 10.0 * 1e-7, tol, 1e-15)
    }

    @Test
    fun `EquilibriumVerifier confirms (Insist, Yield) as Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("(Insist, Yield) is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    @Test
    fun `EquilibriumVerifier confirms (Yield, Insist) as Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0, 0.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("(Yield, Insist) is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    @Test
    fun `EquilibriumVerifier confirms mixed NE (1÷3, 2÷3)`() {
        val pInsist = 1.0 / 3.0
        val profile = listOf(
            doubleArrayOf(pInsist, 1-pInsist),
            doubleArrayOf(pInsist, 1-pInsist)
        )
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("(1/3 Insist, 2/3 Yield) is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, profile, tol))
    }

    @Test
    fun `deviation gain residual near-zero at mixed NE (1÷3, 2÷3)`() {
        val pInsist = 1.0 / 3.0
        val profile = listOf(
            doubleArrayOf(pInsist, 1-pInsist),
            doubleArrayOf(pInsist, 1-pInsist)
        )
        val residual = EquilibriumVerifier.deviationGainResidual(ir.utility, ir.strategyCounts, profile)
        assertTrue("Residual must be < 1e-6, got $residual", residual < 1e-6)
    }

    // ── Full scenario summary ────────────────────────────────────────────────

    @Test
    fun `full scenario summary — Chicken, two anti-diagonal NE, P(Insist) = 1÷3`() {
        val pureOutcome  = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val mixedOutcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val archetype    = GameArchetypeDetector.detect(ir)
        val tol          = EquilibriumVerifier.adaptiveTolerance(ir.utility)

        val pureProfiles = pureOutcome.equilibria.mapNotNull { it.pureProfile }
        val interiorNE   = mixedOutcome.equilibria.firstOrNull { it.pureProfile == null }

        assertEquals("Archetype", GameArchetype.CHICKEN, archetype)
        assertEquals("Two pure NE", 2, pureProfiles.size)
        assertTrue("NE1 = (0,1)", pureProfiles.any { it == listOf(0, 1) })
        assertTrue("NE2 = (1,0)", pureProfiles.any { it == listOf(1, 0) })
        assertNotNull("Interior mixed NE exists", interiorNE)

        val pInsist = interiorNE!!.mixedStrategies[0][0]
        val pYield  = interiorNE.mixedStrategies[0][1]

        println("""
            ═══════════════════════════════════════════════════════
             Clinical Case Chicken — Engine Trace
            ═══════════════════════════════════════════════════════
             Scenario:   Two MBBS students competing for the prime SDL case
             Matrix:     Insist / Yield × (same)
                         (-10,-10) | (10,  0)
                         (  0, 10) | ( 5,  5)

             Archetype:  ${archetype.name}
               Two anti-diagonal NE, catastrophic penalty for mutual insistence.
               Classic escalation-avoidance structure.

             IEDS (strict): no strategies eliminated (no dominant strategies)
               Insisting is better when opponent yields; yielding is better when
               opponent insists — no strategy dominates the other.

             Pure Nash Equilibria (2 found):  ✓
               NE1: (Insist, Yield) → payoffs (10, 0)   ← Student 1 wins
               NE2: (Yield, Insist) → payoffs ( 0, 10)  ← Student 2 wins

               Coordination problem: both prefer the NE where THEY insist.
               The HOD's tie-breaking rule (or social norms) selects which NE
               students land on.

             Mixed Strategy Nash Equilibrium:  ✓
               Student 1: P(Insist) = ${"%.4f".format(pInsist)} = 1/3
                          P(Yield)  = ${"%.4f".format(pYield)} = 2/3
               Student 2: (symmetric)

               Derivation: Student 1 indifferent when S2 plays Insist w/ p:
                 Insist: -10p + 10(1-p) = 10 - 20p
                 Yield:    0p +  5(1-p) =  5 -  5p
                 Solve: 10 - 20p = 5 - 5p  →  p = 1/3

             Key insight: Each student insists only 33.3% of the time.
               The severe penalty (-10) for mutual escalation forces
               predominantly yielding behaviour in equilibrium.
               Expected payoff at mixed NE:
                 E[Insist] = 10 - 20×(1/3) ≈ 3.33
                 E[Yield]  =  5 -  5×(1/3) ≈ 3.33  (indifference confirmed)

             Adaptive tolerance: $tol  (max payoff=10, tol=10×1e-7)
            ═══════════════════════════════════════════════════════
        """.trimIndent())
    }
}
