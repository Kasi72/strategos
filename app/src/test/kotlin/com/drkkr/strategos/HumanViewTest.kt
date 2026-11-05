package com.drkkr.strategos

import com.drkkr.strategos.domain.human.HumanConstants
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.HumanizedView
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.PayoffEntry
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, HL2.1: the counterpart's perceived payoffs, view-only. */
class HumanViewTest {
    private val LTV = PayoffDimension.LONG_TERM_VALUE
    private val REL = PayoffDimension.RELATIONSHIPS
    private val COST = PayoffDimension.COST

    private fun cell(s: Scenario, who: String, mine: String, theirs: String, d: PayoffDimension): Double =
        s.payoffs.single { it.stakeholderId == who && it.actionProfile["you"] == mine && it.actionProfile["mgr"] == theirs }.payoff.values.getValue(d)

    private fun setCell(s: Scenario, mine: String, theirs: String, d: PayoffDimension, v: Double): Scenario = s.copy(payoffs = s.payoffs.map {
        if (it.stakeholderId == "mgr" && it.actionProfile["you"] == mine && it.actionProfile["mgr"] == theirs)
            it.copy(payoff = MultiPayoff(it.payoff.values + (d to v))) else it
    })

    private fun tag(s: Scenario, action: String, vararg tags: String): Scenario = s.copy(actions = s.actions.map {
        if (it.id == action) it.copy(description = it.description.substringBefore("[f:") + "[f:" + tags.sorted().joinToString(",") + "]") else it
    })

    private fun ctx(s: Scenario, f: (HumanContext) -> HumanContext): Scenario = s.copy(humanContext = f(s.humanContext ?: HumanContext()))

    /** hlRaise where the move a1 is tagged and its receptive cell sits 2 below the status quo (REL 6) on RELATIONSHIPS only. */
    private fun base(vararg tags: String): Scenario {
        var s = Fx.hlRaise()
        s = setCell(s, "a1", "r1", LTV, 9.0)         // above the status quo: unchanged
        s = setCell(s, "a1", "r1", REL, 4.0)         // 2 below the status quo (6)
        s = setCell(s, "a1", "r1", COST, 2.0)        // better than the status quo (3)
        return tag(s, "a1", *tags)
    }

    @Test
    fun TC_HL_VIEW_01_lossBelowTheStatusQuoIsDoubled() {
        val v = HumanizedView.apply(base("asks_them_to_give_up"))
        assertEquals(2.0, cell(v, "mgr", "a1", "r1", REL), 1e-9)          // 4 -> a further 2 lost
        assertEquals(9.0, cell(v, "mgr", "a1", "r1", LTV), 1e-9)          // a gain is not touched
        assertEquals(2.0, cell(v, "mgr", "a1", "r1", COST), 1e-9)
        assertEquals(cell(base("asks_them_to_give_up"), "mgr", "a1", "r2", REL), cell(v, "mgr", "a1", "r2", REL), 0.0)   // defensive unchanged
    }

    @Test
    fun TC_HL_VIEW_02_futureCommitmentHalvesTheExtraLoss() {
        val v = HumanizedView.apply(base("asks_them_to_give_up", "future_commitment"))
        assertEquals(3.0, cell(v, "mgr", "a1", "r1", REL), 1e-9)          // the extra drop is 1, not 2
    }

    @Test
    fun TC_HL_VIEW_02b_aCostThatRisesIsALossToo_andNeutralStatusQuoSwitchesItOff() {
        var s = base("asks_them_to_give_up")
        s = setCell(s, "a1", "r1", REL, 7.0)         // no relationship loss in this probe
        s = setCell(s, "a1", "r1", COST, 5.0)        // 2 worse than the status quo cost (3)
        assertEquals(7.0, cell(HumanizedView.apply(s), "mgr", "a1", "r1", COST), 1e-9)      // 5 + 2 (up is worse for them)
        val neutral = ctx(base("asks_them_to_give_up")) { it.copy(statusQuoForThem = "neutral") }
        assertSame(neutral, HumanizedView.apply(neutral))                                    // nothing at stake: nothing to double
    }

