package com.drkkr.strategos

import com.drkkr.strategos.domain.human.Channel
import com.drkkr.strategos.domain.human.DeliveryPlanner
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.TacticCatalog
import com.drkkr.strategos.domain.human.TacticId
import com.drkkr.strategos.domain.human.Timing
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, HL3.3: the delivery planner. It keeps the engine's pick and chooses how to deliver it. */
class DeliveryPlannerTest {
    private val LTV = PayoffDimension.LONG_TERM_VALUE
    private val REL = PayoffDimension.RELATIONSHIPS
    private val COST = PayoffDimension.COST

    private fun them(s: Scenario, move: String, reply: String, x: Double): Scenario = s.copy(payoffs = s.payoffs.map {
        if (it.stakeholderId == "mgr" && it.actionProfile["you"] == move && it.actionProfile["mgr"] == reply)
            it.copy(payoff = MultiPayoff(mapOf(LTV to x, REL to x, COST to 10 - x))) else it
    })

    private fun tag(s: Scenario, action: String, vararg tags: String): Scenario = s.copy(actions = s.actions.map {
        if (it.id == action) it.copy(description = it.description.substringBefore("[f:") + "[f:" + tags.sorted().joinToString(",") + "]") else it
    })

    /** hlRaise where a1 gets even odds (5 vs 5 for the manager) and the given tags and context. */
    private fun raise(vararg tags: String, ctx: (HumanContext) -> HumanContext = { it }): Scenario {
        var s = them(them(Fx.hlRaise(), "a1", "r1", 5.0), "a1", "r2", 5.0)
        s = them(s, "a1", "r1", 5.0)
        s = tag(s, "a1", *tags)
        return s.copy(humanContext = ctx(s.humanContext ?: HumanContext()))
    }

    private fun plan(s: Scenario, support: Boolean = false) =
        DeliveryPlanner.plan(s, s.humanContext!!, s.actions.first { it.id == "a1" }, second = s.actions.first { it.id == "a2" }, support = support)

    @Test
    fun TC_HL_DEL_01_aStallingCounterpartGetsADefaultWithADeadline() {
        val d = plan(raise("open_ended") { it.copy(deadline = "none") })
        val t = d.tactics.single { it.id == TacticId.DEFAULT_WITH_DEADLINE }
        assertTrue("delta p = ${t.deltaP}", t.deltaP >= 0.05)
        assertEquals("Ask with a plan", d.defaultOffer)
        assertEquals(Timing.NOW, d.timing)
    }

    @Test
    fun TC_HL_DEL_02_atMostTwoTacticsAreChosen_bestGainFirst() {
        val s = raise("open_ended", "asks_them_to_give_up", "public", "corrects_them", "appeals_to_fairness_or_gratitude") { it.copy(deadline = "none") }
        val d = plan(s)
        assertEquals(2, d.tactics.size)
        assertTrue(d.tactics.all { it.deltaP >= 0.05 })
        assertEquals(d.tactics.map { it.deltaP }.sortedDescending(), d.tactics.map { it.deltaP })
        // the winners are the biggest movers, not the first in the catalog
        val all = DeliveryPlanner.candidates(s, s.humanContext!!, s.actions.first { it.id == "a1" }, false)
        assertTrue("more than two qualified: ${all.map { it.first.id }}", all.size > 2)
        assertEquals(all.sortedByDescending { it.second }.take(2).map { it.first.id }.toSet(), d.tactics.map { it.id }.toSet())
    }

    @Test
    fun TC_HL_DEL_03_conflictingTacticsKeepOnlyTheLargerGain() {
        val byId = TacticCatalog.offered.associateBy { it.id }
        val two = byId.getValue(TacticId.TWO_GOOD_OPTIONS); val def = byId.getValue(TacticId.DEFAULT_WITH_DEADLINE)
        assertEquals(listOf(two), DeliveryPlanner.resolve(listOf(def to 0.10, two to 0.20)).map { it.first })
        assertEquals(listOf(def), DeliveryPlanner.resolve(listOf(two to 0.10, def to 0.20)).map { it.first })
        val back = byId.getValue(TacticId.STEP_BACK)
        assertEquals(listOf(back), DeliveryPlanner.resolve(listOf(def to 0.10, back to 0.30)).map { it.first })
        // a non-conflicting third one still gets in, up to two
        val future = byId.getValue(TacticId.FUTURE_COMMITMENT)
        assertEquals(listOf(two, future), DeliveryPlanner.resolve(listOf(def to 0.10, two to 0.20, future to 0.15)).map { it.first })
    }

