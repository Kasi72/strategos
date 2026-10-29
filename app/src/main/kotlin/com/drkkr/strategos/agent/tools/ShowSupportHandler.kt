package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.InvalidArguments
import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.UiKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** §17.4. Records only the reason code in an EVENT message, never user text. */
object ShowSupportHandler : ToolHandler {
    val REASONS = setOf("self_harm", "harm_to_others", "abuse", "immediate_danger", "distress")

    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val reason = Args.requireString(args, "reason")
        if (reason !in REASONS) throw InvalidArguments("reason must be one of ${REASONS.joinToString()}")
        ctx.conversations.append(ctx.conversationId, ctx.turnId, MessageRole.EVENT, text = "Support card shown (reason: $reason).")
        return ToolOutcome(buildJsonObject { put("status", "shown") }, "Showed support contacts", UiKind.SUPPORT, endsTurn = true)
    }
}
