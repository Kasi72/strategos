package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.InferredStressTest
import com.drkkr.strategos.domain.agent.StressVerdict
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.PayoffEntry
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferredStressTestTest {

    private val pipeline = AnalysisPipeline()

    @Test
    fun TC_STRESS_01_noAiPayoffs() {
        val r = InferredStressTest.run(Fx.pd(), pipeline = pipeline)
        assertEquals(StressVerdict.NOT_APPLICABLE, r.verdict)
        assertEquals(0, r.runs)
        assertEquals("NOT_APPLICABLE", r.toJson()["verdict"].toString().trim('"'))
    }

    @Test
    fun TC_STRESS_02_deterministic() {
        val a = InferredStressTest.run(Fx.pdAiPayoffs(8), pipeline = pipeline, budgetMs = 60_000)
        val b = InferredStressTest.run(Fx.pdAiPayoffs(8), pipeline = pipeline, budgetMs = 60_000)
        assertEquals(a, b)
        assertEquals(16, a.runs)
        assertTrue(a.verdict == StressVerdict.ROBUST || a.verdict == StressVerdict.FRAGILE)
        assertEquals(a.runs - a.flipCount, a.holdsIn)
    }

    @Test
    fun TC_STRESS_03_respectsMaxEntries() {
        val you = Stakeholder(id = "a", name = "A")
        val them = Stakeholder(id = "b", name = "B")
        val acts = (1..3).map { Action(id = "a$it", stakeholderId = "a", label = "A$it") } +
            (1..5).map { Action(id = "b$it", stakeholderId = "b", label = "B$it") }
        val payoffs = (1..3).flatMap { i ->
            (1..5).flatMap { j ->
                val profile = mapOf("a" to "a$i", "b" to "b$j")
                listOf("a", "b").map { sid ->
                    PayoffEntry(sid, profile, MultiPayoff(mapOf(
                        PayoffDimension.WELLBEING to ((i * 3 + j) % 10).toDouble(),
                        PayoffDimension.COST to ((i + j * 2) % 10).toDouble(),
                        PayoffDimension.SAFETY to 5.0)), factStatus = FactStatus.AI_INFERRED)
                }
            }
        }
        assertEquals(30, payoffs.size)
        val s = Scenario(title = "30", stakeholders = listOf(you, them), actions = acts, payoffs = payoffs)
        val r = InferredStressTest.run(s, pipeline = pipeline, maxEntries = 20, budgetMs = 120_000)
        assertTrue(r.runs <= 40)
        assertEquals(40, r.runs)
        assertTrue(r.flips.size <= 5)
    }

    @Test
    fun TC_STRESS_04_tinyBudgetIsPartial() {
        var clock = 0L
        val r = InferredStressTest.run(Fx.pdAiPayoffs(8), pipeline = pipeline, budgetMs = 1, now = { clock.also { clock += 1 } })
        assertTrue(r.partial)
        assertTrue(r.runs < 16)
    }
}
