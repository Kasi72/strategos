package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.PayoffCoverage
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seen live, 21 Nov: three people named as players (you and two co-founders) with outcomes written as pairs
 * ([your option, Arjun's option]). Every row was one pick short, no payoff was saved, and every turn came back BLOCKED
 * with no move and no X-ray. The pairs now decide the two players; the third person stays on the board as an influencer.
 */
class ThreePeoplePairsTest {
    private val people = """"people":[{"name":"You","role":"player"},{"name":"Arjun","role":"player","veto":true},{"name":"Priya","role":"player","veto":true}]"""
    private val options = """"options":[{"for":"You","label":"Propose a gated pilot with 50% prepay","posture":"repair"},
        {"for":"You","label":"State your terms and hold the line","posture":"boundary"},
        {"for":"You","label":"Send a midnight email ripping up commissions","posture":"reactive"},
        {"for":"Arjun","label":"Accept the gated pilot","posture":"receptive"},
        {"for":"Arjun","label":"Insist on custom deployments now","posture":"defensive"},
        {"for":"Priya","label":"Agree to the pilot","posture":"receptive"},
        {"for":"Priya","label":"Refuse any custom work","posture":"defensive"}],"dims":["RELATIONSHIPS","LONG_TERM_VALUE","COST"]"""
    private val outcomes = """"outcomes":[
        {"when":["Propose a gated pilot with 50% prepay","Accept the gated pilot"],"for_you":["++","++","+"],"for_them":["+","+","+"]},
        {"when":["Propose a gated pilot with 50% prepay","Insist on custom deployments now"],"for_you":["+","+","0"],"for_them":["0","0","0"]},
        {"when":["State your terms and hold the line","Accept the gated pilot"],"for_you":["+","+","+"],"for_them":["0","+","+"]},
        {"when":["State your terms and hold the line","Insist on custom deployments now"],"for_you":["0","0","0"],"for_them":["-","-","-"]},
        {"when":["Send a midnight email ripping up commissions","Accept the gated pilot"],"for_you":["--","--","-"],"for_them":["--","--","--"]},
        {"when":["Send a midnight email ripping up commissions","Insist on custom deployments now"],"for_you":["--","--","--"],"for_them":["--","--","--"]}]"""

    private fun save(json: String, s: Scenario): Scenario {
        val o = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse(json)!!, s).ops, s)
        val v = ChangeSetValidator.validate(s, o)
        val accepted = v.accepted.map { it.op }
        return ChangeSetApplier.apply(s, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
    }

    private fun assertSolvable(s: Scenario) {
        assertEquals(listOf("You", "Arjun"), s.players().map { it.name })
        val priya = s.stakeholders.single { it.name == "Priya" }
        assertEquals(GameRole.INFLUENCER, priya.gameRole)
        assertTrue("an influencer has no options", s.actionsFor(priya.id).isEmpty())
        val cov = PayoffCoverage.of(s)
        assertEquals("every pair is saved for both players", cov.expected, cov.filled)
        assertEquals(12, cov.filled)
        val d = EngineDirectiveBuilder.build(s, AnalysisPipeline().run(s), null)
        assertNotNull("the engine names a move (so the X-ray pill shows)", d.moveToImplement)
    }

    @Test fun pairsDecideTheTwoPlayers_theThirdPersonIsAnInfluencer() {
        assertSolvable(save("{$people,$options,$outcomes}", Scenario(id = "s", title = "Deadlock")))
    }

    @Test fun aThreadAlreadyStuckWithThreePlayersRepairsOnItsNextTurn() {
        val stuck = save("{$people,$options}", Scenario(id = "s", title = "Deadlock"))
        assertEquals("before the fix's trigger: three players, no payoffs", 3, stuck.players().size)
        assertEquals(0, PayoffCoverage.of(stuck).filled)
        assertSolvable(save("{$people,$options,$outcomes}", stuck))
    }

    @Test fun twoPeopleAndFullThreeWayRowsAreUntouched() {
        assertNull(SketchConverter.pairCounterpart(SketchParser.parse("{$options,$outcomes}")!!, Scenario(id = "s", title = "t")))
        val threeWay = """"outcomes":[{"picks":{"You":"State your terms and hold the line","Arjun":"Accept the gated pilot","Priya":"Agree to the pilot"},"scores":{"You":[5,5,5]}}]"""
        assertNull(SketchConverter.pairCounterpart(SketchParser.parse("{$people,$options,$threeWay}")!!, Scenario(id = "s", title = "t")))
    }
}
