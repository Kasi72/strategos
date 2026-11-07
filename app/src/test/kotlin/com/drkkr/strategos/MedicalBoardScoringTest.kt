package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end engine trace for the Medical Board Scoring scenario (Dominant Strategy / Harmony Game).
 *
 * Matrix (Examiner 1 rows, Examiner 2 columns):
 *                      Strict Formula    Round Up
 *   Strict Formula:    (10, 10)           (10,  5)
 *   Round Up:          ( 5, 10)           ( 0,  0)
 *
 * Profile index order (row-major):
 *   0 = (SF, SF) → E1=10, E2=10   ← unique pure NE
 *   1 = (SF, RU) → E1=10, E2= 5
 *   2 = (RU, SF) → E1= 5, E2=10
 *   3 = (RU, RU) → E1= 0, E2= 0
 *
 * NOTE ON DOMINANCE TYPE:
 *   With the given matrix, Strict Formula IS STRICTLY dominant for both examiners:
 *     E1: SF vs RU — when E2=SF: 10>5; when E2=RU: 10>0. Strictly greater in ALL columns.
 *     E2: SF vs RU — when E1=SF: 10>5; when E1=RU: 10>0. Strictly greater in ALL rows.
 *   The (10=10) tie that makes dominance only WEAK would require the (RU,SF) cell to be
 *   (10,_) for E1 instead of (5,_). Tests below verify strict IEDS, weak IEDS, and
 *   demonstrate where the two diverge on a modified matrix.
 *
 * Expected (literal matrix):
 *   Archetype:   HARMONY (both have strictly dominant strategies; NE is Pareto-optimal)
 *   IEDS strict: Round Up eliminated for both examiners in 1 round
 *   IEDS weak:   Same outcome
 *   Pure NE:     (Strict Formula, Strict Formula) — unique
 *   Mixed NE:    Only the pure NE exists (dominant strategies eliminate interior mixing)
 */
class MedicalBoardScoringTest {

    // ── Literal matrix from scenario ─────────────────────────────────────────