    @Test
    fun TC_HL_VIEW_03_publicCorrectionCostsAHigherStatusCounterpartFace() {
        val v = HumanizedView.apply(base("public", "corrects_them").let { s -> setCell(s, "a1", "r1", REL, 6.0) })
        assertEquals(6.0 - 1.5, cell(v, "mgr", "a1", "r1", REL), 1e-9)
        val equalPower = base("public", "corrects_them").let { s -> setCell(s, "a1", "r1", REL, 6.0) }.let { s -> ctx(s) { it.copy(theirPower = "you_higher") } }
        assertSame(equalPower, HumanizedView.apply(equalPower))                              // they are not higher: no change at all
    }

    @Test
    fun TC_HL_VIEW_04_aPrivateCorrectionCostsAThird_andCreditingThemRemovesIt() {
        val priv = base("corrects_them").let { s -> setCell(s, "a1", "r1", REL, 6.0) }
        assertEquals(5.5, cell(HumanizedView.apply(priv), "mgr", "a1", "r1", REL), 1e-9)
        val credited = tag(priv, "a1", "corrects_them", "credits_them")
        assertSame(credited, HumanizedView.apply(credited))
    }

    @Test
    fun TC_HL_VIEW_05_appealingToGratitudeCostsInWorkButNotInFamily_theirInterestHelpsEverywhere() {
        val work = base("appeals_to_fairness_or_gratitude").let { s -> setCell(s, "a1", "r1", REL, 6.0) }
        val w = HumanizedView.apply(work)
        assertEquals(6.0 - 0.5, cell(w, "mgr", "a1", "r1", REL), 1e-9)
        assertEquals(9.0 - 0.5, cell(w, "mgr", "a1", "r1", LTV), 1e-9)
        assertEquals(2.0 + 0.5, cell(w, "mgr", "a1", "r1", COST), 1e-9)                     // cost moves the other way
        val family = ctx(work) { it.copy(relationship = "sibling") }
        assertSame(family, HumanizedView.apply(family))
        val interest = base("their_interest").let { s -> ctx(s) { it.copy(relationship = "sibling") } }.let { s -> setCell(s, "a1", "r1", REL, 6.0) }
        assertEquals(6.0 + 0.5, cell(HumanizedView.apply(interest), "mgr", "a1", "r1", REL), 1e-9)
    }

    @Test
    fun TC_HL_VIEW_06_anOpenEndedAskLetsDoingNothingWinUnlessThereIsADefaultOrDeadline() {
        val open = ctx(base("open_ended")) { it.copy(deadline = "none") }
        val v = HumanizedView.apply(open)
        assertEquals(cell(open, "mgr", "a1", "r2", REL) + 0.5, cell(v, "mgr", "a1", "r2", REL), 1e-9)
        assertEquals(cell(open, "mgr", "a1", "r2", COST) - 0.5, cell(v, "mgr", "a1", "r2", COST), 1e-9)
        val withDefault = tag(open, "a1", "open_ended", "sets_default")
        assertSame(withDefault, HumanizedView.apply(withDefault))
        val withDeadline = ctx(open) { it.copy(deadline = "soft") }
        assertSame(withDeadline, HumanizedView.apply(withDeadline))
        val unknown = ctx(open) { it.copy(deadline = null) }
        assertSame(unknown, HumanizedView.apply(unknown))                                    // an unknown deadline is not "none"
    }

