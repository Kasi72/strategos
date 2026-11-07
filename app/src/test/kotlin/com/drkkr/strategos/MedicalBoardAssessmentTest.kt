package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end engine trace for the Medical Board Assessment (Signaling / Screening Game).
 *
 * Matrix (Psychiatrist rows, Parent columns):
 *                         Genuine    Exaggerate
 *   Grant Certificate:   (10,  10)   (-10,  10)
 *   Reject Application:  (-10,  -5)  (  5,  -5)
 *   Mandate Observation: (  0,  -2)  (  0,  -2)
 *
 * Profile index order (row-major, nCols=2):
 *   0 = (Grant,   Genuine)    → Psych= 10, Parent= 10   ← Pure NE1
 *   1 = (Grant,   Exaggerate) → Psych=-10, Parent= 10
 *   2 = (Reject,  Genuine)    → Psych=-10, Parent= -5
 *   3 = (Reject,  Exaggerate) → Psych=  5, Parent= -5   ← Pure NE2
 *   4 = (Mandate, Genuine)    → Psych=  0, Parent= -2
 *   5 = (Mandate, Exaggerate) → Psych=  0, Parent= -2
 *
 * Key structural feature:
 *   Parent payoffs are IDENTICAL for Genuine and Exaggerate at every Psychiatrist strategy.
 *   Parent is therefore always indifferent → any (Psych strategy, any Parent σ) where
 *   Psych has no profitable deviation is a Nash equilibrium.
 *
 * Pure Nash Equilibria:
 *   NE1: (Grant Certificate, Genuine Presentation)   — payoffs (10, 10)
 *   NE2: (Reject Application, Exaggerate Deficits)   — payoffs  (5, -5)
 *
 * IEDS:
 *   Strict: nothing eliminated — Mandate is NOT strictly dominated.
 *           A mix of Grant(p) + Reject(1-p) beats Mandate iff p>½ (vs Genuine)
 *           AND p<⅓ (vs Exaggerate) — impossible.
 *   Weak:   same result — no weakly dominated strategies.
 *
 * Screening / Mixed NE (Mandate as mechanism):
 *   σ ≡ P(Parent plays Genuine).
 *
 *   Indifference conditions for Psychiatrist:
 *     E[Grant]   = 20σ − 10
 *     E[Reject]  =  5 − 15σ
 *     E[Mandate] =  0  (constant — the screening anchor)
 *
 *   Grant ⟺ Mandate at σ = ½  → Parent plays 50/50; Psych mixes Grant + Mandate.
 *   Reject ⟺ Mandate at σ = ⅓  → Parent plays Genuine 33%; Psych mixes Reject + Mandate.
 *
 *   At σ = ½, α = 0.5 (Psych 50% Grant, 50% Mandate):
 *     E_Parent[Genuine] = E_Parent[Exaggerate] = 0.5(10) + 0.5(−2) = 4.0  ← indifferent ✓
 *
 *   At σ = ⅓, β = 0.5 (Psych 50% Reject, 50% Mandate):
 *     E_Parent[Genuine] = E_Parent[Exaggerate] = 0.5(−5) + 0.5(−2) = −3.5 ← indifferent ✓
 *
 * Adaptive tolerance: max payoff = 10 → tol = 10 × 1e-7 = 1e-6.
 */
class MedicalBoardAssessmentTest {

    private val ir = GameIR(
        scenarioId     = "medical-board-assessment",
        playerIds      = listOf("Psychiatrist", "Parent"),
        actionIds      = listOf(
            listOf("Grant Certificate", "Reject Application", "Mandate Observation"),
            listOf("Genuine Presentation", "Exaggerate Deficits")
        ),
        strategyCounts = listOf(3, 2),
        utility = listOf(
            doubleArrayOf( 10.0, -10.0,  -10.0,  5.0,  0.0,  0.0),   // Psychiatrist payoffs
            doubleArrayOf( 10.0,  10.0,   -5.0, -5.0, -2.0, -2.0)    // Parent payoffs
        ),
        gameClass    = GameClass.FINITE_NORMAL_FORM,
        coveragePct  = 100
    )

