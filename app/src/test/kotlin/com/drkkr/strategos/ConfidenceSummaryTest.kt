package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ConfidenceLevel
import com.drkkr.strategos.domain.agent.StressDirection
import com.drkkr.strategos.domain.agent.StressFlip
import com.drkkr.strategos.domain.agent.StressResult
import com.drkkr.strategos.domain.agent.StressVerdict
import com.drkkr.strategos.domain.game.LabelIssue
import com.drkkr.strategos.domain.human.BannedWords
import com.drkkr.strategos.domain.intent.ConfidenceSummary
import com.drkkr.strategos.domain.intent.ConfidenceTier
import com.drkkr.strategos.domain.intent.IdentifyGaps
import com.drkkr.strategos.domain.intent.NextQuestion
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.negotiation.DealFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Intent Dispatch ID3.5: the confidence tier, its fixed reason, and the missing fact. */
class ConfidenceSummaryTest {
    private val flipOnSam = StressResult(StressVerdict.FRAGILE, 6, listOf(StressFlip("Sam", "Hold firm / Refuse", StressDirection.WORSEN, "Wait")), 1, 5, false)

    @Test fun TC_ID_CONF_01_robust_fragile_keyGap() {
        val robust = ConfidenceSummary.of(ConfidenceLevel.HIGH, false, null, emptyList(), null, "Sam")
        assertEquals(ConfidenceTier.HIGH, robust.tier); assertEquals(ConfidenceSummary.HOLDS, robust.reason); assertNull(robust.gap)
        val fragile = ConfidenceSummary.of(ConfidenceLevel.HIGH, true, flipOnSam, emptyList(), null, "Sam")
        assertEquals(ConfidenceTier.MODERATE, fragile.tier)
        assertEquals("the advice flips if the guess about Sam's side is wrong", fragile.reason)
        assertEquals(ConfidenceSummary.FLIPS_HUMAN, ConfidenceSummary.of(ConfidenceLevel.HIGH, true, null, emptyList(), null, "Sam").reason)
        val walk = IdentifyGaps.walkAwayQuestion()
        val missing = ConfidenceSummary.of(ConfidenceLevel.HIGH, false, null, emptyList(), walk, "Sam")
        assertEquals(ConfidenceTier.LOW_MISSING_KEY_FACT, missing.tier); assertEquals(walk.text, missing.gap)
        // a question that wouldn't change the pick is not a key gap: still HIGH, and the question rides along
        val minor = NextQuestion("limits", IdentifyGaps.TEMPLATES.getValue("limits"))
        assertEquals(ConfidenceTier.HIGH, ConfidenceSummary.of(ConfidenceLevel.HIGH, false, null, emptyList(), minor, "Sam").tier)
        assertEquals(ConfidenceTier.MODERATE, ConfidenceSummary.of(ConfidenceLevel.MEDIUM, false, null, emptyList(), null, "Sam").tier)
    }

    @Test fun TC_ID_CONF_02_labelDominanceCapsTheTierAndSaysWhy() {
        val c = ConfidenceSummary.of(ConfidenceLevel.HIGH, false, null, listOf(LabelIssue.LabelDominance("a")), null, "Sam")
        assertEquals(ConfidenceTier.MODERATE, c.tier)
        assertEquals("the AI's reading favoured one option everywhere", c.reason)
    }

    @Test fun TC_ID_CONF_03_everyTemplateIsCleanAndCarriesNoWalkAwayFigure() {
        val seller = DealFrame.build(NegotiationContext(reservationPoint = "100k", aspirationPoint = "130k"), null, "")!!
        val filled = ConfidenceSummary.TEMPLATES.map { it.replace("{name}", "Asha") } +
            listOf(ConfidenceSummary.of(ConfidenceLevel.HIGH, true, flipOnSam.copy(flips = listOf(StressFlip("Asha", "Accept 100k / Agree", StressDirection.WORSEN, null))), emptyList(), null, "Asha", seller).reason)
        for (t in filled) {
            assertFalse(t, BannedWords.containedIn(t))
            assertFalse(t, Regex("""(?i)\b(batna|zopa|reservation|anchor)""").containsMatchIn(t))
            assertFalse(t, t.contains("100") || t.any { it.isDigit() })
        }
        val json = ConfidenceSummary.of(ConfidenceLevel.LOW, true, flipOnSam, emptyList(), IdentifyGaps.walkAwayQuestion(), "Sam").toJson().toString()
        assertFalse(json.contains("%")); assertFalse(Regex("""\d""").containsMatchIn(json))
    }
}
