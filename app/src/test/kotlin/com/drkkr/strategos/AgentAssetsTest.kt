package com.drkkr.strategos

import com.drkkr.strategos.agent.ToolDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class AgentAssetsTest {

    private fun assetsDir(): File = listOf("src/main/assets/agent", "app/src/main/assets/agent")
        .map(::File).first { it.isDirectory }

    private fun text(name: String) = File(assetsDir(), name).readText(Charsets.UTF_8)
    private fun tools(): List<JsonObject> = Json.parseToJsonElement(text("tools.json")).jsonArray.map { it.jsonObject }
    private fun versionField(key: String) =
        Json.parseToJsonElement(text("agent_version.json")).jsonObject.getValue(key).jsonPrimitive.content

    private fun sha256(name: String): String =
        MessageDigest.getInstance("SHA-256").digest(File(assetsDir(), name).readBytes())
            .joinToString("") { "%02x".format(it) }

    private fun opsItems(tool: String): JsonObject =
        tools().single { it.getValue("name").jsonPrimitive.content == tool }
            .getValue("parameters").jsonObject.getValue("properties").jsonObject
            .getValue("ops").jsonObject.getValue("items").jsonObject

    @Test
    fun TC_ASSET_01_toolsJsonParses() {
        val tools = tools()
        assertEquals(10, tools.size)
        tools.forEach { assertEquals("function", it.getValue("type").jsonPrimitive.content) }
        val names = tools.map { it.getValue("name").jsonPrimitive.content }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun TC_ASSET_02_toolNamesMatchBlueprint() {
        val expected = setOf(
            "get_scenario", "propose_changes", "ask_user", "run_analysis", "explain_framework",
            "what_if", "stress_test_inferred", "update_memory", "show_understanding", "show_support"
        )
        assertEquals(expected, tools().map { it.getValue("name").jsonPrimitive.content }.toSet())
        assertEquals(expected, ToolDispatcher.default().names)
    }

    @Test
    fun TC_ASSET_03_changeOpSchemaExpandedIdentically() {
        val propose = opsItems("propose_changes")
        val whatIf = opsItems("what_if")
        assertEquals(propose, whatIf)
        assertEquals("object", propose.getValue("type").jsonPrimitive.content)
        assertFalse(text("tools.json").contains("@change_op"))
    }

    @Test
    fun TC_ASSET_04_systemPromptHasSkillsAndRules() {
        val prompt = text("system_prompt.md")
        // LEAN PROFILE: no skill sections (the model called them as tools); the rules carry the interview.
        assertEquals(0, prompt.split("## Skill: ").size - 1)
        assertTrue(prompt.contains("Never calculate equilibria"))
        assertTrue(prompt.contains("Guided mode"))
    }

    @Test
    fun TC_ASSET_05_versionHashesMatchFiles() {
        assertEquals(sha256("tools.json"), versionField("toolsSha256"))
        assertEquals(sha256("system_prompt.md"), versionField("promptSha256"))
        assertEquals("2.0.0", versionField("agentVersion"))
    }
}
