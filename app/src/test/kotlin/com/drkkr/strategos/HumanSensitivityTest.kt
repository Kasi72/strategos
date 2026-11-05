package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.human.HumanSensitivity
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, HL2.2: does the pick only exist because of a Human Layer constant? (I-H6) */
class HumanSensitivityTest {
    private val REL = PayoffDimension.RELATIONSHIPS

    private fun raise(a1Rel: Double): Scenario {
        val s = Fx.hlRaise()
        val tagged = s.copy(
            actions = s.actions.map { if (it.id == "a1") it.copy(description = "[posture:repair][f:corrects_them,public]") else it },
            payoffs = s.payoffs.map {
                if (it.stakeholderId == "mgr" && it.actionProfile["you"] == "a1" && it.actionProfile["mgr"] == "r1")
                    it.copy(payoff = MultiPayoff(it.payoff.values + (REL to a1Rel))) else it
            }
        )
        return tagged
    }

    /** A stand-in for the engine: the owner's move the manager is most receptive to (their RELATIONSHIPS score in the view). */
    private val pick: (Scenario) -> String? = { v ->
        v.actionsFor("you").maxByOrNull { a ->
            v.payoffs.single { it.stakeholderId == "mgr" && it.actionProfile["you"] == a.id && it.actionProfile["mgr"] == "r1" }.payoff.values.getValue(REL)
        }?.id
    }

    @Test
    fun TC_HL_SENS_01_aPickThatFlipsWhenTheConstantsAreHalvedIsFragile() {
        // a1 is public + corrects them (face cost). a1 7.2 vs a2 6.0: with the default 1.5 a2 leads; with half the cost a1 leads.
        val s = raise(7.2)
        assertEquals("a2", pick(com.drkkr.strategos.domain.human.HumanizedView.apply(s)))
        assertEquals("a1", pick(com.drkkr.strategos.domain.human.HumanizedView.apply(s, com.drkkr.strategos.domain.human.HumanConstants.DEFAULT.scaled(0.5))))
        assertTrue(HumanSensitivity.pickChanges(s, pick))
    }

    @Test
    fun TC_HL_SENS_01b_theDirectiveIsFragileWhenTheHumanLayerSaysSo() {
        val s = Fx.loanFamily()
        val pr = AnalysisPipeline().run(s)
        assertEquals("ROBUST", EngineDirectiveBuilder.build(s, pr, null).stressVerdict)
        val d = EngineDirectiveBuilder.build(s, pr, null, humanFragile = true)
        assertEquals("FRAGILE", d.stressVerdict)
        assertFalse(d.stressFlip.isNullOrBlank())                       // the coach is told why, in plain words
        assertTrue(d.badge().contains("Fragile"))
    }

    @Test
    fun TC_HL_SENS_02_clearMarginsAreNotFlagged() {
        assertFalse(HumanSensitivity.pickChanges(raise(9.0), pick))     // 9.0 wins at half, default and double the face cost
    }

    @Test
    fun TC_HL_SENS_03_withoutAnyHumanEffectNothingIsRunAndNothingIsFlagged() {
        var calls = 0
        assertFalse(HumanSensitivity.pickChanges(Fx.hlRaise()) { calls++; "a1" })
        assertEquals(0, calls)
        assertFalse(HumanSensitivity.pickChanges(Fx.pd()) { calls++; "x" })
        assertEquals(0, calls)
        // and with the real engine
        assertFalse(HumanSensitivity.pickChanges(Fx.hlRaise(), AnalysisPipeline()))
        assertFalse(HumanSensitivity.pickChanges(Fx.loanFamily(), AnalysisPipeline()))
    }
}