    @Test
    fun TC_HL_DEL_04_aGainBelowTheThresholdIsNotChosen() {
        val def = TacticCatalog.offered.single { it.id == TacticId.DEFAULT_WITH_DEADLINE }
        assertTrue(DeliveryPlanner.resolve(listOf(def to 0.03)).isEmpty())
        assertEquals(1, DeliveryPlanner.resolve(listOf(def to 0.05)).size)
    }

    @Test
    fun TC_HL_DEL_05_aFamilyLoanGetsNoContextTacticAndAWrittenSummary() {
        val s = Fx.hlLoan()
        val d = DeliveryPlanner.plan(s, s.humanContext!!, s.actions.first { it.id == "give" }, second = s.actions.first { it.id == "wait" })
        val ids = d.tactics.map { it.id }
        assertTrue(ids.toString(), TacticId.PUT_IT_IN_WRITING in ids)
        assertTrue(ids.none { it in setOf(TacticId.STEP_BACK, TacticId.CONCEDE_SMALL_FIRST, TacticId.NAME_YOUR_LEVERAGE) })
        assertEquals(0.0, d.tactics.single { it.id == TacticId.PUT_IT_IN_WRITING }.deltaP, 0.0)       // needs no gain, only its condition
    }

    @Test
    fun TC_HL_DEL_06_aFaceCostMeansPrivate_aHotCounterpartMeansWritten() {
        val face = raise("corrects_them") { it.copy(theirPower = "they_higher") }
        assertEquals(Channel.PRIVATE, plan(face).channel)
        assertNull(plan(raise("open_ended") { it.copy(deadline = "none") }).channel)
        val hot = raise("corrects_them") { it.copy(theirPower = "they_higher", theirStateLevel = 2.5) }
        assertEquals(Channel.WRITTEN, plan(hot).channel)
        val warm = raise("corrects_them") { it.copy(theirPower = "they_higher", theirStateLevel = 1.0) }
        assertEquals(Channel.PRIVATE, plan(warm).channel)
        // a correction that is already credited has no face cost
        assertNull(plan(raise("corrects_them", "credits_them") { it.copy(theirPower = "they_higher") }).channel)
    }

    @Test
    fun TC_HL_DEL_07_aSafetyNeedAVulnerableCounterpartOrCoercionMeansAnEmptyDelivery() {
        val busy = raise("open_ended", "asks_them_to_give_up") { it.copy(deadline = "none") }
        assertFalse(plan(busy).isEmpty)
        assertTrue(plan(busy, support = true).isEmpty)
        assertTrue(plan(busy.copy(humanContext = busy.humanContext!!.copy(theirVulnerable = "elder_in_care"))).isEmpty)
        val coerced = busy.copy(humanContext = busy.humanContext!!.copy(relationship = "spouse", theirTactics = listOf("isolates_you")))
        assertTrue(plan(coerced).isEmpty)
        // ... but the same behaviour at work is ordinary
        assertFalse(plan(busy.copy(humanContext = busy.humanContext!!.copy(relationship = "peer", theirTactics = listOf("isolates_you")))).isEmpty)
    }

    @Test
    fun TC_HL_DEL_08_twoGoodOptionsOffersTheMoveAndTheSecondCalmMove() {
        val s = raise("open_ended") { it.copy(deadline = "none") }
        val rec = s.actions.first { it.id == "a1" }
        val d = DeliveryPlanner.assemble(s, s.humanContext!!, rec, s.actions.first { it.id == "a2" },
            listOf(TacticCatalog.offered.single { it.id == TacticId.TWO_GOOD_OPTIONS } to 0.2))
        assertEquals(listOf("Ask with a plan", "Hold the line"), d.optionsToOffer)
        assertNull(d.defaultOffer)
    }

    @Test
    fun TC_HL_DEL_09_sayLessIsAFlagNotATacticSlot() {
        val s = raise("argues") { it.copy(deadline = "none") }
        val d = plan(s)
        assertTrue(d.sayLess)
        assertTrue(d.tactics.none { it.id == TacticId.SAY_LESS })
        assertFalse(plan(raise("open_ended") { it.copy(deadline = "none") }).sayLess)
    }

    @Test
    fun TC_HL_DEL_10_everyPlannedHintIsPlainAndNeverNamesAnything() {
        val s = raise("open_ended", "asks_them_to_give_up", "public", "corrects_them", "appeals_to_fairness_or_gratitude") { it.copy(deadline = "none") }
        for (t in plan(s).tactics) assertFalse(t.hint, com.drkkr.strategos.domain.human.BannedWords.containedIn(t.hint))
    }
}
