package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.ChangeOpDescriber
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.intent.Intent
import com.drkkr.strategos.domain.intent.RecommendationDiff
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID2.3: "the advice changed, because …", built in Kotlin from the turn's saved ops. */
class RecommendationDiffTest {
    private fun sketch(you: Map<String, String>, fear: String? = null) = """{
      "people":[{"name":"You","role":"player"},{"name":"Partner","role":"player"${fear?.let { ",\"fears\":\"$it\"" } ?: ""}}],
      "options":[{"who":"You","do":"Confront","posture":"boundary"},{"who":"You","do":"Wait","posture":"repair"},
        {"who":"Partner","do":"Open up","posture":"receptive"},{"who":"Partner","do":"Shut down","posture":"defensive"}],
      "dims":["WELLBEING","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[{"when":["Confront","Open up"],"for_you":${you.getValue("co")},"for_them":["0","0","0"]},{"when":["Confront","Shut down"],"for_you":${you.getValue("cs")},"for_them":["+","0","0"]},
        {"when":["Wait","Open up"],"for_you":${you.getValue("wo")},"for_them":["+","+","+"]},{"when":["Wait","Shut down"],"for_you":${you.getValue("ws")},"for_them":["0","0","0"]}]}"""

    private val confrontWins = mapOf("co" to listOf("++", "++", "+"), "cs" to listOf("+", "+", "0"), "wo" to listOf("0", "0", "0"), "ws" to listOf("-", "-", "-"))
    private val waitWins = mapOf("co" to listOf("-", "--", "-"), "cs" to listOf("--", "--", "-"), "wo" to listOf("++", "++", "+"), "ws" to listOf("+", "+", "0"))
    private fun q(m: Map<String, List<String>>) = m.mapValues { (_, v) -> v.joinToString(",", "[", "]") { "\"$it\"" } }

    /** One saved turn: the ops the save path accepted, and the scenario after. */
    private fun turn(json: String, s: Scenario): Pair<List<ChangeOp>, Scenario> {
        val o = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse(json)!!, s).ops, s)
        val v = ChangeSetValidator.validate(s, o)
        val accepted = v.accepted.map { it.op }
        return accepted to ChangeSetApplier.apply(s, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
    }

    /** The engine's recommended move, as a label. */
    private fun pick(s: Scenario) = com.drkkr.strategos.domain.intent.EngineTools.pick(s)?.let { id -> s.actions.first { it.id == id }.label }

    @Test fun TC_ID_DIFF_01_aFearThatMovesThePickIsNamedAsTheReason() {
        val (_, one) = turn(sketch(q(confrontWins)), Scenario(id = "d", title = "Talk", decisionOwner = "You"))
        assertEquals("Confront", pick(one))
        val (ops, two) = turn(sketch(q(waitWins), fear = "being blamed"), one)
        assertEquals("Wait", pick(two))
        val diff = RecommendationDiff.of(Intent.STANDARD, "Confront", pick(two), ops, one, two, IntakeChecklist.self(two)?.id)
        assertNotNull(diff)
        assertEquals("Confront", diff!!.from); assertEquals("Wait", diff.to)
        assertTrue(diff.because.size in 1..RecommendationDiff.MAX_BECAUSE)
        // every line is ChangeOpDescriber's own; the fact comes before any "Set outcome" line
        val described = ops.map { ChangeOpDescriber.describe(it, one, ops) }
        assertTrue(diff.because.all { it in described })
        assertEquals("Update Partner", diff.because.first())
        // in the directive JSON as changed_since_last
        val d = EngineDirectiveBuilder.build(two, AnalysisPipeline().run(two), null, "Confront").copy(changedSinceLast = diff).toJson()
        val block = d["changed_since_last"]!!.jsonObject
        assertEquals("Confront", block["from"]!!.jsonPrimitive.content); assertEquals("Wait", block["to"]!!.jsonPrimitive.content)
        assertEquals(diff.because, block["because"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun TC_ID_DIFF_02_aNewFactThatKeepsThePickGivesNoDiff() {
        val (_, one) = turn(sketch(q(confrontWins)), Scenario(id = "d", title = "Talk", decisionOwner = "You"))
        val (ops, two) = turn(sketch(q(confrontWins), fear = "being blamed"), one)
        assertTrue(ops.isNotEmpty())
        assertEquals("Confront", pick(two))
        assertNull(RecommendationDiff.of(Intent.STANDARD, "Confront", pick(two), ops, one, two, IntakeChecklist.self(two)?.id))
        assertFalse(EngineDirectiveBuilder.build(two, AnalysisPipeline().run(two), null, "Confront").toJson().containsKey("changed_since_last"))
        // nothing saved this turn: no diff even when the pick differs
        assertNull(RecommendationDiff.of(Intent.STANDARD, "Wait", "Confront", emptyList(), one, two, IntakeChecklist.self(two)?.id))
        // the last pick was reworded away (it is no longer an option): a rewording is not a change of advice
        assertNull(RecommendationDiff.of(Intent.STANDARD, "Confront them tonight", "Confront", ops, one, two, IntakeChecklist.self(two)?.id))
    }

    @Test fun TC_ID_DIFF_03_aWhatIfTurnNeverHasADiff() {
        val (_, one) = turn(sketch(q(confrontWins)), Scenario(id = "d", title = "Talk", decisionOwner = "You"))
        val (ops, two) = turn(sketch(q(waitWins), fear = "being blamed"), one)
        for (i in Intent.entries.filter { it != Intent.STANDARD })
            assertNull(i.word, RecommendationDiff.of(i, "Confront", pick(two), ops, one, two, IntakeChecklist.self(two)?.id))
    }

    @Test fun TC_ID_DIFF_04_thePreviousPickIsMatchedToThisTurnsRewordedOptions() {
        val (_, one) = turn(sketch(q(confrontWins)), Scenario(id = "d", title = "Talk", decisionOwner = "You"))
        val (ops, two) = turn(sketch(q(waitWins), fear = "being blamed"), one)
        val owner = IntakeChecklist.self(two)?.id
        // last turn's pick, as Call 1 worded it then; this turn's option is "Confront"
        val reworded = "Confront her about it"
        val sent = RecommendationDiff.of(Intent.STANDARD, reworded, "Wait", ops, one, two, owner)
        assertNotNull("matched to Confront, and the pick is now Wait", sent)
        assertEquals(reworded, sent!!.from); assertEquals("Wait", sent.to)
        assertNull("matched to the pick itself: the same advice reworded", RecommendationDiff.of(Intent.STANDARD, "Wait it out a while", "Wait", ops, one, two, owner))
        assertNull("no option matches: no claim", RecommendationDiff.of(Intent.STANDARD, "Ask her sister for advice", "Wait", ops, one, two, owner))
    }
}
