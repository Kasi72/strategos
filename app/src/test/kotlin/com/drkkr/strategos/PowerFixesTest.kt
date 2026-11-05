package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.human.RelationshipDomains
import com.drkkr.strategos.domain.human.power.Exposure
import com.drkkr.strategos.domain.human.power.PowerPlanner
import com.drkkr.strategos.domain.human.power.PowerRead
import com.drkkr.strategos.domain.human.power.StrategicCatalog
import com.drkkr.strategos.domain.human.power.StrategicId
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Power Mode, pm4.5 (CP-P2 fixes): whose side is whose in extraction, contradicting moves, stakes, plays seen once, coach wording. */
class PowerFixesTest {
    private fun apply(json: String, s: Scenario): Scenario {
        val o = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse(json)!!, s).ops, s)
        val v = ChangeSetValidator.validate(s, o)
        val accepted = v.accepted.map { it.op }
        return ChangeSetApplier.apply(s, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
    }
    private fun plan(s: Scenario) = PowerPlanner.plan(s, RelationshipDomains.of(s.humanContext?.relationship, null), true, false, false, 50.0, 0.0)

    // ── 1. Extraction fixtures: what Call 1 should return for the vendor and salary stories, read by the converter ─────────

    private val vendorOptions = """"people":[{"name":"You","role":"player"},{"name":"Karthik","role":"player"}],
      "options":[{"who":"You","do":"Hold at the ceiling and ask what he can do","posture":"boundary"},{"who":"You","do":"Ask for a week","posture":"repair"},
        {"who":"You","do":"Accept the quote","posture":"reactive"},{"who":"Karthik","do":"Come down","posture":"receptive"},{"who":"Karthik","do":"Hold the quote","posture":"defensive"}],
      "dims":["COST","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[{"when":["Hold at the ceiling and ask what he can do","Come down"],"for_you":[8,6,8],"for_them":[5,6,6]},{"when":["Hold at the ceiling and ask what he can do","Hold the quote"],"for_you":[4,5,5],"for_them":[6,5,5]},
        {"when":["Ask for a week","Come down"],"for_you":[7,7,7],"for_them":[5,7,6]},{"when":["Ask for a week","Hold the quote"],"for_you":[5,6,5],"for_them":[6,6,5]},
        {"when":["Accept the quote","Come down"],"for_you":[3,7,4],"for_them":[8,7,7]},{"when":["Accept the quote","Hold the quote"],"for_you":[2,6,3],"for_them":[9,6,7]}]"""

    /** "...Karthik quoted 60,000 dollars a year. My budget ceiling is 45,000 and I'd walk away above that." */
    val vendorTurn1 = """{$vendorOptions,"relationship":"vendor","negotiation":{"lowest_acceptable":"45,000 dollars a year"}}"""
    /** "There's another vendor I've already demoed that would cost about 42,000 but needs more setup work. Karthik doesn't know about them." */
    val vendorTurn2 = """{$vendorOptions,"relationship":"vendor","power":{"your_alternative":"strong"},
      "negotiation":{"if_no_deal":"another vendor I've demoed at about 42,000"}}"""

    @Test fun theVendorsOwnAlternativeBecomesTheFallbackAndRealCompetitionShows() {
        val after = apply(vendorTurn2, apply(vendorTurn1, Scenario(id = "v", title = "CRM")))
        val nc = after.negotiationContext!!
        assertTrue(nc.batna, nc.batna.contains("42,000"))
        assertEquals("", nc.counterpartBatna)
        val f = DealFrame.build(nc, after.humanContext, "")!!
        assertEquals(42000.0, f.fallback!!.value, 0.0)
        assertEquals(true, f.walkAwayCredible)
        // the strength waits in `pending` (a fact said once never repeats), but with if_no_deal it counts as a real fallback
        assertEquals("strong", after.humanContext!!.pending["yourAlternative"])
        assertTrue(plan(after).cardMoves.map { it.tactic.id }.toString(), plan(after).cardMoves.any { it.tactic.id == StrategicId.REAL_COMPETITION })
    }

    private val salaryOptions = """"people":[{"name":"You","role":"player"},{"name":"Leela","role":"player"}],
      "options":[{"who":"You","do":"Name the two systems and ask for a raise that reflects the offer","posture":"repair"},{"who":"You","do":"Wait until after the launch","posture":"boundary"},
        {"who":"You","do":"Hint you might leave","posture":"reactive"},{"who":"Leela","do":"Engage","posture":"receptive"},{"who":"Leela","do":"Put it off","posture":"defensive"}],
      "dims":["COST","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[{"when":["Name the two systems and ask for a raise that reflects the offer","Engage"],"for_you":[8,7,8],"for_them":[6,7,7]},{"when":["Name the two systems and ask for a raise that reflects the offer","Put it off"],"for_you":[5,5,5],"for_them":[6,5,5]},
        {"when":["Wait until after the launch","Engage"],"for_you":[6,6,6],"for_them":[6,6,6]},{"when":["Wait until after the launch","Put it off"],"for_you":[4,5,4],"for_them":[6,6,5]},
        {"when":["Hint you might leave","Engage"],"for_you":[5,3,5],"for_them":[4,3,4]},{"when":["Hint you might leave","Put it off"],"for_you":[3,2,3],"for_them":[5,3,4]}]"""
    /** "I have a real written offer from another company for 30% more... Two critical systems only I really understand..." */
    val salaryTurn = """{$salaryOptions,"relationship":"manager","power":{"relative":"they_higher","they_depend_on_you":"high","your_alternative":"strong"},
      "negotiation":{"if_no_deal":"take the written offer from the other company"}}"""

    @Test fun theSalaryStoryReadsTheirNeedAndTheUsersOfferOnTheRightSide() {
        val after = apply(salaryTurn, Scenario(id = "s", title = "Raise"))
        val hc = after.humanContext!!
        assertEquals("high", hc.pending["theyDependOnYou"] ?: hc.theyDependOnYou)
        assertEquals(null, hc.youDependOnThem)
        assertTrue(plan(after).cardMoves.map { it.tactic.id }.toString(), plan(after).cardMoves.any { it.tactic.id == StrategicId.REAL_COMPETITION })
    }

    @Test fun thePromptNamesEachSide() {
        val p = ArgonPrompts.EXTRACTION_PROMPT
        assertTrue(p.contains("`if_no_deal` (the USER's own other option"))
        assertTrue(p.contains("`their_if_no_deal` (what the OTHER side could do instead)"))
        assertTrue(p.contains("they_depend_on_you (they need the USER"))
        assertTrue(p.contains("your_alternative (the USER's other offers or options)"))
        assertFalse(p.contains("`their_alternative` (what the other side could do instead)"))
        // an older reply with the old key still parses into the same field
        assertEquals("they can hire someone", SketchParser.parse("""{"negotiation":{"their_alternative":"they can hire someone"}}""")!!.negotiation!!.toContext().counterpartBatna)
    }

    @Test fun aPendingStrongAlternativeAloneIsNotEnough() {
        val s = Fx.pmVendor().let { it.copy(humanContext = HumanContext(relationship = "vendor", pending = mapOf("yourAlternative" to "strong"))) }
        assertFalse(plan(s).cardMoves.any { it.tactic.id == StrategicId.REAL_COMPETITION })
        val withIfNoDeal = s.copy(negotiationContext = s.negotiationContext!!.copy(batna = "another vendor"))
        assertTrue(plan(withIfNoDeal).cardMoves.any { it.tactic.id == StrategicId.REAL_COMPETITION })
    }

    // ── 2. Contradicting moves ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun letThemMoveFirstIsNotOfferedWhenTheMovePutsAFigureDown() {
        val t = StrategicCatalog.tactics.single { it.id == StrategicId.LET_THEM_MOVE_FIRST }
        val s = Fx.pmVendor()
        val ctx = s.humanContext!!
        val plain = s.actions.first { it.id == "a1" }
        assertTrue(t.tactic.preconditions(ctx, plain, s))
        assertFalse(t.tactic.preconditions(ctx, plain.copy(label = "Counter at $42,000"), s))
        assertFalse(t.tactic.preconditions(ctx, plain.copy(description = plain.description + "[f:gives_first]"), s))
    }

    // ── 4. Close relationships continue ──────────────────────────────────────────────────────────────────────────────

    @Test fun aCloseRelationshipContinuesAndTheStakeIsTheRelationship() {
        for (r in listOf("spouse", "partner", "parent", "sibling", "friend")) assertTrue(r, Exposure.continuing(HumanContext(relationship = r)))
        val read = PowerRead.build(Fx.pmSpouse(), plan(Fx.pmSpouse()))
        assertEquals("the relationship", read.leverage.reputation)
        assertTrue(read.leverage.summary().contains("At stake: the relationship"))
        assertEquals("this deal", PowerRead.build(Fx.pmVendor(), plan(Fx.pmVendor())).leverage.reputation)
    }

    // ── 5. Their plays after one mention ─────────────────────────────────────────────────────────────────────────────

    @Test fun aPlaySeenOnceShowsOnTheCardLabelled_butChangesNothingElse() {
        val once = Fx.pmManager().let { it.copy(humanContext = it.humanContext!!.copy(theirTactics = listOf("probes_you", "artificial_deadline"), tacticTurns = mapOf("probes_you" to 1, "artificial_deadline" to 2))) }
        val plays = PowerRead.build(once, plan(once)).theirPlays
        assertEquals(listOf("They're digging for your limits.", "The pressure to decide right now may be manufactured."), plays.map { it.sign })
        assertEquals(listOf(true, false), plays.map { it.seenOnce })
        // the engine still waits for two mentions: no defensive gain from probing seen once
        val p = plan(once).cardMoves.associate { it.tactic.id to it.ev }
        assertFalse(p.toString(), (p[StrategicId.STRATEGIC_SILENCE] ?: 0.0) > 0.3)
        // in a close relationship the coercive patterns never show as a play (they route to support)
        val spouse = Fx.pmSpouse().let { it.copy(humanContext = it.humanContext!!.copy(theirTactics = listOf("hot_cold"), tacticTurns = mapOf("hot_cold" to 1))) }
        assertTrue(PowerRead.build(spouse, plan(spouse)).theirPlays.isEmpty())
    }

    // ── 3 and 6. Coach wording ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun theCoachNeverRestatesTheDeclineAndNeverInventsADateOrFigure() {
        val p = ArgonPrompts.ENGINE_COACH_PROMPT
        assertTrue(p.contains("don't restate the decline; go straight to the legitimate move"))
        assertFalse(p.contains("open with it in one sentence"))
        assertTrue(p.contains("Never put a date or a figure the user didn't give into `exact_words`"))
        assertTrue(p.contains("by a date you can hold"))
    }

    @Test fun realCompetitionAlsoAppliesAtWork() {
        val t = StrategicCatalog.tactics.single { it.id == StrategicId.REAL_COMPETITION }
        assertTrue(RelationshipDomain.WORK_HIERARCHY in t.tactic.domains)
        assertTrue(RelationshipDomain.WORK_PEER in t.tactic.domains)
    }
}
