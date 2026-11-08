package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.CommitmentAdvisor
import com.drkkr.strategos.domain.game.GameArchetype
import com.drkkr.strategos.domain.game.GameClass
import com.drkkr.strategos.domain.game.GameIR
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentAdvisorTest {

    private fun scenarioWithActions(
        userActionIds: List<String>,
        reversible: Boolean = true,
        publicVisibility: Boolean = true
    ): Scenario {
        val userId = "user"
        val oppId = "opp"
        val actions = userActionIds.map { id ->
            Action(
                id = id, stakeholderId = userId, label = id,
                reversible = reversible, publicVisibility = publicVisibility
            )
        }
        return Scenario(
            id = "test",
            title = "Test",
            stakeholders = listOf(
                Stakeholder(id = userId, name = "User", gameRole = GameRole.PLAYER),
                Stakeholder(id = oppId, name = "Opp", gameRole = GameRole.PLAYER)
            ),
            actions = actions
        )
    }

    private fun chickenIr(): GameIR {
        // Chicken game: Swerve=0, Drive=1
        // NE: (Swerve, Drive) and (Drive, Swerve)
        return GameIR(
            scenarioId = "chicken",
            playerIds = listOf("user", "opp"),
            actionIds = listOf(listOf("Swerve", "Drive"), listOf("Swerve", "Drive")),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf(0.0, -1.0, 1.0, -10.0),
                doubleArrayOf(0.0, 1.0, -1.0, -10.0)
            ),
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100,
            archetype = GameArchetype.CHICKEN
        )
    }

    @Test
    fun `returns list for chicken game`() {
        val scenario = scenarioWithActions(listOf("Swerve", "Drive"), reversible = false, publicVisibility = true)
        val opps = CommitmentAdvisor.opportunities(chickenIr(), "user", scenario)
        assertNotNull(opps)
    }

    @Test
    fun `empty list when user player id not in IR`() {
        val scenario = scenarioWithActions(listOf("Swerve", "Drive"))
        val opps = CommitmentAdvisor.opportunities(chickenIr(), "unknown", scenario)
        assertTrue(opps.isEmpty())
    }

    @Test
    fun `commitment opportunity has non-blank action`() {
        val scenario = scenarioWithActions(listOf("Swerve", "Drive"), reversible = false, publicVisibility = true)
        val opps = CommitmentAdvisor.opportunities(chickenIr(), "user", scenario)
        opps.forEach { opp ->
            assertTrue(opp.action.isNotBlank())
            assertTrue(opp.rationale.isNotBlank())
            assertTrue(opp.credibilityRisk.isNotBlank())
        }
    }

    @Test
    fun `irreversible public action typed as BURN_BRIDGES`() {
        val scenario = scenarioWithActions(listOf("Drive"), reversible = false, publicVisibility = true)
        val ir = chickenIr()
        val opps = CommitmentAdvisor.opportunities(ir, "user", scenario)
        opps.firstOrNull { it.action == "Drive" }?.let { opp ->
            assertEquals(CommitmentAdvisor.CommitmentType.BURN_BRIDGES, opp.type)
        }
    }
}
