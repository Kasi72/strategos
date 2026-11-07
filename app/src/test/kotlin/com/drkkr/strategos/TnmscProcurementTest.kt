package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end engine trace for the TNMSC Procurement Bidding scenario.
 *
 * Matrix (Vendor A rows, Vendor B columns):
 *                 Bid High   Bid Low
 *   Bid High:    (10, 10)   ( 0, 15)
 *   Bid Low:     (15,  0)   ( 5,  5)
 *
 * Expected:
 *   Dominant strategy for A: Bid Low Margin
 *   Dominant strategy for B: Bid Low Margin
 *   Nash Equilibrium: (Bid Low, Bid Low) = (5, 5)
 *   Archetype: PRISONERS_DILEMMA
 */
class TnmscProcurementTest {

    // Profile order (row-major): (High,High)=0, (High,Low)=1, (Low,High)=2, (Low,Low)=3
    private val ir = GameIR(
        scenarioId  = "tnmsc-procurement",
        playerIds   = listOf("Vendor A", "Vendor B"),
        actionIds   = listOf(
            listOf("Bid High Margin", "Bid Low Margin"),
            listOf("Bid High Margin", "Bid Low Margin")
        ),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf(10.0, 0.0, 15.0, 5.0),   // Vendor A payoffs
            doubleArrayOf(10.0, 15.0, 0.0, 5.0)    // Vendor B payoffs
        ),
        gameClass    = GameClass.FINITE_NORMAL_FORM,
        coveragePct  = 100
    )

    // ── Fix 4 (NxM archetype) + Fix 1 (adaptive tolerance) ──────────────────

    @Test
    fun `archetype detected as PRISONERS_DILEMMA`() {
        val archetype = GameArchetypeDetector.detect(ir)
        assertEquals(
            "TNMSC matrix is a Prisoner's Dilemma",
            GameArchetype.PRISONERS_DILEMMA, archetype
        )
    }

    // ── Fix 9 (strict dominance via IEDS) ───────────────────────────────────

    @Test
    fun `IEDS strict — Bid High Margin eliminated for both vendors`() {
        val result = IteratedDominancePruner.prune(ir, weakDominance = false)

        assertTrue("IEDS should eliminate at least one action", result.anyEliminated)

        val elimA = result.eliminated["Vendor A"] ?: emptyList()
        val elimB = result.eliminated["Vendor B"] ?: emptyList()

        assertTrue("Bid High Margin strictly dominated for Vendor A", "Bid High Margin" in elimA)
        assertTrue("Bid High Margin strictly dominated for Vendor B", "Bid High Margin" in elimB)

        // Reduced game: only (Low, Low) survives
        assertEquals("Reduced game has 1 action for each player",
            listOf(1, 1), result.ir.strategyCounts)
        assertEquals("Surviving action for A: Bid Low Margin",
            "Bid Low Margin", result.ir.actionIds[0][0])
        assertEquals("Surviving action for B: Bid Low Margin",
            "Bid Low Margin", result.ir.actionIds[1][0])
    }

    // ── Fix 1 (adaptive tolerance) + PureNashSolver ─────────────────────────

    @Test
    fun `pure NE is (Bid Low, Bid Low)`() {
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)

        assertTrue("PureNash should find at least one equilibrium, got $outcome",
            outcome is SolverOutcome.Solved)

        val equilibria = (outcome as SolverOutcome.Solved).equilibria
        val profiles = equilibria.mapNotNull { it.pureProfile }

        // Action index 1 = "Bid Low Margin" for both players
        assertTrue("NE must be (Bid Low, Bid Low) = profile (1,1)",
            profiles.any { it == listOf(1, 1) })
        assertFalse("NE must NOT be (Bid High, Bid High) = profile (0,0)",
            profiles.any { it == listOf(0, 0) })
    }

    @Test
    fun `NE payoff at (Bid Low, Bid Low) is (5, 5)`() {
        val profile = intArrayOf(1, 1) // Bid Low Margin for both
        val idx = ir.profileIndex(profile)
        assertEquals("Vendor A payoff at NE", 5.0, ir.utility[0][idx], 1e-9)
        assertEquals("Vendor B payoff at NE", 5.0, ir.utility[1][idx], 1e-9)
    }

    // ── Fix 1 (EquilibriumVerifier adaptive tolerance) ──────────────────────

    @Test
    fun `NE passes EquilibriumVerifier with adaptive tolerance`() {
        // Mixed profile representing the pure (Bid Low, Bid Low) NE
        val mixedProfile = listOf(
            doubleArrayOf(0.0, 1.0),  // Vendor A: 100% Bid Low
            doubleArrayOf(0.0, 1.0)   // Vendor B: 100% Bid Low
        )
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertTrue("Adaptive tolerance >= 1e-9", tol >= 1e-9)
        // Max payoff = 15 → tolerance = max(1e-9, 15 * 1e-7) = 1.5e-6
        assertEquals("Adaptive tolerance = max(1e-9, 15*1e-7)", 15.0 * 1e-7, tol, 1e-15)

        assertTrue("(Bid Low, Bid Low) is a Nash equilibrium",
            EquilibriumVerifier.isNashEquilibrium(
                ir.utility, ir.strategyCounts, mixedProfile, tol
            )
        )
    }

    @Test
    fun `(Bid High, Bid High) is NOT a Nash equilibrium`() {
        val mixedProfile = listOf(
            doubleArrayOf(1.0, 0.0),  // Vendor A: 100% Bid High
            doubleArrayOf(1.0, 0.0)   // Vendor B: 100% Bid High
        )
        val tol = EquilibriumVerifier.adaptiveTolerance(ir.utility)
        assertFalse("(Bid High, Bid High) has a profitable deviation — not a NE",
            EquilibriumVerifier.isNashEquilibrium(
                ir.utility, ir.strategyCounts, mixedProfile, tol
            )
        )
    }

    // ── Fix 3 (MixedNashNxMSolver) — 2-player so it should Solve ───────────

    @Test
    fun `MixedNashNxMSolver finds (Bid Low, Bid Low) as pure NE`() {
        val outcome = MixedNashNxMSolver.solve(ir)

        assertTrue("MixedNash should solve 2-player game, got $outcome",
            outcome is SolverOutcome.Solved)

        val profiles = (outcome as SolverOutcome.Solved).equilibria.mapNotNull { it.pureProfile }
        assertTrue("Pure NE (1,1) found by MixedNash", profiles.any { it == listOf(1, 1) })
    }

    // ── Dominant strategy summary (combines IEDS + NE) ──────────────────────

    @Test
    fun `full scenario summary — dominant strategy and NE match expected`() {
        val pruned = IteratedDominancePruner.prune(ir, weakDominance = false)
        val nashOutcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)
        val archetype = GameArchetypeDetector.detect(ir)

        // Dominant strategies
        assertEquals("Dominant strategy Vendor A", "Bid Low Margin",
            pruned.ir.actionIds[0][0])
        assertEquals("Dominant strategy Vendor B", "Bid Low Margin",
            pruned.ir.actionIds[1][0])

        // Nash Equilibrium
        val profiles = (nashOutcome as SolverOutcome.Solved).equilibria.mapNotNull { it.pureProfile }
        assertTrue("Nash Equilibrium: (Bid Low, Bid Low)", profiles.any { it == listOf(1, 1) })

        // Archetype
        assertEquals("Archetype", GameArchetype.PRISONERS_DILEMMA, archetype)

        println("""
            ═══════════════════════════════════════════════════════
             TNMSC Procurement Bidding — Engine Trace
            ═══════════════════════════════════════════════════════
             Scenario:   Two vendors bidding for TNMSC tender
             Matrix:     Bid High/Low × Bid High/Low
                         (10,10) | ( 0,15)
                         (15, 0) | ( 5, 5)

             Archetype:  ${archetype.name}

             IEDS (strict dominance):
               Vendor A: eliminated = ${pruned.eliminated["Vendor A"]}
               Vendor B: eliminated = ${pruned.eliminated["Vendor B"]}
               Rounds:   ${pruned.rounds}

             Dominant Strategy (Vendor A): Bid Low Margin  ✓
             Dominant Strategy (Vendor B): Bid Low Margin  ✓

             Nash Equilibrium:  (Bid Low Margin, Bid Low Margin)  ✓
             NE Payoffs:        Vendor A = 5, Vendor B = 5

             Adaptive tolerance: ${EquilibriumVerifier.adaptiveTolerance(ir.utility)}
            ═══════════════════════════════════════════════════════
        """.trimIndent())
    }
}
