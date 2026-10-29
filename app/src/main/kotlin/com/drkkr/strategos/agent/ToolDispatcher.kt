package com.drkkr.strategos.agent

import android.util.Log
import com.drkkr.strategos.agent.tools.AskUserHandler
import com.drkkr.strategos.agent.tools.ExplainFrameworkHandler
import com.drkkr.strategos.agent.tools.GetScenarioHandler
import com.drkkr.strategos.agent.tools.ProposeChangesHandler
import com.drkkr.strategos.agent.tools.RunAnalysisHandler
import com.drkkr.strategos.agent.tools.ShowSupportHandler
import com.drkkr.strategos.agent.tools.ShowUnderstandingHandler
import com.drkkr.strategos.agent.tools.StressTestInferredHandler
import com.drkkr.strategos.agent.tools.UpdateMemoryHandler
import com.drkkr.strategos.agent.tools.WhatIfHandler
import com.drkkr.strategos.data.db.dao.WhatIfDao
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.data.repo.UiMode
import com.drkkr.strategos.domain.agent.ApplyResult
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.analysis.PipelineResult
import com.drkkr.strategos.domain.framework.AnalysisResult
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a tool returns to the orchestrator (blueprint §8.0). */
data class ToolOutcome(
    val output: JsonObject, val activityLabel: String, val uiKind: UiKind = UiKind.NONE,
    val refId: String? = null, val endsTurn: Boolean = false
)

fun interface ToolHandler { suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome }

/** Implemented by StrategosViewModel (§10.5). */
interface ScenarioGateway {
    fun activeScenario(): Scenario?
    suspend fun analyzeAndPublish(): PipelineResult?
    fun lastResults(): List<AnalysisResult>
}

data class ToolContext(
    val conversationId: String, val turnId: String, val scenarioId: String,
    val gateway: ScenarioGateway, val pipeline: AnalysisPipeline,
    val conversations: ConversationRepository, val changeSets: ChangeSetRepository,
    val memory: MemoryRepository, val whatIfs: WhatIfDao,
    val userTexts: List<String>,            // all USER messages incl. the current one
    // §17.3: in Guided mode propose_changes applies at once through this hook (CounselController.autoApply).
    val uiMode: UiMode = UiMode.EXPERT,
    val autoApply: (suspend (changeSetId: String) -> ApplyResult?)? = null
)

/** Thrown by a handler when its arguments don't fit the tool contract. */
class InvalidArguments(detail: String) : Exception(detail)

/** Routes function calls to in-process handlers. [dispatch] never throws (except to propagate cancellation). */
class ToolDispatcher(private val handlers: Map<String, ToolHandler>) {
    val names: Set<String> get() = handlers.keys

    suspend fun dispatch(name: String, argumentsJson: String, ctx: ToolContext): ToolOutcome {
        val handler = handlers[name] ?: return error(buildJsonObject { put("error", "unknown_tool"); put("name", name) })
        val args = try {
            if (argumentsJson.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(argumentsJson) as? JsonObject
                ?: return invalid("arguments must be a JSON object")
        } catch (e: Exception) {
            return invalid(e.message ?: "unparseable JSON")
        }
        return try {
            handler.handle(args, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidArguments) {
            invalid(e.message ?: "invalid arguments")
        } catch (e: Exception) {
            Log.w(TAG, "tool $name failed: ${e.javaClass.simpleName}")
            error(buildJsonObject { put("error", "internal_error") })
        }
    }

    private fun invalid(detail: String) =
        error(buildJsonObject { put("error", "invalid_arguments"); put("detail", detail.take(MAX_DETAIL)) })

    private fun error(output: JsonObject) = ToolOutcome(output, ERROR_LABEL)

    companion object {
        private const val TAG = "ToolDispatcher"
        private const val MAX_DETAIL = 200
        const val ERROR_LABEL = "Tool error"

        fun default(): ToolDispatcher = ToolDispatcher(
            mapOf(
                "get_scenario" to GetScenarioHandler,
                "propose_changes" to ProposeChangesHandler,
                "ask_user" to AskUserHandler,
                "run_analysis" to RunAnalysisHandler,
                "explain_framework" to ExplainFrameworkHandler,
                "what_if" to WhatIfHandler,
                "stress_test_inferred" to StressTestInferredHandler,
                "update_memory" to UpdateMemoryHandler,
                "show_understanding" to ShowUnderstandingHandler,
                "show_support" to ShowSupportHandler
            )
        )
    }
}
