package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.HumanizedView
import com.drkkr.strategos.domain.human.RelationshipDomains
import com.drkkr.strategos.domain.human.ResponseModel
import com.drkkr.strategos.domain.human.TacticId
import com.drkkr.strategos.domain.human.power.PowerPlanner
import com.drkkr.strategos.domain.human.power.StrategicCatalog
import com.drkkr.strategos.domain.human.power.StrategicId
import com.drkkr.strategos.domain.intent.Intent
import com.drkkr.strategos.domain.intent.IntentResult
import com.drkkr.strategos.domain.intent.IntentRouter
import com.drkkr.strategos.domain.intent.PlanAhead
import com.drkkr.strategos.domain.intent.RouteInput
import com.drkkr.strategos.domain.intent.TurnChain
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.ConstraintAssessment
import com.drkkr.strategos.domain.model.ConstraintStatus
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.PayoffEntry
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import com.drkkr.strategos.domain.negotiation.NegotiationView
import com.drkkr.strategos.fixtures.Fx
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID4.1: three exchanges, deterministic (BLUEPRINT §6.1). */
class PlanAheadTest {
    private val L = PayoffDimension.LONG_TERM_VALUE
    private val R = PayoffDimension.RELATIONSHIPS
    private val C = PayoffDimension.COST

    /**
     * A co-founder split: you (a1 repair, a2 boundary, a3 reactive) and Ravi with three replies (r1 receptive, r2 defensive,
     * r3 a counter). [mine] and [his] give (LTV, RELATIONSHIPS) per cell; COST is flat.
     */
    private fun split(
        mine: Map<Pair<String, String>, Pair<Double, Double>>, his: Map<Pair<String, String>, Pair<Double, Double>>,
        replies: List<String> = listOf("r1", "r2", "r3")
    ): Scenario {
        val labels = mapOf("r1" to ("Agree to the split" to "receptive"), "r2" to ("Refuse to discuss it" to "defensive"), "r3" to ("Counter with 60/40" to "repair"))
        val actions = listOf(
            Action(id = "a1", stakeholderId = "you", label = "Propose a written vesting plan", description = "[posture:repair]"),
            Action(id = "a2", stakeholderId = "you", label = "Hold at an equal split", description = "[posture:boundary]"),
            Action(id = "a3", stakeholderId = "you", label = "Threaten to walk out", description = "[posture:reactive]")
        ) + replies.map { r -> Action(id = r, stakeholderId = "ravi", label = labels.getValue(r).first, description = "[posture:${labels.getValue(r).second}]") }
        val payoffs = listOf("a1", "a2", "a3").flatMap { a ->
            replies.flatMap { r ->
                val p = mapOf("you" to a, "ravi" to r)
                val (ml, mr) = mine.getValue(a to r); val (hl, hr) = his.getValue(a to r)
                listOf(PayoffEntry("you", p, MultiPayoff(mapOf(L to ml, R to mr, C to 4.0))), PayoffEntry("ravi", p, MultiPayoff(mapOf(L to hl, R to hr, C to 4.0))))
            }
        }
        return Scenario(
            id = "split", title = "Equity split", decisionOwner = "You", stakes = "Our company", repeated = true,
            stakeholders = listOf(Stakeholder(id = "you", name = "You", authority = 5), Stakeholder(id = "ravi", name = "Ravi", authority = 5, influence = 5)),
            actions = actions, payoffs = payoffs, humanContext = HumanContext(relationship = "cofounder")
        )
    }

    private val mine = mapOf(
        ("a1" to "r1") to (8.0 to 8.0), ("a1" to "r2") to (4.0 to 5.0), ("a1" to "r3") to (6.0 to 6.0),
        ("a2" to "r1") to (9.0 to 6.0), ("a2" to "r2") to (3.0 to 3.0), ("a2" to "r3") to (7.0 to 5.0),
        ("a3" to "r1") to (9.0 to 2.0), ("a3" to "r2") to (1.0 to 1.0), ("a3" to "r3") to (8.0 to 2.0)
    )
    private val his = mapOf(
        ("a1" to "r1") to (7.0 to 7.0), ("a1" to "r2") to (3.0 to 3.0), ("a1" to "r3") to (6.0 to 6.0),
        ("a2" to "r1") to (4.0 to 4.0), ("a2" to "r2") to (6.0 to 5.0), ("a2" to "r3") to (6.0 to 6.0),
        ("a3" to "r1") to (2.0 to 2.0), ("a3" to "r2") to (7.0 to 4.0), ("a3" to "r3") to (3.0 to 3.0)
    )

    private fun input(s: Scenario) = RouteInput(Intent.PLAN_AHEAD, HumanizedView.apply(s), decide = TurnChain.decide(null, null))

    private fun label(s: Scenario, id: String) = s.actions.first { it.id == id }.label

