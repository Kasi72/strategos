package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.CoachReply
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.DealCheck
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.negotiation.WalkAwayMask
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Negotiation Numbers, ng5 (CP-N decision): the walk-away and fallback figures are masked in what the coach sees, checked more strictly, and the frame is sanitised. */
class WalkAwayMaskTest {
    /** The vendor story: a buyer with a $45,000 ceiling and another vendor at $42,000. */
    private val vendorNc = NegotiationContext(reservationPoint = "$45,000 a year", aspirationPoint = "at or below $45,000 a year", batna = "another vendor at about 42,000")
    private val vendorCtx = HumanContext(relationship = "vendor")
    private val vendor = DealFrame.build(vendorNc, vendorCtx, "")!!

    private fun buyerScenario(): Scenario = Fx.hlRaise().let { s ->
        s.copy(negotiationContext = vendorNc, humanContext = vendorCtx, actions = s.actions.map {
            when (it.id) {
                "a1" -> it.copy(label = "Tell Karthik plainly that $45,000 is your hard ceiling")
                "a2" -> it.copy(label = "Mention the other vendor at $42,000")
                "a3" -> it.copy(label = "Accept the $60,000 quote")
                "r1" -> it.copy(label = "Come back near $45,000")
                else -> it
            }
        })
    }

    private fun reply(exact: String = "Let's talk.", clear: String = "Hold your ground.", doThis: List<String> = listOf("Stay calm"), followUp: String = "Want help with the email?") =
        CoachReply(engineMove = "m", whatHappened = "They quoted $60,000.", clearWinMove = clear, exactWords = exact, doThis = doThis, dontDoThis = listOf("Don't rush"), followUp = followUp)

    @Test fun theMaskReplacesTheWalkAwayAndTheFallbackInTheUsersOptionLabelsInTheViewOnly() {
        val s = buyerScenario()
        val v = WalkAwayMask.apply(s, vendor)
        val labels = v.actions.associate { it.id to it.label }
        // ng7: "that your ceiling is your hard ceiling" reads as "that you have a firm ceiling"
        assertEquals("Tell Karthik plainly that you have a firm ceiling", labels["a1"])
        // ng6: a credible fallback is leverage and stays; a weak one ("another vendor at 48,000", above the ceiling) is masked
        assertEquals("Mention the other vendor at $42,000", labels["a2"])
        val weak = DealFrame.build(vendorNc.copy(batna = "another vendor at 48,000"), vendorCtx, "")!!
        assertEquals(false, weak.walkAwayCredible)
        assertEquals("Mention the other vendor at your other option", WalkAwayMask.text("Mention the other vendor at $48,000", weak))
        assertEquals("Accept the $60,000 quote", labels["a3"])
        assertEquals("the other side's options are not masked", "Come back near $45,000", labels["r1"])
        // the saved scenario is never changed: the view is a copy
        assertEquals("Tell Karthik plainly that $45,000 is your hard ceiling", s.actions.first { it.id == "a1" }.label)
        assertSame(s, WalkAwayMask.apply(s, null))
        // a seller's walk-away is "your floor"; within ±1% counts
        val seller = DealFrame.build(NegotiationContext(reservationPoint = "100k", aspirationPoint = "130k"), null, "")!!
        assertEquals("Hold at your floor", WalkAwayMask.text("Hold at 100,500", seller))
        assertEquals("Hold at 102,000", WalkAwayMask.text("Hold at 102,000", seller))
    }

    @Test fun exactWordsWithTheWalkAwayAreUnusableWithNoVerbNeeded() {
        assertFalse(DealCheck.usable(reply(exact = "Karthik, $45,000 a year is our hard ceiling."), vendor))
    }

