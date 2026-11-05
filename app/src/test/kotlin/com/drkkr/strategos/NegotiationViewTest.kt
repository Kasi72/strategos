package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.agent.StatedFloor
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.HumanizedView
import com.drkkr.strategos.domain.model.Constraint
import com.drkkr.strategos.domain.model.ConstraintStatus
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.negotiation.Direction
import com.drkkr.strategos.domain.negotiation.NegotiationView
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Negotiation Numbers, NG3: the walk-away as a real, view-only limit inside the vote. */
class NegotiationViewTest {

    /** The raise fixture as a seller: "Accept 95k" is the move the engine would pick without a walk-away. */
    private fun seller(nc: NegotiationContext? = NegotiationContext(reservationPoint = "100k", aspirationPoint = "130k")): Scenario = Fx.hlRaise().let { s ->
        s.copy(negotiationContext = nc, actions = s.actions.map {
            when (it.id) { "a1" -> it.copy(label = "Accept 95k"); "a2" -> it.copy(label = "Counter at 130k"); "a3" -> it.copy(label = "Ask for a week"); else -> it }
        })
    }

    private fun buyer(): Scenario = Fx.hlRaise().let { s ->
        s.copy(negotiationContext = NegotiationContext(reservationPoint = "45000", aspirationPoint = "38000"),
            humanContext = HumanContext(relationship = "vendor"),
            actions = s.actions.map {
                when (it.id) { "a1" -> it.copy(label = "Agree to 48,000"); "a2" -> it.copy(label = "Offer 40,000"); else -> it }
            })
    }

    /** "Untouched": every other option's feasibility and limit summary in the engine's candidates is what it was without the frame. */
    private fun assertUntouched(s: Scenario, v: Scenario, ids: List<String>) {
        val before = AnalysisPipeline().run(s).report!!.candidates.associateBy { it.actionId }
        val after = AnalysisPipeline().run(v).report!!.candidates.associateBy { it.actionId }
        for (id in ids) {
            assertEquals("$id feasibility", before[id]?.feasibilityStatus, after[id]?.feasibilityStatus)
            assertEquals("$id limit summary", before[id]?.constraintStatusSummary, after[id]?.constraintStatusSummary)
            assertEquals("$id blocking limits", before[id]?.blockingConstraints, after[id]?.blockingConstraints)
        }
        assertEquals("the report's overall status", AnalysisPipeline().run(s).report!!.executiveStatus, AnalysisPipeline().run(v).report!!.executiveStatus)
    }

    private fun frameOf(s: Scenario, message: String = "") = DealFrame.build(s.negotiationContext, s.humanContext, message)

    @Test fun TC_NG_VIEW_01_aFigureBelowASellersWalkAwayIsExcludedAndTheOthersAreUntouched() {
        val s = seller()
        val v = NegotiationView.apply(s, frameOf(s))
        val added = v.constraints - s.constraints.toSet()
        assertEquals(1, added.size)
        val c = added.single()
        assertEquals("Walk-away: 100,000", c.label)
        // PLAN §3.3 says POLICY; POLICY is not one of RecommendationEngine's hard kinds, so it could never exclude anything.
        // USER_HARD is the hard kind for a user's own limit (see PROGRESS, NG3).
        assertEquals(Constraint.Kind.USER_HARD, c.kind)
        assertEquals(FactStatus.USER_ESTIMATE, c.factStatus)
        assertEquals(NegotiationView.CONSTRAINT_ID, c.id)

        val violated = v.constraintAssessments.filter { it.constraintId == c.id && it.status == ConstraintStatus.VIOLATED }.map { it.actionId }
        assertEquals(listOf("a1"), violated)

        val after = AnalysisPipeline().run(v).report!!
        assertTrue("Accept 95k is excluded", after.pathways.first { it.actionId == "a1" }.excluded)
        assertUntouched(s, v, listOf("a2", "a3"))

        // The pick moves off it, and nothing sent to the coach carries the walk-away's figure.
        val plain = EngineDirectiveBuilder.build(s, AnalysisPipeline().run(s), null)
        assertEquals("Accept 95k", plain.moveToImplement)
        val d = EngineDirectiveBuilder.build(v, AnalysisPipeline().run(v), null, deal = frameOf(s))
        assertNotEquals("Accept 95k", d.moveToImplement)
        assertFalse(d.toJson().toString(), Regex("""100,000|100k|100000""").containsMatchIn(d.toJson().toString().replace(Regex("\"deal\":\\{.*?}"), "")))
    }

    @Test fun TC_NG_VIEW_02_forABuyerAFigureAboveTheWalkAwayIsTheViolation() {
        val s = buyer()
        val v = NegotiationView.apply(s, frameOf(s))
        val status = v.constraintAssessments.filter { it.constraintId == NegotiationView.CONSTRAINT_ID }.associate { it.actionId to it.status }
        assertEquals(ConstraintStatus.VIOLATED, status["a1"])
        assertNotEquals(ConstraintStatus.VIOLATED, status["a2"])
        assertTrue(AnalysisPipeline().run(v).report!!.pathways.first { it.actionId == "a1" }.excluded)
        assertUntouched(s, v, listOf("a2", "a3"))
    }

