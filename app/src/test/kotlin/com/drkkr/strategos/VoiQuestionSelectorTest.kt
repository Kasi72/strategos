package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.VoiQuestionSelector
import com.drkkr.strategos.domain.game.GameArchetype
import com.drkkr.strategos.domain.model.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VoiQuestionSelectorTest {

    private fun scenario() = Scenario(
        id = "test",
        title = "Test",
        description = "test scenario",
        stakeholders = emptyList()
    )

    @Test
    fun `returns question for PRISONERS_DILEMMA`() {
        val result = VoiQuestionSelector.select(scenario(), GameArchetype.PRISONERS_DILEMMA)
        assertNotNull(result)
        assertEquals("repeated-game horizon", result!!.profileSummary)
        assertEquals(1, result.remainingCount)
    }

    @Test
    fun `returns question for CHICKEN`() {
        val result = VoiQuestionSelector.select(scenario(), GameArchetype.CHICKEN)
        assertNotNull(result)
        assertEquals("commitment device", result!!.profileSummary)
    }

    @Test
    fun `returns question for STAG_HUNT`() {
        val result = VoiQuestionSelector.select(scenario(), GameArchetype.STAG_HUNT)
        assertNotNull(result)
        assertEquals("coordination signal", result!!.profileSummary)
    }

    @Test
    fun `returns question for BATTLE_OF_SEXES`() {
        val result = VoiQuestionSelector.select(scenario(), GameArchetype.BATTLE_OF_SEXES)
        assertNotNull(result)
        assertEquals("tie-breaking mechanism", result!!.profileSummary)
    }

    @Test
    fun `returns question for ZERO_SUM`() {
        val result = VoiQuestionSelector.select(scenario(), GameArchetype.ZERO_SUM)
        assertNotNull(result)
        assertEquals("opponent outside option", result!!.profileSummary)
    }

    @Test
    fun `returns question for DEADLOCK`() {
        val result = VoiQuestionSelector.select(scenario(), GameArchetype.DEADLOCK)
        assertNotNull(result)
        assertEquals("escape mechanism", result!!.profileSummary)
    }

    @Test
    fun `returns question for LEADER`() {
        val result = VoiQuestionSelector.select(scenario(), GameArchetype.LEADER)
        assertNotNull(result)
        assertEquals("opponent outside option", result!!.profileSummary)
    }

    @Test
    fun `returns question for HERO`() {
        val result = VoiQuestionSelector.select(scenario(), GameArchetype.HERO)
        assertNotNull(result)
        assertEquals("sequential commitment", result!!.profileSummary)
    }

    @Test
    fun `returns question for PURE_COORDINATION`() {
        val result = VoiQuestionSelector.select(scenario(), GameArchetype.PURE_COORDINATION)
        assertNotNull(result)
        assertEquals("Schelling focal point", result!!.profileSummary)
    }

    @Test
    fun `returns null for HARMONY`() {
        assertNull(VoiQuestionSelector.select(scenario(), GameArchetype.HARMONY))
    }

    @Test
    fun `returns null for GENERIC`() {
        assertNull(VoiQuestionSelector.select(scenario(), GameArchetype.GENERIC))
    }

    @Test
    fun `suppresses already-asked question`() {
        val q = "Will you interact with this person again after this"
        val result = VoiQuestionSelector.select(
            scenario(),
            GameArchetype.PRISONERS_DILEMMA,
            askedQuestions = listOf("Prior answer containing: $q, yes?")
        )
        assertNull(result)
    }

    @Test
    fun `returns question when different question was asked`() {
        val result = VoiQuestionSelector.select(
            scenario(),
            GameArchetype.PRISONERS_DILEMMA,
            askedQuestions = listOf("Has either of you made public commitments?")
        )
        assertNotNull(result)
    }

    @Test
    fun `clarification has correct remainingCount`() {
        val result = VoiQuestionSelector.select(scenario(), GameArchetype.CHICKEN)
        assertNotNull(result)
        assertEquals(1, result!!.remainingCount)
    }
}
