package com.drkkr.strategos

import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import com.drkkr.strategos.framework.behavioral.RelationalRepairModule
import com.drkkr.strategos.framework.behavioral.RepairInputs
import com.drkkr.strategos.framework.behavioral.computeRepair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the Relational Repair Game (melting-prize stag hunt).
 * Pure-function invariants plus one regression guard for the decaying
 * prize_trajectory calc key surfaced to the UI.
 */
class RelationalRepairTests {

    private fun inputs(
        S: Double = 0.8, q: Double = 0.9, H: Double = 0.6, eH: Double = 0.1,
        R: Double = 0.2, A: Double = 0.3, Fe: Double = 0.1,
        historyGrowth: Double = 0.12, prizeDecay: Double = 0.08
    ) = RepairInputs(S, q, H, eH, R, A, Fe, historyGrowth, prizeDecay)

    @Test
    fun `survives equals left greater than right`() {
        val out = computeRepair(inputs(), horizon = 8, pStar = null)
        // left = S*q*(H+eH-R) = 0.8*0.9*0.5 = 0.36 ; right = A-Fe = 0.2
        assertEquals(0.36, out.left, 1e-9)
        assertEquals(0.20, out.right, 1e-9)
        assertTrue(out.survives)
    }

    @Test
    fun `bond fails when pull-to-leave dominates`() {
        val out = computeRepair(inputs(A = 0.9, Fe = 0.0), horizon = 8, pStar = null)
        assertTrue(out.right > out.left)
        assertTrue(!out.survives)
    }

    @Test
    fun `maintained history trajectory grows monotonically`() {
        val out = computeRepair(inputs(historyGrowth = 0.12), horizon = 10, pStar = null)
        for (t in 1 until out.historyPath.size) {
            assertTrue(out.historyPath[t] >= out.historyPath[t - 1])
        }
    }

    @Test
    fun `renewal half-life is infinite when renewal probability is certain`() {
        val out = computeRepair(inputs(q = 1.0), horizon = 8, pStar = null)
        assertTrue(out.renewalHalflife.isInfinite())
    }

    @Test
    fun `renewal half-life is finite and positive when probability below one`() {
        val out = computeRepair(inputs(q = 0.9), horizon = 8, pStar = null)
        assertTrue(out.renewalHalflife.isFinite())
        assertTrue(out.renewalHalflife > 0.0)
    }

    @Test
    fun `renewal survival is q to the horizon`() {
        val out = computeRepair(inputs(q = 0.8), horizon = 5, pStar = null)
        assertEquals(Math.pow(0.8, 5.0), out.renewalSurvival, 1e-9)
    }

    @Test
    fun `no repair deadline when prize does not decay`() {
        val out = computeRepair(inputs(prizeDecay = 0.0), horizon = 12, pStar = null)
        // initialPrize (H+eH) stays above R+Fe forever -> t* never triggers
        assertEquals(-1, out.tStar)
    }

    // ── Regression guard: prize_trajectory must DECAY, not grow ───────────────
    // Guards the fix where the UI previously charted the growing history path
    // in place of the melting prize.
    @Test
    fun `analyze exposes a strictly decaying prize_trajectory`() {
        val scenario = Scenario(
            id = "repair-1",
            title = "Rebuild the bond",
            description = "Reconnect and mend a frayed relationship over time.",
            repeated = true,
            timeHorizon = 8,
            stakeholders = listOf(
                Stakeholder(name = "Partner A", credibility = 6),
                Stakeholder(name = "Partner B", credibility = 6)
            )
        )

        val result = RelationalRepairModule().analyze(scenario)

        @Suppress("UNCHECKED_CAST")
        val prize = result.calculations["prize_trajectory"] as? List<Double>
        assertTrue("prize_trajectory must be present", prize != null && prize.size > 1)
        prize!!
        for (t in 1 until prize.size) {
            assertTrue("prize must decay at t=$t", prize[t] < prize[t - 1])
        }
    }
}
