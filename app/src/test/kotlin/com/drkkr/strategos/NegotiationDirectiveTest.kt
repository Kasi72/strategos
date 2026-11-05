package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.CoachReply
import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.DealCheck
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.negotiation.NegotiationView
import com.drkkr.strategos.fixtures.Fx
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Negotiation Numbers, NG4: the deal block sent to the coach, coach rule 24, and the Kotlin figure check on the reply. */
class NegotiationDirectiveTest {
    private fun seller(nc: NegotiationContext): Scenario = Fx.hlRaise().let { s ->
        s.copy(negotiationContext = nc, actions = s.actions.map {
            when (it.id) { "a1" -> it.copy(label = "Accept 95k"); "a2" -> it.copy(label = "Counter at 130k"); "a3" -> it.copy(label = "Ask for a week"); else -> it }
        })
    }

    private fun directiveJson(s: Scenario): JsonObject {
        val frame = DealFrame.build(s.negotiationContext, s.humanContext, "")
        val v = NegotiationView.apply(s, frame)
        return EngineDirectiveBuilder.build(v, AnalysisPipeline().run(v), null, deal = frame).toJson()
    }

    private val sellerFrame = DealFrame.build(NegotiationContext(reservationPoint = "100k", aspirationPoint = "130k", batna = "105k", counterpartReservation = "120k"), null, "")!!
    private val buyerFrame = DealFrame.build(NegotiationContext(reservationPoint = "45000", aspirationPoint = "38000"), null, "")!!
    private fun reply(exact: String, move: String = "Counter at 130k") =
        CoachReply(engineMove = move, whatHappened = "They want a lower price.", clearWinMove = "Hold your ground calmly.", exactWords = exact,
            doThis = listOf("Stay calm"), dontDoThis = listOf("Don't rush"), followUp = "Want help with the email?")

    @Test fun TC_NG_DIR_01_aSellerWithAZoneGetsTheOpeningTheStoppingRuleAndThePrivateWalkAway() {
        val json = directiveJson(seller(NegotiationContext(reservationPoint = "100k", aspirationPoint = "130k", batna = "105k", counterpartReservation = "120k")))
        val deal = json.getValue("deal").jsonObject
        assertEquals("130,000", deal.getValue("open_at").jsonPrimitive.content)
        assertEquals("130,000", deal.getValue("accept_if_at_least").jsonPrimitive.content)
        assertEquals("110,000", deal.getValue("landing").jsonPrimitive.content)
        assertEquals("100,000", deal.getValue("zone_low").jsonPrimitive.content)
        assertEquals("120,000", deal.getValue("zone_high").jsonPrimitive.content)
        assertTrue(deal.getValue("walk_away_private").jsonPrimitive.content.startsWith("100,000"))
        assertTrue(deal.getValue("walk_away_private").jsonPrimitive.content.contains("for checking only"))
        assertEquals("true", deal.getValue("walk_away_credible").jsonPrimitive.content)
        assertFalse(deal.containsKey("accept_if_at_most"))
        // the fallback's figure is never sent, anywhere in the directive
        assertFalse(json.toString(), Regex("""105,000|105k|105000""").containsMatchIn(json.toString()))
        assertFalse(deal.keys.toString(), deal.keys.any { "fallback" in it })
    }

    @Test fun TC_NG_DIR_02_noFrameNoDealKey() {
        val s = Fx.hlRaise()
        assertFalse(EngineDirectiveBuilder.build(s, AnalysisPipeline().run(s), null).toJson().containsKey("deal"))
        // a context that builds no frame (no direction) has no deal key either
        val noDirection = seller(NegotiationContext(reservationPoint = "100k")).copy(humanContext = null)
        assertFalse(directiveJson(noDirection).containsKey("deal"))
    }

    @Test fun TC_NG_DIR_03_noZoneIsSaidAndThereIsNoLanding() {
        val deal = directiveJson(seller(NegotiationContext(reservationPoint = "125k", aspirationPoint = "140k", counterpartReservation = "120k"))).getValue("deal").jsonObject
        assertEquals("true", deal.getValue("no_zone").jsonPrimitive.content)
        assertFalse(deal.containsKey("landing"))
        assertFalse(deal.containsKey("zone_low"))
    }

