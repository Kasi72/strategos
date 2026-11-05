package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.SketchHuman
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.human.Channel
import com.drkkr.strategos.domain.human.DeliveryPlanner
import com.drkkr.strategos.domain.human.HumanConstants
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.HumanizedView
import com.drkkr.strategos.domain.human.TacticId
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, hl3.6: the user's last attempt (their habit) steers the delivery; four small effects; constants scale. */
class PlannerHabitTest {
    private val LTV = PayoffDimension.LONG_TERM_VALUE
    private val REL = PayoffDimension.RELATIONSHIPS
    private val COST = PayoffDimension.COST

    private fun even(s: Scenario, move: String): Scenario = s.copy(payoffs = s.payoffs.map {
        if (it.stakeholderId == "mgr" && it.actionProfile["you"] == move)
            it.copy(payoff = MultiPayoff(mapOf(LTV to 5.0, REL to 5.0, COST to 5.0))) else it
    })

    /** The raise story with a recommended move that carries NO tags of its own (it is the good version), and a described last attempt. */
    private fun raise(last: List<String>, vararg moveTags: String, ctx: (HumanContext) -> HumanContext = { it }): Scenario {
        var s = even(Fx.hlRaise(), "a2")
        s = s.copy(actions = s.actions.map { if (it.id == "a2") it.copy(description = "[posture:boundary]" + if (moveTags.isEmpty()) "" else "[f:${moveTags.sorted().joinToString(",")}]") else it })
        return s.copy(humanContext = ctx(HumanContext(relationship = "manager", theirPower = "they_higher", lastAttemptTags = last)))
    }

    private fun ids(s: Scenario) = DeliveryPlanner.diagnose(s, s.humanContext!!, s.actions.first { it.id == "a2" }, false).filter { it.gatePass && it.preconditionPass }.map { it.id }

    @Test
    fun TC_HL_HABIT_01_aDescribedLastAttemptMakesTheirInterestAndPrivateCreditApply() {
        val s = raise(listOf("appeals_to_fairness_or_gratitude", "public", "corrects_them"))
        val rows = DeliveryPlanner.diagnose(s, s.humanContext!!, s.actions.first { it.id == "a2" }, false).associateBy { it.id }
        for (id in listOf(TacticId.THEIR_INTEREST_FRAME, TacticId.PRIVATE_AND_CREDIT)) {
            val r = rows.getValue(id)
            assertTrue("$id passes", r.gatePass && r.preconditionPass)
            assertTrue("$id gain ${r.deltaP}", (r.deltaP ?: 0.0) >= 0.05)
        }
        val plan = DeliveryPlanner.plan(s, s.humanContext!!, s.actions.first { it.id == "a2" }, s.actions.first { it.id == "a1" })
        assertEquals(setOf(TacticId.THEIR_INTEREST_FRAME, TacticId.PRIVATE_AND_CREDIT), plan.tactics.map { it.id }.toSet())
    }

    @Test
    fun TC_HL_HABIT_02_withoutALastAttemptNothingApplies_andTheMovesOwnTagsStillCount() {
        assertTrue(ids(raise(emptyList())).none { it == TacticId.THEIR_INTEREST_FRAME || it == TacticId.PRIVATE_AND_CREDIT })
        assertTrue(ids(raise(emptyList(), "appeals_to_fairness_or_gratitude")).contains(TacticId.THEIR_INTEREST_FRAME))
    }

    @Test
    fun TC_HL_HABIT_03_onlyHabitsCarryOver_notEverythingTheyDid() {
        val s = raise(listOf("written", "credits_them", "gives_first", "withdraws"))
        assertTrue(ids(s).none { it == TacticId.THEIR_INTEREST_FRAME || it == TacticId.PRIVATE_AND_CREDIT || it == TacticId.DEFAULT_WITH_DEADLINE })
    }

    @Test
    fun TC_HL_HABIT_04_aHabitOfCorrectingAHigherStatusPersonMeansPrivate() {
        val s = raise(listOf("corrects_them"))
        assertEquals(Channel.PRIVATE, DeliveryPlanner.plan(s, s.humanContext!!, s.actions.first { it.id == "a2" }).channel)
    }

