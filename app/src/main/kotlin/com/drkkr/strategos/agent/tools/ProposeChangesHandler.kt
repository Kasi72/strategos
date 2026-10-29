package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.data.repo.UiMode
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.OpRejection
import com.drkkr.strategos.domain.agent.ShortIds
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
        val summary = Args.string(args, "summary")?.takeIf { it.isNotBlank() } ?: "Changes"
        val scenario = ctx.gateway.activeScenario() ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)
        val guided = ctx.uiMode == UiMode.GUIDED && ctx.autoApply != null
        // LEAN PROFILE: in Guided mode one call saves the answer and asks the next question.
        val ask = if (guided) askOf(args) else null
        if (Args.array(args, "ops").isNullOrEmpty() && ask != null) {
            return ToolOutcome(buildJsonObject { put("status", "asked") }, "Asked a question", UiKind.QUESTIONS, endsTurn = true)
        }
        val ops = Args.withRationale(OpNormalizer.normalize(ShortIds.expandOps(Args.ops(args, ChangeSetValidator.MAX_OPS), scenario), scenario), summary)

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
        val autoApply = ctx.autoApply
        if (guided && autoApply != null) {
            val r = autoApply(entity.id)
                ?: return ToolOutcome(Args.error("apply_failed"), ToolDispatcher.ERROR_LABEL)
            val applied = buildJsonObject {
                put("status", "applied")
                put("applied_count", r.applied)
                putJsonObject("ids") { v.refIds.forEach { (ref, id) -> put(ref, ShortIds.short(id)) } }
                put("skipped", buildJsonArray {
                    r.skipped.forEach { (i, reason) -> addJsonObject { put("index", v.accepted.getOrNull(i)?.index ?: i); put("reason", reason) } }
                    v.rejected.forEach { rej -> addJsonObject { put("index", rej.index); put("reason", rej.reason) } }
                })
            }
            // Ask only when everything was saved; otherwise the agent sees the rejections and corrects them first.
            val askNow = ask != null && v.rejected.isEmpty() && r.skipped.isEmpty()
            return if (askNow) ToolOutcome(JsonObject(applied + ("asked" to JsonPrimitive(true))), "Saved ${r.applied} changes", UiKind.QUESTIONS, endsTurn = true)
            else ToolOutcome(applied, "Saved ${r.applied} changes")
        }
        val out = buildJsonObject {
            put("status", "pending_user_review")
            put("change_set_id", entity.id)
            put("accepted_count", v.accepted.size)
            putJsonObject("ids") { v.refIds.forEach { (ref, id) -> put(ref, ShortIds.short(id)) } }
            put("rejected", rejections(v.rejected))
        }
        return ToolOutcome(out, "Proposed ${v.accepted.size} changes", UiKind.CHANGE_SET, refId = entity.id, endsTurn = true)
    }

    /** The optional `ask {text, options}` of a Guided call, validated like ask_user. */
    private fun askOf(args: JsonObject): JsonObject? {
        val ask = args["ask"] as? JsonObject ?: return null
        Args.string(ask, "text")?.takeIf { it.isNotBlank() } ?: return null
        return ask
    }

    internal fun rejections(rejected: List<OpRejection>): JsonArray = buildJsonArray {
        rejected.forEach { r -> addJsonObject { put("index", r.index); put("reason", r.reason) } }
    }
}
