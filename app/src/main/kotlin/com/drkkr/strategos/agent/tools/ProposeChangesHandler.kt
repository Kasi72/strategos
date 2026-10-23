package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.OpRejection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** §8.2. Persists a PENDING change set; never changes the scenario (I2). */
object ProposeChangesHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val summary = Args.requireString(args, "summary")
        val ops = Args.ops(args, ChangeSetValidator.MAX_OPS)
        val scenario = ctx.gateway.activeScenario() ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)

        val pending = ctx.changeSets.pending(ctx.scenarioId)
        if (pending.size >= AgentConfig.MAX_PENDING_CHANGE_SETS) {
            val ids = JsonArray(pending.map { JsonPrimitive(it.id) })
            return ToolOutcome(Args.error("too_many_pending", mapOf("pending_ids" to ids)), ToolDispatcher.ERROR_LABEL)
        }

        val v = ChangeSetValidator.validate(scenario, ops)
        if (v.accepted.isEmpty()) {
            val out = buildJsonObject { put("status", "rejected"); put("rejected", rejections(v.rejected)) }
            return ToolOutcome(out, "Proposed changes were rejected")
        }

        val refIdsJson = Json.encodeToString(JsonObject.serializer(), JsonObject(v.refIds.mapValues { JsonPrimitive(it.value) }))
        val entity = ctx.changeSets.create(
            scenarioId = ctx.scenarioId, conversationId = ctx.conversationId, summary = summary,
            opsJson = Args.encodeOps(v.accepted.map { it.op }), refIdsJson = refIdsJson, baseScenarioVersion = scenario.version
        )
        val out = buildJsonObject {
            put("status", "pending_user_review")
            put("change_set_id", entity.id)
            put("accepted_count", v.accepted.size)
            putJsonObject("ids") { v.refIds.forEach { (ref, id) -> put(ref, id) } }
            put("rejected", rejections(v.rejected))
        }
        return ToolOutcome(out, "Proposed ${v.accepted.size} changes", UiKind.CHANGE_SET, refId = entity.id, endsTurn = true)
    }

    internal fun rejections(rejected: List<OpRejection>): JsonArray = buildJsonArray {
        rejected.forEach { r -> addJsonObject { put("index", r.index); put("reason", r.reason) } }
    }
}
