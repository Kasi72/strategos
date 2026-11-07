package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end engine trace for the Algorithmic Trading scenario (3×3 Cyclic Zero-Sum / RPS-structure).
 *
 * Matrix (Trader A rows, Trader B columns):
 *                  Supertrend    Bollinger    VWAP
 *   Supertrend:    ( 0,  0)      (-5,  5)     ( 5, -5)
 *   Bollinger:     ( 5, -5)      ( 0,  0)     (-5,  5)
 *   VWAP:          (-5,  5)      ( 5, -5)     ( 0,  0)
 *
 * Profile index order (row-major, nCols=3):
 *   0 = (ST, ST) → A= 0, B= 0
 *   1 = (ST, BB) → A=-5, B= 5
 *   2 = (ST, VP) → A= 5, B=-5
 *   3 = (BB, ST) → A= 5, B=-5
 *   4 = (BB, BB) → A= 0, B= 0
 *   5 = (BB, VP) → A=-5, B= 5
 *   6 = (VP, ST) → A=-5, B= 5
 *   7 = (VP, BB) → A= 5, B=-5
 *   8 = (VP, VP) → A= 0, B= 0
 *
 * Expected:
 *   Archetype:   ZERO_SUM  (GameClass.ZERO_SUM_2P fast-path)
 *   IEDS:        No strategies eliminated (cyclic structure — no dominance)
 *   Pure NE:     None (cyclic: ST beats BB, BB beats VP, VP beats ST)
 *   Mixed NE:    (1/3, 1/3, 1/3) for both players
 *   Game value:  0  (symmetric skew-symmetric matrix, minimax theorem)
 */
class AlgorithmicTradingTest {

    private val ir = GameIR(
        scenarioId     = "algorithmic-trading",
        playerIds      = listOf("Trader A", "Trader B"),
        actionIds      = listOf(
            listOf("Supertrend", "Bollinger Bands", "VWAP"),
            listOf("Supertrend", "Bollinger Bands", "VWAP")
        ),
        strategyCounts = listOf(3, 3),
        utility = listOf(
            doubleArrayOf( 0.0, -5.0,  5.0,   5.0,  0.0, -5.0,   -5.0,  5.0,  0.0),  // Trader A
            doubleArrayOf( 0.0,  5.0, -5.0,  -5.0,  0.0,  5.0,    5.0, -5.0,  0.0)   // Trader B
        ),
        gameClass    = GameClass.ZERO_SUM_2P,
        coveragePct  = 100
    )

    // ── Zero-sum verification ────────────────────────────────────────────────

    @Test
    fun `all 9 profiles sum to zero (zero-sum game)`() {
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
        assertEquals("GameClass.ZERO_SUM_2P must trigger ZERO_SUM archetype immediately",
            GameArchetype.ZERO_SUM, archetype)
    }

    // ── IEDS: cyclic structure — nothing dominated ───────────────────────────

