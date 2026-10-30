package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

class DecisionStateMachineTest {

    @Test
    fun `initial state is EXPLORATORY`() {
        assertEquals(DecisionState.EXPLORATORY, DecisionStateMachine().state)
    }

    @Test
    fun `EXPLORATORY can transition to CONDITIONAL`() {
        val sm = DecisionStateMachine()
        val result = sm.transition(DecisionState.CONDITIONAL, "model compiled")
        assertTrue(result is TransitionResult.Allowed)
        assertEquals(DecisionState.CONDITIONAL, sm.state)
    }

    @Test
    fun `EXPLORATORY cannot transition directly to VERIFIED`() {
        val sm = DecisionStateMachine()
        val result = sm.transition(DecisionState.VERIFIED)
        assertTrue(result is TransitionResult.Rejected)
        assertEquals(DecisionState.EXPLORATORY, sm.state)
    }

    @Test
    fun `CONDITIONAL can transition to VERIFIED`() {
        val sm = DecisionStateMachine()
        sm.transition(DecisionState.CONDITIONAL)
        val result = sm.transition(DecisionState.VERIFIED, "residual <= 1e-9")
        assertTrue(result is TransitionResult.Allowed)
        assertEquals(DecisionState.VERIFIED, sm.state)
    }

    @Test
    fun `CONDITIONAL can revert to EXPLORATORY`() {
        val sm = DecisionStateMachine()
        sm.transition(DecisionState.CONDITIONAL)
        val result = sm.transition(DecisionState.EXPLORATORY, "payoffs changed")
        assertTrue(result is TransitionResult.Allowed)
        assertEquals(DecisionState.EXPLORATORY, sm.state)
    }

    @Test
    fun `VERIFIED can revert to CONDITIONAL on payoff update`() {
        val sm = DecisionStateMachine()
        sm.transition(DecisionState.CONDITIONAL)
        sm.transition(DecisionState.VERIFIED)
        val result = sm.transition(DecisionState.CONDITIONAL, "payoffs updated")
        assertTrue(result is TransitionResult.Allowed)
        assertEquals(DecisionState.CONDITIONAL, sm.state)
    }

    @Test
    fun `VERIFIED can revert to EXPLORATORY on model invalidation`() {
        val sm = DecisionStateMachine()
        sm.transition(DecisionState.CONDITIONAL)
        sm.transition(DecisionState.VERIFIED)
        val result = sm.transition(DecisionState.EXPLORATORY, "player removed")
        assertTrue(result is TransitionResult.Allowed)
        assertEquals(DecisionState.EXPLORATORY, sm.state)
    }

    @Test
    fun `invalidate always reaches EXPLORATORY regardless of current state`() {
        val sm = DecisionStateMachine()
        sm.transition(DecisionState.CONDITIONAL)
        sm.transition(DecisionState.VERIFIED)
        sm.invalidate("structure changed")
        assertEquals(DecisionState.EXPLORATORY, sm.state)
    }

    @Test
    fun `history records every transition`() {
        val sm = DecisionStateMachine()
        sm.transition(DecisionState.CONDITIONAL, "compiled")
        sm.transition(DecisionState.VERIFIED, "verified")
        sm.transition(DecisionState.CONDITIONAL, "updated")
        assertEquals(3, sm.history.size)
        assertEquals(DecisionState.EXPLORATORY, sm.history[0].from)
        assertEquals(DecisionState.CONDITIONAL, sm.history[0].to)
        assertEquals(DecisionState.VERIFIED, sm.history[1].to)
        assertEquals(DecisionState.CONDITIONAL, sm.history[2].to)
    }

    @Test
    fun `rejected transition does not mutate state or history`() {
        val sm = DecisionStateMachine()
        val sizeBefore = sm.history.size
        sm.transition(DecisionState.VERIFIED)
        assertEquals(DecisionState.EXPLORATORY, sm.state)
        assertEquals(sizeBefore, sm.history.size)
    }

    @Test
    fun `history is immutable snapshot`() {
        val sm = DecisionStateMachine()
        sm.transition(DecisionState.CONDITIONAL)
        val snap = sm.history
        sm.transition(DecisionState.VERIFIED)
        assertEquals(1, snap.size)
        assertEquals(2, sm.history.size)
    }
}
