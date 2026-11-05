package com.drkkr.strategos

import com.drkkr.strategos.domain.human.Ethics
import com.drkkr.strategos.domain.human.EthicsGate
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.human.Tactic
import com.drkkr.strategos.domain.human.TacticId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
