package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ArgonPrompts
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID3.6: the coach rules for the new blocks are in the prompt. */
class IntentCoachRulesTest {
    @Test fun TC_ID_COACH_01_rules25to30CarryTheirKeyPhrases() {
        val p = ArgonPrompts.ENGINE_COACH_PROMPT
        for (n in 25..30) assertTrue("rule $n", p.contains("\n$n. "))
        for (phrase in listOf("advice changed", "one question", "what_if", "too_close", "never a percentage", "only when not HIGH"))
            assertTrue(phrase, p.contains(phrase, ignoreCase = true))
        for (key in listOf("changed_since_last", "next_question", "what_shifted", "compare", "top_options", "their_reaction", "confidence.reason"))
            assertTrue(key, p.contains(key))
    }
}
