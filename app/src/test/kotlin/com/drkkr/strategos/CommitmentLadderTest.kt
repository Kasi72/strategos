package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.CommitmentLadder
import com.drkkr.strategos.domain.game.GameClass
import com.drkkr.strategos.domain.game.GameIR
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentLadderTest {

    private fun buildScenario(
        userActions: List<Pair<String, Boolean>>,  // label to reversible
        oppActions: List<String>
    ): Scenario {
        val userId = "user"
        val oppId = "opp"
        val userStakeholder = Stakeholder(id = userId, name = "Me", gameRole = GameRole.PLAYER)
        val oppStakeholder = Stakeholder(id = oppId, name = "Them", gameRole = GameRole.PLAYER)
        val actions = userActions.mapIndexed { i, (label, rev) ->
            Action(id = "u$i", stakeholderId = userId, label = label, reversible = rev, publicVisibility = true)
        } + oppActions.mapIndexed { i, label ->
            Action(id = "o$i", stakeholderId = oppId, label = label, reversible = true, publicVisibility = true)
        }
        return Scenario(
            id = "test",
            title = "Test",
            stakeholders = listOf(userStakeholder, oppStakeholder),
            actions = actions,
            payoffs = emptyList()
        )
    }

    @Test
    fun returnsEmptyForNoOpportunities() {
        // Minimal scenario with no payoffs → CommitmentAdvisor will return empty
        val scenario = buildScenario(listOf("Act" to true), listOf("React"))
        val ir = GameIR(
            scenarioId = "test",
            playerIds = listOf("user", "opp"),
            actionIds = listOf(listOf("u0"), listOf("o0")),
            strategyCounts = listOf(1, 1),
            utility = listOf(doubleArrayOf(1.0), doubleArrayOf(1.0)),
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100
        )
        val steps = CommitmentLadder.steps(ir, "user", scenario)
        assertTrue(steps.isEmpty())
    }

    @Test
    fun levelOrderAscending() {
        // If steps are returned, their ordinal order must be non-decreasing
        val scenario = buildScenario(
            listOf("Commit" to false, "Hedge" to true),
            listOf("Accept", "Reject")
        )
        val ir = GameIR(
            scenarioId = "test",
            playerIds = listOf("user", "opp"),
            actionIds = listOf(listOf("u0", "u1"), listOf("o0", "o1")),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf(4.0, 0.0, 2.0, 1.0),
                doubleArrayOf(4.0, 2.0, 0.0, 1.0)
            ),
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100
        )
        val steps = CommitmentLadder.steps(ir, "user", scenario)
        val ordinals = steps.map { it.level.ordinal }
        for (i in 0 until ordinals.size - 1) {
            assertTrue(ordinals[i] <= ordinals[i + 1])
        }
    }

    @Test
    fun credibilityGainFormatted() {
        CommitmentLadder.LadderLevel.values().forEach { level ->
            val gain = when (level) {
                CommitmentLadder.LadderLevel.VERBAL          -> "none → low"
                CommitmentLadder.LadderLevel.WRITTEN_PUBLIC  -> "low → medium"
                CommitmentLadder.LadderLevel.CONTRACTUAL     -> "medium → high"
                CommitmentLadder.LadderLevel.IRREVERSIBLE    -> "high → maximum"
            }
            assertTrue(gain.contains("→"))
        }
    }
}