    @Test fun TC_ID_PLAY_01_theFirstMoveIsThePick_twoBranchesFromTheTwoLikeliestReplies_andACloseTactic() {
        val s = split(mine, his)
        val inp = input(s)
        val block = (runBlocking { IntentRouter().route(inp) } as IntentResult.PlanAhead).block
        assertEquals(inp.decide(inp.view), block.first)
        val pickId = inp.view.actions.first { it.label == block.first }.id
        val likeliest = ResponseModel.replies(inp.view, "you", pickId, 1.0)
        assertEquals(3, likeliest.size)
        assertEquals(likeliest.take(2).map { it.label }, block.branches.map { it.ifThey })
        assertFalse("the least likely reply is not a branch", block.branches.any { it.ifThey == likeliest.last().label })
        // each branch's answer is a move of yours, never the reactive one
        for (b in block.branches) assertTrue(b.thenYou, b.thenYou in listOf(label(s, "a1"), label(s, "a2")))

        val pick = s.actions.first { it.id == pickId }
        val close = PlanAhead.close(s, s.humanContext, pick, false, 1.0, null, PlanAhead.hasReceptiveBranch(inp.view, block)) { null }
        assertNotNull(close)
        assertTrue(close!!.id in PlanAhead.LOCK_IN)
        val done = PlanAhead.finish(block, inp.view, block.first, 1.0, emptySet(), null, close)!!
        val json = done.toJson().toString()
        for (k in listOf("\"first\"", "\"move\"", "\"branches\"", "\"if_they\"", "\"then_you\"", "\"close\"")) assertTrue(k, json.contains(k))
        assertFalse("no number in the block", Regex("""\d""").containsMatchIn(json.replace("60/40", "")))
    }

    @Test fun TC_ID_PLAY_02_whenTheBestRawResponseIsReactiveOrPastTheWalkAway_theNextValidMoveIsUsed() {
        // against r2 the reactive move is best raw; against r1 the boundary move is, but it crosses the walk-away
        val m = mine + mapOf(("a3" to "r2") to (9.0 to 9.0), ("a2" to "r1") to (10.0 to 10.0))
        val s = split(m, his).let { x ->
            x.copy(constraintAssessments = x.constraintAssessments + ConstraintAssessment(
                id = "w:a2", constraintId = NegotiationView.CONSTRAINT_ID, actionId = "a2", status = ConstraintStatus.VIOLATED,
                assessedBy = ConstraintAssessment.AssessedBy.RULE, timestamp = 0L, assessedAt = 0L))
        }
        val view = HumanizedView.apply(s)
        val pick = view.actions.first { it.id == "a1" }
        val branches = PlanAhead.branches(view, "you", pick, 1.0, emptySet()).associate { it.ifThey to it.thenYou }
        assertEquals(label(s, "a1"), branches[label(s, "r1")])
        branches[label(s, "r2")]?.let { assertEquals(label(s, "a1"), it) }
        assertFalse(branches.values.any { it == label(s, "a3") || it == label(s, "a2") })
        // a dominated move is skipped the same way
        val noA2 = PlanAhead.branches(HumanizedView.apply(split(m, his)), "you", pick, 1.0, setOf("a2"))
        assertFalse(noA2.any { it.thenYou == label(s, "a2") })
    }

    @Test fun TC_ID_PLAY_03_aCounterpartWithOneOptionGivesOneBranch() {
        val s = split(mine, his, replies = listOf("r1"))
        val view = HumanizedView.apply(s)
        val branches = PlanAhead.branches(view, "you", view.actions.first { it.id == "a1" }, 1.0, emptySet())
        assertEquals(1, branches.size)
        assertEquals(label(s, "r1"), branches.single().ifThey)
    }

    @Test fun TC_ID_PLAY_04_aPowerCloseOnlyThroughThePowerPlan_neverForASpouse() {
        fun close(s: Scenario, pickId: String = "a1"): Pair<com.drkkr.strategos.domain.intent.PlanClose?, com.drkkr.strategos.domain.human.power.PowerPlan> {
            val plan = PowerPlanner.plan(s, RelationshipDomains.of(s.humanContext?.relationship, null, s.negotiationContext), true, false, false, 50.0, 0.0)
            val pick = s.actions.first { it.id == pickId }
            val hc = s.humanContext!!
            return PlanAhead.close(s, hc, pick, false, 1.0, plan, true) { id -> StrategicCatalog.tactics.first { it.id == id }.hint(s, hc) } to plan
        }
        // negotiation: the close is a Power move exactly when the Power plan delivers a closing one
        val (vendorClose, vendorPlan) = close(Fx.pmVendor())
        val powerClose = vendorPlan.deliveryMoves.firstOrNull { it.id in PlanAhead.POWER_CLOSE }
        assertNotNull("the vendor fixture has a closing Power move", powerClose)
        assertEquals(powerClose!!.id, vendorClose!!.id)
        assertFalse("never the walk-away figure", vendorClose.hint.contains("40"))
        // the same scenario with the lock on: no Power plan reaches the close
        val locked = PowerPlanner.plan(Fx.pmVendor(), RelationshipDomains.of("vendor", null, Fx.pmVendor().negotiationContext), true, false, true, 50.0, 0.0)
        assertTrue(locked.deliveryMoves.isEmpty())
        // spouse: never a Power move
        val (spouseClose, spousePlan) = close(Fx.pmSpouse(), "s1")
        assertTrue(spousePlan.deliveryMoves.isEmpty())
        assertTrue(spouseClose == null || spouseClose.id !is StrategicId)
    }

    @Test fun coachRule31PresentsThePlanAsAPlanToAdapt() {
        val p = ArgonPrompts.ENGINE_COACH_PROMPT
        assertTrue(p.contains("\n31. PLAN AHEAD"))
        assertTrue(p.contains("plan to adapt, not a prediction"))
        assertTrue(TacticId.PUT_IT_IN_WRITING in PlanAhead.LOCK_IN)
    }
}
