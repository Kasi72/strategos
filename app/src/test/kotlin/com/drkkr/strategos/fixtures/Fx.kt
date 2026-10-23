package com.drkkr.strategos.fixtures

import com.drkkr.strategos.domain.model.*

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
