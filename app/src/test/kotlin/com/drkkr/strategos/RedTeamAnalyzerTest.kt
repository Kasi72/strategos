package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.RedTeamAnalyzer
import com.drkkr.strategos.domain.game.GameArchetype
import com.drkkr.strategos.domain.game.GameClass
import com.drkkr.strategos.domain.game.GameIR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedTeamAnalyzerTest {

    private fun pdIr(userFirst: Boolean = true): GameIR {
        val players = if (userFirst) listOf("user", "opp") else listOf("opp", "user")
        return GameIR(
            scenarioId = "pd",
            playerIds = players,
            actionIds = listOf(listOf("Cooperate", "Defect"), listOf("Cooperate", "Defect")),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf(3.0, 0.0, 5.0, 1.0),
                doubleArrayOf(3.0, 5.0, 0.0, 1.0)
            ),
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100,
            archetype = GameArchetype.PRISONERS_DILEMMA
        )
    }

    @Test
    fun `returns result for prisoner dilemma`() {
        val result = RedTeamAnalyzer.analyze(pdIr(), "user")
        assertNotNull(result)
    }

    @Test
    fun `challenge is non-empty`() {
        val result = RedTeamAnalyzer.analyze(pdIr(), "user")
        assertNotNull(result)
        assertTrue(result!!.challenge.isNotBlank())
    }

    @Test
    fun `returns null for single player IR`() {
        val ir = GameIR(
            scenarioId = "single",
            playerIds = listOf("user"),
            actionIds = listOf(listOf("A")),
            strategyCounts = listOf(1),
            utility = listOf(doubleArrayOf(1.0)),
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100
        )
        val result = RedTeamAnalyzer.analyze(ir, "user")
        assertNull(result)
    }

    @Test
    fun `returns null when user id not in IR`() {
        val result = RedTeamAnalyzer.analyze(pdIr(), "unknown")
        assertNull(result)
    }

    @Test
    fun `hedge move is non-null when equilibria exist`() {
        val result = RedTeamAnalyzer.analyze(pdIr(), "user")
        assertNotNull(result)
        // hedgeMove may be null only if actionIds are empty; in this test they are present
        assertNotNull(result!!.hedgeMove)
    }

    @Test
    fun `coordination game returns result`() {
        // Pure coordination: both Cooperate (0,0) is the only NE
        val ir = GameIR(
            scenarioId = "coord",
            playerIds = listOf("user", "opp"),
            actionIds = listOf(listOf("A", "B"), listOf("A", "B")),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf(2.0, 0.0, 0.0, 1.0),
                doubleArrayOf(2.0, 0.0, 0.0, 1.0)
            ),
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100,
            archetype = GameArchetype.PURE_COORDINATION
        )
        val result = RedTeamAnalyzer.analyze(ir, "user")
        assertNotNull(result)
    }
}
