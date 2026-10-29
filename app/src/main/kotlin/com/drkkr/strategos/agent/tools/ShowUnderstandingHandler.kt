package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.InvalidArguments
import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** §17.4. The bullets stay in the TOOL_CALL payload; the card renders them as written. */
object ShowUnderstandingHandler : ToolHandler {
    private const val MAX_BULLET = 160

    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val bullets = Args.array(args, "bullets") ?: throw InvalidArguments("bullets is required")
        if (bullets.size !in 2..8) throw InvalidArguments("bullets must have 2-8 items")
        bullets.forEachIndexed { i, b ->
            val text = (b as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw InvalidArguments("bullets[$i] must be a string")
            if (text.isBlank() || text.length > MAX_BULLET) throw InvalidArguments("bullets[$i] must be 1-$MAX_BULLET characters")
        }
        return ToolOutcome(buildJsonObject { put("status", "shown") }, "Showed a recap", UiKind.UNDERSTANDING, endsTurn = true)
    }
}
