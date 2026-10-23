package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.InvalidArguments
import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.MemoryKind
import com.drkkr.strategos.domain.agent.QuoteVerifier
import com.drkkr.strategos.domain.model.FactStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** §8.8. A FACT is USER_ESTIMATE only when its quote is verified against the user's own messages. */
object UpdateMemoryHandler : ToolHandler {
    private const val MAX_ADDS = 10
    private const val MAX_TEXT = 300

    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val adds = Args.array(args, "add").orEmpty()
        if (adds.size > MAX_ADDS) throw InvalidArguments("add must have at most $MAX_ADDS items")
        val parsed = adds.mapIndexed { i, el ->
            val obj = el as? JsonObject ?: throw InvalidArguments("add[$i] must be an object")
            val kind = runCatching { MemoryKind.valueOf(Args.requireString(obj, "kind")) }
                .getOrElse { throw InvalidArguments("add[$i].kind must be one of ${MemoryKind.entries.joinToString()}") }
            Triple(kind, Args.requireString(obj, "text").take(MAX_TEXT), Args.string(obj, "quote"))
        }
        val resolveIds = Args.array(args, "resolve").orEmpty().map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw InvalidArguments("resolve must hold ids")
        }

        val added = parsed.map { (kind, text, quote) ->
            val verified = kind == MemoryKind.FACT && QuoteVerifier.isVerified(quote, ctx.userTexts)
            ctx.memory.add(ctx.scenarioId, kind, text, if (verified) FactStatus.USER_ESTIMATE else FactStatus.AI_INFERRED)
        }
        val resolved = ctx.memory.resolve(ctx.scenarioId, resolveIds)
        val out = buildJsonObject {
            putJsonArray("added") { added.forEach { add(it) } }
            put("resolved", resolved)
        }
        return ToolOutcome(out, "Updated memory")
    }
}
