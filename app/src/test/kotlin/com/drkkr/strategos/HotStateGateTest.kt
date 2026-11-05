package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.human.DeliveryPlanner
import com.drkkr.strategos.domain.human.HotStateGate
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.Timing
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, HL4: protect the cold Planner from the hot Doer (Nudge Ch.2). The gate changes WHEN, never WHETHER. */
class HotStateGateTest {
    private fun move(posture: String, vararg tags: String) =
        Action(stakeholderId = "you", label = "Send it", description = "[posture:$posture]" + if (tags.isEmpty()) "" else "[f:${tags.sorted().joinToString(",")}]")

    private val hot = HumanContext(yourStateLevel = 2.5)

    @Test
    fun TC_HL_HOT_01_aHotUserAndAnIrreversibleOrImmediateMoveWaits() {
        assertEquals(Timing.AFTER_COOLING, HotStateGate.timing(hot, move("boundary", "irreversible"), false, false))
        assertEquals(Timing.AFTER_COOLING, HotStateGate.timing(hot, move("repair", "immediate"), false, false))
        assertEquals(Timing.AFTER_COOLING, HotStateGate.timing(HumanContext(yourStateLevel = 2.0), move("boundary", "irreversible"), false, false))   // 2.0 counts
        // a boundary said in public is a confrontation, even if it is not tagged immediate
        assertEquals(Timing.AFTER_COOLING, HotStateGate.timing(hot, move("boundary", "public"), false, false))
    }

    @Test
    fun TC_HL_HOT_02_aCalmUserOrACalmMoveIsNotHeld() {
        assertEquals(Timing.NOW, HotStateGate.timing(HumanContext(yourStateLevel = 0.5), move("boundary", "irreversible"), false, false))
        assertEquals(Timing.NOW, HotStateGate.timing(HumanContext(yourStateLevel = 1.9), move("boundary", "irreversible"), false, false))
        assertEquals("hot, but a reversible private move", Timing.NOW, HotStateGate.timing(hot, move("repair", "credits_them"), false, false))
        assertEquals("a public REPAIR is not a confrontation", Timing.NOW, HotStateGate.timing(hot, move("repair", "public"), false, false))
        assertEquals(Timing.NOW, HotStateGate.timing(null, move("boundary", "irreversible"), false, false))
        assertEquals(Timing.NOW, HotStateGate.timing(hot, null, false, false))
    }

    @Test
    fun TC_HL_HOT_03_aSafetyNeedAlwaysWinsAndLeavesNoDelivery() {
        assertEquals(Timing.NOW, HotStateGate.timing(hot, move("boundary", "irreversible"), support = true, userSaysNow = false))
        val s = Fx.hlSpouse()
        val rec = s.actions.first { it.id == "s2" }
        assertTrue(DeliveryPlanner.plan(s, s.humanContext!!, rec, support = true, userSaysNow = false).isEmpty)
    }

    @Test
    fun TC_HL_HOT_04_whenTheUserHasDecidedTheirChoiceWins() {
        assertEquals(Timing.NOW, HotStateGate.timing(hot, move("boundary", "irreversible"), false, userSaysNow = true))
        for (said in listOf("I'm sending it tonight anyway", "I'm going to tell her now", "I already decided to send it", "I will send it right now"))
            assertTrue(said, HotStateGate.userSaysNow(said))
        for (said in listOf("Should I send it?", "What time is best to tell her?", "She went quiet and I'm furious", "It's past midnight"))
            assertFalse(said, HotStateGate.userSaysNow(said))
    }

    @Test
    fun TC_HL_HOT_05_aHotCounterpartMeansWaitForTheirCalmWhenTheMoveNeedsTheirYes() {
        val upset = HumanContext(yourStateLevel = 0.5, theirStateLevel = 2.5)
        assertEquals(Timing.WHEN_CALM, HotStateGate.timing(upset, move("repair", "asks_them_to_give_up"), false, false))
        assertEquals(Timing.WHEN_CALM, HotStateGate.timing(upset, move("repair"), false, false))
        assertEquals("a move that asks nothing of them", Timing.NOW, HotStateGate.timing(upset, move("boundary", "withdraws"), false, false))
        assertEquals("they are calm", Timing.NOW, HotStateGate.timing(HumanContext(theirStateLevel = 1.0), move("repair", "asks_them_to_give_up"), false, false))
        // both hot: the user's own heat is the bigger risk
        assertEquals(Timing.AFTER_COOLING, HotStateGate.timing(HumanContext(yourStateLevel = 2.5, theirStateLevel = 2.5), move("boundary", "irreversible"), false, false))
    }

    @Test
    fun TC_HL_HOT_07_thePlannerCarriesTheTimingEvenWithNoTactic() {
        val s = Fx.hlSpouse().let { sp -> sp.copy(actions = sp.actions.map { if (it.id == "s2") it.copy(description = "[posture:boundary][f:irreversible]") else it }) }
        val d = DeliveryPlanner.plan(s, s.humanContext!!, s.actions.first { it.id == "s2" })
        assertEquals(Timing.AFTER_COOLING, d.timing)
        assertFalse("a timing alone is a delivery", d.isEmpty)
        val calm = DeliveryPlanner.plan(s.copy(humanContext = s.humanContext!!.copy(yourStateLevel = 0.0)), s.humanContext!!.copy(yourStateLevel = 0.0), s.actions.first { it.id == "s2" })
        assertEquals(Timing.NOW, calm.timing)
        val said = DeliveryPlanner.plan(s, s.humanContext!!, s.actions.first { it.id == "s2" }, userSaysNow = true)
        assertEquals(Timing.NOW, said.timing)
    }

    @Test
    fun TC_HL_COACH_04_ruleTwentyHandlesBothTimings_andRuleTenAsksForAWhenAndWhere() {
        val p = ArgonPrompts.ENGINE_COACH_PROMPT
        val rule20 = p.substringAfter("\n20. TIMING").substringBefore("\n22. ")
        assertTrue(rule20.contains("AFTER_COOLING") && rule20.contains("WHEN_CALM"))
        assertTrue(rule20.contains("draft it now, send it at"))
        assertTrue(rule20.contains("pending_cooling") && rule20.contains("did you send it", ignoreCase = true))
        val rule10 = p.substringAfter("\n10. ").substringBefore("\n11. ")
        assertTrue(rule10, rule10.contains("when-and-where") || rule10.contains("when and where"))
    }
}
