package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** §8.5 */
object ExplainFrameworkHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val moduleId = Args.requireString(args, "module_id")
        val results = ctx.gateway.lastResults()
        val r = results.firstOrNull { it.moduleId == moduleId }
            ?: return ToolOutcome(
                Args.error("not_in_last_run", mapOf("available" to JsonArray(results.map { JsonPrimitive(it.moduleId) }))),
                ToolDispatcher.ERROR_LABEL
            )
        val out = buildJsonObject {
            put("module_id", r.moduleId)
            put("name", r.moduleName)
            put("category", r.frameworkCategory.name)
            put("calculation_status", r.calculationStatus.name)
            putJsonArray("findings") {
                r.findings.take(8).forEach { f ->
                    addJsonObject { put("title", f.title); put("detail", f.detail.take(300)); put("severity", f.severity.name) }
                }
            }
            putJsonArray("assumptions") { r.assumptions.take(6).forEach { add(it.label) } }
            put("explanation", r.explanation.take(800))
        }
        return ToolOutcome(out, "Explained ${r.moduleName}")
    }
}
