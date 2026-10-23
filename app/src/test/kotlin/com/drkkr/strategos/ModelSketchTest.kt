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
    fun nearDuplicateOptionsAreMerged() {
        val json = """{"people":[{"name":"You","role":"PLAYER"},{"name":"Brother","role":"PLAYER"}],
            "options":[{"for":"You","label":"Propose a different financial arrangement"},
                       {"for":"You","label":"Propose a different financial arrangement (e.g., a smaller loan)"},
                       {"for":"You","label":"Wait"},{"for":"You","label":"Wait and see"}]}"""
        val (s, _) = apply(json, Scenario(title = "t"))
        // Long near-duplicates merge; short labels ("Wait" vs "Wait and see") need an exact match, so both stay.
        assertEquals(listOf("Propose a different financial arrangement", "Wait", "Wait and see"), s.actions.map { it.label })
    }

    @Test
    fun gapsAreDescribedForTheRepairRequest() {
        val (s, _) = apply(Sketches.loanStoryIncomplete, Scenario(title = "t"))
        val gaps = com.drkkr.strategos.domain.agent.SketchGaps.describe(s)!!
        assertTrue(gaps, gaps.contains("Brother has fewer than 2 options"))
        val (full, _) = apply(Sketches.loanStory, Scenario(title = "t"))
        assertNull(com.drkkr.strategos.domain.agent.SketchGaps.describe(full))
        // Options complete but some outcomes missing → the combinations are listed by name.
        val partial = full.copy(payoffs = full.payoffs.drop(4))
        assertTrue(com.drkkr.strategos.domain.agent.SketchGaps.describe(partial)!!.contains("You: "))
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

    @Test
    fun liveReplyWithFancyHyphensLooseLabelsAndPlaceholders() {
        // Shape of a real reply: non-breaking hyphens, picks that paraphrase the option, "short" left in wants/fears, an echoed instruction.
        val json = """{"goal":"Decide whether to co\u2011sign","people":[{"name":"You","role":"PLAYER","wants":"short","fears":"short"},
            {"name":"Brother","role":"PLAYER","wants":"short"},{"name":"Wife","role":"INFLUENCER","veto":true,"fears":"Debt"}],
            "options":[{"for":"You","label":"Co\u2011sign the loan"},{"for":"You","label":"Do not co-sign"},
                       {"for":"Brother","label":"Accept the co\u2011sign offer"},{"for":"Brother","label":"Reject the co-sign offer"}],
            "dims":["WELLBEING","RELATIONSHIPS","COST"],
            "outcomes":[{"picks":{"you":"Co-sign the loan","Brother":"Accept the co-sign offer"},"scores":{"You":[6,8,4],"Brother":[8,9,3]}},
                        {"picks":{"You":"Co-sign the loan","Brother":"Reject"},"scores":{"You":[4,5,4],"Brother":[5,4,3]}},
                        {"picks":{"You":"Do not co-sign","Brother":"Accept"},"scores":{"You":[8,7,0],"Brother":[7,8,0]}},
                        {"picks":{"You":"Do not co-sign","Brother":"Reject the co-sign offer"},"scores":{"You":[9,9,0],"Brother":[6,5,0]}},
                        {"picks":{"You":"co-sign","Brother":"Accept"},"scores":{"You":[1,1,1],"Brother":[1,1,1]}}],
            "ask":"ONLY if the story is too thin to model: one short question"}"""
        val sketch = SketchParser.parse(json)!!
        val converted = SketchConverter.toOps(sketch, Scenario(title = "t"))
        assertNull(converted.ask)                                                   // the echoed instruction is not a question
        val (s, problems) = apply(json, Scenario(title = "t"))
        assertEquals(emptyList<Any>(), problems)
        assertEquals(emptyList<String>(), s.stakeholders.single { it.name == "You" }.goals)       // "short" ignored
        assertEquals(emptyList<String>(), s.stakeholders.single { it.name == "Brother" }.goals)
        assertEquals(listOf("Debt"), s.stakeholders.single { it.name == "Wife" }.fears)
        // "Reject", "Accept" and "Co-sign the loan" matched their options loosely: 4 combinations x 2 players saved.
        // The 5th pick, "co-sign", fits two options ("Co-sign the loan", "Do not co-sign"), so it is dropped, not guessed.
        assertEquals(8, PayoffCoverage.of(s).filled)
        assertEquals(8, PayoffCoverage.of(s).expected)
    }
}
