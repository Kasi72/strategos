package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.SketchHuman
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.human.HumanVocab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, HL1.4: extraction prompt rules 19-22. */
class HumanPromptTest {
    private val p = ArgonPrompts.EXTRACTION_PROMPT

    /** Length of EXTRACTION_PROMPT on integration/kasi-engine-testing before this branch. */
    private val OLD_LENGTH = 9386

    @Test
    fun TC_HL_PROMPT_01_everyVocabularyWordIsSpelledOut() {
        val vocabularies = mapOf(
            "relationship" to HumanVocab.RELATIONSHIP, "power.relative" to HumanVocab.POWER_RELATIVE,
            "dependence" to HumanVocab.DEPENDENCE, "alternative" to HumanVocab.ALTERNATIVE, "setting" to HumanVocab.SETTING,
            "status_quo_for_them" to HumanVocab.STATUS_QUO_FOR_THEM, "deadline" to HumanVocab.DEADLINE,
            "their_vulnerable" to HumanVocab.VULNERABLE, "your_state" to HumanVocab.YOUR_STATE, "their_state" to HumanVocab.THEIR_STATE,
            "option_tags" to HumanVocab.OPTION_TAGS, "their_tactics" to HumanVocab.THEIR_TACTICS
        )
        for ((name, words) in vocabularies) for (w in words) assertTrue("prompt is missing $name word '$w'", p.contains(w))
        assertTrue(p.contains("only if the user says so"))
        for (rule in 19..22) assertTrue("rule $rule", p.contains("\n$rule. "))
    }

    @Test
    fun TC_HL_PROMPT_02_growthStaysWithinTheAgreedBudget() {
        val growth = p.length - OLD_LENGTH
        // BLUEPRINT-QUESTION: STEPS asks for <= 1,200 chars of growth AND every closed vocabulary spelled out. The vocabularies
        // alone are ~850 chars, so with the wording and the schema keys the minimum is ~1,550 (~390 tokens). Limit set to 1,600;
        // the 1,200 target is only reachable by dropping the their_tactics list. Raised with the user at CP-A.
        assertTrue("prompt grew by $growth chars", growth in 1..1600)
    }

    @Test
    fun TC_HL_PROMPT_03_theSchemaExampleStillParsesAndSaysNothingAboutAnyStory() {
        val example = p.substring(p.indexOf("JSON SCHEMA:") + "JSON SCHEMA:".length)
        val sk = SketchParser.parse(example)
        assertNotNull(sk)
        assertTrue(example.contains("\"option_tags\"") && example.contains("\"their_tactics\""))
        assertNull(SketchHuman.context(sk!!))                    // nothing for the model to copy into a real story
        assertTrue(SketchHuman.optionTags(sk).isEmpty())
        assertEquals(3, sk.options.count { it.owner == "You" })  // the existing example is untouched
    }

    @Test
    fun TC_HL_PROMPT_04_existingRulesAreUntouched() {
        for (needle in listOf("18. NEGOTIATION FACTS", "17. ENOUGH TO RECOMMEND", "STAGE PROGRESSION", "THIRD-PARTY REQUESTS", "Do NOT give advice"))
            assertTrue(needle, p.contains(needle))
    }
}
