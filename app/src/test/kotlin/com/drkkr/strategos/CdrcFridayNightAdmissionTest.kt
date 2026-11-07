package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end engine trace for the CDRC Friday Night Admission scenario (Chicken / Brinkmanship).
 *
 * Matrix (Psychiatry PG rows, ER Duty Officer columns):
 *                   Push to Ward   Keep in ER
 *   Refuse Bed:    (-20, -20)      (10,  -5)   ← NE1
 *   Admit to Ward: ( -5,  10)      ( 0,   0)
 *                       ↑ NE2
 *
 * Profile index order (row-major, 2×2):
 *   0 = (Refuse, Push)  → Psych=-20, ER=-20   Dean intervenes
 *   1 = (Refuse, Keep)  → Psych= 10, ER=  -5  ← NE1 (Psych wins)
 *   2 = (Admit,  Push)  → Psych= -5, ER=  10  ← NE2 (ER wins)
 *   3 = (Admit,  Keep)  → Psych=  0, ER=   0  Status quo
 *
 * Expected:
 *   Archetype: CHICKEN (two anti-diagonal pure NE, catastrophic mutual aggression)
 *   IEDS strict: no strategies eliminated
 *   Pure NE: (Refuse, Keep) and (Admit, Push)
 *   Mixed NE: P(Refuse) = P(Push) = 2/5
 *
 * Mixed NE derivation (indifference):
 *   Let p = P(Psych plays Refuse), q = P(ER plays Push)
 *   ER indifferent: E[Push] = -20p + 10(1-p) = 10-30p
 *                  E[Keep] = -5p
 *   10 - 30p = -5p  →  p = 2/5
 *   Symmetric game → q = 2/5
 */
class CdrcFridayNightAdmissionTest {

