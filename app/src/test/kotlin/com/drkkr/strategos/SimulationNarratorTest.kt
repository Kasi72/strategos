package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.SimulationNarrator
import com.drkkr.strategos.domain.game.GameArchetype
import com.drkkr.strategos.domain.game.GameClass
import com.drkkr.strategos.domain.game.GameIR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationNarratorTest {

    private fun pdIr(): GameIR = GameIR(
        scenarioId = "pd",
        playerIds = listOf("user", "opp"),
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

    @Test
    fun `simulate returns result for 2-player game`() {
        val result = SimulationNarrator.simulate(pdIr(), rounds = 2)
        assertNotNull(result)
    }

    @Test
    fun `simulate produces correct number of steps`() {
        val result = SimulationNarrator.simulate(pdIr(), rounds = 3)
        assertNotNull(result)
        assertEquals(3, result!!.steps.size)
    }

    @Test
    fun `steps contain both player actions`() {
        val result = SimulationNarrator.simulate(pdIr(), rounds = 1)
        assertNotNull(result)
        val step = result!!.steps.first()
        assertEquals(2, step.playerActions.size)
        assertTrue(step.playerActions.containsKey("user"))
        assertTrue(step.playerActions.containsKey("opp"))
    }

    @Test
    fun `summary is non-empty`() {
        val result = SimulationNarrator.simulate(pdIr(), rounds = 2)
        assertNotNull(result)
        assertTrue(result!!.summary.isNotBlank())
    }

    @Test
    fun `single player game returns null`() {
        val ir = GameIR(
            scenarioId = "solo",
            playerIds = listOf("user"),
            actionIds = listOf(listOf("A")),
            strategyCounts = listOf(1),
            utility = listOf(doubleArrayOf(1.0)),
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100
        )
        val result = SimulationNarrator.simulate(ir, rounds = 2)
        assertNull(result)
    }

    @Test
    fun `rounds capped at 5`() {
        val result = SimulationNarrator.simulate(pdIr(), rounds = 10)
        assertNotNull(result)
        assertEquals(5, result!!.steps.size)
    }
}
