package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.OpType
import com.drkkr.strategos.domain.agent.PayoffCoverage
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Sketches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SIMPLE PROFILE: sketch JSON → change ops → the existing validator and applier. */
class ModelSketchTest {

    /** The same steps the app runs: convert, normalize, validate, apply everything accepted. */
    private fun apply(json: String, base: Scenario): Pair<Scenario, List<Pair<Int, String>>> {
        val sketch = SketchParser.parse(json)!!
        val ops = OpNormalizer.normalize(SketchConverter.toOps(sketch, base).ops, base)
        val v = ChangeSetValidator.validate(base, ops)
        val accepted = v.accepted.map { it.op }
        val r = ChangeSetApplier.apply(base, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t")
        return r.scenario to (v.rejected.map { it.index to it.reason } + r.skipped)
    }

    @Test
    fun firstMessageBuildsAFullModel() {
        val (s, problems) = apply(Sketches.loanStory, Scenario(title = "Loan"))
        assertEquals(emptyList<Any>(), problems)
        assertEquals(listOf("You", "Brother"), s.players().map { it.name })
        assertEquals(setOf("Wife", "Parents"), s.stakeholders.filter { it.gameRole == GameRole.INFLUENCER }.map { it.name }.toSet())
        assertTrue(s.stakeholders.single { it.name == "Wife" }.canVeto)
        assertEquals(6, s.actions.size)
        val cov = PayoffCoverage.of(s)
        assertEquals(18, cov.expected)
        assertEquals(18, cov.filled)
        assertEquals(setOf(PayoffDimension.WELLBEING, PayoffDimension.RELATIONSHIPS, PayoffDimension.COST), cov.dimensions.toSet())
        assertEquals("You", s.decisionOwner)
        assertEquals(1, s.constraints.size)
        assertTrue((s.stakeholders.map { it.factStatus } + s.actions.map { it.factStatus } + s.payoffs.map { it.factStatus }).all { it == FactStatus.AI_INFERRED })
    }

    @Test
    fun followUpMergesIntoTheSavedModelWithoutDuplicates() {
        val (first, _) = apply(Sketches.loanStory, Scenario(title = "Loan"))
        val (second, problems) = apply(Sketches.followUp, first)
        assertEquals(emptyList<Any>(), problems)
        assertEquals(first.stakeholders.size, second.stakeholders.size)
        assertEquals(first.actions.size, second.actions.size)
        assertEquals(first.payoffs.size, second.payoffs.size)                       // upsert, not append
        assertEquals(listOf("Losing face"), second.stakeholders.single { it.name == "Brother" }.fears)
        val you = second.stakeholders.single { it.name == "You" }.id
        val waitAsk = second.payoffs.single { p ->
            p.stakeholderId == you && second.actions.filter { it.id in p.actionProfile.values }.map { it.label }.toSet() == setOf("Wait", "Ask parents")
        }
        assertEquals(9.0, waitAsk.payoff.values.getValue(PayoffDimension.WELLBEING), 0.0)
    }

    @Test
    fun extraPlayersBecomeInfluencersAndDimsAreReused() {
        val many = """{"people":[{"name":"You","role":"PLAYER"},{"name":"A","role":"PLAYER"},{"name":"B","role":"PLAYER"},{"name":"C","role":"PLAYER"}],
            "options":[{"for":"C","label":"Do something"}],"dims":["SAFETY","ETHICS","COST"]}"""
        val (s, problems) = apply(many, Scenario(title = "t"))
        assertEquals(emptyList<Any>(), problems)
        assertEquals(3, s.players().size)
        assertEquals(GameRole.INFLUENCER, s.stakeholders.single { it.name == "C" }.gameRole)
        assertTrue(s.actions.isEmpty())                                             // options only for players
        // With dims already in use the follow-up's own dims are ignored.
        val (first, _) = apply(Sketches.loanStory, Scenario(title = "Loan"))
        val ops = SketchConverter.toOps(SketchParser.parse(
            """{"outcomes":[{"picks":{"You":"Wait","Brother":"Ask parents"},"scores":{"You":[1,2,3,4,5]}}],"dims":["SAFETY","ETHICS","LEGALITY","COST"]}""")!!, first).ops
        assertTrue(ops.single { it.type == OpType.SET_PAYOFF }.values!!.map { it.dimension }.containsAll(listOf(PayoffDimension.WELLBEING, PayoffDimension.COST)))
    }

    @Test
    fun askOnlyAndMessyJson() {
        val s = Scenario(title = "t")
        val asked = SketchConverter.toOps(SketchParser.parse("Sure!\n```json\n${Sketches.ASK}\n```")!!, s)
        assertEquals("Who else is affected, and what could you do?", asked.ask)
        assertTrue(asked.ops.size <= 1)                                              // at most the scenario owner field
        assertNull(SketchParser.parse("no json here"))
        assertNull(SketchParser.parse("{broken"))
        assertNotNull(SketchParser.parse("""{"people":[],"unknown_field":1}"""))
        val support = SketchConverter.toOps(SketchParser.parse("""{"support":true}""")!!, s)
        assertTrue(support.support)
    }
}
