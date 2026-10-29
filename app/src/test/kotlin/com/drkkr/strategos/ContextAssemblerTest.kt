package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.ContextAssembler
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MemoryKind
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.data.repo.UiMode
import kotlinx.serialization.json.jsonArray
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextAssemblerTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var settings: SettingsRepository
    private lateinit var memory: MemoryRepository
    private lateinit var cid: String
    private var scenario: Scenario = Fx.pd()
    private lateinit var assembler: ContextAssembler

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        db.scenarioDao().upsert(ScenarioEntity("pd", "PD", "DRAFT", 1, 1L, 1L, "{}"))
        conversations = ConversationRepository(db)
        settings = SettingsRepository(db)
        memory = MemoryRepository(db)
        cid = conversations.getOrCreateActive("pd", "m", "v").id
        assembler = ContextAssembler({ "SYSTEM PROMPT" }, conversations, ChangeSetRepository(db), memory, settings,
            { scenario }, { LocalDate.of(2026, 10, 23) })
    }

    @After fun tearDown() { db.close() }

    private fun content(o: kotlinx.serialization.json.JsonElement) = o.jsonObject.getValue("content").jsonPrimitive.content
    private fun role(o: kotlinx.serialization.json.JsonElement) = o.jsonObject.getValue("role").jsonPrimitive.content

    private fun live(input: JsonArray): JsonObject {
        val text = input.map(::content).single { it.startsWith(ContextAssembler.HEADER) }
        return Json.parseToJsonElement(text.substringAfter("```json\n").substringBeforeLast("\n```")).jsonObject
    }

    @Test
    fun TC_CTX_01_order() = runBlocking {
        listOf("one", "two", "three").forEachIndexed { i, t ->
            conversations.append(cid, "t", if (i == 1) MessageRole.ASSISTANT else MessageRole.USER, t)
        }
        conversations.saveSummary(cid, "Earlier: the user runs a clinic.", throughSeq = 1)
        conversations.append(cid, "t", MessageRole.USER, "newest")
        val input = assembler.build(cid, "pd")
        assertEquals(6, input.size)
        assertEquals("SYSTEM PROMPT", content(input[0]))
        assertTrue(content(input[1]).startsWith("Summary of earlier conversation:\n"))
        assertEquals(listOf("two", "three"), listOf(content(input[2]), content(input[3])))
        assertEquals(listOf("assistant", "user"), listOf(role(input[2]), role(input[3])))
        assertTrue(content(input[4]).startsWith(ContextAssembler.HEADER))
        assertEquals("system", role(input[4]))
        assertEquals("newest", content(input[5]))
    }

    @Test
    fun TC_CTX_02_toolMessagesExcludedAndEventsPrefixed() = runBlocking {
        conversations.append(cid, "t", MessageRole.USER, "hello")
        conversations.append(cid, "t", MessageRole.TOOL_CALL, "", toolName = "get_scenario", callId = "c1", payloadJson = "{}")
        conversations.append(cid, "t", MessageRole.TOOL_RESULT, "Read the model", toolName = "get_scenario", callId = "c1", payloadJson = "{\"secret\":1}")
        conversations.append(cid, "t", MessageRole.ERROR, "oops")
        conversations.append(cid, "t", MessageRole.EVENT, "User applied 2 of 2 proposed changes")
        val input = assembler.build(cid, "pd")
        val texts = input.map(::content)
        assertFalse(texts.any { it.contains("secret") || it == "Read the model" || it == "oops" })
        assertEquals(ContextAssembler.EVENT_PREFIX + "User applied 2 of 2 proposed changes", texts.last())
        assertEquals("user", role(input.last()))
    }

    @Test
    fun TC_CTX_03_memoryOmittedWhenOff() = runBlocking {
        memory.add("pd", MemoryKind.FACT, "Deadline is Friday", FactStatus.USER_ESTIMATE)
        conversations.append(cid, "t", MessageRole.USER, "hi")
        assertTrue("memory" in live(assembler.build(cid, "pd")))
        settings.setIncludeMemory(false)
        assertFalse("memory" in live(assembler.build(cid, "pd")))
    }

    @Test
    fun TC_CTX_04_staleAnalysisAndChangedFlag() = runBlocking {
        conversations.append(cid, "t", MessageRole.USER, "hi")
        conversations.saveLastReport(cid, """{"executive_status":"PROCEED"}""", scenarioVersion = scenario.version)
        val first = live(assembler.build(cid, "pd"))
        assertFalse(first.getValue("last_analysis_stale").jsonPrimitive.boolean)
        assertTrue(first.getValue("scenario_changed_since_last_turn").jsonPrimitive.boolean)
        assertFalse(live(assembler.build(cid, "pd")).getValue("scenario_changed_since_last_turn").jsonPrimitive.boolean)
        scenario = scenario.copy(version = scenario.version + 1)
        val after = live(assembler.build(cid, "pd"))
        assertTrue(after.getValue("last_analysis_stale").jsonPrimitive.boolean)
        assertTrue(after.getValue("scenario_changed_since_last_turn").jsonPrimitive.boolean)
        assertEquals("2026-10-23", after.getValue("now").jsonPrimitive.content)
        assertEquals("guided", after.getValue("ui_mode").jsonPrimitive.content)
    }

    @Test
    fun TC_CTX_05_guidedIntakeGaps() = runBlocking {
        settings.setUiMode(UiMode.GUIDED)
        scenario = Fx.loanFamily().copy(constraints = emptyList())
        conversations.append(cid, "t", MessageRole.USER, "hi")
        val ctx = live(assembler.build(cid, "pd"))
        assertEquals("guided", ctx.getValue("ui_mode").jsonPrimitive.content)
        val ids = ctx.getValue("intake_gaps").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertTrue(ids.contains("limits"))
        memory.add("pd", MemoryKind.FACT, "No limits mentioned (limits_none)", FactStatus.AI_INFERRED)
        val after = live(assembler.build(cid, "pd")).getValue("intake_gaps").jsonArray
        assertTrue(after.isEmpty())
    }

    @Test
    fun lean_askedQuestionIsInHistory() = runBlocking {
        conversations.append(cid, "t", MessageRole.USER, "My brother wants a loan.")
        conversations.append(cid, "t", MessageRole.TOOL_CALL, toolName = "propose_changes", callId = "c1",
            payloadJson = """{"ops":[],"ask":{"text":"What would a good outcome look like?"}}""")
        conversations.append(cid, "t", MessageRole.TOOL_RESULT, "Asked a question", toolName = "propose_changes", callId = "c1", payloadJson = """{"status":"asked"}""")
        conversations.append(cid, "t", MessageRole.USER, "He gets his shop and we stay out of debt.")
        val input = assembler.build(cid, "pd")
        val asked = input.single { content(it) == "What would a good outcome look like?" }
        assertEquals("assistant", role(asked))
        assertFalse(input.map(::content).any { it.contains("\"ops\"") })   // never raw tool JSON
    }
}