    @Test
    fun `IEDS strict — no strategies eliminated (cyclic dominance)`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = false)
        assertFalse("No strictly dominated strategy in RPS-like game", result.anyEliminated)
        assertEquals("3×3 game unchanged", listOf(3, 3), result.ir.strategyCounts)
        assertEquals("Zero IEDS rounds", 0, result.rounds)
    }

    @Test
    fun `IEDS weak — no strategies eliminated (cyclic dominance)`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = true)
        assertFalse("No weakly dominated strategy in RPS-like game", result.anyEliminated)
        assertEquals("3×3 game unchanged", listOf(3, 3), result.ir.strategyCounts)
    }

    // ── Pure NE: must not exist ──────────────────────────────────────────────

    @Test
    fun `no pure Nash equilibrium exists`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)
        assertFalse("Cyclic zero-sum has no pure NE, got $outcome", outcome is SolverOutcome.Solved)
        assertTrue("Must be Incomplete", outcome is SolverOutcome.Incomplete)
    }

    @Test
    fun `EquilibriumVerifier rejects all pure profiles`() {
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                val pure = listOf(
                    DoubleArray(3).also { it[i] = 1.0 },
                    DoubleArray(3).also { it[j] = 1.0 }
                )
                assertFalse("Pure profile ($i,$j) must not be a NE",
                    EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, pure, tol)
                )
            }
        }
    }

    // ── Mixed NE via MixedNashNxMSolver ─────────────────────────────────────

    @Test
    fun `MixedNashNxMSolver returns Solved for 2-player 3x3 zero-sum game`() {
        val outcome = MixedNashNxMSolver.solve(ir)
        assertTrue("Expected Solved, got $outcome", outcome is SolverOutcome.Solved)
    }

    @Test
    fun `interior mixed NE exists with all strategies weighted`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val mixed = outcome.equilibria.firstOrNull { eq ->
            eq.pureProfile == null &&
            eq.mixedStrategies[0].all { it > 0.01 } &&
            eq.mixedStrategies[1].all { it > 0.01 }
        }
        assertNotNull("Must find interior 3-strategy mixed NE", mixed)
    }

    @Test
    fun `Trader A mixed strategy — P(each) ≈ 1÷3`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val mixed = outcome.equilibria.first { it.pureProfile == null }
        val pA = mixed.mixedStrategies[0]
        assertEquals("P(Supertrend) = 1/3",    1.0 / 3.0, pA[0], 1e-4)
        assertEquals("P(Bollinger Bands) = 1/3", 1.0 / 3.0, pA[1], 1e-4)
        assertEquals("P(VWAP) = 1/3",           1.0 / 3.0, pA[2], 1e-4)
    }

    @Test
    fun `Trader B mixed strategy — P(each) ≈ 1÷3`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val mixed = outcome.equilibria.first { it.pureProfile == null }
        val pB = mixed.mixedStrategies[1]
        assertEquals("P(Supertrend) = 1/3",    1.0 / 3.0, pB[0], 1e-4)
        assertEquals("P(Bollinger Bands) = 1/3", 1.0 / 3.0, pB[1], 1e-4)
        assertEquals("P(VWAP) = 1/3",           1.0 / 3.0, pB[2], 1e-4)
    }

    // ── Game value = 0 (minimax theorem) ────────────────────────────────────

    @Test
    fun `game value is 0 at mixed NE (minimax theorem)`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val mixed = outcome.equilibria.first { it.pureProfile == null }

        var expectedA = 0.0
        var expectedB = 0.0
        for (i in 0 until ir.strategyCounts[0]) {
            for (j in 0 until ir.strategyCounts[1]) {
                val idx  = ir.profileIndex(intArrayOf(i, j))
                val prob = mixed.mixedStrategies[0][i] * mixed.mixedStrategies[1][j]
                expectedA += prob * ir.utility[0][idx]
                expectedB += prob * ir.utility[1][idx]
            }
        }
        assertEquals("Trader A expected payoff (game value) = 0", 0.0, expectedA, 1e-9)
        assertEquals("Trader B expected payoff = 0",             0.0, expectedB, 1e-9)
    }

    // ── EquilibriumVerifier confirms (1/3, 1/3, 1/3) ────────────────────────

    @Test
    fun `EquilibriumVerifier adaptive tolerance is 5e-7 (max payoff = 5)`() {
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertEquals("tol = max(1e-9, 5*1e-7) = 5e-7", 5.0 * 1e-7, tol, 1e-15)
    }

    @Test
    fun `EquilibriumVerifier confirms (1÷3, 1÷3, 1÷3) × (1÷3, 1÷3, 1÷3) is Nash`() {
        val p = 1.0 / 3.0
        val profile = listOf(
            doubleArrayOf(p, p, p),
            doubleArrayOf(p, p, p)
        )
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("(1/3, 1/3, 1/3) must be a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, profile, tol)
        )
    }

    @Test
    fun `deviation gain residual near-zero at (1÷3, 1÷3, 1÷3)`() {
        val p = 1.0 / 3.0
        val profile = listOf(doubleArrayOf(p, p, p), doubleArrayOf(p, p, p))
        val residual = EquilibriumVerifier.deviationGainResidual(ir.utility, ir.strategyCounts, profile)
        assertTrue("Residual must be < 1e-6, got $residual", residual < 1e-6)
    }

    // ── Full scenario summary ────────────────────────────────────────────────

    @Test
    fun `full scenario summary — 3x3 cyclic zero-sum, uniform mixed NE, game value 0`() {
        val pureOutcome  = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)
        val mixedOutcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val archetype    = GameArchetypeDetector.detect(ir)
        val iedsResult   = IteratedDominancePruner.prune(ir, weakDominance = false)
        val tol          = EquilibriumVerifier.adaptiveTolerance(ir.utility)

        val interiorNE = mixedOutcome.equilibria.first { it.pureProfile == null }
        val pA = interiorNE.mixedStrategies[0]
        val pB = interiorNE.mixedStrategies[1]

        // Compute game value
        var gameValue = 0.0
        for (i in 0 until ir.strategyCounts[0]) {
            for (j in 0 until ir.strategyCounts[1]) {
                val idx = ir.profileIndex(intArrayOf(i, j))
                gameValue += pA[i] * pB[j] * ir.utility[0][idx]
            }
        }

        // Assertions
        assertFalse("No pure NE", pureOutcome is SolverOutcome.Solved)
        assertEquals("Archetype", GameArchetype.ZERO_SUM, archetype)
        assertFalse("No IEDS eliminations", iedsResult.anyEliminated)
        assertEquals("P(ST) Trader A = 1/3",  1.0/3.0, pA[0], 1e-4)
        assertEquals("P(BB) Trader A = 1/3",  1.0/3.0, pA[1], 1e-4)
        assertEquals("P(VP) Trader A = 1/3",  1.0/3.0, pA[2], 1e-4)
        assertEquals("P(ST) Trader B = 1/3",  1.0/3.0, pB[0], 1e-4)
        assertEquals("P(BB) Trader B = 1/3",  1.0/3.0, pB[1], 1e-4)
        assertEquals("P(VP) Trader B = 1/3",  1.0/3.0, pB[2], 1e-4)
        assertEquals("Game value = 0",         0.0, gameValue, 1e-9)

        println("""
            ═══════════════════════════════════════════════════════
             Algorithmic Trading — Engine Trace
            ═══════════════════════════════════════════════════════
             Scenario:   Two algorithmic traders competing for alpha
             Matrix:     Supertrend / Bollinger Bands / VWAP  (3×3)
                         ( 0, 0) | (-5, 5) | ( 5,-5)
                         ( 5,-5) | ( 0, 0) | (-5, 5)
                         (-5, 5) | ( 5,-5) | ( 0, 0)

             Archetype:  ${archetype.name}  (GameClass.ZERO_SUM_2P fast-path)

             IEDS (strict): no strategies eliminated
               Cyclic dominance: Supertrend beats Bollinger, Bollinger beats VWAP,
               VWAP beats Supertrend — no strategy dominated for any player.

             Pure Nash Equilibrium: NONE ✓
               Every pure profile has a profitable unilateral deviation.
               Cyclic structure (identical to Rock-Paper-Scissors).

             Mixed Strategy Nash Equilibrium:  ✓
               Trader A:
                 P(Supertrend)      = ${"%.4f".format(pA[0])}  = 33.3%
                 P(Bollinger Bands) = ${"%.4f".format(pA[1])}  = 33.3%
                 P(VWAP)            = ${"%.4f".format(pA[2])}  = 33.3%

               Trader B:
                 P(Supertrend)      = ${"%.4f".format(pB[0])}  = 33.3%
                 P(Bollinger Bands) = ${"%.4f".format(pB[1])}  = 33.3%
                 P(VWAP)            = ${"%.4f".format(pB[2])}  = 33.3%

             Game Value (Minimax Theorem): ${"%.6f".format(gameValue)}  ✓
               Expected payoff = 0 — perfectly balanced competition.
               Neither trader can gain positive expected return in equilibrium.

             Key insight: Skew-symmetric zero-sum matrix forces uniform mixing.
               Any deviation from 1/3 each is exploitable by the opponent.
               Market alpha is entirely random in equilibrium — a pure randomisation
               strategy is the only unexploitable approach.

             Adaptive tolerance: $tol  (max payoff=5, tol=5×1e-7)
             Deviation gain residual at (1/3, 1/3, 1/3): < 1e-6
            ═══════════════════════════════════════════════════════
        """.trimIndent())
    }
}
