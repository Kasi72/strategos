package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end engine trace for the SDL Module Free-Rider scenario (Prisoner's Dilemma).
 *
 * Matrix (Student 1 rows, Student 2 columns):
 *                  Work Hard   Slack Off
 *   Work Hard:    (8, 8)       (0, 10)
 *   Slack Off:    (10, 0)      (2, 2)   ← unique NE
 *
 * Profile index order (row-major, 2×2):
 *   0 = (Work Hard, Work Hard) → S1=8,  S2=8   ← Pareto optimal (not NE)
 *   1 = (Work Hard, Slack Off) → S1=0,  S2=10  ← Sucker (S)
 *   2 = (Slack Off, Work Hard) → S1=10, S2=0   ← Temptation (T)
 *   3 = (Slack Off, Slack Off) → S1=2,  S2=2   ← Punishment (P) = unique NE
 *
 * PD payoff ordering T > R > P > S:
 *   T = 10  (Temptation — free-ride while partner works)
 *   R =  8  (Reward — mutual hard work)
 *   P =  2  (Punishment — both slack; partial SDL credit from lectures only)
 *   S =  0  (Sucker — work hard while partner free-rides, exhausted, unacknowledged)
 *   10 > 8 > 2 > 0  ✓
 *
 * Expected:
 *   Archetype: PRISONERS_DILEMMA
 *   IEDS strict: Work Hard eliminated for BOTH in 1 round
 *   Pure NE: only (Slack Off, Slack Off) = profile (1,1)
 *   No interior mixed NE (strict dominant strategy)
 *
 * Dominance verification:
 *   S1 payoff when S2=WH: SO(10) > WH(8)
 *   S1 payoff when S2=SO: SO(2)  > WH(0)
 *   → Slack Off strictly dominates Work Hard for S1 (symmetric for S2).
 *
 * Social dilemma: (Work Hard, Work Hard) = (8,8) is Pareto superior to (2,2),
 * but rational self-interest drives both to Slack Off.
 */
class SdlFreeRiderTest {

    private val ir = GameIR(
        scenarioId     = "sdl-module-free-rider",
        playerIds      = listOf("MBBS Student 1", "MBBS Student 2"),
        actionIds      = listOf(
            listOf("Work Hard", "Slack Off"),
            listOf("Work Hard", "Slack Off")
        ),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf(8.0, 0.0, 10.0, 2.0),   // Student 1 payoffs
            doubleArrayOf(8.0, 10.0, 0.0, 2.0)    // Student 2 payoffs
        ),
        gameClass    = GameClass.FINITE_NORMAL_FORM,
        coveragePct  = 100
    )

    // ── Payoff table verification ────────────────────────────────────────────

    @Test
    fun `(Work Hard, Work Hard) payoffs are (8, 8) — Pareto optimal`() {
        val idx = ir.profileIndex(intArrayOf(0, 0))
        assertEquals("S1 payoff", 8.0, ir.utility[0][idx], 1e-9)
        assertEquals("S2 payoff", 8.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `(Work Hard, Slack Off) payoffs are (0, 10) — sucker works alone`() {
        val idx = ir.profileIndex(intArrayOf(0, 1))
        assertEquals("S1 sucker: 0", 0.0, ir.utility[0][idx], 1e-9)
        assertEquals("S2 free-rides: 10", 10.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `(Slack Off, Work Hard) payoffs are (10, 0) — free-rider`() {
        val idx = ir.profileIndex(intArrayOf(1, 0))
        assertEquals("S1 free-rides: 10", 10.0, ir.utility[0][idx], 1e-9)
        assertEquals("S2 sucker: 0", 0.0, ir.utility[1][idx], 1e-9)
    }

    @Test
    fun `NE (Slack Off, Slack Off) payoffs are (2, 2)`() {
        val idx = ir.profileIndex(intArrayOf(1, 1))
        assertEquals("S1 at NE", 2.0, ir.utility[0][idx], 1e-9)
        assertEquals("S2 at NE", 2.0, ir.utility[1][idx], 1e-9)
    }

    // ── Dominance verification ───────────────────────────────────────────────

    @Test
    fun `Slack Off dominates Work Hard for S1 when S2 plays Work Hard (10 gt 8)`() {
        val soVsWH = ir.utility[0][ir.profileIndex(intArrayOf(1, 0))]
        val whVsWH = ir.utility[0][ir.profileIndex(intArrayOf(0, 0))]
        assertTrue("SO(10) > WH(8) when S2=WH", soVsWH > whVsWH)
    }

    @Test
    fun `Slack Off dominates Work Hard for S1 when S2 plays Slack Off (2 gt 0)`() {
        val soVsSO = ir.utility[0][ir.profileIndex(intArrayOf(1, 1))]
        val whVsSO = ir.utility[0][ir.profileIndex(intArrayOf(0, 1))]
        assertTrue("SO(2) > WH(0) when S2=SO", soVsSO > whVsSO)
    }

    @Test
    fun `Slack Off dominates Work Hard for S2 when S1 plays Work Hard (10 gt 8)`() {
        val soVsWH = ir.utility[1][ir.profileIndex(intArrayOf(0, 1))]
        val whVsWH = ir.utility[1][ir.profileIndex(intArrayOf(0, 0))]
        assertTrue("SO(10) > WH(8) when S1=WH", soVsWH > whVsWH)
    }

    @Test
    fun `Slack Off dominates Work Hard for S2 when S1 plays Slack Off (2 gt 0)`() {
        val soVsSO = ir.utility[1][ir.profileIndex(intArrayOf(1, 1))]
        val whVsSO = ir.utility[1][ir.profileIndex(intArrayOf(1, 0))]
        assertTrue("SO(2) > WH(0) when S1=SO", soVsSO > whVsSO)
    }

    // ── Archetype: PRISONERS_DILEMMA ─────────────────────────────────────────

    @Test
    fun `archetype detected as PRISONERS_DILEMMA`() {
        val archetype = GameArchetypeDetector.detect(ir)
        assertEquals("Strict dominant strategy, Pareto-suboptimal NE = PRISONERS_DILEMMA",
            GameArchetype.PRISONERS_DILEMMA, archetype)
    }

    // ── IEDS strict: Work Hard eliminated in one round ───────────────────────

    @Test
    fun `IEDS strict — Work Hard eliminated for both players`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = false)
        assertTrue("At least one elimination", result.anyEliminated)
    }

    @Test
    fun `IEDS strict — exactly one round needed`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = false)
        assertEquals("One round of strict elimination", 1, result.rounds)
    }

    @Test
    fun `IEDS strict — surviving game is 1×1 (Slack Off, Slack Off)`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = false)
        assertEquals("One strategy per player survives", listOf(1, 1), result.ir.strategyCounts)
    }

    @Test
    fun `IEDS weak — same result as strict (strict dominance means weak too)`() {
        val strictResult = IteratedDominancePruner.prune(ir, weakDominance = false)
        val weakResult   = IteratedDominancePruner.prune(ir, weakDominance = true)
        assertEquals("Weak IEDS also eliminates to 1×1",
            listOf(1, 1), weakResult.ir.strategyCounts)
        assertEquals("Same number of rounds",
            strictResult.rounds, weakResult.rounds)
    }

    // ── Pure NE: unique equilibrium at (Slack Off, Slack Off) ───────────────

    @Test
    fun `exactly one pure Nash equilibrium`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)
        assertTrue("Must find pure NE, got $outcome", outcome is SolverOutcome.Solved)
        val profiles = (outcome as SolverOutcome.Solved).equilibria.mapNotNull { it.pureProfile }
        assertEquals("Exactly one pure NE", 1, profiles.size)
    }

    @Test
    fun `pure NE is (Slack Off, Slack Off) — profile (1,1)`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("NE = (1,1) exists", profiles.any { it == listOf(1, 1) })
    }

    @Test
    fun `(Work Hard, Work Hard) is NOT a Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 0.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertFalse("Both have profitable deviation to Slack Off — not a NE",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    @Test
    fun `(Work Hard, Slack Off) is NOT a Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertFalse("S1 deviates to Slack Off for 2 > 0 — not a NE",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    // ── MixedNashNxMSolver: only pure NE at (1,1), no interior mixed NE ─────

    @Test
    fun `MixedNashNxMSolver returns Solved`() {
        val outcome = MixedNashNxMSolver.solve(ir)
        assertTrue("Expected Solved, got $outcome", outcome is SolverOutcome.Solved)
    }

    @Test
    fun `MixedNashNxMSolver finds only NE at (Slack Off, Slack Off)`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val profiles = outcome.equilibria.mapNotNull { it.pureProfile }
        assertTrue("NE (1,1) found", profiles.any { it == listOf(1, 1) })
    }

    @Test
    fun `no interior mixed NE exists (strict dominant strategy — no mixing)`() {
        val outcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val interior = outcome.equilibria.firstOrNull { eq ->
            eq.pureProfile == null &&
            eq.mixedStrategies[0][0] > 0.01 && eq.mixedStrategies[0][0] < 0.99
        }
        assertNull("Strict dominant strategy means no interior mixed NE", interior)
    }

    // ── EquilibriumVerifier ──────────────────────────────────────────────────

    @Test
    fun `EquilibriumVerifier adaptive tolerance is 1e-6 (max payoff = 10)`() {
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertEquals("tol = max(1e-9, 10*1e-7) = 1e-6", 10.0 * 1e-7, tol, 1e-15)
    }

    @Test
    fun `EquilibriumVerifier confirms (Slack Off, Slack Off) as Nash equilibrium`() {
        val mixed = listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("(SO, SO) is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(ir.utility, ir.strategyCounts, mixed, tol))
    }

    @Test
    fun `deviation gain residual near-zero at (Slack Off, Slack Off)`() {
        val profile = listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
        val residual = EquilibriumVerifier.deviationGainResidual(ir.utility, ir.strategyCounts, profile)
        assertTrue("Residual < 1e-6 at NE, got $residual", residual < 1e-6)
    }

    @Test
    fun `deviation gain residual positive at (Work Hard, Work Hard) — both want to deviate`() {
        val profile = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 0.0))
        val residual = EquilibriumVerifier.deviationGainResidual(ir.utility, ir.strategyCounts, profile)
        assertTrue("Residual > 0 at non-NE (WH,WH), got $residual", residual > 0)
    }

    // ── Social dilemma properties ────────────────────────────────────────────

    @Test
    fun `NE payoff (2,2) is Pareto-suboptimal compared to (Work Hard, Work Hard) = (8,8)`() {
        val nePayoffS1  = ir.utility[0][ir.profileIndex(intArrayOf(1, 1))]
        val paretoS1    = ir.utility[0][ir.profileIndex(intArrayOf(0, 0))]
        assertTrue("Pareto outcome (8) better than NE (2) for S1", paretoS1 > nePayoffS1)

        val nePayoffS2  = ir.utility[1][ir.profileIndex(intArrayOf(1, 1))]
        val paretoS2    = ir.utility[1][ir.profileIndex(intArrayOf(0, 0))]
        assertTrue("Pareto outcome (8) better than NE (2) for S2", paretoS2 > nePayoffS2)
    }

    @Test
    fun `PD payoff ordering T gt R gt P gt S holds (10 gt 8 gt 2 gt 0)`() {
        val temptation = ir.utility[0][ir.profileIndex(intArrayOf(1, 0))]   // T = 10
        val reward     = ir.utility[0][ir.profileIndex(intArrayOf(0, 0))]   // R = 8
        val punishment = ir.utility[0][ir.profileIndex(intArrayOf(1, 1))]   // P = 2
        val sucker     = ir.utility[0][ir.profileIndex(intArrayOf(0, 1))]   // S = 0
        assertTrue("T(10) > R(8) > P(2) > S(0) — PD ordering holds",
            temptation > reward && reward > punishment && punishment > sucker)
    }

    // ── Full scenario summary ────────────────────────────────────────────────

    @Test
    fun `full scenario summary — PD, Slack Off dominant, NE = (Slack Off, Slack Off)`() {
        val pureOutcome  = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE) as SolverOutcome.Solved
        val mixedOutcome = MixedNashNxMSolver.solve(ir) as SolverOutcome.Solved
        val archetype    = GameArchetypeDetector.detect(ir)
        val iedsResult   = IteratedDominancePruner.prune(ir, weakDominance = false)
        val tol          = EquilibriumVerifier.adaptiveTolerance(ir.utility)

        val pureProfiles = pureOutcome.equilibria.mapNotNull { it.pureProfile }
        val interiorNE   = mixedOutcome.equilibria.firstOrNull { it.pureProfile == null }

        assertEquals("Archetype", GameArchetype.PRISONERS_DILEMMA, archetype)
        assertEquals("One pure NE", 1, pureProfiles.size)
        assertTrue("NE = (1,1)", pureProfiles.any { it == listOf(1, 1) })
        assertNull("No interior mixed NE", interiorNE)
        assertEquals("IEDS leaves 1×1 game", listOf(1, 1), iedsResult.ir.strategyCounts)

        println("""
            ═══════════════════════════════════════════════════════
             SDL Module Free-Rider — Engine Trace
            ═══════════════════════════════════════════════════════
             Scenario:   Two MBBS students preparing an SDL module.
                         Each can Work Hard (costly effort) or Slack Off
                         (free-ride on partner). Facilitator cannot
                         distinguish individual contributions.
             Players:    MBBS Student 1 (Work Hard / Slack Off)
                         MBBS Student 2 (Work Hard / Slack Off)
             Matrix:     WH / SO × WH / SO
                         (8, 8) | (0, 10)
                         (10, 0)| (2, 2)

             Archetype:  ${archetype.name}
               Classic social dilemma: rational individual choice
               produces collectively suboptimal outcome.

             PD Payoff Ordering confirmed T > R > P > S:
               T (Temptation / Free-ride)       = 10
               R (Reward / Mutual WH)           =  8
               P (Punishment / Both SO)         =  2  (partial SDL credit from lectures)
               S (Sucker / Exploited WH)        =  0  (all effort, unacknowledged)
               10 > 8 > 2 > 0  ✓
               2R = 16 > T + S = 10  ✓  (joint efficiency favours cooperation)

             Strict Dominance Analysis:
               S1 vs WH opponent: SO=10 > WH=8  → SO dominates
               S1 vs SO opponent: SO=2  > WH=0  → SO dominates
               Slack Off strictly dominates Work Hard for BOTH players.
               No conditional reasoning needed — dominant regardless of opponent.

             IEDS (strict dominance, 1 round):  ✓
               Round 1: Work Hard eliminated for both players.
               Surviving strategies: Slack Off × Slack Off.
               Surviving game: 1×1 matrix, payoffs (2, 2).
               Rounds: ${iedsResult.rounds}  |  Any eliminated: ${iedsResult.anyEliminated}
               Surviving strategyCounts: ${iedsResult.ir.strategyCounts}

             Pure Nash Equilibria (1 found):  ✓
               NE: (Slack Off, Slack Off) → payoffs (2, 2)
               This is the unique dominant strategy equilibrium.

               (Work Hard, Work Hard) = (8, 8) is Pareto-superior for BOTH
               students — but rational students defect to Slack Off.
               This is the defining tragedy of the Prisoner's Dilemma.

             Mixed Strategy NE:  None interior (dominant strategy eliminates mixing).
               When one strategy strictly dominates regardless of opponent's mix,
               no mixed equilibrium exists in the interior of the simplex.
               MixedNashNxMSolver confirms: no interior mixed NE found.

             Key insight: Social cost of rationality.
               Each student's private calculus says "Slack Off".
               The collective outcome (2,2) is worse for BOTH than (8,8).
               Escape routes: binding commitments, monitoring, repeated play
               reputation, group identity, or pair assessment by facilitator.

             Adaptive tolerance: $tol  (max payoff=10, tol=10×1e-7)
            ═══════════════════════════════════════════════════════
        """.trimIndent())
    }
}
