package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.ReportDigest
import com.drkkr.strategos.domain.framework.ModelValidator
import com.drkkr.strategos.domain.framework.ModuleRunStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** §8.4. Goes through the gateway so the Report tab, snapshot and lifecycle update exactly as the Analyse button does. */
object RunAnalysisHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val started = System.currentTimeMillis()
        val pr = ctx.gateway.analyzeAndPublish()
        val ms = System.currentTimeMillis() - started
        val scenario = ctx.gateway.activeScenario() ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)

        if (pr?.report == null) {
            val issues = (pr?.validity ?: ModelValidator.validate(scenario)).issues
            val out = buildJsonObject {
                put("status", "blocked")
                putJsonArray("validity_issues") {
                    issues.forEach { i -> addJsonObject { put("severity", i.severity.name); put("field", i.field); put("message", i.message) } }
                }
            }
            return ToolOutcome(out, "Analysis blocked by model issues")
        }

        var digest = ReportDigest.build(pr, scenario)
        val pending = ctx.changeSets.pending(ctx.scenarioId).size
        if (pending > 0) digest = JsonObject(digest + ("pending_change_sets" to JsonPrimitive(pending)))
        ctx.conversations.saveLastReport(ctx.conversationId, digest.toString(), scenario.version)

        val ran = pr.moduleRuns.count { it.status == ModuleRunStatus.SUCCESS }
        return ToolOutcome(digest, "Ran $ran frameworks in $ms ms on device", UiKind.RECOMMENDATION)
    }
}
