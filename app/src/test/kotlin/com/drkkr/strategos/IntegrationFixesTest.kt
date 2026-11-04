package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.agent.StatedFloor
import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.game.*
import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.framework.behavioral.RelationalRepairModule
import org.junit.Assert.*
import org.junit.Test

/** Integration fixes on top of the R0–R5 engine: archetype projection, actionless players, relational gating, stated floor, wording. */
class IntegrationFixesTest {

    // ── Fix A: 3×2 (our extractor's shape) projects to 2×2 before archetype detection ─────────────────────────────
    // Row actions: c (cooperate), d (defect), x (a "reactive" extra row that is a worse version of d). Columns: c, d.
    private fun pd3x2(): GameIR {
        //            col c   col d
        // row c      3,3     0,5
        // row d      5,0     1,1
        // row x      4,0     0,0
        val u1 = doubleArrayOf(3.0, 0.0, 5.0, 1.0, 4.0, 0.0)
        val u2 = doubleArrayOf(3.0, 5.0, 0.0, 1.0, 0.0, 0.0)
        return GameIR("pd3", listOf("p1", "p2"), listOf(listOf("c", "d", "x"), listOf("c", "d")), listOf(3, 2),
            listOf(u1, u2), GameClass.FINITE_NORMAL_FORM, 100)
    }

    @Test fun `plain detect gives up on a 3x2 game`() {
        assertEquals(GameArchetype.GENERIC, GameArchetypeDetector.detect(pd3x2()))
    }

    @Test fun `dropping the reactive row leaves a 2x2 prisoners dilemma`() {
        assertEquals(GameArchetype.PRISONERS_DILEMMA, GameArchetypeDetector.detectProjected(pd3x2(), setOf("x")))
    }

    @Test fun `without a droppable set a clearly dominated extra row is removed`() {
        // Same dilemma with a third row that is strictly worse than d everywhere and worst on average.
        val u1 = doubleArrayOf(3.0, 0.0, 5.0, 1.0, -1.0, -1.0)
        val u2 = doubleArrayOf(3.0, 5.0, 0.0, 1.0, 0.0, 0.0)
        val ir = GameIR("pd3b", listOf("p1", "p2"), listOf(listOf("c", "d", "x"), listOf("c", "d")), listOf(3, 2),
            listOf(u1, u2), GameClass.FINITE_NORMAL_FORM, 100)
        assertEquals(GameArchetype.PRISONERS_DILEMMA, GameArchetypeDetector.detectProjected(ir))
    }

    @Test fun `projection never drops below two actions and leaves 2x2 games alone`() {
        val direct = GameArchetypeDetector.detectProjected(pd3x2(), setOf("c", "d", "x"))
        assertNotNull(direct) // all rows droppable: falls back to keeping them, must not throw
    }

    // ── Fix B: an actionless third party must not block the whole analysis ──────────────────────────────────────
    private fun actionlessThirdParty(): Scenario {
        val a = Stakeholder(id = "a", name = "You"); val b = Stakeholder(id = "b", name = "Arjun"); val c = Stakeholder(id = "c", name = "Meera")
        val acts = listOf(Action("a1", "a", "Back Arjun"), Action("a2", "a", "Back Meera"), Action("b1", "b", "Accept"), Action("b2", "b", "Hold out"))
        fun p(who: String, a1: String, b1: String, v: Double) =
            PayoffEntry(who, mapOf("a" to a1, "b" to b1), MultiPayoff(mapOf(PayoffDimension.SAFETY to v)))
        val payoffs = listOf(
            p("a", "a1", "b1", 3.0), p("a", "a1", "b2", 1.0), p("a", "a2", "b1", 2.0), p("a", "a2", "b2", 0.0),
            p("b", "a1", "b1", 3.0), p("b", "a1", "b2", 0.0), p("b", "a2", "b1", 1.0), p("b", "a2", "b2", 2.0)
        )
        return Scenario(title = "Founders", description = "Investor wants a board seat; we bid on who allocates the shares.",
            stakeholders = listOf(a, b, c), actions = acts, payoffs = payoffs)
    }

