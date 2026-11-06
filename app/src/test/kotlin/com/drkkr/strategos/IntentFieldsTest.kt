package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.human.HumanVocab
import com.drkkr.strategos.domain.intent.Intent
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID2.1: the intent fields in Call 1's sketch. */
class IntentFieldsTest {
    private fun intentOf(raw: String?) = SketchParser.parse("""{${raw?.let { "\"intent\":$it" } ?: ""}}""")!!.intentKind

    @Test fun TC_ID_INT_01_theIntentIsAClosedWordAndAnythingElseIsStandard() {
        assertEquals(setOf("standard", "what_if", "compare", "top_options", "their_reaction", "plan_ahead"), HumanVocab.INTENTS)
        for (i in Intent.entries) assertEquals(i, intentOf("\"${i.word}\""))
        assertEquals(Intent.WHAT_IF, intentOf("\"WHAT_IF\""))
        assertEquals(Intent.TOP_OPTIONS, intentOf("\"Top options\""))
        assertEquals(Intent.STANDARD, intentOf("\"banana\""))
        assertEquals(Intent.STANDARD, intentOf(null))
        assertEquals(Intent.STANDARD, intentOf("null"))
        assertEquals(Intent.COMPARE, intentOf("""{"type":"compare"}"""))
        // the detail, lenient like the other blocks
        val s = SketchParser.parse("""{"intent":"compare","intent_detail":{"move_a":"Hold firm","move_b":["Ask for a week"]}}""")!!
        assertEquals("Hold firm", s.intentDetail?.moveA); assertEquals("Ask for a week", s.intentDetail?.moveB)
        assertNotNull("a non-object detail does not break the sketch", SketchParser.parse("""{"intent":"what_if","intent_detail":"offer 50k"}"""))
        // the prompt names every intent and the three keys
        val p = ArgonPrompts.EXTRACTION_PROMPT
        for (w in HumanVocab.INTENTS) assertTrue(w, p.contains(w))
        for (k in listOf("\"intent\"", "\"intent_detail\"", "\"what_if\"")) assertTrue(k, p.contains(k))
        assertTrue(p.contains("hypothetical ONLY under what_if"))
    }

    private val main = """"people":[{"name":"You","role":"player"},{"name":"Karthik","role":"player"}],
      "options":[{"who":"You","do":"Hold firm","posture":"boundary"},{"who":"You","do":"Ask for a week","posture":"repair"},
        {"who":"Karthik","do":"Agree","posture":"receptive"},{"who":"Karthik","do":"Refuse","posture":"defensive"}],
      "dims":["COST","RELATIONSHIPS"],
      "outcomes":[{"when":["Hold firm","Agree"],"for_you":["+","0"],"for_them":["-","0"]},{"when":["Hold firm","Refuse"],"for_you":["-","-"],"for_them":["0","-"]},
        {"when":["Ask for a week","Agree"],"for_you":["0","+"],"for_them":["0","+"]},{"when":["Ask for a week","Refuse"],"for_you":["-","0"],"for_them":["0","0"]}]"""
    private val whatIf = """"what_if":{"people":[{"name":"You","role":"player"},{"name":"Meena","role":"player"}],
      "options":[{"who":"You","do":"Offer 50k","posture":"repair"},{"who":"Meena","do":"Take it","posture":"receptive"}],
      "dims":["COST"],"outcomes":[{"when":["Offer 50k","Take it"],"for_you":["++"],"for_them":["+"]}],
      "limits":["never sign today"],"what_if":{"people":[{"name":"Deeper","role":"player"}]}}"""

    private fun save(json: String, s: Scenario): Scenario {
        val o = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse(json)!!, s).ops, s)
        val v = ChangeSetValidator.validate(s, o)
        val accepted = v.accepted.map { it.op }
        return ChangeSetApplier.apply(s, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
    }

    private val ENCODE = Json { encodeDefaults = true }

    /** The saved JSON, with each generated id replaced by its order of first appearance and each clock reading blanked (two separate saves mint different ids and times). */
    private fun bytes(s: Scenario): String {
        val seen = mutableMapOf<String, Int>()
        return Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            .replace(ENCODE.encodeToString(Scenario.serializer(), s)) { m -> "id${seen.getOrPut(m.value) { seen.size }}" }
            .replace(Regex("\"(\\w*[tT]imestamp|\\w*At)\":\\d+"), "\"$1\":0")
    }

    @Test fun TC_ID_INT_02_theSavePathWritesNothingFromWhatIf() {
        val start = Scenario(id = "s", title = "Deal")
        // an empty main part with a full what_if: nothing to save at all
        val only = SketchParser.parse("""{"intent":"what_if",$whatIf}""")!!
        assertNotNull(only.whatIf); assertEquals("Meena", only.whatIf!!.people[1].name)
        assertNull("one level only", only.whatIf!!.whatIf)
        assertTrue(SketchConverter.toOps(only, start).ops.isEmpty())
        // with a main part: exactly what the same sketch saves without what_if, byte for byte
        val saved = save("""{$main}""", start)
        val withWhatIf = save("""{$main,"intent":"what_if","intent_detail":{"change":"offer 50k"},$whatIf}""", start)
        assertEquals(bytes(saved), bytes(withWhatIf))
        assertTrue(withWhatIf.stakeholders.none { it.name == "Meena" })
        assertTrue(withWhatIf.actions.none { it.label == "Offer 50k" })
        // a later turn on top of the saved one: still byte-identical
        assertEquals(bytes(save("""{$main}""", saved)), bytes(save("""{$main,$whatIf}""", saved)))
    }
}