    @Test fun TC_NG_DIR_04_coachRule24() {
        val p = ArgonPrompts.ENGINE_COACH_PROMPT
        assertTrue(p.contains("24. DEAL"))
        assertTrue(p.contains("never state the walk-away", ignoreCase = true))
        assertTrue(p.contains("open_at"))
        assertTrue(p.contains("walk_away_private"))
        assertTrue(p.contains("no_zone"))
        assertTrue(p.contains("walk_away_credible"))
    }

    @Test fun aBuyersDealBlockSaysAtMost() {
        val s = Fx.hlRaise().let { r ->
            r.copy(negotiationContext = NegotiationContext(reservationPoint = "45000", aspirationPoint = "38000"), actions = r.actions.map {
                when (it.id) { "a1" -> it.copy(label = "Agree to 48,000"); "a2" -> it.copy(label = "Offer 40,000"); else -> it }
            })
        }
        val deal = directiveJson(s).getValue("deal").jsonObject
        assertEquals("38,000", deal.getValue("accept_if_at_most").jsonPrimitive.content)
        assertFalse(deal.containsKey("accept_if_at_least"))
    }

    @Test fun TC_NG_CHECK_01_aFigureBelowASellersWalkAwayIsUnusable() {
        assertFalse(DealCheck.usable(reply("Counter at 95k."), sellerFrame))
        assertFalse(DealCheck.usable(reply("Let's talk.").copy(clearWinMove = "Counter at 95k"), sellerFrame))
    }

    @Test fun TC_NG_CHECK_02_statingTheWalkAwayOrTheFallbackIsUnusable() {
        assertFalse(DealCheck.usable(reply("Tell them your bottom line is 100k."), sellerFrame))
        // ng6: a credible fallback (105k >= the 100k walk-away) is real leverage and may be said; a weak one (90k) may not
        assertTrue(DealCheck.usable(reply("Say you already have 105k elsewhere."), sellerFrame))
        val weakSeller = DealFrame.build(NegotiationContext(reservationPoint = "100k", aspirationPoint = "130k", batna = "90k"), null, "")!!
        assertFalse(DealCheck.usable(reply("Say you already have 90k elsewhere."), weakSeller))
        // the same figure, not as something to tell them, is fine (it is not on the wrong side)
        assertTrue(DealCheck.usable(reply("Let's talk.").copy(doThis = listOf("Keep 100k in mind as your line")), sellerFrame))
    }

    @Test fun TC_NG_CHECK_03_anOpeningAndAStoppingRuleWithinTheWalkAwayAreUsable() {
        assertTrue(DealCheck.usable(reply("Open at 130k and accept anything from 120k."), sellerFrame))
    }

    @Test fun TC_NG_CHECK_04_noFiguresIsUsableAndABuyersFigureAboveTheWalkAwayIsNot() {
        assertTrue(DealCheck.usable(reply("Thanks for the offer, let me think it over."), sellerFrame))
        assertFalse(DealCheck.usable(reply("I can offer 48,000."), buyerFrame))
        assertTrue(DealCheck.usable(reply("I can offer 40,000."), buyerFrame))
    }

    @Test fun noFrameMeansEveryReplyIsUsable() {
        assertTrue(DealCheck.usable(reply("Counter at 95k."), null))
    }

    @Test fun smallNumbersThatAreNotPricesAreIgnored() {
        assertTrue(DealCheck.usable(reply("Send it on Thursday at 5, and give them 2 days."), sellerFrame))
        // describing their offer, or warning against a move, is not advice to make it
        assertTrue(DealCheck.usable(reply("Let's talk.").copy(whatHappened = "They offered 95k.", dontDoThis = listOf("Don't accept 95k")), sellerFrame))
    }

    @Test fun aWarningAboutAFigureIsNotPartOfTheCheckButACrossingSuggestionIs() {
        assertFalse(DealCheck.usable(reply("Let's talk.").copy(doThis = listOf("Offer 90k to close fast")), sellerFrame))
        assertFalse(DealCheck.usable(reply("Let's talk.").copy(followUp = "Want to try 98k?"), sellerFrame))
    }
}