    // ── Payoff table verification ────────────────────────────────────────────

    @Test
    fun `NE1 (Grant, Genuine) payoffs are (10, 10)`() {
        val idx = ir.profileIndex(intArrayOf(0, 0))
        assertEquals("Psych at (Grant, Genuine)",   10.0, ir.utility[0][idx], 1e-9)
        assertEquals("Parent at (Grant, Genuine)",  10.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `NE2 (Reject, Exaggerate) payoffs are (5, -5)`() {
        val idx = ir.profileIndex(intArrayOf(1, 1))
        assertEquals("Psych at (Reject, Exaggerate)",   5.0, ir.utility[0][idx], 1e-9)
        assertEquals("Parent at (Reject, Exaggerate)", -5.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `(Grant, Exaggerate) payoffs are (-10, 10) — risky approval`() {
        val idx = ir.profileIndex(intArrayOf(0, 1))
        assertEquals("Psych loses utility (-10)", -10.0, ir.utility[0][idx], 1e-9)
        assertEquals("Parent wins grant (10)",     10.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `Mandate payoffs are constant (0, -2) regardless of parent type`() {
        val idxGen  = ir.profileIndex(intArrayOf(2, 0))
        val idxExag = ir.profileIndex(intArrayOf(2, 1))
        assertEquals("Psych at (Mandate, Genuine)",     0.0, ir.utility[0][idxGen],  1e-9)
        assertEquals("Psych at (Mandate, Exaggerate)",  0.0, ir.utility[0][idxExag], 1e-9)
        assertEquals("Parent at (Mandate, Genuine)",   -2.0, ir.utility[1][idxGen],  1e-9)
        assertEquals("Parent at (Mandate, Exaggerate)", -2.0, ir.utility[1][idxExag], 1e-9)
    }

    // ── Structural property: Parent always indifferent ───────────────────────

    @Test
    fun `Parent payoffs equal for Genuine and Exaggerate under every Psych strategy`() {
        for (i in 0 until 3) {
            val idxGen  = ir.profileIndex(intArrayOf(i, 0))
            val idxExag = ir.profileIndex(intArrayOf(i, 1))
            assertEquals(
                "Parent indifferent under Psych strategy $i",
                ir.utility[1][idxGen], ir.utility[1][idxExag], 1e-9
            )
        }
    }

    // ── Archetype ────────────────────────────────────────────────────────────

    @Test
    fun `archetype detected as PURE_COORDINATION for 3x2 game with two diagonal NE`() {
        val archetype = GameArchetypeDetector.detect(ir)
        assertEquals("3×2 game — two pure NE on diagonal = PURE_COORDINATION",
            GameArchetype.PURE_COORDINATION, archetype)
    }

    // ── IEDS: Mandate is NOT dominated ───────────────────────────────────────

    @Test
    fun `IEDS strict — no strategies eliminated (Mandate not strictly dominated)`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = false)
        assertFalse("No strictly dominated strategies in this signaling game", result.anyEliminated)
        assertEquals("3×2 game unchanged", listOf(3, 2), result.ir.strategyCounts)
        assertEquals("Zero IEDS rounds", 0, result.rounds)
    }

    @Test
    fun `IEDS weak — no strategies eliminated`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = true)
        assertFalse("No weakly dominated strategies either", result.anyEliminated)
        assertEquals("3×2 game unchanged", listOf(3, 2), result.ir.strategyCounts)
    }

    @Test
    fun `Grant does not strictly dominate Mandate — loses at Exaggerate`() {
        // Grant: 10 vs Genuine, -10 vs Exaggerate
        // Mandate: 0 vs Genuine, 0 vs Exaggerate
        // -10 < 0 → Grant does NOT dominate Mandate
        val grantVsGen  = ir.utility[0][ir.profileIndex(intArrayOf(0, 0))]  // 10
        val grantVsExag = ir.utility[0][ir.profileIndex(intArrayOf(0, 1))]  // -10
        val mandVsExag  = ir.utility[0][ir.profileIndex(intArrayOf(2, 1))]  // 0
        assertTrue("Grant beats Mandate vs Genuine (10 > 0)", grantVsGen > 0.0)
        assertTrue("Grant loses to Mandate vs Exaggerate (-10 < 0)", grantVsExag < mandVsExag)
    }

    @Test
    fun `Reject does not strictly dominate Mandate — loses at Genuine`() {
        // Reject: -10 vs Genuine, 5 vs Exaggerate
        // Mandate: 0 vs Genuine, 0 vs Exaggerate
        // -10 < 0 → Reject does NOT dominate Mandate
        val rejectVsGen  = ir.utility[0][ir.profileIndex(intArrayOf(1, 0))]  // -10
        val mandVsGen    = ir.utility[0][ir.profileIndex(intArrayOf(2, 0))]  // 0
        assertTrue("Reject loses to Mandate vs Genuine (-10 < 0)", rejectVsGen < mandVsGen)
    }

    // ── Pure NE: two equilibria ───────────────────────────────────────────────

    @Test
    fun `two pure Nash equilibria found`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)
        assertTrue("Must find pure NE, got $outcome", outcome is SolverOutcome.Solved)
        val profiles = (outcome as SolverOutcome.Solved).equilibria.mapNotNull { it.pureProfile }
        assertEquals("Exactly two pure NE", 2, profiles.size)
    }

