package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end engine trace for the CDRC Stag Hunt scenario.
 *
 * Matrix (Psychiatrist A rows, Psychiatrist B columns):
 *                Pool Data   Case Report
 *   Pool Data:  (20, 20)     ( 0, 15)
 *   Case Report:(15,  0)     (10, 10)
 *
 * Profile index order (row-major):
 *   0 = (PD, PD) → A=20, B=20   ← Payoff-Dominant NE
 *   1 = (PD, CR) → A= 0, B=15
 *   2 = (CR, PD) → A=15, B= 0
 *   3 = (CR, CR) → A=10, B=10   ← Risk-Dominant NE
 *
 * Expected:
 *   Archetype:   STAG_HUNT
 *   Pure NE:     (Pool Data, Pool Data) — payoff-dominant (20,20)
 *                (Case Report, Case Report) — risk-dominant (10,10)
 *   Mixed NE:    P(Pool Data) = 2/3, P(Case Report) = 1/3  (both players)
 *   IEDS:        No strategies eliminated (no dominant strategies)
 *
 * Mixed NE derivation (indifference principle):
 *   A indifferent if B plays PD with prob p:
 *   A_PD = 20p + 0(1-p) = 20p
 *   A_CR = 15p + 10(1-p) = 10 + 5p
 *   Indifference: 20p = 10 + 5p  →  p = 2/3
 */
class StagHuntTest {

