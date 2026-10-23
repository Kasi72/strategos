package com.drkkr.strategos.fixtures

import com.drkkr.strategos.agent.ScenarioGateway
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.analysis.PipelineResult
import com.drkkr.strategos.domain.framework.AnalysisResult
import com.drkkr.strategos.domain.model.Scenario

/** ScenarioGateway backed by a `var scenario`. Counts calls to analyzeAndPublish (TEST_CASES §0). */
class FakeGateway(var scenario: Scenario?, private val pipeline: AnalysisPipeline = AnalysisPipeline()) : ScenarioGateway {
    var analyzeCalls = 0
        private set
    var results: List<AnalysisResult> = emptyList()

    override fun activeScenario(): Scenario? = scenario

    override suspend fun analyzeAndPublish(): PipelineResult? {
        analyzeCalls++
        val s = scenario ?: return null
        return pipeline.run(s).also { results = it.results }
    }

    override fun lastResults(): List<AnalysisResult> = results
}
