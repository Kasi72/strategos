package com.drkkr.strategos

import com.drkkr.strategos.domain.game.BackwardInductionSolver
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.SequenceNode
import org.junit.Assert.*
import org.junit.Test

class BackwardInductionSolverTest {

    private val W = mapOf(PayoffDimension.WELLBEING to 1.0)

    private fun payoff(vararg pairs: Pair<String, Double>): Map<String, MultiPayoff> =
        pairs.associate { (id, v) -> id to MultiPayoff(mapOf(PayoffDimension.WELLBEING to v)) }

    // Leaf with payoffs → returned as-is.
    @Test
    fun `leaf node returns its payoffs`() {
        val leaf = SequenceNode(id = "l1", stakeholderId = "Alice", actionLabel = "Accept",
            terminalPayoffs = payoff("Alice" to 8.0, "Bob" to 3.0))
        val result = BackwardInductionSolver.solve(leaf, W)
        assertNotNull(result)
        assertEquals(listOf("Accept"), result!!.equilibriumPath)
        assertEquals(8.0, result.payoffs["Alice"]!!.weighted(W), 1e-9)
    }

    // Null root → null result.
    @Test
    fun `null root returns null`() {
        assertNull(BackwardInductionSolver.solve(null, W))
    }

    // Simple 2-level tree: Alice moves first, picks the child that maximises her payoff.
    @Test
    fun `decision node picks best child`() {
        val lowLeaf  = SequenceNode("l1", "Alice", "Low",  terminalPayoffs = payoff("Alice" to 2.0))
        val highLeaf = SequenceNode("l2", "Alice", "High", terminalPayoffs = payoff("Alice" to 7.0))
        val root = SequenceNode(id = "r", stakeholderId = "Alice", actionLabel = "Root",
            children = listOf(lowLeaf, highLeaf))
        val result = BackwardInductionSolver.solve(root, W)
        assertNotNull(result)
        assertTrue("SPE path should include High", result!!.equilibriumPath.contains("High"))
        assertEquals(7.0, result.payoffs["Alice"]!!.weighted(W), 1e-9)
    }

    // Chance node computes expectation.
    @Test
    fun `chance node returns expected payoff`() {
        val leafA = SequenceNode("la", null, "Rain",     probability = 0.4, terminalPayoffs = payoff("You" to 0.0))
        val leafB = SequenceNode("lb", null, "Sunshine", probability = 0.6, terminalPayoffs = payoff("You" to 10.0))
        val root  = SequenceNode("r",  null, "Nature",   children = listOf(leafA, leafB))
        val result = BackwardInductionSolver.solve(root, W)
        assertNotNull(result)
        assertEquals(6.0, result!!.payoffs["You"]!!.weighted(W), 1e-9)
    }

    // 3-level tree: Bob moves after Alice; SPE found by backward induction.
    @Test
    fun `two-level sequential game SPE`() {
        // Alice: Cooperate or Defect. If she cooperates, Bob: Trust or Distrust.
        val bobTrust    = SequenceNode("bt", "Bob", "Trust",    terminalPayoffs = payoff("Alice" to 5.0, "Bob" to 5.0))
        val bobDistrust = SequenceNode("bd", "Bob", "Distrust", terminalPayoffs = payoff("Alice" to 2.0, "Bob" to 7.0))
        val cooperate   = SequenceNode("c",  "Bob", "Cooperate", children = listOf(bobTrust, bobDistrust))
        val defect      = SequenceNode("d",  "Alice", "Defect",    terminalPayoffs = payoff("Alice" to 3.0, "Bob" to 1.0))
        val root        = SequenceNode("r",  "Alice", "Root",      children = listOf(cooperate, defect))

        val result = BackwardInductionSolver.solve(root, W)
        assertNotNull(result)
        // Bob always picks Distrust (7 > 5), so Alice gets 2 from Cooperate vs 3 from Defect → Alice Defects.
        assertTrue("SPE should include Defect", result!!.equilibriumPath.contains("Defect"))
        assertEquals(3.0, result.payoffs["Alice"]!!.weighted(W), 1e-9)
    }
}