    @Test fun aCredibleFallbackMayBeSaid_aWeakOneMayNot() {
        assertEquals(true, vendor.walkAwayCredible)
        assertTrue(DealCheck.usable(reply(exact = "I have another offer at $42,000."), vendor))
        val weak = DealFrame.build(vendorNc.copy(batna = "another vendor at 48,000"), vendorCtx, "")!!
        assertEquals(false, weak.walkAwayCredible)
        assertFalse(DealCheck.usable(reply(exact = "I have another offer at $48,000."), weak))
        // credible unknown (no figure for the fallback): nothing to block
        val unknown = DealFrame.build(vendorNc.copy(batna = "keep the old system"), vendorCtx, "")!!
        assertNull(unknown.walkAwayCredible)
        assertTrue(DealCheck.usable(reply(exact = "We could keep our old system."), unknown))
    }

    @Test fun privateAdviceWithTheWalkAwayIsUsable_aSpeechCueOrAQuoteIsNot() {
        assertTrue(DealCheck.usable(reply(doThis = listOf("Don't go above $45,000")), vendor))
        assertFalse(DealCheck.usable(reply(clear = "Write to Karthik that $45,000 is your ceiling."), vendor))
        assertFalse(DealCheck.usable(reply(doThis = listOf("Email him: 45,000 is the most")), vendor))
        assertFalse(DealCheck.usable(reply(followUp = "Your line is \"not above $45,000\"."), vendor))
    }

    @Test fun aFollowUpAnswerThatGivesTheWalkAwayAsALineIsRejected() {
        assertFalse(DealCheck.usableAnswer("Your strongest line tomorrow is \"I'm not going above $45,000\".", vendor))
        assertFalse(DealCheck.usableAnswer("Tell him you can do 45,000.", vendor))
        assertTrue(DealCheck.usableAnswer("Keep $45,000 in your head as the line you won't cross.", vendor))
        assertTrue(DealCheck.usableAnswer("Anything is fine.", null))
    }

    @Test fun aTargetEqualToTheWalkAwayIsTreatedAsMissing() {
        assertNull(vendor.target)
        assertNull(vendor.openAt)
        assertTrue(vendor.notes.toString(), vendor.notes.any { "target" in it })
    }

    @Test fun aFallbackEqualToTheWalkAwayOrAWalkAwayStatementIsTreatedAsMissing() {
        assertEquals(42000.0, vendor.fallback!!.value, 0.0)
        assertEquals(true, vendor.walkAwayCredible)
        val same = DealFrame.build(vendorNc.copy(batna = "45,000"), vendorCtx, "")!!
        assertNull(same.fallback); assertNull(same.walkAwayCredible)
        val statement = DealFrame.build(vendorNc.copy(batna = "walk away above $45,000"), vendorCtx, "")!!
        assertNull(statement.fallback); assertNull(statement.walkAwayCredible)
        assertEquals(42000.0, DealFrame.build(vendorNc.copy(batna = "walk away and take the 42,000 vendor"), vendorCtx, "")!!.fallback!!.value, 0.0)
    }

    @Test fun maskedFallbackTextNamesNoFigure() {
        val shown = WalkAwayMask.text("Options to consider\n1. State that you will walk away above $45,000: best fit right now", vendor)
        assertFalse(shown, shown.contains("45,000"))
        assertTrue(shown, shown.contains("above your ceiling"))
    }

    @Test fun aRejectedFollowUpAnswerLosesTheRevealingSentencesAndGainsTheKeepPrivateLine() {
        // vendor turn 4 (pm4.6 replay), the answer that survived its retry
        val t4 = "Here's what that means in practice for tomorrow. Open by naming your number as a firm limit, not a starting point. " +
            "Then say it simply: \"My number is my hard ceiling — what can you do to get me there?\" If he pushes on Friday, ask where that date comes from."
        assertFalse(DealCheck.usableAnswer(t4, vendor))
        val shown = DealCheck.sanitizeAnswer(t4, vendor)
        assertTrue(shown, DealCheck.usableAnswer(shown, vendor))
        assertFalse(shown, shown.contains("naming your number"))
        assertFalse(shown, shown.contains("\""))
        assertFalse(shown, shown.contains("say it simply"))
        assertTrue(shown, shown.contains("If he pushes on Friday, ask where that date comes from."))
        assertTrue(shown, shown.endsWith("• Keep your walk-away to yourself."))
        // without a frame nothing changes
        assertEquals(t4, DealCheck.sanitizeAnswer(t4, null))
    }
}
