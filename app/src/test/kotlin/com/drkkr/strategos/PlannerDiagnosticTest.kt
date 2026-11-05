package com.drkkr.strategos

import com.drkkr.strategos.domain.human.DeliveryPlanner
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.TacticId
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, hl3.5: the planner diagnostic, and PUT_IT_IN_WRITING no longer needs a money dimension. */
class PlannerDiagnosticTest {
    /** The family-loan story as the live model drew it: no COST dimension at all. */
    private fun loanWithoutCost(ctx: HumanContext): Scenario = Fx.loanFamily().let { s ->
        s.copy(
            payoffs = s.payoffs.map { e -> e.copy(payoff = MultiPayoff(e.payoff.values.filterKeys { it != PayoffDimension.COST })) },
            humanContext = ctx
        )
    }

    private fun writes(s: Scenario, move: String = "give") =
        DeliveryPlanner.diagnose(s, s.humanContext!!, s.actions.first { it.id == move }, false)
            .single { it.id == TacticId.PUT_IT_IN_WRITING }

    private val verbal = "Arun keeps asking for the 3 lakh with only a verbal promise to repay; nothing is written down."

    @Test
    fun TC_HL_WRITE_01_aFamilyLoanWithNoCostDimensionStillGetsAWrittenSummary() {
        val s = loanWithoutCost(HumanContext(relationship = "sibling", statusQuo = verbal))
        assertTrue(s.payoffs.none { PayoffDimension.COST in it.payoff.values.keys })
        val d = writes(s)
        assertTrue(d.gatePass); assertTrue(d.preconditionPass)
        val plan = DeliveryPlanner.plan(s, s.humanContext!!, s.actions.first { it.id == "give" }, s.actions.first { it.id == "wait" })
        assertTrue(plan.tactics.any { it.id == TacticId.PUT_IT_IN_WRITING })
    }

    @Test
    fun TC_HL_WRITE_02_aCofounderSplitNeedsNoKeywordAtAll() {
        assertTrue(writes(loanWithoutCost(HumanContext(relationship = "cofounder"))).preconditionPass)
    }

    @Test
    fun TC_HL_WRITE_03_aNegotiationWithFiguresGetsOne() {
        val s = loanWithoutCost(HumanContext(relationship = "vendor")).copy(negotiationContext = NegotiationContext(openingAnchor = "10 percent"))
        assertTrue(writes(s).preconditionPass)
    }

    @Test
    fun TC_HL_WRITE_04_notEveryFamilyOrWorkSituationNeedsPaper() {
        val plain = loanWithoutCost(HumanContext(relationship = "sibling", statusQuo = "He wants me to visit for the festival.")).copy(description = "A family visit", stakes = "Family peace")
        assertFalse("no agreement in play", writes(plain).preconditionPass)
        assertFalse("a spouse conflict is not a deal", writes(loanWithoutCost(HumanContext(relationship = "spouse", statusQuo = verbal))).preconditionPass)
        assertFalse("a boss is not in scope", writes(loanWithoutCost(HumanContext(relationship = "manager", statusQuo = verbal))).preconditionPass)
        val written = loanWithoutCost(HumanContext(relationship = "sibling", statusQuo = verbal)).let { s ->
            s.copy(actions = s.actions.map { if (it.id == "give") it.copy(description = "[f:written]") else it })
        }
        assertFalse("already in writing", writes(written).preconditionPass)
    }

    @Test
    fun TC_HL_DIAG_01_theTableCoversEveryTacticAndSaysWhyOneDidNotRun() {
        val s = loanWithoutCost(HumanContext(relationship = "sibling", statusQuo = verbal, theirVulnerable = "child"))
        val rows = DeliveryPlanner.diagnose(s, s.humanContext!!, s.actions.first { it.id == "give" }, false)
        assertEquals(19, rows.size)
        assertTrue("a vulnerable counterpart stops every tactic at the gate", rows.none { it.gatePass })
        val open = loanWithoutCost(HumanContext(relationship = "sibling", statusQuo = verbal))
        val ok = DeliveryPlanner.diagnose(open, open.humanContext!!, open.actions.first { it.id == "give" }, false)
        assertNull("no gain is computed for a precondition-only tactic", ok.single { it.id == TacticId.PUT_IT_IN_WRITING }.deltaP)
        assertTrue(ok.single { it.id == TacticId.PUT_IT_IN_WRITING }.preconditionOnly)
        assertTrue(ok.filter { it.gatePass && it.preconditionPass }.size >= 1)
    }
}