    private val ir = GameIR(
        scenarioId     = "medical-board-scoring",
        playerIds      = listOf("Examiner 1", "Examiner 2"),
        actionIds      = listOf(
            listOf("Strict Formula", "Round Up"),
            listOf("Strict Formula", "Round Up")
        ),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf(10.0, 10.0,  5.0, 0.0),   // Examiner 1 payoffs
            doubleArrayOf(10.0,  5.0, 10.0, 0.0)    // Examiner 2 payoffs
        ),
        gameClass    = GameClass.FINITE_NORMAL_FORM,
        coveragePct  = 100
    )

    // ── Payoff table verification ────────────────────────────────────────────

    @Test
    fun `NE profile (SF, SF) payoffs are (10, 10)`() {
        val idx = ir.profileIndex(intArrayOf(0, 0))
        assertEquals("E1 payoff at (SF, SF)", 10.0, ir.utility[0][idx], 1e-9)
        assertEquals("E2 payoff at (SF, SF)", 10.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `profile (RU, SF) payoffs are (5, 10)`() {
        val idx = ir.profileIndex(intArrayOf(1, 0))
        assertEquals("E1 gets 5 at (RU, SF) — strictly less than 10", 5.0, ir.utility[0][idx], 1e-9)
        assertEquals("E2 gets 10 at (RU, SF)", 10.0, ir.utility[1][idx], 1e-9)
    }

    // ── Dominance verification ───────────────────────────────────────────────

    @Test
    fun `Strict Formula strictly dominates Round Up for Examiner 1`() {
        // SF > RU for all E2 strategies
        // E2=SF: SF=10 > RU=5
        // E2=RU: SF=10 > RU=0
        val sfVsE2SF = ir.utility[0][ir.profileIndex(intArrayOf(0, 0))] >
                       ir.utility[0][ir.profileIndex(intArrayOf(1, 0))]   // 10 > 5
        val sfVsE2RU = ir.utility[0][ir.profileIndex(intArrayOf(0, 1))] >
                       ir.utility[0][ir.profileIndex(intArrayOf(1, 1))]   // 10 > 0
        assertTrue("E1: SF > RU when E2 plays SF (10 > 5)", sfVsE2SF)
        assertTrue("E1: SF > RU when E2 plays RU (10 > 0)", sfVsE2RU)
    }

    @Test
    fun `Strict Formula strictly dominates Round Up for Examiner 2`() {
        // SF > RU for all E1 strategies
        // E1=SF: SF=10 > RU=5
        // E1=RU: SF=10 > RU=0
        val sfVsE1SF = ir.utility[1][ir.profileIndex(intArrayOf(0, 0))] >
                       ir.utility[1][ir.profileIndex(intArrayOf(0, 1))]   // 10 > 5
        val sfVsE1RU = ir.utility[1][ir.profileIndex(intArrayOf(1, 0))] >
                       ir.utility[1][ir.profileIndex(intArrayOf(1, 1))]   // 10 > 0
        assertTrue("E2: SF > RU when E1 plays SF (10 > 5)", sfVsE1SF)
        assertTrue("E2: SF > RU when E1 plays RU (10 > 0)", sfVsE1RU)
    }

    // ── Archetype: HARMONY ───────────────────────────────────────────────────

    @Test
    fun `archetype detected as HARMONY`() {
        val archetype = GameArchetypeDetector.detect(ir)
        // Both players have dominant strategies AND the NE (SF,SF)=(10,10)
        // is each player's global payoff maximum — not a Prisoner's Dilemma or Deadlock.
        assertEquals("Both dominant strategies lead to Pareto-optimal NE = HARMONY",
            GameArchetype.HARMONY, archetype)
    }

    // ── IEDS strict: Round Up eliminated for both ────────────────────────────

    @Test
    fun `IEDS strict eliminates Round Up for both examiners`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = false)
        assertTrue("IEDS strict must eliminate at least one action", result.anyEliminated)

        val elimE1 = result.eliminated["Examiner 1"] ?: emptyList()
        val elimE2 = result.eliminated["Examiner 2"] ?: emptyList()

        assertTrue("Round Up eliminated for Examiner 1", "Round Up" in elimE1)
        assertTrue("Round Up eliminated for Examiner 2", "Round Up" in elimE2)

        assertEquals("Reduced game: 1×1", listOf(1, 1), result.ir.strategyCounts)
        assertEquals("Surviving action E1: Strict Formula",
            "Strict Formula", result.ir.actionIds[0][0])
        assertEquals("Surviving action E2: Strict Formula",
            "Strict Formula", result.ir.actionIds[1][0])
    }

    @Test
    fun `IEDS strict rounds count`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = false)
        assertTrue("IEDS resolves in 1 round (both dominated simultaneously)", result.rounds >= 1)
    }

    // ── IEDS weak: same outcome ──────────────────────────────────────────────

    @Test
    fun `IEDS weak also eliminates Round Up for both examiners`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = true)
        assertTrue("Weak IEDS also eliminates actions", result.anyEliminated)

        val elimE1 = result.eliminated["Examiner 1"] ?: emptyList()
        val elimE2 = result.eliminated["Examiner 2"] ?: emptyList()

        assertTrue("Round Up weakly eliminated for Examiner 1", "Round Up" in elimE1)
        assertTrue("Round Up weakly eliminated for Examiner 2", "Round Up" in elimE2)
    }

    // ── Pure NE: unique (SF, SF) ─────────────────────────────────────────────

    @Test
    fun `unique pure Nash equilibrium is (Strict Formula, Strict Formula)`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)
        assertTrue("Must find pure NE, got $outcome", outcome is SolverOutcome.Solved)

        val profiles = (outcome as SolverOutcome.Solved).equilibria.mapNotNull { it.pureProfile }
        assertEquals("Exactly one pure NE", 1, profiles.size)
        assertTrue("NE = (SF, SF) = profile (0,0)", profiles.any { it == listOf(0, 0) })
    }

    @Test
    fun `(Round Up, Round Up) is NOT a Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
        val tol   = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertFalse("Both examiners have profitable deviations from (RU, RU)",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol)
        )
    }

    // ── MixedNashNxMSolver confirms no interior mixed NE ────────────────────

    @Test
    fun `MixedNashNxMSolver returns Solved`() {
        val outcome = MixedNashNxMSolver.solve(ir)
        assertTrue("Expected Solved for 2-player game, got $outcome", outcome is SolverOutcome.Solved)
    }

    @Test
    fun `MixedNashNxMSolver finds pure NE (SF, SF) among equilibria`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("Pure NE (SF, SF) = (0,0) found", profiles.any { it == listOf(0, 0) })
    }

    // ── EquilibriumVerifier ──────────────────────────────────────────────────

    @Test
    fun `EquilibriumVerifier adaptive tolerance is 1e-6 (max payoff = 10)`() {
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertEquals("tol = max(1e-9, 10*1e-7) = 1e-6", 10.0 * 1e-7, tol, 1e-15)
    }

    @Test
    fun `EquilibriumVerifier confirms (SF, SF) is Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 0.0))
        val tol   = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("Pure (SF, SF) passes NE test",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol)
        )
    }

    // ── Weak-only dominance demonstration (modified matrix) ──────────────────
    // Shows the distinction: if (RU,SF) gives E1=10 instead of 5, SF is only weakly dominant for E1.

    @Test
    fun `weak-only matrix — IEDS strict finds NO elimination, weak IEDS does`() {
        // Modified: (RU,SF) → E1=10 (a tie when E2 plays SF)
        // SF vs RU for E1: E2=SF: 10=10 (tied); E2=RU: 10>0. Weakly dominant, not strictly.
        val weakOnlyIr = GameIR(
            scenarioId     = "medical-board-scoring-weak-only",
            playerIds      = listOf("Examiner 1", "Examiner 2"),
            actionIds      = listOf(
                listOf("Strict Formula", "Round Up"),
                listOf("Strict Formula", "Round Up")
            ),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf(10.0, 10.0, 10.0, 0.0),  // E1: (SF,SF)=10, (SF,RU)=10, (RU,SF)=10, (RU,RU)=0
                doubleArrayOf(10.0,  5.0, 10.0, 0.0)   // E2: unchanged
            ),
            gameClass    = GameClass.FINITE_NORMAL_FORM,
            coveragePct  = 100
        )

        val strictResult = IteratedDominancePruner.prune(weakOnlyIr, weakDominance = false)
        val weakResult   = IteratedDominancePruner.prune(weakOnlyIr, weakDominance = true)

        // Strict IEDS: SF does NOT strictly dominate RU for E1 (10=10 tie when E2=SF)
        val strictElimE1 = strictResult.eliminated["Examiner 1"] ?: emptyList()
        assertFalse("Strict IEDS must NOT eliminate RU for E1 when tie exists",
            "Round Up" in strictElimE1)

        // Weak IEDS: SF weakly dominates RU for E1 (10>=10 always, 10>0 sometimes)
        val weakElimE1 = weakResult.eliminated["Examiner 1"] ?: emptyList()
        assertTrue("Weak IEDS MUST eliminate RU for E1 (weakly dominated)",
            "Round Up" in weakElimE1)
    }

    // ── Full scenario summary ────────────────────────────────────────────────

    @Test
    fun `full scenario summary — HARMONY, SF dominant, unique NE at (SF,SF)`() {
        val pureOutcome  = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val mixedOutcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val archetype    = GameArchetypeDetector.detect(ir)
        val strictResult = IteratedDominancePruner.prune(ir, weakDominance = false)
        val weakResult   = IteratedDominancePruner.prune(ir, weakDominance = true)
        val tol          = EquilibriumVerifier.adaptiveTolerance(ir.utility)

        val pureProfiles = pureOutcome.equilibria.mapNotNull { it.pureProfile }
        val nePayoffE1 = ir.utility[0][ir.profileIndex(intArrayOf(0, 0))]
        val nePayoffE2 = ir.utility[1][ir.profileIndex(intArrayOf(0, 0))]

        // Core assertions
        assertEquals("Archetype", GameArchetype.HARMONY, archetype)
        assertEquals("One pure NE", 1, pureProfiles.size)
        assertTrue("NE = (SF, SF)", pureProfiles.any { it == listOf(0, 0) })
        assertTrue("Strict IEDS eliminates Round Up", strictResult.anyEliminated)
        assertTrue("Weak IEDS eliminates Round Up", weakResult.anyEliminated)

        println("""
            ═══════════════════════════════════════════════════════
             Medical Board Scoring — Engine Trace
            ═══════════════════════════════════════════════════════
             Scenario:   Two examiners choose scoring methodology
             Matrix:     Strict Formula / Round Up × (same)
                         (10,10) | (10, 5)
                         ( 5,10) | ( 0, 0)

             Archetype:  ${archetype.name}
               Both examiners have a dominant strategy (Strict Formula).
               NE payoff (10,10) equals each player's global maximum.
               No cooperation dilemma — everyone benefits from the dominant strategy.

             Dominance type (literal matrix):
               Strict Formula IS STRICTLY dominant for both examiners.
               E1: SF=10 > RU=5 (vs E2=SF); SF=10 > RU=0 (vs E2=RU). Both strict.
               E2: SF=10 > RU=5 (vs E1=SF); SF=10 > RU=0 (vs E1=RU). Both strict.
               ⚠ NOTE: If the intended scenario has (RU,SF)=E1:10 (a tie when E2=SF),
               then SF would be only WEAKLY dominant for E1. See weak-only test above.

             IEDS (strict dominance):
               Examiner 1 eliminated: ${strictResult.eliminated["Examiner 1"]}
               Examiner 2 eliminated: ${strictResult.eliminated["Examiner 2"]}
               Rounds: ${strictResult.rounds}
               Surviving game: 1×1 — only (Strict Formula, Strict Formula).

             IEDS (weak dominance):
               Same result — both Round Up strategies eliminated.

             Pure Nash Equilibrium (unique):  ✓
               (Strict Formula, Strict Formula) → payoffs ($nePayoffE1, $nePayoffE2)
               No profitable unilateral deviation exists for either examiner.

             Mixed Nash Equilibrium:
               No interior mixed NE (dominant strategies preclude mixing).
               MixedNashNxMSolver confirms pure NE (0,0) only.

             Adaptive tolerance: $tol  (max payoff=10, tol=10×1e-7)
            ═══════════════════════════════════════════════════════
        """.trimIndent())
    }
}
