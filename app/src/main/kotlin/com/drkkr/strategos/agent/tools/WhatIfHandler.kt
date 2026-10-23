package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.db.entities.WhatIfEntity
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.ShortIds
import com.drkkr.strategos.domain.agent.WhatIfRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** §8.6. Works on a copy; the scenario never changes. refId is the what_if_run id. */
object WhatIfHandler : ToolHandler {
    private const val MAX_OPS = 20

    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val label = Args.requireString(args, "label")
        val base = ctx.gateway.activeScenario() ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)
        val ops = Args.withRationale(OpNormalizer.normalize(ShortIds.expandOps(Args.ops(args, MAX_OPS), base), base), label)

        val outcome = WhatIfRunner.run(base, ops, ctx.pipeline, label)
        val id = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) {
            ctx.whatIfs.insert(
                WhatIfEntity(
                    id = id, scenarioId = ctx.scenarioId, conversationId = ctx.conversationId, label = label,
                    opsJson = Args.encodeOps(ops), baseScenarioVersion = base.version,
                    resultJson = outcome.comparison.toString(), createdAt = System.currentTimeMillis()
                )
            )
        }
        val out = JsonObject(outcome.comparison + ("what_if_id" to JsonPrimitive(id)))
        return ToolOutcome(out, "Simulated: $label", UiKind.WHAT_IF, refId = id)
    }
}
