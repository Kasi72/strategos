package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.CoachReply
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.negotiation.DealCheck
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.negotiation.WalkAwayMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Negotiation Numbers, ng7: their quote is not their limit, rule 24's no-zone wording, advice to reveal the walk-away, mask phrasing. */
class DealGuardTest {
    private val vendorNc = NegotiationContext(reservationPoint = "$45,000 a year", counterpartReservation = "$60,000 a year")
    private val vendorCtx = HumanContext(relationship = "vendor")
    private val vendor = DealFrame.build(vendorNc, vendorCtx, "")!!
    private fun reply(exact: String = "Let's talk.", clear: String = "Hold your ground.", doThis: List<String> = listOf("Stay calm")) =
        CoachReply(engineMove = "m", whatHappened = "x", clearWinMove = clear, exactWords = exact, doThis = doThis, dontDoThis = emptyList(), followUp = "Ready?")

    @Test fun aFigureTheUserCalledTheirQuoteIsTheirOpeningNotTheirLimit() {
        val said = "I'm buying a CRM. The vendor's sales rep Karthik quoted 60,000 dollars a year. My budget ceiling is 45,000."
        val f = DealFrame.build(vendorNc, vendorCtx, "What should I say?", recent = said)!!
        assertNull(f.theirLimit)
        assertNull(f.zone)
        assertFalse(f.noZone)
        assertFalse(f.notes.toString(), f.notes.any { "no overlap" in it })
        // without such a word nearby the figure stays their limit
        val g = DealFrame.build(vendorNc, vendorCtx, "", recent = "I think 60,000 is the most he can do.")!!
        assertEquals(60000.0, g.theirLimit!!.value, 0.0)
        assertTrue(g.noZone)
        // "asking", "priced", "listed", "offered" count too
        for (w in listOf("He's asking 60k", "It's priced at $60,000", "Listed at 60,000", "They offered 60,000"))
            assertNull(w, DealFrame.build(vendorNc, vendorCtx, "", recent = w)!!.theirLimit)
    }

    @Test fun rule24SaysTheGapHasToCloseNotThatNoDealIsPossible() {
        val p = ArgonPrompts.ENGINE_COACH_PROMPT
        assertTrue(p.contains("their position is beyond your line, so the gap has to close or your fallback matters"))
        assertFalse(p.contains("a deal may not be possible"))
        assertTrue(ArgonPrompts.ANSWER_FOLLOWUP_PROMPT.contains("Never advise revealing the walk-away"))
    }

    @Test fun adviceToRevealTheWalkAwayIsRejectedEvenWithoutTheFigure() {
        assertFalse(DealCheck.usable(reply(doThis = listOf("Say the number once, then stop")), vendor))
        assertFalse(DealCheck.usable(reply(clear = "Tell him your budget and ask him to match it."), vendor))
        assertFalse(DealCheck.usable(reply(exact = "Let me share my bottom line with you."), vendor))
        assertFalse(DealCheck.usableAnswer("Then say it simply. Say the number once, then stop and let him respond.", vendor))
        // negated advice is allowed
        assertTrue(DealCheck.usable(reply(doThis = listOf("Don't name your ceiling")), vendor))
        assertTrue(DealCheck.usable(reply(clear = "Never reveal your walk-away; ask what he can do."), vendor))
        assertTrue(DealCheck.usableAnswer("Keep your ceiling private and let him move first.", vendor))
        assertTrue(DealCheck.usableAnswer("Ask him to name a number first.", vendor))
        // without a frame nothing is checked (I-N1)
        assertTrue(DealCheck.usableAnswer("Say the number once.", null))
    }

    @Test fun aMaskedYourCeilingIsYourCeilingReadsAsAFirmCeiling() {
        assertEquals("Tell Karthik plainly that you have a firm ceiling and ask what he can do",
            WalkAwayMask.text("Tell Karthik plainly that $45,000 is your hard ceiling and ask what he can do", vendor))
        assertEquals("Remember that you have a firm ceiling.", WalkAwayMask.text("Remember that $45,000 a year is your ceiling.", vendor))
        val seller = DealFrame.build(NegotiationContext(reservationPoint = "100k", aspirationPoint = "130k"), null, "")!!
        assertEquals("Say that you have a firm floor", WalkAwayMask.text("Say that 100k is your floor", seller))
        // other masked sentences are left as they were
        assertEquals("State that you'll walk away above your ceiling", WalkAwayMask.text("State that you'll walk away above $45,000", vendor))
    }
}