    @Test fun TC_NG_VIEW_03_noFrameReturnsTheSameInstance() {
        val s = seller()
        assertSame(s, NegotiationView.apply(s, null))
        // no negotiation context -> no frame -> the same instance
        val r = Fx.hlRaise()
        assertSame(r, NegotiationView.apply(r, frameOf(r)))
    }

    @Test fun TC_NG_VIEW_05_statedFloorRespectsDirection() {
        // Before NG3 a buyer with a 45,000 limit had "Offer 40,000" blocked, because 40,000 < 45,000 read as "below the floor".
        assertTrue(StatedFloor.leadsBelow("Agree to 48,000", 45000.0, Direction.LOWER_IS_BETTER))
        assertFalse(StatedFloor.leadsBelow("Offer 40,000", 45000.0, Direction.LOWER_IS_BETTER))
        assertTrue(StatedFloor.leadsBelow("Accept 95k", 100000.0, Direction.HIGHER_IS_BETTER))
        assertFalse(StatedFloor.leadsBelow("Counter at 130k", 100000.0, Direction.HIGHER_IS_BETTER))
        assertFalse(StatedFloor.leadsBelow("Ask for a week", 100000.0, Direction.LOWER_IS_BETTER))
        assertFalse(StatedFloor.leadsBelow("Offer 40,000", null, Direction.LOWER_IS_BETTER))
        // and the directive's backstop uses it: with a buyer's frame the 40,000 offer can still be picked
        val s = buyer()
        val d = EngineDirectiveBuilder.build(s, AnalysisPipeline().run(s), null, deal = frameOf(s))
        assertFalse(d.blockedOrDominatedMoves.toString(), d.blockedOrDominatedMoves.any { it.move == "Offer 40,000" && "walk-away" in it.reason })
        assertNotEquals("Agree to 48,000", d.moveToImplement)
    }

    @Test fun TC_NG_VIEW_06_withoutANegotiationContextEveryDirectiveIsTheBaseline() {
        val fixtures = listOf("loanFamily" to Fx.loanFamily(), "pd" to Fx.pd(), "pdWithInfluencer" to Fx.pdWithInfluencer(), "pdAiPayoffs1" to Fx.pdAiPayoffs(1),
            "hlRaise" to Fx.hlRaise(), "hlLoan" to Fx.hlLoan(), "hlSpouse" to Fx.hlSpouse()) + Fx.templates().mapIndexed { i, s -> "template$i" to s }
        for ((name, s) in fixtures) {
            assertEquals(null, s.negotiationContext)
            val plain = javaClass.getResource("/negotiation/baseline/$name.json")!!.readText()
            val frame = DealFrame.build(s.negotiationContext, s.humanContext, "I need at least 110k")
            assertEquals("plain directive for $name", plain, EngineDirectiveBuilder.build(s, AnalysisPipeline().run(s), null, deal = frame).toJson().toString())
            // the flow's view chain: analysis view -> negotiation view -> humanised view
            val flow = javaClass.getResource("/negotiation/baseline/$name.flow.json")!!.readText()
            val view = HumanizedView.apply(NegotiationView.apply(EngineDirectiveBuilder.analysisView(s, null, null), frame))
            assertEquals("flow directive for $name", flow, EngineDirectiveBuilder.build(view, AnalysisPipeline().run(view), null, deal = frame).toJson().toString())
        }
    }

    @Test fun aNegotiationContextWithNoUsableFrameAlsoKeepsTheBaseline() {
        // a context whose figures can't be read (a range) builds no frame, so the directive is the human-layer one (I-N1)
        val s = Fx.hlRaise().copy(negotiationContext = NegotiationContext(reservationPoint = "between 40 and 45k", aspirationPoint = "a lot"))
        val frame = DealFrame.build(s.negotiationContext, s.humanContext, "")
        assertEquals(null, frame)
        val flow = javaClass.getResource("/negotiation/baseline/hlRaise.flow.json")!!.readText()
        val view = HumanizedView.apply(NegotiationView.apply(EngineDirectiveBuilder.analysisView(s, null, null), frame))
        assertEquals(flow, EngineDirectiveBuilder.build(view, AnalysisPipeline().run(view), null, deal = frame).toJson().toString())
    }

    @Test fun theWalkAwayIsNeverListedAsAKnownFact() {
        val s = seller()
        val v = NegotiationView.apply(s, frameOf(s))
        val d = EngineDirectiveBuilder.build(v, AnalysisPipeline().run(v), null, deal = frameOf(s))
        assertFalse(d.knownFacts.toString(), d.knownFacts.any { "Walk-away" in it })
        assertTrue(d.blockedOrDominatedMoves.toString(), d.blockedOrDominatedMoves.none { "100" in it.reason })
    }

    @Test fun anExistingUnassessedHardLimitKeepsEveryOptionsStatus() {
        val base = seller()
        val s = base.copy(constraints = listOf(Constraint(id = "c1", label = "No public scenes", kind = Constraint.Kind.ETHICAL)))
        val v = NegotiationView.apply(s, frameOf(s))
        assertTrue(AnalysisPipeline().run(v).report!!.pathways.first { it.actionId == "a1" }.excluded)
        assertUntouched(s, v, s.actions.map { it.id } - "a1")
    }
}
