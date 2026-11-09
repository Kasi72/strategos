package com.drkkr.strategos

import com.drkkr.strategos.agent.ScenarioGateway
import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.tools.CalibratePayoffsHandler
import com.drkkr.strategos.data.db.dao.WhatIfDao
import com.drkkr.strategos.data.db.entities.WhatIfEntity
import com.drkkr.strategos.domain.analysis.PipelineResult
import com.drkkr.strategos.domain.framework.AnalysisResult
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CalibratePayoffsHandlerTest {

    private class FakeGateway(
        private val scenario: Scenario?,
        private val pipelineResult: PipelineResult?
    ) : ScenarioGateway {
        override fun activeScenario() = scenario
        override suspend fun analyzeAndPublish() = pipelineResult
        override fun lastResults(): List<AnalysisResult> = emptyList()
    }

    private object NoopWhatIfDao : WhatIfDao {
        override suspend fun insert(e: WhatIfEntity) = Unit
        override suspend fun update(e: WhatIfEntity) = Unit
        override suspend fun get(id: String): WhatIfEntity? = null
    }

    /** Allocates an instance without invoking any constructor — bypasses Kotlin null-check intrinsics. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> ghostOf(clazz: Class<T>): T {
        val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        f.isAccessible = true
        val unsafe = f.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, clazz) as T
    }

    private fun buildCtx(scenario: Scenario?, pipelineResult: PipelineResult?): ToolContext =
        ToolContext(
            conversationId = "c1",
            turnId = "t1",
            scenarioId = scenario?.id ?: "none",
            gateway = FakeGateway(scenario, pipelineResult),
            pipeline = ghostOf(com.drkkr.strategos.domain.analysis.AnalysisPipeline::class.java),
            conversations = ghostOf(com.drkkr.strategos.data.repo.ConversationRepository::class.java),
            changeSets = ghostOf(com.drkkr.strategos.data.repo.ChangeSetRepository::class.java),
            memory = ghostOf(com.drkkr.strategos.data.repo.MemoryRepository::class.java),
            whatIfs = NoopWhatIfDao,
            userTexts = emptyList(),
            outcomes = null
        )

    @Test
    fun returnsErrorWhenNoScenario() = runTest {
        val ctx = buildCtx(null, null)
        val result = CalibratePayoffsHandler.handle(JsonObject(emptyMap()), ctx)
        assertEquals(ToolDispatcher.ERROR_LABEL, result.activityLabel)
        assertEquals("no_scenario", result.output["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun returnsErrorWhenNoPayoffs() = runTest {
        val scenario = Scenario(
            id = "s1", title = "Test",
            stakeholders = listOf(
                Stakeholder(id = "u", name = "Me", gameRole = GameRole.PLAYER),
                Stakeholder(id = "o", name = "Other", gameRole = GameRole.PLAYER)
            ),
            actions = listOf(
                Action(id = "a0", stakeholderId = "u", label = "Push", reversible = true, publicVisibility = true),
                Action(id = "b0", stakeholderId = "o", label = "Accept", reversible = true, publicVisibility = true)
            ),
            payoffs = emptyList()
        )
        val ctx = buildCtx(scenario, null)
        val result = CalibratePayoffsHandler.handle(JsonObject(emptyMap()), ctx)
        assertEquals(ToolDispatcher.ERROR_LABEL, result.activityLabel)
        assertEquals("no_payoffs", result.output["error"]?.jsonPrimitive?.content)
        assertNotNull(result.output["hint"])
    }
}
