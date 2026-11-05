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
        // BLUEPRINT-QUESTION (resolved by the user): STEPS asks for <= 1,200 chars of growth AND every closed vocabulary spelled
        // out; the vocabularies alone are ~850 chars. The user accepted a larger prompt (1,600), then 1,800 in hl1.5 for short
        // definitions of the ambiguous tags and the "only if the user said it" guards. Restoring the schema keys (below) after the
        // live replay showed the model had stopped filling them took it to ~1,880, so the limit was 1,900; the safety wording added to rule 9 in HL5.2 (controlling money, cutting you off from friends or family, intimidation) took it to 2,000. hl3.6 added `last_attempt_tags` (one rule sentence and one schema key, +133 chars), so the limit is 2,133 (2,000 + exactly that growth; actual 2,093). hl3.7 added the corrects_them wording for last_attempt_tags and "in any language" in rule 9 (+85 chars), so the limit is 2,218 (actual 2,178).
        assertTrue("prompt grew by $growth chars", growth in 1..2218)
    }

    @Test
    fun TC_HL_PROMPT_03_theSchemaExampleStillParsesAndSaysNothingAboutAnyStory() {
        val example = p.substring(p.indexOf("JSON SCHEMA:") + "JSON SCHEMA:".length)
        val sk = SketchParser.parse(example)
        assertNotNull(sk)
        // Every new key must stay in the example: when relationship, status_quo and the states were trimmed out of it (hl1.5), the
        // live model stopped returning them altogether.
        for (k in listOf("relationship", "power", "status_quo", "your_state", "their_state", "option_tags", "their_tactics"))
            assertTrue("schema example is missing \"$k\"", example.contains("\"$k\""))
        assertNull(SketchHuman.context(sk!!))                    // nothing for the model to copy into a real story
        assertTrue(SketchHuman.optionTags(sk).isEmpty())
        assertEquals(3, sk.options.count { it.owner == "You" })  // the existing example is untouched
    }

    @Test
    fun TC_HL_PROMPT_04_existingRulesAreUntouched() {
        for (needle in listOf("18. NEGOTIATION FACTS", "17. ENOUGH TO RECOMMEND", "STAGE PROGRESSION", "THIRD-PARTY REQUESTS", "Do NOT give advice"))
            assertTrue(needle, p.contains(needle))
    }

    @Test
    fun TC_HL_PROMPT_05_ambiguousTagsAreDefinedAndSoftFieldsAreGuarded() {
        for (needle in listOf(
            "never an apology",                                   // corrects_them
            "says what will happen unless they object",           // sets_default
            "done today",                                         // immediate
            "cannot be undone",                                   // irreversible
            "they lose something they have",                      // asks_them_to_give_up
            "others see or hear it",                         // public
            "deliberate or repeated",                             // their_tactics
            "not one delay or quiet moment",
            "only when the user doubts the deadline",             // artificial_deadline
            "a real constraint goes here",
            "ONLY if the user said them"                          // alternatives, setting, dependence
        )) assertTrue("prompt should contain: $needle", p.contains(needle))
    }
}