    @Test
    fun TC_HL_VIEW_07_stackedEffectsAreCappedAndClamped() {
        var s = base("asks_them_to_give_up", "public", "corrects_them", "appeals_to_fairness_or_gratitude")
        s = setCell(s, "a1", "r1", LTV, 1.0)         // 4 below the status quo (5): loss -4 more, capped
        s = setCell(s, "a1", "r1", COST, 6.0)        // 3 worse than the status quo (3)
        val v = HumanizedView.apply(s)
        // REL: loss -2, face -1.5, gratitude -0.5 = -4  -> capped at -2.0
        assertEquals(4.0 - 2.0, cell(v, "mgr", "a1", "r1", REL), 1e-9)
        // LTV: loss -4, gratitude -0.5 -> capped at -2.0 -> -1.0 -> clamped to 0
        assertEquals(0.0, cell(v, "mgr", "a1", "r1", LTV), 1e-9)
        // COST: worse for them means UP: +3 (loss) +0.5 -> capped at +2.0
        assertEquals(8.0, cell(v, "mgr", "a1", "r1", COST), 1e-9)
        for (e in v.payoffs) for (x in e.payoff.values.values) assertTrue(x in 0.0..10.0)
    }

    @Test
    fun TC_HL_VIEW_08_theOwnersRowsAreNeverTouched() {
        val s = base("asks_them_to_give_up", "public", "corrects_them", "appeals_to_fairness_or_gratitude", "open_ended")
        val v = HumanizedView.apply(ctx(s) { it.copy(deadline = "none") })
        assertNotSame(s, v)
        assertEquals(s.payoffs.filter { it.stakeholderId == "you" }, v.payoffs.filter { it.stakeholderId == "you" })
        assertEquals(s.actions, v.actions); assertEquals(s.stakeholders, v.stakeholders)
        assertEquals(s.payoffs.size, v.payoffs.size)
    }

    @Test
    fun TC_HL_VIEW_09_returnsTheSameInstanceWhenThereIsNothingToDo() {
        val raise = Fx.hlRaise()
        assertSame(raise, HumanizedView.apply(raise))                                        // a context but no tags and no effects
        val noContext = base("asks_them_to_give_up", "public", "corrects_them").copy(humanContext = null)
        assertSame(noContext, HumanizedView.apply(noContext))
        val spouse = Fx.hlSpouse()
        assertSame(spouse, HumanizedView.apply(spouse))
        val loan = Fx.hlLoan()
        assertSame(loan, HumanizedView.apply(loan))                                          // no posture tags on the counterpart: cannot tell receptive
        val pd = Fx.pd()
        assertSame(pd, HumanizedView.apply(pd))
    }

    @Test
    fun TC_HL_VIEW_10_aPublicSettingHelpsACommitmentThatSticksButHurtsBreakingFromTheGroup() {
        val sticky = ctx(base("public")) { it.copy(goalIs = "lasting_commitment") }
        assertEquals(cell(sticky, "mgr", "a1", "r1", REL) + 0.25, cell(HumanizedView.apply(sticky), "mgr", "a1", "r1", REL), 1e-9)
        val faceCost = ctx(base("public", "corrects_them")) { it.copy(goalIs = "lasting_commitment") }
        val f = HumanizedView.apply(faceCost)
        assertEquals(cell(faceCost, "mgr", "a1", "r1", REL) - 1.5, cell(f, "mgr", "a1", "r1", REL), 1e-9)   // no +0.25 when there is a face cost
        val group = ctx(base("public")) { it.copy(goalIs = "break_from_group") }
        assertEquals(cell(group, "mgr", "a1", "r1", REL) - 0.5, cell(HumanizedView.apply(group), "mgr", "a1", "r1", REL), 1e-9)
        val noGoal = base("public")
        assertSame(noGoal, HumanizedView.apply(noGoal))
    }

    @Test
    fun TC_HL_VIEW_11_theConstantsScale() {
        val s = base("asks_them_to_give_up")
        // lambda halved to 1.0: a loss counts once, so there is no extra drop and nothing to change
        assertSame(s, HumanizedView.apply(s, HumanConstants.DEFAULT.scaled(0.5)))
        // lambda doubled to 4.0: the extra drop would be 6, but one cell never moves more than the cap (2.0)
        assertEquals(2.0, cell(HumanizedView.apply(s, HumanConstants.DEFAULT.scaled(2.0)), "mgr", "a1", "r1", REL), 1e-9)
    }
}
