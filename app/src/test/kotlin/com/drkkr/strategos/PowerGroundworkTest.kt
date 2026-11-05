package com.drkkr.strategos

import com.drkkr.strategos.domain.human.Ethics
import com.drkkr.strategos.domain.human.EthicsGate
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.human.Tactic
import com.drkkr.strategos.domain.human.TacticId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.drkkr.strategos.domain.human.power.PowerConstants
import org.junit.Test

/** Power Mode, PM0: the ethics tiers and the constants. */
class PowerGroundworkTest {
    private fun tactic(e: Ethics) = Tactic(TacticId.SAY_LESS, "L4", e, true, RelationshipDomain.values().toSet(), { _, _, _ -> true }, emptySet(), emptySet(), "x")

    @Test fun TC_PM_ENUM_01_theFourEthicsTiers() {
        assertEquals(listOf("BENIGN", "CONTEXT", "STRATEGIC", "MANIPULATIVE"), Ethics.values().map { it.name })
    }

    @Test fun TC_PM_ENUM_02_theHumanLayerGateNeverAllowsStrategicOrManipulative() {
        val ctxs = listOf<HumanContext?>(null, HumanContext(), HumanContext(theirVulnerable = "none"))
        for (e in listOf(Ethics.STRATEGIC, Ethics.MANIPULATIVE)) for (d in RelationshipDomain.values()) for (support in listOf(true, false)) for (c in ctxs)
            assertFalse("$e $d support=$support", EthicsGate.allowed(tactic(e), c, d, support))
    }

    @Test fun TC_PM_CONST_01_defaultsAreTheAgreedValues() {
        val c = PowerConstants.DEFAULT
        assertEquals(mapOf(RelationshipDomain.NEGOTIATION to 0.15, RelationshipDomain.ADVERSARIAL to 0.20,
            RelationshipDomain.WORK_PEER to 0.25, RelationshipDomain.WORK_HIERARCHY to 0.30), c.qBase)
        assertEquals(0.15, c.qContinuing, 0.0)
        assertEquals(0.15, c.qSuspicious, 0.0)
        assertEquals(-0.10, c.qNewCounterpart, 0.0)
        assertEquals(0.05, c.qMin, 0.0)
        assertEquals(0.8, c.qMax, 0.0)
        assertEquals(3, c.maxCardMoves)
        assertEquals(2, c.maxDeliveryMoves)
        assertEquals(2.0, c.closeCallFactor, 0.0)
        assertEquals(0.10, c.minEv, 0.0)
        assertEquals(0.3, c.defensiveProbeGain, 0.0)
        assertTrue(c.qMin < c.qMax)
        for (d in RelationshipDomain.values().filter { !it.isClose }) assertTrue("$d has a base", d in c.qBase)
    }
}
