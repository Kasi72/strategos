package com.drkkr.strategos

import com.drkkr.strategos.domain.human.Ethics
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.human.RelationshipDomains
import com.drkkr.strategos.domain.human.Tactic
import com.drkkr.strategos.domain.human.power.PowerConstants
import com.drkkr.strategos.domain.human.power.PowerPlan
import com.drkkr.strategos.domain.human.power.PowerPlanner
import com.drkkr.strategos.domain.human.power.StrategicCatalog
import com.drkkr.strategos.domain.human.power.StrategicId
import com.drkkr.strategos.domain.human.power.StrategicTactic
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Power Mode, PM2.3: the planner (I-P3): rank the allowed hardball moves by EV after exposure, and say so when hardball doesn't pay. */
class PowerPlannerTest {
    private fun domainOf(s: Scenario) = RelationshipDomains.of(s.humanContext?.relationship, null)
    private fun plan(
        s: Scenario, on: Boolean = true, support: Boolean = false, lock: Boolean = false, benignBest: Double = 0.0,
        tactics: List<StrategicTactic> = StrategicCatalog.tactics
    ): PowerPlan = PowerPlanner.plan(s, domainOf(s), on, support, lock, 50.0, benignBest, PowerConstants.DEFAULT, tactics)

    private fun isEmpty(p: PowerPlan) = p.cardMoves.isEmpty() && p.deliveryMoves.isEmpty() && p.noHardballReason == null && p.closeDomainNote == null

    @Test fun TC_PM_PLAN_01_offMeansNothing() = assertTrue(isEmpty(plan(Fx.pmVendor(), on = false)))

    @Test fun TC_PM_PLAN_02_aVendorGetsUpToThreePositiveMovesIncludingHoldYourNumber() {
        val p = plan(Fx.pmVendor())
        assertTrue(p.cardMoves.size in 1..3)
        assertTrue(p.cardMoves.all { it.ev > 0 })
        assertEquals(p.cardMoves.sortedByDescending { it.ev }, p.cardMoves)
        assertTrue(p.cardMoves.map { it.tactic.id }.toString(), p.cardMoves.any { it.tactic.id == StrategicId.HOLD_YOUR_NUMBER })
        assertTrue(p.deliveryMoves.size <= 2)
        assertNull(p.noHardballReason)
    }

    @Test fun TC_PM_PLAN_03_aSpouseGetsNoMovesAndTheCloseRelationshipNote() {
        val p = plan(Fx.pmSpouse())
        assertTrue(p.cardMoves.isEmpty())
        assertTrue(p.deliveryMoves.isEmpty())
        assertEquals("Hardball moves are for work and negotiations — here's how the dynamics look.", p.closeDomainNote)
    }

    @Test fun TC_PM_PLAN_04_whenHardballDoesntPayTheCardSaysWhy() {
        val small = StrategicCatalog.tactics.map { it.copy(gain = it.gain * 0.1) }
        val p = plan(Fx.pmManager(), tactics = small)
        assertTrue(p.cardMoves.isEmpty())
        assertEquals("a high chance they'd see through it", p.noHardballReason)
    }

    @Test fun TC_PM_PLAN_05_aStrongerBenignMoveKeepsHardballOutOfTheDelivery() {
        val p = plan(Fx.pmVendor(), benignBest = 5.0)
        assertFalse(p.cardMoves.isEmpty())
        assertTrue(p.deliveryMoves.isEmpty())
    }

    @Test fun TC_PM_PLAN_06_aLeaderThatFlipsWhenQIsHalvedOrDoubledIsACloseCall() {
        fun custom(id: StrategicId, gain: Double, loss: Double) = StrategicTactic(
            Tactic(id, "L4", Ethics.STRATEGIC, true, setOf(RelationshipDomain.NEGOTIATION), { _, _, _ -> true }, emptySet(), emptySet(), "x"), gain, loss)
        // vendor q = 0.05: X 0.875 vs Y 0.8075 at q; at 2q = 0.10, X 0.75 vs Y 0.765 -> the leader flips
        val flips = plan(Fx.pmVendor(), tactics = listOf(custom(StrategicId.HOLD_YOUR_NUMBER, 1.0, 1.0), custom(StrategicId.STRATEGIC_SILENCE, 0.85, 0.0)))
        assertEquals(StrategicId.HOLD_YOUR_NUMBER, flips.cardMoves.first().tactic.id)
        assertTrue(flips.cardMoves.first().closeCall)
        val clear = plan(Fx.pmVendor(), tactics = listOf(custom(StrategicId.HOLD_YOUR_NUMBER, 1.0, 1.0), custom(StrategicId.STRATEGIC_SILENCE, 0.3, 0.0)))
        assertFalse(clear.cardMoves.first().closeCall)
    }

    @Test fun TC_PM_PLAN_07_supportAVulnerableCounterpartOrTheLockMeanNothing() {
        assertTrue(isEmpty(plan(Fx.pmVendor(), support = true)))
        val child = Fx.pmVendor().let { it.copy(humanContext = it.humanContext!!.copy(theirVulnerable = "child")) }
        assertTrue(isEmpty(plan(child)))
        assertTrue(isEmpty(plan(Fx.pmVendor(), lock = true)))
    }

    @Test fun theOtherTwoReasons() {
        // a cofounder (q 0.40, continuing): small gains -> "continues"
        val small = StrategicCatalog.tactics.map { it.copy(gain = it.gain * 0.1) }
        assertEquals("this relationship continues after the deal", plan(Fx.pmCofounder(), tactics = small).noHardballReason)
        // a vendor (q 0.05, not continuing): tiny gains, high losses -> "the gain is small here"
        val tiny = StrategicCatalog.tactics.map { it.copy(gain = 0.001, exposureLoss = 1.0) }
        assertEquals("the gain is small here", plan(Fx.pmVendor(), tactics = tiny).noHardballReason)
    }
}
