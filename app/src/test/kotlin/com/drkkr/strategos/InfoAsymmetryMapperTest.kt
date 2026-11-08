package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.InfoAsymmetryMapper
import com.drkkr.strategos.domain.model.Belief
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InfoAsymmetryMapperTest {

    private fun scenario(
        userInfo: List<String> = emptyList(),
        oppInfo: List<String> = emptyList(),
        beliefs: List<Belief> = emptyList()
    ): Scenario {
        return Scenario(
            id = "test",
            title = "Test",
            stakeholders = listOf(
                Stakeholder(id = "user", name = "User", gameRole = GameRole.PLAYER, informationAvailable = userInfo),
                Stakeholder(id = "opp", name = "Opp", gameRole = GameRole.PLAYER, informationAvailable = oppInfo)
            ),
            beliefs = beliefs
        )
    }

    @Test
    fun `balanced info returns BALANCED`() {
        val result = InfoAsymmetryMapper.map(scenario(), "user")
        assertEquals(InfoAsymmetryMapper.InfoAdvantage.BALANCED, result.advantage)
    }

    @Test
    fun `user has more info returns USER_ADVANTAGE`() {
        val result = InfoAsymmetryMapper.map(
            scenario(
                userInfo = listOf("budget", "timeline", "constraints"),
                oppInfo = emptyList()
            ),
            "user"
        )
        assertEquals(InfoAsymmetryMapper.InfoAdvantage.USER_ADVANTAGE, result.advantage)
    }

    @Test
    fun `opponent has more info returns OPPONENT_ADVANTAGE`() {
        val result = InfoAsymmetryMapper.map(
            scenario(
                userInfo = emptyList(),
                oppInfo = listOf("market data", "competitor pricing", "internal targets")
            ),
            "user"
        )
        assertEquals(InfoAsymmetryMapper.InfoAdvantage.OPPONENT_ADVANTAGE, result.advantage)
    }

    @Test
    fun `unknown belief creates a gap entry`() {
        val belief = Belief(label = "Will they accept?", probability = 0.5, status = FactStatus.UNKNOWN)
        val result = InfoAsymmetryMapper.map(scenario(beliefs = listOf(belief)), "user")
        assertTrue(result.gaps.any { it.topic == "Will they accept?" })
    }

    @Test
    fun `summary is non-empty`() {
        val result = InfoAsymmetryMapper.map(scenario(), "user")
        assertTrue(result.summary.isNotBlank())
    }

    @Test
    fun `unknown user id returns UNKNOWN advantage`() {
        val result = InfoAsymmetryMapper.map(scenario(), "nobody")
        assertEquals(InfoAsymmetryMapper.InfoAdvantage.UNKNOWN, result.advantage)
    }
}
