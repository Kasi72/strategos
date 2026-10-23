package com.drkkr.strategos.fixtures

import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.templates.CrisisManagementTemplate
import com.drkkr.strategos.templates.InstitutionalHealthcareTemplate
import com.drkkr.strategos.templates.ProcurementTemplate
import com.drkkr.strategos.templates.ResourceAllocationTemplate

/** Shared test fixtures (TEST_CASES §0). */
object Fx {

    private fun payoff(v: Double) = MultiPayoff(mapOf(PayoffDimension.LONG_TERM_VALUE to v))

    /** Prisoner's dilemma: players p1 and p2, actions c1/d1 and c2/d2, full payoffs, all USER_ESTIMATE. */
    fun pd(): Scenario {
        val payoffs = listOf(
            PayoffEntry("p1", mapOf("p1" to "c1", "p2" to "c2"), payoff(3.0)),
            PayoffEntry("p1", mapOf("p1" to "c1", "p2" to "d2"), payoff(0.0)),
            PayoffEntry("p1", mapOf("p1" to "d1", "p2" to "c2"), payoff(5.0)),
            PayoffEntry("p1", mapOf("p1" to "d1", "p2" to "d2"), payoff(1.0)),
            PayoffEntry("p2", mapOf("p1" to "c1", "p2" to "c2"), payoff(3.0)),
            PayoffEntry("p2", mapOf("p1" to "c1", "p2" to "d2"), payoff(5.0)),
            PayoffEntry("p2", mapOf("p1" to "d1", "p2" to "c2"), payoff(0.0)),
            PayoffEntry("p2", mapOf("p1" to "d1", "p2" to "d2"), payoff(1.0))
        )
        return Scenario(
            id = "pd", title = "Prisoner's Dilemma",
            stakeholders = listOf(Stakeholder(id = "p1", name = "P1"), Stakeholder(id = "p2", name = "P2")),
            actions = listOf(
                Action(id = "c1", stakeholderId = "p1", label = "Cooperate"),
                Action(id = "d1", stakeholderId = "p1", label = "Defect"),
                Action(id = "c2", stakeholderId = "p2", label = "Cooperate"),
                Action(id = "d2", stakeholderId = "p2", label = "Defect")
            ),
            payoffs = payoffs
        )
    }

    /** [pd] plus an INFLUENCER "Media" (m1) with no actions. */
    fun pdWithInfluencer(): Scenario = pd().let {
        it.copy(stakeholders = it.stakeholders + Stakeholder(id = "m1", name = "Media", gameRole = GameRole.INFLUENCER))
    }

    /** [pd] with the first [n] payoff entries set to AI_INFERRED. */
    fun pdAiPayoffs(n: Int): Scenario = pd().let { s ->
        s.copy(payoffs = s.payoffs.mapIndexed { i, p -> if (i < n) p.copy(factStatus = FactStatus.AI_INFERRED) else p })
    }

    /**
     * Guided example: "You" and "Brother" are players; "Wife" (can veto) and "Parents" are influencers.
     * Dimensions WELLBEING, RELATIONSHIPS, COST; full 3x3 payoffs for both players, all USER_ESTIMATE.
     */
    fun loanFamily(): Scenario {
        val mine = listOf("cosign", "give", "wait")
        val his = listOf("accept", "refuse", "askp")
        val payoffs = buildList {
            mine.forEachIndexed { i, a ->
                his.forEachIndexed { j, b ->
                    val profile = mapOf("you" to a, "bro" to b)
                    add(PayoffEntry("you", profile, MultiPayoff(mapOf(
                        PayoffDimension.WELLBEING to (3.0 + i + j), PayoffDimension.RELATIONSHIPS to (6.0 - j), PayoffDimension.COST to (8.0 - 3 * i)))))
                    add(PayoffEntry("bro", profile, MultiPayoff(mapOf(
                        PayoffDimension.WELLBEING to (8.0 - 2 * i - j), PayoffDimension.RELATIONSHIPS to (5.0 + i - j), PayoffDimension.COST to (2.0 + j)))))
                }
            }
        }
        return Scenario(
            id = "loan", title = "Loan for my brother", decisionOwner = "You",
            description = "My brother wants me to co-sign a large loan and my wife is against it.",
            stakes = "Family peace and our savings",
            stakeholders = listOf(
                Stakeholder(id = "you", name = "You"),
                Stakeholder(id = "bro", name = "Brother"),
                Stakeholder(id = "wife", name = "Wife", gameRole = GameRole.INFLUENCER, canVeto = true),
                Stakeholder(id = "parents", name = "Parents", gameRole = GameRole.INFLUENCER)
            ),
            actions = listOf(
                Action(id = "cosign", stakeholderId = "you", label = "Co-sign"),
                Action(id = "give", stakeholderId = "you", label = "Give a fixed amount"),
                Action(id = "wait", stakeholderId = "you", label = "Wait"),
                Action(id = "accept", stakeholderId = "bro", label = "Accept"),
                Action(id = "refuse", stakeholderId = "bro", label = "Refuse"),
                Action(id = "askp", stakeholderId = "bro", label = "Ask parents")
            ),
            payoffs = payoffs,
            constraints = listOf(Constraint(id = "peace", label = "Don't risk peace at home", kind = Constraint.Kind.ETHICAL))
        )
    }

    /** The 4 built-in templates. */
    fun templates(): List<Scenario> = listOf(
        CrisisManagementTemplate.create(), InstitutionalHealthcareTemplate.create(),
        ProcurementTemplate.create(), ResourceAllocationTemplate.create()
    )

    /** A 1.x scenario: no factStatus and no gameRole fields anywhere. */
    val v1ScenarioJson: String = """
        {
          "id": "v1",
          "title": "Legacy",
          "stakeholders": [
            {"id": "s1", "name": "Alice"},
            {"id": "s2", "name": "Bob"}
          ],
          "actions": [
            {"id": "a1", "stakeholderId": "s1", "label": "Go"},
            {"id": "a2", "stakeholderId": "s2", "label": "Stay"}
          ],
          "constraints": [
            {"id": "k1", "label": "Law", "kind": "LEGAL"}
          ],
          "payoffs": [
            {"stakeholderId": "s1", "actionProfile": {"s1": "a1", "s2": "a2"},
             "payoff": {"values": {"COST": 2.0}}}
          ]
        }
    """.trimIndent()
}
