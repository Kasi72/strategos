package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.game.GameModelService
import com.drkkr.strategos.domain.intent.Intent
import com.drkkr.strategos.domain.intent.TopOptions
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.PayoffEntry
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.negotiation.NegotiationView
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID3.3: best, upside, insurance; never dominated, reactive or past the walk-away. */
class TopOptionsTest {
    private val R = PayoffDimension.RELATIONSHIPS

    /** [mine] maps a move's label to (posture, your score if they say yes, your score if they say no). */
    private fun game(mine: List<Triple<String, String, Pair<Double, Double>>>, nc: NegotiationContext? = null): Scenario {
        val actions = mine.mapIndexed { i, (label, posture, _) -> Action(id = "m$i", stakeholderId = "you", label = label, description = "[posture:$posture]") } +
            listOf(Action(id = "yes", stakeholderId = "sam", label = "Agree", description = "[posture:receptive]"),
                Action(id = "no", stakeholderId = "sam", label = "Refuse", description = "[posture:defensive]"))
        val pay = mine.flatMapIndexed { i, (_, _, v) ->
            listOf(PayoffEntry("you", mapOf("you" to "m$i", "sam" to "yes"), MultiPayoff(mapOf(R to v.first)), FactStatus.AI_INFERRED),
                PayoffEntry("you", mapOf("you" to "m$i", "sam" to "no"), MultiPayoff(mapOf(R to v.second)), FactStatus.AI_INFERRED),
                // Sam prefers to refuse the first move and to agree to the others, so neither reply is dominated (no iterated cascade)
                PayoffEntry("sam", mapOf("you" to "m$i", "sam" to "yes"), MultiPayoff(mapOf(R to if (i == 0) 4.0 else 6.0)), FactStatus.AI_INFERRED),
                PayoffEntry("sam", mapOf("you" to "m$i", "sam" to "no"), MultiPayoff(mapOf(R to 5.0)), FactStatus.AI_INFERRED))
        }
        return Scenario(id = "t", title = "t", decisionOwner = "You", negotiationContext = nc,
            stakeholders = listOf(Stakeholder(id = "you", name = "You", gameRole = GameRole.PLAYER), Stakeholder(id = "sam", name = "Sam", gameRole = GameRole.PLAYER)),
            actions = actions, payoffs = pay)
    }

    @Test fun TC_ID_TOP_01_fourOptionsGiveThreeDifferentMovesInTheThreeRoles() {
        val s = game(listOf(
            Triple("Talk it through tonight", "repair", 7.0 to 5.0),     // the pick
            Triple("Ask for the full amount", "boundary", 9.0 to 2.0),   // the most if they agree
            Triple("Put it in writing first", "repair", 6.0 to 5.5),     // the best worst case after the pick
            Triple("Drop a hint", "repair", 5.0 to 4.0)))
        val top = TopOptions.of(s, "Talk it through tonight", emptySet())
        assertEquals(listOf(TopOptions.BEST, TopOptions.UPSIDE, TopOptions.INSURANCE), top.map { it.role })
        assertEquals(listOf("Talk it through tonight", "Ask for the full amount", "Put it in writing first"), top.map { it.move })
        assertEquals(3, top.map { it.move }.distinct().size)
        assertTrue(top.all { it.tradeoff == TopOptions.TRADEOFFS[it.role] })
    }

