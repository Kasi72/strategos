package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.IntakeGap
import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.human.BannedWords
import com.drkkr.strategos.domain.human.FallbackShift
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.intent.IdentifyGaps
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.PayoffEntry
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import com.drkkr.strategos.fixtures.Fx
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID2.4: one question at most, from fixed templates, in priority order. */
class IdentifyGapsTest {
    private val R = PayoffDimension.RELATIONSHIPS

    /** You: "hold" / "give"; Karthik: "yes" (receptive) / "no" (defensive). */
    private fun vendor(ctx: HumanContext) = Scenario(id = "g", title = "CRM", decisionOwner = "You",
        stakeholders = listOf(Stakeholder(id = "you", name = "You", gameRole = GameRole.PLAYER), Stakeholder(id = "k", name = "Karthik", gameRole = GameRole.PLAYER)),
        actions = listOf(Action(id = "hold", stakeholderId = "you", label = "Hold firm", description = "[posture:boundary]"),
            Action(id = "give", stakeholderId = "you", label = "Offer extras first", description = "[posture:repair]"),
            Action(id = "yes", stakeholderId = "k", label = "Agree", description = "[posture:receptive]"),
            Action(id = "no", stakeholderId = "k", label = "Refuse", description = "[posture:defensive]")),
        payoffs = listOf("hold" to "yes", "hold" to "no", "give" to "yes", "give" to "no").flatMap { (m, t) ->
            listOf(PayoffEntry("you", mapOf("you" to m, "k" to t), MultiPayoff(mapOf(R to 5.0)), FactStatus.AI_INFERRED),
                PayoffEntry("k", mapOf("you" to m, "k" to t), MultiPayoff(mapOf(R to if (t == "yes") 6.0 else 5.0)), FactStatus.AI_INFERRED))
        }, humanContext = ctx)

    @Test fun TC_ID_GAP_01_threeOpenGapsGiveOneQuestionInPriorityOrder() {
        val s = vendor(HumanContext(relationship = "manager"))
        val gaps = listOf(IntakeGap("limits", hint = "x"), IntakeGap("wants_fears", "Karthik", "x"), IntakeGap("my_options", hint = "x"))
        val q = IdentifyGaps.next(s, gaps, null, null)
        assertEquals("wants_fears", q?.id)
        assertEquals("What does Karthik want most here, and what worries them?", q?.text)
        // already asked in this thread: the next one, never the same twice
        assertEquals("my_options", IdentifyGaps.next(s, gaps, null, null, alreadyAsked = listOf("Sure. ${q!!.text}"))?.id)
        // the directive carries exactly one question, as a string
        val d = EngineDirectiveBuilder.build(Fx.loanFamily(), AnalysisPipeline().run(Fx.loanFamily()), null).copy(nextQuestion = q).toJson()
        assertEquals(q.text, d["next_question"]!!.jsonPrimitive.content)
        assertNull(IdentifyGaps.next(s, emptyList(), null, null))
    }

    @Test fun TC_ID_GAP_02_aGapThatFlipsThePickComesBeforeTheMissingWalkAway() {
        val s = vendor(HumanContext(relationship = "vendor"))
        // a stand-in pick that their refusal cell decides: their fallback, strong or weak, flips it
        val pick: (Scenario) -> String? = { v ->
            if (v.payoffs.single { it.stakeholderId == "k" && it.actionProfile["you"] == "give" && it.actionProfile["k"] == "no" }.payoff.values.getValue(R) > 5.0) "hold" else "give"
        }
        assertTrue(FallbackShift.unknownTheirFallbackMatters(s, null, false, false, pick))
        val flips = setOf(IdentifyGaps.THEIR_FALLBACK)
        val q = IdentifyGaps.next(s, IntakeChecklist.gaps(s), null, null, flips)!!
        assertEquals(IdentifyGaps.THEIR_FALLBACK, q.id)
        assertTrue(q.text, q.text.startsWith("One thing would change my advice:"))
        assertTrue(q.text.contains("Karthik"))
        // without the flip, the missing walk-away is next
        assertEquals(IdentifyGaps.WALK_AWAY, IdentifyGaps.next(s, IntakeChecklist.gaps(s), null, null)?.id)
    }

    @Test fun TC_ID_GAP_03_and_04_theD3Rule() {
        val vendor = vendor(HumanContext(relationship = "vendor"))
        assertTrue("a negotiation, a figure in the pick, no walk-away", IdentifyGaps.askFirst(vendor, null, "Offer \$45,000 with onboarding"))
        assertFalse("no figure in the pick", IdentifyGaps.askFirst(vendor, null, "Hold firm"))
        val known = vendor.copy(negotiationContext = com.drkkr.strategos.domain.model.NegotiationContext(reservationPoint = "\$50,000"))
        assertFalse("walk-away known", IdentifyGaps.askFirst(known, null, "Offer \$45,000 with onboarding"))
        val spouse = vendor(HumanContext(relationship = "spouse"))
        assertFalse("not a negotiation: advise first", IdentifyGaps.askFirst(spouse, null, "Offer 2 evenings a week"))
        assertNotNull(IdentifyGaps.next(spouse, listOf(IntakeGap("wants_fears", "Karthik", "x")), null, null))
        assertEquals(IdentifyGaps.WALK_AWAY, IdentifyGaps.walkAwayQuestion().id)
    }

    @Test fun TC_ID_GAP_05_everyTemplatePassesTheBannedWordsAndCarriesNoFigure() {
        val jargon = Regex("""(?i)\b(batna|zopa|reservation|anchor)""")
        for ((id, t) in IdentifyGaps.TEMPLATES) {
            val text = t.replace("{name}", "Karthik")
            assertFalse(id, BannedWords.containedIn(text))
            assertFalse(id, jargon.containsMatchIn(text))
            assertFalse(id, text.any { it.isDigit() })
            assertTrue(id, text.trim().endsWith("?"))
            assertEquals(id, 1, text.count { it == '?' })
        }
    }
}
