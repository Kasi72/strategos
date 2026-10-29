package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.QuoteVerifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteVerifierTest {

    @Test
    fun TC_QUOTE_01_caseAndWhitespaceIgnored() {
        assertTrue(QuoteVerifier.isVerified("she can veto it", listOf("Honestly, she CAN   veto it.")))
    }

    @Test
    fun TC_QUOTE_02_tooShort() {
        assertFalse(QuoteVerifier.isVerified("veto", listOf("Honestly, she CAN   veto it.")))
    }

    @Test
    fun TC_QUOTE_03_paraphraseRejected() {
        assertFalse(QuoteVerifier.isVerified("my manager decides", listOf("my boss decides")))
    }

    @Test
    fun TC_QUOTE_04_tamil() {
        val quote = "அம்மா ஒப்புக்கொள்ள மாட்டார்"
        assertTrue(QuoteVerifier.isVerified(quote, listOf("எனக்கு தெரியும், அம்மா ஒப்புக்கொள்ள மாட்டார் என்று.")))
    }

    @Test
    fun TC_QUOTE_05_eventTextNotPassedIsNotEvidence() {
        // The caller passes USER texts only; a phrase that appeared only in an EVENT message is absent from the list.
        val userTexts = listOf("We need to decide by Friday.")
        val eventOnly = "Applied 3 of 4 changes"
        assertFalse(QuoteVerifier.isVerified(eventOnly, userTexts))
    }

    @Test
    fun surroundingQuotesAndPunctuationStripped() {
        assertTrue(QuoteVerifier.isVerified("“She can veto it!”", listOf("she can veto it")))
        assertFalse(QuoteVerifier.isVerified(null, listOf("anything at all")))
        assertFalse(QuoteVerifier.isVerified("   ", listOf("   ")))
    }
}
