package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.TimingAdvisor
import com.drkkr.strategos.domain.game.OpponentModel
import com.drkkr.strategos.domain.model.Constraint
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TimingAdvisorTest {

    private fun scenario(constraints: List<Constraint> = emptyList(), discountFactor: Double? = null) = Scenario(
        id = "test",
        title = "Test",
        stakeholders = emptyList(),
        constraints = constraints,
        discountFactor = discountFactor
    )

    @Test
    fun `high discount factor yields WAIT`() {
        val opp = OpponentModel(playerId = "opp", epsilon = 0.1, discountFactor = 0.8)
        val advice = TimingAdvisor.advise(scenario(), opp)
        assertEquals(TimingAdvisor.TimingAdvice.WAIT, advice)
    }

    @Test
    fun `low discount factor yields SET_DEADLINE`() {
        val opp = OpponentModel(playerId = "opp", epsilon = 0.1, discountFactor = 0.2)
        val advice = TimingAdvisor.advise(scenario(), opp)
        assertEquals(TimingAdvisor.TimingAdvice.SET_DEADLINE, advice)
    }

    @Test
    fun `no discount data and no constraint yields NO_GUIDANCE`() {
        val advice = TimingAdvisor.advise(scenario())
        assertEquals(TimingAdvisor.TimingAdvice.NO_GUIDANCE, advice)
    }

    @Test
    fun `binding policy constraint yields SET_DEADLINE even with no opponent model`() {
        val constraint = Constraint(
            label = "Board deadline",
            kind = Constraint.Kind.POLICY,
            factStatus = FactStatus.CONFIRMED
        )
        val advice = TimingAdvisor.advise(scenario(constraints = listOf(constraint)))
        assertEquals(TimingAdvisor.TimingAdvice.SET_DEADLINE, advice)
    }

    @Test
    fun `WAIT note is non-null`() {
        val note = TimingAdvisor.note(TimingAdvisor.TimingAdvice.WAIT)
        assertNotNull(note)
    }

    @Test
    fun `NO_GUIDANCE note is null`() {
        val note = TimingAdvisor.note(TimingAdvisor.TimingAdvice.NO_GUIDANCE)
        assertNull(note)
    }
}
