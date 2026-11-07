package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end engine trace for the Regional Political Campaign Allocation scenario (Zero-Sum Game).
 *
 * Matrix (Incumbent rows, Challenger columns):
 *                          Focus Healthcare    Focus Infrastructure
 *   Focus Healthcare:         ( 5, -5)              (-5,  5)
 *   Focus Infrastructure:     (-5,  5)              ( 5, -5)
 *
 * Profile index order (row-major):
 *   0 = (HC,    HC)    → Incumbent= 5, Challenger=-5
 *   1 = (HC,    Infra) → Incumbent=-5, Challenger= 5
 *   2 = (Infra, HC)    → Incumbent=-5, Challenger= 5
 *   3 = (Infra, Infra) → Incumbent= 5, Challenger=-5
 *
 * Expected:
 *   Archetype:   ZERO_SUM  (payoffs sum to 0 at every profile)
 *   Pure NE:     None — Incumbent wants to match, Challenger wants to mismatch.
 *   Mixed NE:    (50%, 50%) for both players.
 *   Game value:  0  (sum of mixed-strategy expected payoffs = 0)
 *   IEDS:        No strategies eliminated (symmetric game, no dominance).
 */
class RegionalCampaignAllocationTest {

    private val ir = GameIR(
        scenarioId     = "regional-campaign-allocation",
        playerIds      = listOf("Incumbent Party", "Challenger Party"),
        actionIds      = listOf(
            listOf("Focus Healthcare", "Focus Infrastructure"),
            listOf("Focus Healthcare", "Focus Infrastructure")
        ),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf( 5.0, -5.0, -5.0,  5.0),   // Incumbent payoffs
            doubleArrayOf(-5.0,  5.0,  5.0, -5.0)    // Challenger payoffs
        ),
        gameClass    = GameClass.ZERO_SUM_2P,
        coveragePct  = 100
    )

    // ── Zero-sum verification ────────────────────────────────────────────────

    @Test
    fun `all profiles sum to zero (zero-sum game)`() {
        for (i in 0 until ir.strategyCounts[0]) {
            for (j in 0 until ir.strategyCounts[1]) {
                val idx = ir.profileIndex(intArrayOf(i, j))
                val sum = ir.utility[0][idx] + ir.utility[1][idx]
                assertEquals("Profile ($i,$j) must sum to 0", 0.0, sum, 1e-9)
            }
        }
    }

    // ── Archetype: ZERO_SUM ──────────────────────────────────────────────────

    @Test
    fun `archetype detected as ZERO_SUM`() {
        val archetype = GameArchetypeDetector.detect(ir)
        assertEquals("GameClass.ZERO_SUM_2P triggers ZERO_SUM archetype immediately",
            GameArchetype.ZERO_SUM, archetype)
    }

    // ── Pure NE: must not exist ──────────────────────────────────────────────

    @Test
    fun `no pure Nash equilibrium exists`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)
        assertFalse("Zero-sum matching-pennies has no pure NE", outcome is SolverOutcome.Solved)
        assertTrue("Must be Incomplete", outcome is SolverOutcome.Incomplete)
    }

    // ── IEDS: symmetric game, no dominant strategies ─────────────────────────

    @Test
    fun `IEDS strict — no strategies eliminated`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = false)
        assertFalse("No strictly dominated strategies in symmetric zero-sum game", result.anyEliminated)
        assertEquals("Game unchanged after IEDS", listOf(2, 2), result.ir.strategyCounts)
        assertEquals("Zero IEDS rounds", 0, result.rounds)
    }

    // ── Mixed NE: (50%, 50%) for both players ───────────────────────────────

    @Test
    fun `MixedNashNxMSolver finds interior mixed NE`() {
        val outcome = MixedNashNxMSolver.solve(ir)
        assertTrue("Expected Solved for 2-player zero-sum game, got $outcome",
            outcome is SolverOutcome.Solved)

        val mixed = (outcome as SolverOutcome.Solved).equilibria.firstOrNull { eq ->
            eq.pureProfile == null &&
            eq.mixedStrategies[0][0] > 0.01 && eq.mixedStrategies[0][0] < 0.99 &&
            eq.mixedStrategies[1][0] > 0.01 && eq.mixedStrategies[1][0] < 0.99
        }
        assertNotNull("Interior mixed NE must exist", mixed)
    }

    @Test
    fun `Incumbent mixed strategy — P(Healthcare) = 50%`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val mixed = outcome.equilibria.first { it.pureProfile == null }
        assertEquals("P(Focus Healthcare) = 0.5", 0.5, mixed.mixedStrategies[0][0], 1e-4)
        assertEquals("P(Focus Infrastructure) = 0.5", 0.5, mixed.mixedStrategies[0][1], 1e-4)
    }

    @Test
    fun `Challenger mixed strategy — P(Healthcare) = 50%`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val mixed = outcome.equilibria.first { it.pureProfile == null }
        assertEquals("P(Focus Healthcare) = 0.5", 0.5, mixed.mixedStrategies[1][0], 1e-4)
        assertEquals("P(Focus Infrastructure) = 0.5", 0.5, mixed.mixedStrategies[1][1], 1e-4)
    }

    // ── Game value = 0 (minimax theorem) ────────────────────────────────────

    @Test
    fun `game value is 0 at mixed NE (minimax theorem)`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val mixed = outcome.equilibria.first { it.pureProfile == null }

        // Expected payoff = sum over profiles of product of mixed probs × utility
        val p0 = mixed.mixedStrategies[0]   // Incumbent
        val p1 = mixed.mixedStrategies[1]   // Challenger
        var expectedIncumbent  = 0.0
        var expectedChallenger = 0.0
        for (i in 0 until ir.strategyCounts[0]) {
            for (j in 0 until ir.strategyCounts[1]) {
                val idx  = ir.profileIndex(intArrayOf(i, j))
                val prob = p0[i] * p1[j]
                expectedIncumbent  += prob * ir.utility[0][idx]
                expectedChallenger += prob * ir.utility[1][idx]
            }
        }
        assertEquals("Incumbent expected payoff (game value) = 0", 0.0, expectedIncumbent,  1e-9)
        assertEquals("Challenger expected payoff = 0",             0.0, expectedChallenger, 1e-9)
    }

    // ── EquilibriumVerifier confirms (50/50, 50/50) ──────────────────────────

    @Test
    fun `EquilibriumVerifier confirms (50%, 50%) × (50%, 50%) is Nash equilibrium`() {
        val mixedProfile = listOf(
            doubleArrayOf(0.5, 0.5),
            doubleArrayOf(0.5, 0.5)
        )
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        // Max |payoff| = 5 → tol = max(1e-9, 5*1e-7) = 5e-7
        assertEquals("Adaptive tolerance = 5e-7", 5.0 * 1e-7, tol, 1e-15)

        assertTrue("(50/50, 50/50) is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixedProfile, tol)
        )
    }

    @Test
    fun `deviation gain residual near-zero at (50%, 50%)`() {
        val mixedProfile = listOf(doubleArrayOf(0.5, 0.5), doubleArrayOf(0.5, 0.5))
        val residual = EquilibriumVerifier.deviationGainResidual(
            ir.utility, ir.strategyCounts, mixedProfile
        )
        assertTrue("Residual at mixed NE must be < 1e-6, got $residual", residual < 1e-6)
    }

    @Test
    fun `pure (Healthcare, Healthcare) is NOT a Nash equilibrium`() {
        val mixedProfile = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 0.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertFalse("Challenger deviates to Infra for payoff 5 — not a NE",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixedProfile, tol)
        )
    }

    // ── Full scenario summary ────────────────────────────────────────────────

    @Test
    fun `full scenario summary — zero-sum, 50-50 mixed NE, game value 0`() {
        val pureOutcome  = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)
        val mixedOutcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val archetype    = GameArchetypeDetector.detect(ir)
        val tol          = EquilibriumVerifier.adaptiveTolerance(ir.utility)

        val interiorNE = mixedOutcome.equilibria.first { it.pureProfile == null }
        val pIncHC   = interiorNE.mixedStrategies[0][0]
        val pIncInf  = interiorNE.mixedStrategies[0][1]
        val pChaHC   = interiorNE.mixedStrategies[1][0]
        val pChaInf  = interiorNE.mixedStrategies[1][1]

        // Compute game value
        var gameValue = 0.0
        for (i in 0 until ir.strategyCounts[0]) {
            for (j in 0 until ir.strategyCounts[1]) {
                val idx = ir.profileIndex(intArrayOf(i, j))
                gameValue += interiorNE.mixedStrategies[0][i] *
                             interiorNE.mixedStrategies[1][j] *
                             ir.utility[0][idx]
            }
        }

        // Assertions
        assertFalse("No pure NE", pureOutcome is SolverOutcome.Solved)
        assertEquals("Archetype", GameArchetype.ZERO_SUM, archetype)
        assertEquals("P(HC) Incumbent = 0.5",  0.5, pIncHC,  1e-4)
        assertEquals("P(HC) Challenger = 0.5", 0.5, pChaHC,  1e-4)
        assertEquals("Game value = 0",         0.0, gameValue, 1e-9)

        println("""
            ═══════════════════════════════════════════════════════
             Regional Campaign Allocation — Engine Trace
            ═══════════════════════════════════════════════════════
             Scenario:   Incumbent vs Challenger — swing constituency
             Matrix:     HC/Infra × HC/Infra
                         ( 5,-5) | (-5, 5)
                         (-5, 5) | ( 5,-5)

             Archetype:  ${archetype.name}

             IEDS (strict): no strategies eliminated (pure conflict)

             Pure Nash Equilibrium: NONE ✓
               Incumbent wants to MATCH Challenger's choice.
               Challenger wants to MISMATCH Incumbent's choice.
               No profile is stable against unilateral deviation.

             Mixed Strategy Nash Equilibrium:  ✓
               Incumbent Party:
                 P(Focus Healthcare)      = ${"%.4f".format(pIncHC)}  = 50%
                 P(Focus Infrastructure)  = ${"%.4f".format(pIncInf)}  = 50%

               Challenger Party:
                 P(Focus Healthcare)      = ${"%.4f".format(pChaHC)}  = 50%
                 P(Focus Infrastructure)  = ${"%.4f".format(pChaInf)}  = 50%

             Game Value (Minimax Theorem): ${"%.6f".format(gameValue)}  ✓
               Expected payoff = 0 — game is perfectly balanced.
               Neither party can guarantee a positive expected return.

             Key insight: Pure conflict with no dominant strategies forces
             randomisation. The unique equilibrium is maximum unpredictability
             — each party randomises 50/50 to deny the opponent any edge.

             Adaptive tolerance: $tol  (max payoff=5, tol=5×1e-7)
             Deviation gain residual at mixed NE: < 1e-6
            ═══════════════════════════════════════════════════════
        """.trimIndent())
    }
}
