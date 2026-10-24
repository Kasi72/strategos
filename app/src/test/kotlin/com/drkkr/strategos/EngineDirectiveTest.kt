package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.*
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** The engine's verdict as handed to the interpreting model: built only from Kotlin results. */
class EngineDirectiveTest {
    private val s = Fx.loanFamily()
    private val pr = AnalysisPipeline().run(s)

    @Test fun readyDirectiveNamesThePickTheFallbackAndWhatToAvoid() {
        val d = EngineDirectiveBuilder.build(s, pr, StressResult(StressVerdict.ROBUST, 4, emptyList(), 0, 4, false))
        assertTrue(d.ready)
        val pick = pr.report!!.pathways.first { it.label == "Recommended" }.action
        assertEquals(pick, d.recommendedMove)
        assertEquals(pick, d.moveToImplement)                              // robust: implement the recommendation
        assertEquals("ROBUST", d.stressVerdict)
        assertTrue(d.badge(), d.badge().startsWith("Engine: ") && d.badge().endsWith("Robust"))
        val json = d.toJson()
        assertEquals(pick, json.getValue("move_to_implement").jsonPrimitive.content)
        assertTrue(json.getValue("blocked_or_dominated_moves").jsonArray.size in 1..2)
        assertFalse("the pick is never listed as blocked", d.blockedOrDominatedMoves.any { it.move == pick })
    }

    @Test fun aFragilePickSwitchesToTheMoveTheStressTestFlipsTo() {
        val own = s.actionsFor(IntakeChecklist.self(s)!!.id).map { it.label }
        val pick = pr.report!!.pathways.first { it.label == "Recommended" }.action
        val other = own.first { !it.equals(pick, ignoreCase = true) }
        val stress = StressResult(StressVerdict.FRAGILE, 4, listOf(StressFlip("Brother", "A / B", StressDirection.WORSEN, other)), 1, 3, false)
        val d = EngineDirectiveBuilder.build(s, pr, stress)
        assertEquals(other, d.safeFallbackMove)
        assertEquals(other, d.moveToImplement)
        assertEquals("FRAGILE", d.stressVerdict)
        assertTrue(d.stressFlip!!.contains(other))
        assertTrue(d.badge().endsWith("Fragile") && d.badge().contains(other.take(30)))
    }

    @Test fun blockedMovesCarryTheOtherSidesLikelyReply() {
        val d = EngineDirectiveBuilder.build(s, pr, null)
        assertTrue(d.blockedOrDominatedMoves.all { !it.triggers.isNullOrBlank() && it.triggers!!.contains("would most likely respond") })
    }

    @Test fun nothingToRankMeansNotReady() {
        val empty = Scenario(title = "empty")
        val d = EngineDirectiveBuilder.build(empty, AnalysisPipeline().run(empty), null)
        assertFalse(d.ready); assertNull(d.moveToImplement)
        assertEquals("Engine: not enough detail to rank your options yet", d.badge())
    }
}