    @Test fun TC_ID_TOP_02_neverReactiveDominatedOrPastTheWalkAway() {
        val raw = game(listOf(
            Triple("Hold at the asking price", "boundary", 7.0 to 5.0),
            Triple("Beg them to reconsider", "reactive", 9.5 to 1.0),        // reactive
            Triple("Accept 90k today", "repair", 9.0 to 5.0),                // crosses the 100k walk-away
            Triple("Offer a small discount", "repair", 6.5 to 4.5),          // strictly dominated by the first
            Triple("Ask what budget they have", "repair", 6.0 to 5.5)),
            nc = NegotiationContext(reservationPoint = "100k", aspirationPoint = "130k"))
        val view = NegotiationView.apply(raw, DealFrame.build(raw.negotiationContext, null, ""))
        val dominated = GameModelService.analyzeWithContext(view, contextText = "").pruneResult?.eliminated?.get("you").orEmpty().toSet()
        assertTrue("the pruner (no proxy cell here) eliminates the discount: $dominated", "m3" in dominated)
        val moves = TopOptions.of(view, "Hold at the asking price", dominated).map { it.move }
        assertFalse(moves.toString(), moves.any { it in setOf("Beg them to reconsider", "Accept 90k today", "Offer a small discount") })
        assertEquals(listOf("Hold at the asking price", "Ask what budget they have"), moves)
    }

    @Test fun TC_ID_TOP_03_onlyTwoValidMovesGiveTwoEntriesAndNoFiller() {
        val s = game(listOf(Triple("Talk it through tonight", "repair", 7.0 to 5.0), Triple("Ask for the full amount", "boundary", 9.0 to 2.0),
            Triple("Send angry texts", "reactive", 8.0 to 1.0)))
        val top = TopOptions.of(s, "Talk it through tonight", emptySet())
        assertEquals(2, top.size)
        assertEquals(listOf(TopOptions.BEST, TopOptions.UPSIDE), top.map { it.role })
    }

    @Test fun TC_ID_TOP_04_aStandardTurnGetsTheBlockWhenThePickIsFragileOrTheLabelsFavouredOneOption() {
        assertTrue(TopOptions.wanted(Intent.STANDARD, fragile = true, autoTopOptions = false))
        assertTrue(TopOptions.wanted(Intent.STANDARD, fragile = false, autoTopOptions = true))
        assertTrue(TopOptions.wanted(Intent.TOP_OPTIONS, fragile = false, autoTopOptions = false))
        assertFalse(TopOptions.wanted(Intent.STANDARD, fragile = false, autoTopOptions = false))
        val s = game(listOf(Triple("Talk it through tonight", "repair", 7.0 to 5.0), Triple("Ask for the full amount", "boundary", 9.0 to 2.0)))
        val d = EngineDirectiveBuilder.build(s, AnalysisPipeline().run(s), null).copy(topOptions = TopOptions.of(s, "Talk it through tonight", emptySet())).toJson()
        assertEquals(2, d["top_options"]!!.jsonArray.size)
    }

    @Test fun TC_ID_TOP_05_aMoveDominatedOnlyByAWalkAwayCrossingMoveSurvives() {
        val raw = game(listOf(
            Triple("Hold at the asking price", "boundary", 7.0 to 5.0),
            Triple("Accept 90k today", "repair", 9.0 to 7.0),                 // crosses the 100k walk-away; dominates the next one
            Triple("Offer a small discount", "repair", 8.0 to 6.5),           // dominated only by the 90k move
            Triple("Ask what budget they have", "repair", 6.0 to 5.5)),
            nc = NegotiationContext(reservationPoint = "100k", aspirationPoint = "130k"))
        val view = NegotiationView.apply(raw, DealFrame.build(raw.negotiationContext, null, ""))
        // before: the pruner drops the discount because of the excluded move
        val before = GameModelService.analyzeWithContext(view, contextText = "").pruneResult?.eliminated?.get("you").orEmpty().toSet()
        assertTrue(before.toString(), "m2" in before)
        // after: the walk-away move is set aside first, and the discount survives
        assertEquals(setOf("m1"), com.drkkr.strategos.domain.intent.ExclusionFirst.excluded(view))
        val after = com.drkkr.strategos.domain.intent.ExclusionFirst.dominated(view)
        assertFalse(after.toString(), "m2" in after)
        val moves = TopOptions.of(view, "Hold at the asking price", after).map { it.move }
        assertTrue(moves.toString(), "Offer a small discount" in moves)
        assertFalse("Accept 90k today" in moves)
    }
}
