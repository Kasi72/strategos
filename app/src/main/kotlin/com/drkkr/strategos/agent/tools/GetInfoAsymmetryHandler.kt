package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.InfoAsymmetryMapper
import com.drkkr.strategos.domain.agent.IntakeChecklist
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

/** Maps information asymmetry between players and advises on exploitation or mitigation. */
object GetInfoAsymmetryHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val scenario = ctx.gateway.activeScenario()
            ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)

        val userPlayer = IntakeChecklist.self(scenario)
            ?: return ToolOutcome(
                buildJsonObject { put("status", "not_applicable"); put("reason", "No decision-owner found in scenario.") },
                "No decision-owner"
            )

        val infoMap = InfoAsymmetryMapper.map(scenario, userPlayer.id)

        val out = buildJsonObject {
            put("status", "ok")
            put("advantage", infoMap.advantage.name)
            put("summary", infoMap.summary)
            if (infoMap.gaps.isNotEmpty()) {
                putJsonArray("gaps") {
                    infoMap.gaps.forEach { gap ->
                        addJsonObject {
                            put("topic", gap.topic)
                            put("known_by", gap.knownBy)
                            put("unknown_by", gap.unknownBy)
                            put("exploit_hint", gap.exploitHint)
                        }
                    }
                }
            }
        }
        return ToolOutcome(out, "Mapped ${infoMap.gaps.size} information gap(s) — ${infoMap.advantage.name}", UiKind.RECOMMENDATION)
    }
}
