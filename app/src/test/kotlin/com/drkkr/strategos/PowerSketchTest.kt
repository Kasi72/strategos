package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchHuman
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.HumanVocab
import com.drkkr.strategos.domain.human.ManipulationRadar
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.model.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Power Mode, PM3.1: `probes_you` and `user_asks_for` in extraction. */
class PowerSketchTest {
    private val options = """"people":[{"name":"You","role":"player"},{"name":"Karthik","role":"player"}],
      "options":[{"who":"You","do":"Hold firm","posture":"boundary"},{"who":"You","do":"Ask for a week","posture":"repair"},
        {"who":"Karthik","do":"Agree","posture":"receptive"},{"who":"Karthik","do":"Refuse","posture":"defensive"}],
      "dims":["COST","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[{"when":["Hold firm","Agree"],"for_you":[8,6,7],"for_them":[5,6,6]},{"when":["Hold firm","Refuse"],"for_you":[4,4,5],"for_them":[6,4,5]},
        {"when":["Ask for a week","Agree"],"for_you":[6,7,6],"for_them":[6,7,6]},{"when":["Ask for a week","Refuse"],"for_you":[5,5,5],"for_them":[6,5,5]}]"""

    private fun asks(raw: String?) = SketchHuman.context(SketchParser.parse("""{${raw?.let { "\"user_asks_for\":\"$it\"," } ?: ""}"relationship":"vendor"}""")!!)?.userAsksFor

    private fun apply(json: String, s: Scenario): Scenario {
        val o = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse(json)!!, s).ops, s)
        val v = ChangeSetValidator.validate(s, o)
        val accepted = v.accepted.map { it.op }
        return ChangeSetApplier.apply(s, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
    }

    @Test fun TC_PM_SKETCH_01_userAsksForIsAClosedVocabulary() {
        assertEquals("false_facts", asks("False Facts"))
        assertNull(asks("blackmail"))
        assertNull(asks(null))
        assertNull("none is nothing", asks("none"))
        assertEquals(setOf("none", "false_facts", "exploit_weakness", "isolate", "make_dependent", "fake_friend", "scapegoat"), HumanVocab.USER_ASKS_FOR)
    }

    @Test fun TC_PM_SKETCH_02_userAsksForIsNotCarriedForward() {
        val start = Scenario(id = "s", title = "Deal")
        val one = apply("""{$options,"relationship":"vendor","user_asks_for":"false_facts"}""", start)
        assertEquals("false_facts", one.humanContext?.userAsksFor)
        // a turn with other human fields (a question-only reply saves nothing at all, by the Human Layer's rule, so turns carry options)
        val two = apply("""{$options,"relationship":"vendor","user_asks_for":"none"}""", one)
        assertNull(two.humanContext?.userAsksFor)
        // and a turn with no human fields at all
        val again = apply("""{$options,"relationship":"vendor","user_asks_for":"fake_friend"}""", one)
        assertEquals("fake_friend", again.humanContext?.userAsksFor)
        val bare = apply("""{$options}""", again)
        assertNull(bare.humanContext?.userAsksFor)
        assertEquals("the rest is kept", "vendor", bare.humanContext?.relationship)
    }

    @Test fun TC_PM_SKETCH_03_thePromptNamesProbingAndEveryRequestKind_withinItsBudget() {
        val p = ArgonPrompts.EXTRACTION_PROMPT
        assertTrue(p.contains("probes_you"))
        for (v in HumanVocab.USER_ASKS_FOR) assertTrue(v, p.contains(v))
        assertTrue(p.contains("classify only what the user explicitly asks the app to help them do"))
        val growth = p.length - START_OF_BRANCH_LENGTH - LATER_PACKS_GROWTH
        assertTrue("grew by $growth", growth in 1..400)
    }

    @Test fun probingIsReadFromCallOneAndTheRadarNamesIt() {
        val ctx = SketchHuman.context(SketchParser.parse("""{"relationship":"vendor","their_tactics":["probes_you","none"]}""")!!)
        assertEquals(listOf("probes_you"), ctx?.theirTactics)
        val confirmed = HumanContext(relationship = "vendor", theirTactics = listOf("probes_you"), tacticTurns = mapOf("probes_you" to 2))
        val w = ManipulationRadar.scan(confirmed, RelationshipDomain.NEGOTIATION).watchFor.single()
        assertEquals("They're digging for your limits.", w.sign)
        assertEquals("Answer questions about your limits with ranges, not your bottom line.", w.protect)
        assertTrue(w.source.contains("L14"))
    }

    companion object {
        /** `EXTRACTION_PROMPT.length` when the power-mode branch was created (negotiation-numbers @ 36e5442). */
        const val START_OF_BRANCH_LENGTH = 11788
        /**
         * Characters later packs added to the same prompt, measured exactly, so this budget still counts Power Mode's own growth
         * only: Intent Dispatch id1.2 (rule 4's label wording and the example's labels, +370).
         */
        const val LATER_PACKS_GROWTH = 370
    }
}