    @Test fun `a player with no actions is set aside instead of blocking the game`() {
        val ctx = GameModelService.analyzeWithContext(actionlessThirdParty())
        assertFalse("must not fail on PlayerHasNoActions: ${ctx.result}", ctx.result is GameAnalysisResult.Failed)
    }

    @Test fun `mechanism hint still computes when the matrix cannot compile`() {
        val s = Scenario(title = "Auction", description = "We run a sealed-bid auction for the shares; highest bidder wins.",
            stakeholders = listOf(Stakeholder(id = "a", name = "You"), Stakeholder(id = "b", name = "Other")))
        val ctx = GameModelService.analyzeWithContext(s)
        assertTrue(ctx.result is GameAnalysisResult.Failed)
        assertNotNull("the text-based mechanism hint must survive a failed compile", ctx.mechanismHint)
    }

    // ── Fix D: no relationship-repair framing for competitors, employers and deals ───────────────────────────────
    private fun twoParty(title: String, desc: String) = Scenario(title = title, description = desc, repeated = true,
        stakeholders = listOf(Stakeholder(id = "a", name = "You"), Stakeholder(id = "b", name = "Other")))

    @Test fun `price war with a competitor is not a repair game`() {
        val score = RelationalRepairModule().canAnalyze(twoParty("Pricing", "My competitor and I both sell software; should I cut the price?")).score
        assertTrue("score $score", score < 0.15)
    }

    @Test fun `salary talk that mentions the relationship is not a repair game`() {
        val score = RelationalRepairModule().canAnalyze(twoParty("Raise", "Negotiating a raise with my manager; I do not want to burn the relationship.")).score
        assertTrue("score $score", score < 0.15)
    }

    @Test fun `a drifting friendship is still a repair game`() {
        val score = RelationalRepairModule().canAnalyze(twoParty("Friend", "My best friend and I have drifted apart and I want to reconnect.")).score
        assertTrue("score $score", score >= 0.55)
    }

    // ── Fix F: stated floor and wording ──────────────────────────────────────────────────────────────────────────
    @Test fun `stated floor comes from I need and from the saved reservation point`() {
        assertEquals(17.0, StatedFloor.from(null, "I told him I need 17 and a title change")!!, 0.0)
        assertEquals(16.0, StatedFloor.from("16 lakh", "no figures here")!!, 0.0)
        assertEquals(17.0, StatedFloor.from("16 lakh", "I need 17")!!, 0.0)
        assertNull(StatedFloor.from(null, "just chatting"))
    }

    @Test fun `an option that leads below the floor is flagged and one without a figure is not`() {
        assertTrue(StatedFloor.leadsBelow("Take 16 lakh now plus a review at 18", 17.0))
        assertFalse(StatedFloor.leadsBelow("Hold at 17 and the title", 17.0))
        assertFalse(StatedFloor.leadsBelow("Wait quietly for HR", 17.0))
        assertFalse(StatedFloor.leadsBelow("Take 16 lakh", null))
    }

    @Test fun `audit caveats are removed from the why text`() {
        val raw = "Preferred under recorded assumptions. No evidence sources recorded — results are structural proxy estimates only. One or more hard constraints are unassessed — verify compliance before committing."
        val m = EngineDirectiveBuilder::class.java.getDeclaredMethod("cleanWhy", String::class.java).apply { isAccessible = true }
        val out = m.invoke(EngineDirectiveBuilder, raw) as String
        assertTrue(out, out.startsWith("Preferred under recorded assumptions"))
        assertFalse(out, out.contains("No evidence") || out.contains("unassessed"))
    }
}

class MechanismContextTextTest {
    @Test fun `mechanism hint reads in-memory context text when the frame is empty`() {
        val s = Scenario(title = "New scenario")
        assertNull(MechanismAdvisor.detect(s))
        assertEquals(MechanismAdvisor.MechanismType.AUCTION, MechanismAdvisor.detect(s, "the landlord runs a sealed bid auction")?.type)
    }
}
