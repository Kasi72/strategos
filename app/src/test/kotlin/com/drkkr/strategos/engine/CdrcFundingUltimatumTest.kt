package com.drkkr.strategos.engine

import com.drkkr.strategos.domain.game.BackwardInductionSolver
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.SequenceNode
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end engine trace for the CDRC Funding Ultimatum scenario (Sequential Bargaining / SPNE).
 *
 * Extensive-form game tree:
 *
 *   NHM Director (root)
 *   ├── Propose Aggressive (80/20)
 *   │   └── Clinical Lead
 *   │       ├── Accept  → (Director=80, Lead=20)   ← NE1
 *   │       └── Reject  → (Director= 0, Lead= 0)
 *   └── Propose Fair (50/50)
 *       └── Clinical Lead
 *           ├── Accept  → (Director=50, Lead=50)
 *           └── Reject  → (Director= 0, Lead= 0)
 *
 * Backward induction (SPNE derivation):
 *   Step 1 — Clinical Lead's subgames:
 *     After Aggressive: Accept (20) > Reject (0) → Clinical Lead chooses Accept.
 *     After Fair:       Accept (50) > Reject (0) → Clinical Lead chooses Accept.
 *   Step 2 — NHM Director (knowing Lead accepts both):
 *     Aggressive → 80;  Fair → 50.  Director chooses Aggressive (80 > 50).
 *
 *   SPNE: (Propose Aggressive, Accept) with payoffs (80, 20).
 */
class CdrcFundingUltimatumTest {

    // Single payoff dimension — scalar utility equal to the numeric budget payoff.
    private val W = mapOf(PayoffDimension.EFFICIENCY to 1.0)

    private fun payoff(director: Double, lead: Double): Map<String, MultiPayoff> = mapOf(
        "NHM Director"  to MultiPayoff(mapOf(PayoffDimension.EFFICIENCY to director)),
        "Clinical Lead" to MultiPayoff(mapOf(PayoffDimension.EFFICIENCY to lead))
    )

    // ── Game tree ──────────────────────────────────────────────────────────────

    // After "Propose Aggressive": Clinical Lead subgame
    private val aggressiveAccept = SequenceNode(
        id = "agg-accept", stakeholderId = "Clinical Lead",
        actionLabel = "Accept",
        terminalPayoffs = payoff(80.0, 20.0)
    )
    private val aggressiveReject = SequenceNode(
        id = "agg-reject", stakeholderId = "Clinical Lead",
        actionLabel = "Reject",
        terminalPayoffs = payoff(0.0, 0.0)
    )
    private val afterAggressive = SequenceNode(
        id = "after-agg", stakeholderId = "Clinical Lead",
        actionLabel = "Propose Aggressive (80/20)",
        children = listOf(aggressiveAccept, aggressiveReject)
    )

    // After "Propose Fair": Clinical Lead subgame
    private val fairAccept = SequenceNode(
        id = "fair-accept", stakeholderId = "Clinical Lead",
        actionLabel = "Accept",
        terminalPayoffs = payoff(50.0, 50.0)
    )
    private val fairReject = SequenceNode(
        id = "fair-reject", stakeholderId = "Clinical Lead",
        actionLabel = "Reject",
        terminalPayoffs = payoff(0.0, 0.0)
    )
    private val afterFair = SequenceNode(
        id = "after-fair", stakeholderId = "Clinical Lead",
        actionLabel = "Propose Fair (50/50)",
        children = listOf(fairAccept, fairReject)
    )

    // Root: NHM Director
    private val root = SequenceNode(
        id = "root", stakeholderId = "NHM Director",
        actionLabel = "Start",
        children = listOf(afterAggressive, afterFair)
    )

    // ── Payoff table verification ──────────────────────────────────────────────

    @Test
    fun `(Aggressive, Accept) payoffs are (80, 20)`() {
        assertEquals(80.0, aggressiveAccept.terminalPayoffs["NHM Director"]!!
            .values[PayoffDimension.EFFICIENCY]!!, 1e-9)
        assertEquals(20.0, aggressiveAccept.terminalPayoffs["Clinical Lead"]!!
            .values[PayoffDimension.EFFICIENCY]!!, 1e-9)
    }

