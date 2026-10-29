package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.PriorId
import com.drkkr.strategos.domain.agent.XRayNotEnough
import com.drkkr.strategos.domain.agent.XRayPriors
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.*
import org.junit.Test

class XRayPriorsTest {

    @Test fun TC_XRAY_01_priorsLabelWithTheCounterpartName() {
        val priors = XRayPriors.of(Fx.loanFamily())
        val takes = priors.single { it.id == PriorId.TAKES_LIMIT_WELL }
        assertEquals("Brother takes it well", takes.label)
        assertEquals(XRayPriors.COACH_DEFAULT, takes.value, 0.0)   // nothing learned yet
        assertFalse(takes.isUserSet)
    }

    @Test fun TC_XRAY_02_withPriorsSetsCounterpartFieldClamped() {
        val after = XRayPriors.withPriors(Fx.loanFamily(), mapOf(PriorId.TAKES_LIMIT_WELL to 0.99))
        val bro = after.stakeholders.first { it.id == "bro" }
        assertEquals(0.90, bro.probRespectsBoundary!!, 1e-9)       // clamped to MAX
        assertEquals(FactStatus.USER_ESTIMATE, bro.factStatus)      // not vouched without markUser
    }

    @Test fun TC_XRAY_03_keepMarksUserAndValueReadsBack() {
        val after = XRayPriors.withPriors(Fx.loanFamily(), mapOf(PriorId.TRUST_BASELINE to 0.7), markUser = true)
        val priors = XRayPriors.of(after)
        val trust = priors.single { it.id == PriorId.TRUST_BASELINE }
        assertEquals(0.7, trust.value, 1e-9)
        assertTrue(trust.isUserSet)
    }

    @Test fun TC_XRAY_04_resetClearsBackToDefault() {
        val kept = XRayPriors.withPriors(Fx.loanFamily(), mapOf(PriorId.SOFTENS_ON_REPAIR to 0.8), markUser = true)
        val reset = XRayPriors.reset(kept, setOf(PriorId.SOFTENS_ON_REPAIR))
        assertNull(reset.stakeholders.first { it.id == "bro" }.probSoftensOnRepair)
        assertEquals(XRayPriors.COACH_DEFAULT, XRayPriors.of(reset).single { it.id == PriorId.SOFTENS_ON_REPAIR }.value, 0.0)
    }

    @Test fun TC_XRAY_05_notEnoughOnlyWhenExploratoryOrNoPick() {
        assertNull(XRayNotEnough.from(exploratoryOnly = false, hasRecommendedMove = true))
        assertNotNull(XRayNotEnough.from(exploratoryOnly = true, hasRecommendedMove = true))
        assertNotNull(XRayNotEnough.from(exploratoryOnly = false, hasRecommendedMove = false))
    }

    @Test fun TC_XRAY_06_notEnoughPhrasesTheFirstMissingFactAsAQuestion() {
        val p = XRayNotEnough.from(exploratoryOnly = true, hasRecommendedMove = false, firstMissing = "What your brother wants")!!
        assertEquals("To weigh this, I need to know: what your brother wants.", p.question)
    }
}
