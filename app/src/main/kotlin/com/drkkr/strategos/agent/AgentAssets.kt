package com.drkkr.strategos.agent

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The exported agent definition (blueprint §7.9), generated into assets/agent/ by scripts/export-agent.sh. */
class AgentAssets(private val context: Context) {
    val systemPrompt: String by lazy { read("system_prompt.md") }
    val tools: JsonArray by lazy { Json.parseToJsonElement(read("tools.json")).jsonArray }
    val agentVersion: String by lazy {
        Json.parseToJsonElement(read("agent_version.json")).jsonObject.getValue("agentVersion").jsonPrimitive.content
    }

    private fun read(name: String): String =
        context.assets.open("$DIR/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }

    companion object {
        const val DIR = "agent"
    }
}