    @Test
    fun `(Fair, Accept) payoffs are (50, 50)`() {
        assertEquals(50.0, fairAccept.terminalPayoffs["NHM Director"]!!
            .values[PayoffDimension.EFFICIENCY]!!, 1e-9)
        assertEquals(50.0, fairAccept.terminalPayoffs["Clinical Lead"]!!
            .values[PayoffDimension.EFFICIENCY]!!, 1e-9)
    }

    @Test
    fun `(Aggressive, Reject) and (Fair, Reject) payoffs are (0, 0)`() {
        assertEquals(0.0, aggressiveReject.terminalPayoffs["NHM Director"]!!
            .values[PayoffDimension.EFFICIENCY]!!, 1e-9)
        assertEquals(0.0, aggressiveReject.terminalPayoffs["Clinical Lead"]!!
            .values[PayoffDimension.EFFICIENCY]!!, 1e-9)
        assertEquals(0.0, fairReject.terminalPayoffs["NHM Director"]!!
            .values[PayoffDimension.EFFICIENCY]!!, 1e-9)
        assertEquals(0.0, fairReject.terminalPayoffs["Clinical Lead"]!!
            .values[PayoffDimension.EFFICIENCY]!!, 1e-9)
    }

    // ── Clinical Lead's subgame decisions ─────────────────────────────────────

    @Test
    fun `Clinical Lead subgame after Aggressive — chooses Accept (20 gt 0)`() {
        val result = BackwardInductionSolver.solve(afterAggressive, W)
        assertNotNull("Solver must resolve aggressive subgame", result)
        assertTrue("Clinical Lead accepts (20 > 0)",
            result!!.equilibriumPath.contains("Accept"))
        assertEquals(20.0,
            result.payoffs["Clinical Lead"]!!.weighted(W), 1e-9)
    }

    @Test
    fun `Clinical Lead subgame after Fair — chooses Accept (50 gt 0)`() {
        val result = BackwardInductionSolver.solve(afterFair, W)
        assertNotNull("Solver must resolve fair subgame", result)
        assertTrue("Clinical Lead accepts (50 > 0)",
            result!!.equilibriumPath.contains("Accept"))
        assertEquals(50.0,
            result.payoffs["Clinical Lead"]!!.weighted(W), 1e-9)
    }

    @Test
    fun `Clinical Lead never Rejects — 0 is dominated by any positive payoff`() {
        val aggressiveResult = BackwardInductionSolver.solve(afterAggressive, W)!!
        val fairResult       = BackwardInductionSolver.solve(afterFair, W)!!
        assertFalse("Reject not chosen after Aggressive",
            aggressiveResult.equilibriumPath.contains("Reject"))
        assertFalse("Reject not chosen after Fair",
            fairResult.equilibriumPath.contains("Reject"))
    }

    // ── Full SPNE via backward induction ──────────────────────────────────────

    @Test
    fun `BackwardInductionSolver returns non-null Solved outcome`() {
        val outcome = BackwardInductionSolver.solveDetailed(root, W)
        assertTrue("Must be Solved", outcome is BackwardInductionSolver.Outcome.Solved)
    }

    @Test
    fun `SPNE path starts with Propose Aggressive`() {
        val result = BackwardInductionSolver.solve(root, W)
        assertNotNull(result)
        assertTrue("NHM Director plays Propose Aggressive",
            result!!.equilibriumPath.any { it.contains("Aggressive", ignoreCase = true) })
    }

    @Test
    fun `SPNE path contains Accept`() {
        val result = BackwardInductionSolver.solve(root, W)
        assertNotNull(result)
        assertTrue("Clinical Lead accepts along SPE path",
            result!!.equilibriumPath.contains("Accept"))
    }

    @Test
    fun `SPNE equilibrium payoff for NHM Director is 80`() {
        val result = BackwardInductionSolver.solve(root, W)!!
        assertEquals("Director payoff = 80", 80.0,
            result.payoffs["NHM Director"]!!.weighted(W), 1e-9)
    }