    private val ir = GameIR(
        scenarioId     = "cdrc-stag-hunt",
        playerIds      = listOf("Psychiatrist A", "Psychiatrist B"),
        actionIds      = listOf(
            listOf("Pool Data", "Case Report"),
            listOf("Pool Data", "Case Report")
        ),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf(20.0,  0.0, 15.0, 10.0),   // Psychiatrist A payoffs
            doubleArrayOf(20.0, 15.0,  0.0, 10.0)    // Psychiatrist B payoffs
        ),
        gameClass    = GameClass.FINITE_NORMAL_FORM,
        coveragePct  = 100
    )

    // ── Payoff table verification ────────────────────────────────────────────

    @Test
    fun `NE1 (Pool Data, Pool Data) payoffs are (20, 20)`() {
        val idx = ir.profileIndex(intArrayOf(0, 0))
        assertEquals("A payoff at (PD, PD)", 20.0, ir.utility[0][idx], 1e-9)
        assertEquals("B payoff at (PD, PD)", 20.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `NE2 (Case Report, Case Report) payoffs are (10, 10)`() {
        val idx = ir.profileIndex(intArrayOf(1, 1))
        assertEquals("A payoff at (CR, CR)", 10.0, ir.utility[0][idx], 1e-9)
        assertEquals("B payoff at (CR, CR)", 10.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `defection profile (Pool Data, Case Report) payoffs are (0, 15)`() {
        val idx = ir.profileIndex(intArrayOf(0, 1))
        assertEquals("A gets 0 at (PD, CR)", 0.0, ir.utility[0][idx], 1e-9)
        assertEquals("B gets 15 at (PD, CR)", 15.0, ir.utility[1][idx], 1e-9)
    }

    // ── Archetype: STAG_HUNT ─────────────────────────────────────────────────

    @Test
    fun `archetype detected as STAG_HUNT`() {
        val archetype = GameArchetypeDetector.detect(ir)
        assertEquals("Two diagonal NE, cooperation payoff-dominant = STAG_HUNT",
            GameArchetype.STAG_HUNT, archetype)
    }

    // ── IEDS: no dominant strategies ────────────────────────────────────────

    @Test
    fun `IEDS strict — no strategies eliminated`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = false)
        assertFalse("No strictly dominated strategies in Stag Hunt", result.anyEliminated)
        assertEquals("Game unchanged", listOf(2, 2), result.ir.strategyCounts)
        assertEquals("Zero IEDS rounds", 0, result.rounds)
    }

    @Test
    fun `IEDS weak — no strategies eliminated`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = true)
        assertFalse("No weakly dominated strategies in Stag Hunt", result.anyEliminated)
    }

    // ── Pure NE: two on the diagonal ────────────────────────────────────────

    @Test
    fun `two pure Nash equilibria found`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)
        assertTrue("Must find pure NE, got $outcome", outcome is SolverOutcome.Solved)
        val profiles = (outcome as SolverOutcome.Solved).equilibria.mapNotNull { it.pureProfile }
        assertEquals("Exactly two pure NE", 2, profiles.size)
    }

    @Test
    fun `NE1 is (Pool Data, Pool Data) — profile (0,0)`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("Payoff-dominant NE (PD,PD) = (0,0) exists", profiles.any { it == listOf(0, 0) })
    }

    @Test
    fun `NE2 is (Case Report, Case Report) — profile (1,1)`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("Risk-dominant NE (CR,CR) = (1,1) exists", profiles.any { it == listOf(1, 1) })
    }

    @Test
    fun `(Pool Data, Case Report) is NOT a Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertFalse("A deviates to CR for 10 > 0 — not a NE",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    // ── Equilibrium dominance properties ────────────────────────────────────

    @Test
    fun `(PD,PD) is payoff-dominant — highest joint payoff`() {
        val pdIdx = ir.profileIndex(intArrayOf(0, 0))
        val crIdx = ir.profileIndex(intArrayOf(1, 1))
        assertTrue("A payoff: (PD,PD) > (CR,CR)",
            ir.utility[0][pdIdx] > ir.utility[0][crIdx])
        assertTrue("B payoff: (PD,PD) > (CR,CR)",
            ir.utility[1][pdIdx] > ir.utility[1][crIdx])
    }

    @Test
    fun `(CR,CR) is risk-dominant — safer under uncertainty about opponent`() {
        // Risk dominance: CR is best response to 50/50 opponent mixing
        // A_PD vs 50/50 = 20*0.5 + 0*0.5 = 10
        // A_CR vs 50/50 = 15*0.5 + 10*0.5 = 12.5
        // So CR is better against a randomising opponent → risk dominant
        val pUniform = 0.5
        val aPDvsRandom = ir.utility[0][ir.profileIndex(intArrayOf(0,0))] * pUniform +
                          ir.utility[0][ir.profileIndex(intArrayOf(0,1))] * (1-pUniform)
        val aCRvsRandom = ir.utility[0][ir.profileIndex(intArrayOf(1,0))] * pUniform +
                          ir.utility[0][ir.profileIndex(intArrayOf(1,1))] * (1-pUniform)
        assertTrue("CR earns more than PD vs 50/50 opponent (risk dominance)",
            aCRvsRandom > aPDvsRandom)
    }

    // ── Mixed NE via MixedNashNxMSolver ─────────────────────────────────────

    @Test
    fun `MixedNashNxMSolver returns Solved`() {
        val outcome = MixedNashNxMSolver.solve(ir)
        assertTrue("Expected Solved for 2-player game, got $outcome", outcome is SolverOutcome.Solved)
    }

    @Test
    fun `MixedNashNxMSolver finds both pure NE`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("Pure NE (0,0) found", profiles.any { it == listOf(0, 0) })
        assertTrue("Pure NE (1,1) found", profiles.any { it == listOf(1, 1) })
    }

    @Test
    fun `interior mixed NE exists with both strategies weighted`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val mixed = outcome.equilibria.firstOrNull { eq ->
            eq.pureProfile == null &&
            eq.mixedStrategies[0][0] > 0.01 && eq.mixedStrategies[0][0] < 0.99 &&
            eq.mixedStrategies[1][0] > 0.01 && eq.mixedStrategies[1][0] < 0.99
        }
        assertNotNull("Interior mixed NE must exist for Stag Hunt", mixed)
    }

    @Test
    fun `mixed NE — P(Pool Data) ≈ 2÷3 for both players`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val mixed = outcome.equilibria.first { it.pureProfile == null }
        val pA = mixed.mixedStrategies[0]
        val pB = mixed.mixedStrategies[1]
        assertEquals("A: P(Pool Data) = 2/3",   2.0/3.0, pA[0], 1e-4)
        assertEquals("A: P(Case Report) = 1/3", 1.0/3.0, pA[1], 1e-4)
        assertEquals("B: P(Pool Data) = 2/3",   2.0/3.0, pB[0], 1e-4)
        assertEquals("B: P(Case Report) = 1/3", 1.0/3.0, pB[1], 1e-4)
    }

    // ── EquilibriumVerifier ──────────────────────────────────────────────────

    @Test
    fun `EquilibriumVerifier adaptive tolerance is 2e-6 (max payoff = 20)`() {
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertEquals("tol = max(1e-9, 20*1e-7) = 2e-6", 20.0 * 1e-7, tol, 1e-15)
    }

    @Test
    fun `EquilibriumVerifier confirms (PD,PD) as Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 0.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("(PD,PD) is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    @Test
    fun `EquilibriumVerifier confirms (CR,CR) as Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("(CR,CR) is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    @Test
    fun `EquilibriumVerifier confirms mixed NE (2÷3, 1÷3)`() {
        val p = 2.0 / 3.0
        val profile = listOf(doubleArrayOf(p, 1-p), doubleArrayOf(p, 1-p))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("(2/3 PD, 1/3 CR) is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, profile, tol))
    }

    @Test
    fun `deviation gain residual near-zero at mixed NE (2÷3, 1÷3)`() {
        val p = 2.0 / 3.0
        val profile = listOf(doubleArrayOf(p, 1-p), doubleArrayOf(p, 1-p))
        val residual = EquilibriumVerifier.deviationGainResidual(ir.utility, ir.strategyCounts, profile)
        assertTrue("Residual must be < 1e-6, got $residual", residual < 1e-6)
    }

    // ── Full scenario summary ────────────────────────────────────────────────

    @Test
    fun `full scenario summary — Stag Hunt, two pure NE, payoff vs risk dominance`() {
        val pureOutcome  = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val mixedOutcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val archetype    = GameArchetypeDetector.detect(ir)
        val tol          = EquilibriumVerifier.adaptiveTolerance(ir.utility)

        val pureProfiles = pureOutcome.equilibria.mapNotNull { it.pureProfile }
        val interiorNE   = mixedOutcome.equilibria.firstOrNull { it.pureProfile == null }

        assertEquals("Archetype", GameArchetype.STAG_HUNT, archetype)
        assertEquals("Two pure NE", 2, pureProfiles.size)
        assertTrue("NE1 = (PD,PD)", pureProfiles.any { it == listOf(0, 0) })
        assertTrue("NE2 = (CR,CR)", pureProfiles.any { it == listOf(1, 1) })
        assertNotNull("Interior mixed NE exists", interiorNE)

        val pPD = interiorNE!!.mixedStrategies[0][0]
        val pCR = interiorNE.mixedStrategies[0][1]

        println("""
            ═══════════════════════════════════════════════════════
             CDRC Stag Hunt — Engine Trace
            ═══════════════════════════════════════════════════════
             Scenario:   Two psychiatrists choosing publication strategy
             Matrix:     Pool Data / Case Report × (same)
                         (20,20) | ( 0,15)
                         (15, 0) | (10,10)

             Archetype:  ${archetype.name}
               Two diagonal Nash Equilibria — pure coordination game.
               Players benefit from matching strategies but disagree
               which equilibrium to coordinate on.

             IEDS (strict): no strategies eliminated (no dominant strategies)

             Pure Nash Equilibria (2 found):  ✓
               NE1: (Pool Data, Pool Data)     → payoffs (20, 20)  ← PAYOFF-DOMINANT
               NE2: (Case Report, Case Report) → payoffs (10, 10)  ← RISK-DOMINANT

             Payoff Dominance:
               (PD,PD) gives both players 20 — the highest payoff for anyone.
               If both trust each other and cooperate, this is strictly best.

             Risk Dominance:
               (CR,CR) is safer: if the opponent randomises 50/50,
               Case Report yields expected payoff 12.5 vs Pool Data's 10.
               A risk-averse psychiatrist prefers the guaranteed 10 over
               the gamble (20 if partner cooperates, 0 if they defect).

             Mixed Strategy Nash Equilibrium:  ✓
               Psychiatrist A: P(Pool Data) = ${"%.4f".format(pPD)} ≈ 2/3
                               P(Case Report) = ${"%.4f".format(pCR)} ≈ 1/3
               Psychiatrist B: (symmetric by symmetry of the game)

               The mixed NE is the tipping point: if P(cooperation) > 2/3,
               pool data is the unique rational choice; below 2/3, case report.

             Key insight: The core tension is TRUST vs SAFETY.
               Pool data only if you believe the other will also pool.
               Social interventions (pre-commitment, communication) shift
               the probability above the 2/3 threshold.

             Adaptive tolerance: $tol  (max payoff=20, tol=20×1e-7)
            ═══════════════════════════════════════════════════════
        """.trimIndent())
    }
}