    @Test
    fun TC_HL_HABIT_05_lastAttemptTagsAreReadThroughTheClosedVocabulary_andKeptUntilReplaced() {
        val sk = SketchParser.parse("""{"last_attempt_tags":["appeals_to_fairness_or_gratitude","Public","sneaky"],"relationship":"manager"}""")!!
        assertEquals(listOf("appeals_to_fairness_or_gratitude", "public"), SketchHuman.context(sk)!!.lastAttemptTags)
        assertTrue(SketchHuman.context(SketchParser.parse("""{"goal":"g"}""")!!)?.lastAttemptTags.orEmpty().isEmpty())
        val tolerant = SketchParser.parse("""{"last_attempt_tags":"public"}""")!!                  // a bare string must not sink the reply
        assertEquals(listOf("public"), SketchHuman.context(tolerant)!!.lastAttemptTags)
        val saved = HumanContext(lastAttemptTags = listOf("public"))
        assertEquals(listOf("public"), saved.merge(HumanContext(yourStateLevel = 1.0)).lastAttemptTags)
        assertEquals(listOf("argues"), saved.merge(HumanContext(lastAttemptTags = listOf("argues"))).lastAttemptTags)
    }

    @Test
    fun TC_HL_HABIT_06_thePromptAsksForItInTheSameWords() {
        val p = ArgonPrompts.EXTRACTION_PROMPT
        assertTrue(p.contains("last_attempt_tags") && p.contains("ALREADY"))
        assertTrue(p.substringAfter("JSON SCHEMA:").contains("\"last_attempt_tags\""))
    }

    // ── four small bounded effects, scaled by the sensitivity check ───────────────

    private fun withTag(tag: String): Scenario {
        val s = even(Fx.hlRaise(), "a1")
        return s.copy(actions = s.actions.map { if (it.id == "a1") it.copy(description = "[posture:repair][f:$tag]") else it })
    }
    private fun rel(s: Scenario, reply: String) = s.payoffs.single { it.stakeholderId == "mgr" && it.actionProfile["you"] == "a1" && it.actionProfile["mgr"] == reply }.payoff.values.getValue(REL)

    @Test
    fun TC_HL_EFFECT_01_givingFirstTheYardstickLeverageAndSteppingBackEachAddAQuarterPoint() {
        val c = HumanConstants.DEFAULT
        assertEquals(0.25, c.giveFirstShift, 0.0); assertEquals(0.25, c.yardstickShift, 0.0)
        assertEquals(0.25, c.leverageShift, 0.0); assertEquals(0.25, c.stepBackShift, 0.0)
        for (tag in listOf("gives_first", "outside_yardstick", "names_leverage", "withdraws")) {
            val s = withTag(tag)
            val v = HumanizedView.apply(s)
            assertEquals(tag, rel(s, "r1") + 0.25, rel(v, "r1"), 1e-9)                          // their receptive score goes up
            assertEquals("$tag: the defensive reply is untouched", rel(s, "r2"), rel(v, "r2"), 0.0)
        }
    }

    @Test
    fun TC_HL_EFFECT_02_theNewConstantsScaleWithTheSensitivityCheck() {
        val d = HumanConstants.DEFAULT; val s2 = d.scaled(2.0)
        assertEquals(d.giveFirstShift * 2, s2.giveFirstShift, 1e-9); assertEquals(d.yardstickShift * 2, s2.yardstickShift, 1e-9)
        assertEquals(d.leverageShift * 2, s2.leverageShift, 1e-9); assertEquals(d.stepBackShift * 2, s2.stepBackShift, 1e-9)
        val s = withTag("gives_first")
        assertEquals(rel(s, "r1") + 0.5, rel(HumanizedView.apply(s, s2), "r1"), 1e-9)
        assertEquals(rel(s, "r1") + 0.125, rel(HumanizedView.apply(s, d.scaled(0.5)), "r1"), 1e-9)
    }

    @Test
    fun TC_HL_EFFECT_03_theTacticsNowHaveAMeasurableGain() {
        val s = even(Fx.hlRaise(), "a2").let { sc ->
            sc.copy(actions = sc.actions.map { if (it.id == "a2") it.copy(description = "[posture:boundary]") else it },
                humanContext = HumanContext(relationship = "peer", theirStateLevel = 2.5))
        }
        val concede = DeliveryPlanner.diagnose(s, s.humanContext!!, s.actions.first { it.id == "a2" }, false).single { it.id == TacticId.CONCEDE_SMALL_FIRST }
        assertTrue(concede.preconditionPass)
        assertTrue("gain ${concede.deltaP}", (concede.deltaP ?: 0.0) >= 0.05)
        assertFalse(concede.preconditionOnly)
    }
}
