package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.SafetyReply
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, HL5.2: when the user may be unsafe, the reply is safety-first, never strategy. */
class SafetyReplyTest {
    @Test
    fun TC_HL_SAFE_01_aWarmReplyThatPointsToSomeoneTheyTrustIsAcceptable() {
        assertTrue(SafetyReply.acceptable("That sounds exhausting, and I'm glad you told me. You don't have to carry it alone: talk to someone you trust. The support card has numbers you can use right now."))
        assertTrue(SafetyReply.acceptable(SafetyReply.FALLBACK))
        assertTrue(SafetyReply.FALLBACK.contains("someone you trust") && SafetyReply.FALLBACK.contains("support card"))
    }

    @Test
    fun TC_HL_SAFE_02_anythingThatAdvisesConfrontingStandingFirmWithholdingOrTestingIsRefused() {
        for (bad in listOf(
            "You should stand firm with him.", "Stand your ground tonight.", "Confront her about the money.", "Don't apologise to warm him up.",
            "Do not apologize, it teaches him silence works.", "Say you won't keep guessing what you did.", "Withhold the answer until he asks nicely.",
            "Test him by staying quiet for a day.", "Hold the line and don't give in.", "Hold your boundary.", "Give him an ultimatum.", "Stop asking him for money."
        )) assertFalse(bad, SafetyReply.acceptable("I hear you. $bad Please talk to someone you trust."))
    }

    @Test
    fun TC_HL_SAFE_03_emptyJsonLongOrNamingReplyIsRefused() {
        assertFalse(SafetyReply.acceptable(null)); assertFalse(SafetyReply.acceptable("  "))
        assertFalse(SafetyReply.acceptable("""{"clear_win_move":"do this"}"""))
        assertFalse(SafetyReply.acceptable("x".repeat(701)))
        assertFalse(SafetyReply.acceptable("As Law 31 says, talk to someone you trust."))
    }

    @Test
    fun TC_HL_SAFE_04_thePromptsSayWhatTheyMustSay() {
        val p = ArgonPrompts.SAFETY_REPLY_PROMPT
        for (needle in listOf("acknowledge", "someone they trust", "support card", "NEVER advise confronting, standing firm with, withholding from, or testing"))
            assertTrue("safety prompt: $needle", p.contains(needle))
        val extraction = ArgonPrompts.EXTRACTION_PROMPT
        assertTrue(extraction.contains("controlling money") && extraction.contains("cutting you off from friends or family") && extraction.contains("intimidation"))
        val coach = ArgonPrompts.ENGINE_COACH_PROMPT
        assertTrue(coach.contains("\n21. WATCH FOR") && coach.contains("`watch_for`") && coach.contains("`protect`") && coach.contains("without accusing"))
    }
}