    @Test
    fun `SPNE equilibrium payoff for Clinical Lead is 20`() {
        val result = BackwardInductionSolver.solve(root, W)!!
        assertEquals("Lead payoff = 20", 20.0,
            result.payoffs["Clinical Lead"]!!.weighted(W), 1e-9)
    }

    @Test
    fun `NHM Director chooses Aggressive not Fair (80 gt 50)`() {
        val aggrResult = BackwardInductionSolver.solve(afterAggressive, W)!!
        val fairResult = BackwardInductionSolver.solve(afterFair, W)!!
        val directorPayoffAggr = aggrResult.payoffs["NHM Director"]!!.weighted(W)
        val directorPayoffFair = fairResult.payoffs["NHM Director"]!!.weighted(W)
        assertTrue("Aggressive (80) > Fair (50) for Director",
            directorPayoffAggr > directorPayoffFair)
    }

    @Test
    fun `Fair split is NOT the SPNE despite equal payoffs`() {
        val result = BackwardInductionSolver.solve(root, W)!!
        assertFalse("Fair not chosen in SPNE",
            result.equilibriumPath.all { it.contains("Fair", ignoreCase = true) })
    }

    // ── SPNE uniqueness and structure ─────────────────────────────────────────

    @Test
    fun `equilibrium path length is exactly 2 (one Director move + one Lead move)`() {
        val result = BackwardInductionSolver.solve(root, W)!!
        assertEquals("Path: [Director action, Lead action]", 2, result.equilibriumPath.size)
    }

    @Test
    fun `tree has 4 terminal leaf nodes`() {
        fun countLeaves(node: SequenceNode): Int =
            if (node.children.isEmpty()) 1 else node.children.sumOf { countLeaves(it) }
        assertEquals("4 terminal outcomes", 4, countLeaves(root))
    }

    // ── Full scenario summary ──────────────────────────────────────────────────

    @Test
    fun `full scenario summary — SPNE via backward induction`() {
        val result = BackwardInductionSolver.solve(root, W)!!
        val aggrSubgame = BackwardInductionSolver.solve(afterAggressive, W)!!
        val fairSubgame = BackwardInductionSolver.solve(afterFair, W)!!

        val spnePath       = result.equilibriumPath
        val directorPayoff = result.payoffs["NHM Director"]!!.weighted(W)
        val leadPayoff     = result.payoffs["Clinical Lead"]!!.weighted(W)

        assertEquals("SPNE Director payoff = 80", 80.0, directorPayoff, 1e-9)
        assertEquals("SPNE Lead payoff = 20",     20.0, leadPayoff,     1e-9)
        assertTrue("Path includes Aggressive",
            spnePath.any { it.contains("Aggressive", ignoreCase = true) })
        assertTrue("Path includes Accept", spnePath.contains("Accept"))

        println("""
            ═══════════════════════════════════════════════════════
             CDRC Funding Ultimatum — Backward Induction Engine Trace
            ═══════════════════════════════════════════════════════
             Scenario:   NHM Director proposes budget split; Clinical Lead accepts or rejects.
                         Funds lapse (0, 0) if rejected — ultimatum structure.

             Step 1 — Backward Induction: Clinical Lead's subgames
               After Aggressive: Accept (20) > Reject (0)
                 → Lead plays Accept. Lead payoff = ${"%.1f".format(aggrSubgame.payoffs["Clinical Lead"]!!.weighted(W))}
               After Fair:       Accept (50) > Reject (0)
                 → Lead plays Accept. Lead payoff = ${"%.1f".format(fairSubgame.payoffs["Clinical Lead"]!!.weighted(W))}

             Step 2 — NHM Director's decision (knowing Lead always accepts):
               Aggressive → Director gets 80;  Fair → Director gets 50
               80 > 50 → Director plays Propose Aggressive.

             SPNE: $spnePath
               NHM Director = ${"%.1f".format(directorPayoff)}, Clinical Lead = ${"%.1f".format(leadPayoff)}

             Key insight: Backward induction strips non-credible threats.
               Reject yields 0 at the actual decision node — a rational agent
               will never follow through. Director exploits this to maximise payoff.
            ═══════════════════════════════════════════════════════
        """.trimIndent())
    }
}
