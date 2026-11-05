package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Runs a single named framework module. Use when the user wants a deep-dive beyond [RunAnalysisHandler]. */
object RunSpecificFrameworkHandler : ToolHandler {
    private val registry = AnalysisPipeline.defaultRegistry()

    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val moduleId = Args.requireString(args, "module_id")
        val scenario = ctx.gateway.activeScenario()
            ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)

        val module = registry.modules().firstOrNull { it.id == moduleId }
            ?: return ToolOutcome(
                Args.error("unknown_module",
                    mapOf("available" to JsonArray(registry.modules().map { JsonPrimitive(it.id) }))),
                ToolDispatcher.ERROR_LABEL
            )

        val applicability = module.canAnalyze(scenario)
        if (applicability.score < 0.05) {
            return ToolOutcome(
                buildJsonObject {
                    put("status", "not_applicable")
                    put("module_id", moduleId)
                    put("score", applicability.score)
                    putJsonArray("reasons") { applicability.reasons.forEach { add(it) } }
                },
                "${module.name} not applicable"
            )
        }

        val result = module.analyze(scenario).copy(applicability = applicability)
        val out = buildJsonObject {
            put("status", "ok")
            put("module_id", result.moduleId)
            put("name", result.moduleName)
            put("calculation_status", result.calculationStatus.name)
            putJsonArray("findings") {
                result.findings.take(8).forEach { f ->
                    addJsonObject {
                        put("title", f.title)
                        put("detail", f.detail.take(300))
                        put("severity", f.severity.name)
                    }
                }
            }
            put("explanation", result.explanation.take(800))
        }
        return ToolOutcome(out, "Ran ${module.name}", UiKind.RECOMMENDATION)
    }
}