    private val ir = GameIR(
        scenarioId     = "cdrc-friday-night-admission",
        playerIds      = listOf("Psychiatry PG Resident", "ER Duty Officer"),
        actionIds      = listOf(
            listOf("Refuse Bed", "Admit to Ward"),
            listOf("Push to Ward", "Keep in ER")
        ),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf(-20.0, 10.0, -5.0, 0.0),   // Psychiatry PG payoffs
            doubleArrayOf(-20.0, -5.0, 10.0, 0.0)    // ER Duty Officer payoffs
        ),
        gameClass    = GameClass.FINITE_NORMAL_FORM,
        coveragePct  = 100
    )

    // ── Payoff table verification ────────────────────────────────────────────

    @Test
    fun `NE1 (Refuse, Keep) payoffs are (10, -5)`() {
        val idx = ir.profileIndex(intArrayOf(0, 1))
        assertEquals("Psych payoff at (Refuse, Keep)", 10.0, ir.utility[0][idx], 1e-9)
        assertEquals("ER payoff at (Refuse, Keep)", -5.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `NE2 (Admit, Push) payoffs are (-5, 10)`() {
        val idx = ir.profileIndex(intArrayOf(1, 0))
        assertEquals("Psych payoff at (Admit, Push)", -5.0, ir.utility[0][idx], 1e-9)
        assertEquals("ER payoff at (Admit, Push)", 10.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `mutual aggression (Refuse, Push) payoffs are (-20, -20)`() {
        val idx = ir.profileIndex(intArrayOf(0, 0))
        assertEquals("Psych loses", -20.0, ir.utility[0][idx], 1e-9)
        assertEquals("ER loses", -20.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `status quo (Admit, Keep) payoffs are (0, 0)`() {
        val idx = ir.profileIndex(intArrayOf(1, 1))
        assertEquals("Psych status quo", 0.0, ir.utility[0][idx], 1e-9)
        assertEquals("ER status quo", 0.0, ir.utility[1][idx], 1e-9)
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
    fun `pure NE1 is (Refuse Bed, Keep in ER) — profile (0,1)`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("NE (Refuse, Keep) = (0,1) exists", profiles.any { it == listOf(0, 1) })
    }

    @Test
    fun `pure NE2 is (Admit to Ward, Push to Ward) — profile (1,0)`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("NE (Admit, Push) = (1,0) exists", profiles.any { it == listOf(1, 0) })
    }

    @Test
    fun `(Refuse, Push) mutual aggression is NOT a Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 0.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertFalse("Both have profitable deviation away from mutual aggression — not a NE",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    @Test
    fun `(Admit, Keep) status quo is NOT a Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertFalse("Both have profitable deviation to Aggressive — not a NE",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    // ── Mixed NE: P(Aggressive) = 2/5 ───────────────────────────────────────

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
    fun `Psych mixed strategy — P(Refuse) = 2÷5`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val mixed = outcome.equilibria.first { it.pureProfile == null }
        val pPsych = mixed.mixedStrategies[0]
        assertEquals("P(Refuse Bed) = 2/5",    2.0/5.0, pPsych[0], 1e-4)
        assertEquals("P(Admit to Ward) = 3/5", 3.0/5.0, pPsych[1], 1e-4)
    }

    @Test
    fun `ER mixed strategy — P(Push) = 2÷5`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val mixed = outcome.equilibria.first { it.pureProfile == null }
        val pER = mixed.mixedStrategies[1]
        assertEquals("P(Push to Ward) = 2/5",  2.0/5.0, pER[0], 1e-4)
        assertEquals("P(Keep in ER) = 3/5",    3.0/5.0, pER[1], 1e-4)
    }

    // ── EquilibriumVerifier ──────────────────────────────────────────────────

    @Test
    fun `EquilibriumVerifier adaptive tolerance is 2e-6 (max payoff = 20)`() {
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertEquals("tol = max(1e-9, 20*1e-7) = 2e-6", 20.0 * 1e-7, tol, 1e-15)
    }

    @Test
    fun `EquilibriumVerifier confirms (Refuse, Keep) as Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("(Refuse, Keep) is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    @Test
    fun `EquilibriumVerifier confirms (Admit, Push) as Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0, 0.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("(Admit, Push) is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    @Test
    fun `EquilibriumVerifier confirms mixed NE (2÷5, 3÷5)`() {
        val pAggressive = 2.0 / 5.0
        val profile = listOf(
            doubleArrayOf(pAggressive, 1 - pAggressive),
            doubleArrayOf(pAggressive, 1 - pAggressive)
        )
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("(2/5 Aggressive, 3/5 Yield) is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, profile, tol))
    }

    @Test
    fun `deviation gain residual near-zero at mixed NE (2÷5, 3÷5)`() {
        val pAggressive = 2.0 / 5.0
        val profile = listOf(
            doubleArrayOf(pAggressive, 1 - pAggressive),
            doubleArrayOf(pAggressive, 1 - pAggressive)
        )
        val residual = EquilibriumVerifier.deviationGainResidual(ir.utility, ir.strategyCounts, profile)
        assertTrue("Residual must be < 1e-6, got $residual", residual < 1e-6)
    }

    // ── Full scenario summary ────────────────────────────────────────────────

    @Test
    fun `full scenario summary — Chicken, two anti-diagonal NE, P(Aggressive) = 2÷5`() {
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

        val pAggressive = interiorNE!!.mixedStrategies[0][0]
        val pYield      = interiorNE.mixedStrategies[0][1]

        println("""
            ═══════════════════════════════════════════════════════
             CDRC Friday Night Admission — Engine Trace
            ═══════════════════════════════════════════════════════
             Scenario:   Busy Friday night — intoxicated patient in ER.
                         ER Duty Officer wants to push to CDRC ward.
                         Psychiatry PG wants to protect last bed for DTs case.
             Players:    Psychiatry PG Resident (Refuse/Admit)
                         ER Duty Officer (Push/Keep)
             Matrix:     Refuse / Admit × Push / Keep
                         (-20,-20) | (10, -5)
                         ( -5, 10) | ( 0,  0)

             Archetype:  ${archetype.name}
               Two anti-diagonal NE, catastrophic penalty for mutual aggression.
               Classic brinkmanship: whoever commits to aggressive strategy first wins.

             IEDS (strict): no strategies eliminated (no dominant strategies)
               Refusing is better when ER yields; admitting is better when ER pushes.
               No strategy dominates the other — each is conditional on opponent's action.

             Pure Nash Equilibria (2 found):  ✓
               NE1: (Refuse Bed, Keep in ER)   → payoffs (10, -5)   ← Psych wins
               NE2: (Admit to Ward, Push to Ward) → payoffs (-5, 10) ← ER wins

               Coordination problem: each player prefers the NE where THEY play Aggressive.
               First-mover advantage — whoever commits to Aggressive first wins.
               If neither yields → Dean/MS intervenes → both reprimanded (-20, -20).

             Mixed Strategy Nash Equilibrium:  ✓
               Psychiatry PG: P(Refuse Bed)    = ${"%.4f".format(pAggressive)} = 2/5
                              P(Admit to Ward) = ${"%.4f".format(pYield)} = 3/5
               ER Duty Officer: (symmetric)

               Derivation: ER indifferent when Psych plays Refuse w/ prob p:
                 Push: -20p + 10(1-p) = 10 - 30p
                 Keep:  -5p + 0(1-p)  = -5p
                 Solve: 10 - 30p = -5p  →  p = 2/5

             Key insight: At mixed NE, each player escalates 40% of the time.
               The 20% chance of mutual escalation (Dean intervention) is the
               equilibrium cost of brinkmanship. Whoever blinks first loses 5;
               mutual aggression costs both 20 — the asymmetric NE payoffs
               (10, -5) reflect exactly this 'winner-loser' structure.

               Expected payoff at mixed NE:
                 E[Refuse]  = 10 - 30×(2/5) ≈ -2.0
                 E[Admit]   = -5×(2/5) ≈ -2.0  (indifference confirmed)

             Adaptive tolerance: $tol  (max payoff=20, tol=20×1e-7)
            ═══════════════════════════════════════════════════════
        """.trimIndent())
    }
}
