package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntakeChecklistTest {

    @Test
    fun TC_GAP_01_emptyScenario() {
        val ids = IntakeChecklist.gaps(Scenario(title = "")).map { it.id }
        assertEquals(listOf("situation", "goal", "self"), ids.take(3))
    }

    @Test
    fun TC_GAP_02_completeLoanFamilyHasNoGaps() {
        assertEquals(emptyList<Any>(), IntakeChecklist.gaps(Fx.loanFamily()))
    }

    @Test
    fun TC_GAP_03_brotherWithOneAction() {
        val s = Fx.loanFamily().let { f -> f.copy(actions = f.actions.filterNot { it.id in setOf("refuse", "askp") }) }
        val gap = IntakeChecklist.gaps(s).first { it.id == "their_options" }
        assertEquals("Brother", gap.about)
    }

    @Test
    fun TC_GAP_04_limitsNoneMemorySkipsLimits() {
        val s = Fx.loanFamily().copy(constraints = emptyList())
        assertTrue(IntakeChecklist.gaps(s).any { it.id == "limits" })
        assertTrue(IntakeChecklist.gaps(s, limitsAnswered = true).none { it.id == "limits" })
    }

    @Test
    fun powerAndOutcomesGaps() {
        val f = Fx.loanFamily()
        val s = f.copy(
            stakeholders = f.stakeholders.map { if (it.id == "parents") it.copy(influence = 0) else it },
            payoffs = f.payoffs.drop(1)
        )
        val gaps = IntakeChecklist.gaps(s)
        assertEquals("Parents", gaps.single { it.id == "power" }.about)
        assertTrue(gaps.any { it.id == "outcomes" })
    }
}