    @Test
    fun `pure NE1 is (Grant Certificate, Genuine Presentation) — profile (0,0)`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("NE (Grant, Genuine) = (0,0) exists", profiles.any { it == listOf(0, 0) })
    }

    @Test
    fun `pure NE2 is (Reject Application, Exaggerate Deficits) — profile (1,1)`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("NE (Reject, Exaggerate) = (1,1) exists", profiles.any { it == listOf(1, 1) })
    }

    @Test
    fun `(Grant, Exaggerate) is NOT a Nash equilibrium — Psych deviates to Reject`() {
        val mixed = listOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0)
        )
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertFalse("Psych prefers Reject(5) over Grant(-10) vs Exaggerate",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    @Test
    fun `(Reject, Genuine) is NOT a Nash equilibrium — Psych deviates to Grant`() {
        val mixed = listOf(
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(1.0, 0.0)
        )
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertFalse("Psych prefers Grant(10) over Reject(-10) vs Genuine",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    // ── EquilibriumVerifier confirms pure NE ─────────────────────────────────

    @Test
    fun `EquilibriumVerifier adaptive tolerance is 1e-6 (max payoff = 10)`() {
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertEquals("tol = max(1e-9, 10×1e-7) = 1e-6", 10.0 * 1e-7, tol, 1e-15)
    }

    @Test
    fun `EquilibriumVerifier confirms (Grant, Genuine) is Nash equilibrium`() {
        val mixed = listOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(1.0, 0.0)
        )
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("(Grant, Genuine) confirmed NE",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    @Test
    fun `EquilibriumVerifier confirms (Reject, Exaggerate) is Nash equilibrium`() {
        val mixed = listOf(
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 1.0)
        )
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("(Reject, Exaggerate) confirmed NE",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    // ── Screening mechanism: Mandate as equilibrium anchor ───────────────────

    @Test
    fun `at sigma=1÷2 Psych expected payoffs — Grant equals Mandate (0)`() {
        val sigma = 0.5
        val eGrant  = 20.0 * sigma - 10.0
        val eMandate = 0.0
        assertEquals("E[Grant] at σ=½", 0.0, eGrant,   1e-9)
        assertEquals("E[Mandate]",       0.0, eMandate, 1e-9)
        val eReject = 5.0 - 15.0 * sigma
        assertTrue("E[Reject]=-2.5 < E[Mandate]=0 at σ=½", eReject < eMandate)
    }

    @Test
    fun `at sigma=1÷3 Psych expected payoffs — Reject equals Mandate (0)`() {
        val sigma = 1.0 / 3.0
        val eReject  = 5.0 - 15.0 * sigma
        val eMandate = 0.0
        assertEquals("E[Reject] at σ=⅓", 0.0, eReject,  1e-4)
        assertEquals("E[Mandate]",         0.0, eMandate, 1e-9)
        val eGrant = 20.0 * sigma - 10.0
        assertTrue("E[Grant]=-10/3 < E[Mandate]=0 at σ=⅓", eGrant < eMandate)
    }

    @Test
    fun `EquilibriumVerifier confirms screening NE (Grant+Mandate, 50÷50 sigma=1÷2)`() {
        // Psych: 50% Grant, 0% Reject, 50% Mandate; Parent: 50% Genuine
        val mixed = listOf(
            doubleArrayOf(0.5, 0.0, 0.5),
            doubleArrayOf(0.5, 0.5)
        )
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("Grant+Mandate mix at σ=½ is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    @Test
    fun `EquilibriumVerifier confirms screening NE (Reject+Mandate, sigma=1÷3)`() {
        // Psych: 0% Grant, 50% Reject, 50% Mandate; Parent: 1/3 Genuine
        val mixed = listOf(
            doubleArrayOf(0.0, 0.5, 0.5),
            doubleArrayOf(1.0/3.0, 2.0/3.0)
        )
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("Reject+Mandate mix at σ=⅓ is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    @Test
    fun `deviation gain residual near-zero at screening NE (Grant+Mandate, sigma=1÷2)`() {
        val mixed = listOf(
            doubleArrayOf(0.5, 0.0, 0.5),
            doubleArrayOf(0.5, 0.5)
        )
        val residual = EquilibriumVerifier.deviationGainResidual(ir.utility, ir.strategyCounts, mixed)
        assertTrue("Residual < 1e-6 at screening NE, got $residual", residual < 1e-6)
    }

    // ── MixedNashNxMSolver ────────────────────────────────────────────────────

    @Test
    fun `MixedNashNxMSolver returns Solved for 3x2 game`() {
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

    // ── Full scenario summary ────────────────────────────────────────────────

    @Test
    fun `full scenario summary — Mandate as screening mechanism, two pure NE`() {
        val pureOutcome  = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val mixedOutcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val archetype    = GameArchetypeDetector.detect(ir)
        val strictResult = IteratedDominancePruner.prune(ir, weakDominance = false)
        val tol          = EquilibriumVerifier.adaptiveTolerance(ir.utility)

        val pureProfiles  = pureOutcome.equilibria.mapNotNull { it.pureProfile }
        val mixedProfiles = mixedOutcome.equilibria.mapNotNull { it.pureProfile }
        assertEquals("Archetype", GameArchetype.PURE_COORDINATION, archetype)
        assertEquals("Two pure NE", 2, pureProfiles.size)
        assertTrue("NE1 = (0,0)", pureProfiles.any { it == listOf(0, 0) })
        assertTrue("NE2 = (1,1)", pureProfiles.any { it == listOf(1, 1) })
        assertTrue("MixedSolver also finds pure NE", mixedProfiles.isNotEmpty())
        assertFalse("No IEDS eliminations", strictResult.anyEliminated)

        val sigma_half  = 0.5
        val sigma_third = 1.0 / 3.0
        val eGrantHalf   = 20.0 * sigma_half - 10.0
        val eRejectThird = 5.0 - 15.0 * sigma_third

        println("""
            ═══════════════════════════════════════════════════════
             Medical Board Assessment — Engine Trace
            ═══════════════════════════════════════════════════════
             Scenario:   Psychiatrist grants, rejects, or mandates observation; parent
                         may genuinely present deficits or exaggerate them to gain
                         certification for private school funding.

             Matrix:     Grant / Reject / Mandate × Genuine / Exaggerate
                         (10, 10)  (-10, 10)
                         (-10, -5) (  5, -5)
                         (  0, -2) (  0, -2)

             Archetype:  ${archetype.name}
               3×2 asymmetric game. Parent payoffs IDENTICAL for both strategies
               regardless of Psychiatrist choice — parent is structurally indifferent.
               This is a degenerate signaling game: type revelation fails because
               the screening payoffs to the sender are independent of type.

             IEDS (strict dominance): no strategies eliminated
               Mandate is NOT strictly dominated:
                 Grant vs Mandate: 10>0 (Genuine) BUT -10<0 (Exaggerate) → Grant ✗
                 Reject vs Mandate: -10<0 (Genuine) → Reject ✗
                 Mixed Grant+Reject: needs p>½ (vs Genuine) AND p<⅓ (vs Exaggerate) → impossible ✗
               Mandate survives IEDS because it is a hedge against uncertainty.

             Pure Nash Equilibria (2 found):  ✓
               NE1: (Grant Certificate, Genuine Presentation) → payoffs (10, 10)
                    Psych grants accurately; genuine parent wins.
               NE2: (Reject Application, Exaggerate Deficits) → payoffs (5, -5)
                    Psych rejects defensively; exaggerating parent fails.

               Note: Both NE hold because Parent is INDIFFERENT in both — no strictly
               profitable deviation exists for Parent from either column.

             Mandate Observation — Screening Mechanism:  ✓
               Mandate anchors Psych's expected payoff at 0 (independent of parent type).
               This creates two screening equilibrium thresholds:

               σ = P(Parent genuine)

               Grant ⟺ Mandate when σ = ½:
                 E[Grant]  = 20σ − 10 = ${"%.3f".format(eGrantHalf)} (at σ=½)
                 E[Mandate] = 0.000
                 → Psych mixes Grant + Mandate; Parent plays 50/50
                 → E_Parent[Genuine] = E_Parent[Exaggerate] = 0.5(10)+0.5(−2) = 4.0  ✓

               Reject ⟺ Mandate when σ = ⅓:
                 E[Reject]  = 5 − 15σ = ${"%.3f".format(eRejectThird)} (at σ=⅓)
                 E[Mandate] = 0.000
                 → Psych mixes Reject + Mandate; Parent plays Genuine 33.3%
                 → E_Parent[Genuine] = E_Parent[Exaggerate] = 0.5(−5)+0.5(−2) = −3.5  ✓

             Key insight: MANDATE AS COSTLY SIGNAL DETERRENCE
               The mandate imposes a fixed cost −2 on the parent regardless of type.
               Because genuine parents gain 10 (Grant) vs −2 (Mandate), they prefer
               Grant — but exaggerating parents ALSO prefer Grant (10 vs −2).
               The payoff structure gives Mandate equal deterrent effect on both types:
               neither type is separated. This models "observational mandates" that impose
               process costs but do not discriminate between honest and strategic reporters.

               In the screening equilibrium at σ=½:
                 Genuine parent:    prefers Grant (10) over Mandate (−2)
                 Exaggerating parent: also prefers Grant (10) over Mandate (−2)
                 → Mandate cannot separate types; it only provides Psych a safe
                   "zero-payoff hedge" against uncertainty about parent type.

             Mixed equilibria in {Grant+Mandate} and {Reject+Mandate} families:
               Each is a continuum parametrised by Psych's mixing weight α.
               EquilibriumVerifier confirms α=0.5 in each family.

             Adaptive tolerance: $tol  (max payoff=10, tol=10×1e-7)
            ═══════════════════════════════════════════════════════
        """.trimIndent())
    }
}
