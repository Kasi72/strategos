package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.InvalidArguments
import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * §8.3. The questions stay in the TOOL_CALL payload. The orchestrator sets refId to the TOOL_RESULT message id,
 * which a handler can't know.
 */
object AskUserHandler : ToolHandler {
    private const val MAX_QUESTIONS = 3
    private const val MAX_OPTIONS = 4

    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val questions = Args.array(args, "questions") ?: throw InvalidArguments("questions is required")
        if (questions.size !in 1..MAX_QUESTIONS) throw InvalidArguments("questions must have 1-$MAX_QUESTIONS items")
        questions.forEachIndexed { i, q ->
            val obj = q as? JsonObject ?: throw InvalidArguments("questions[$i] must be an object")
            Args.requireString(obj, "id")
            Args.requireString(obj, "text")
            val options = Args.array(obj, "options") ?: JsonArray(emptyList())
            if (options.size > MAX_OPTIONS) throw InvalidArguments("questions[$i].options must have at most $MAX_OPTIONS items")
            if (options.any { (it as? JsonPrimitive)?.isString != true }) throw InvalidArguments("questions[$i].options must be strings")
        }
        val label = if (questions.size == 1) "Asked a question" else "Asked ${questions.size} questions"
        return ToolOutcome(buildJsonObject { put("status", "shown") }, label, UiKind.QUESTIONS, endsTurn = true)
    }
}
