package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.agent.AgentOrchestrator
import com.drkkr.strategos.agent.ContextAssembler
import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.TurnResult
import com.drkkr.strategos.agent.TurnTrigger
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.db.entities.SettingEntity
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.data.repo.UiMode
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.fixtures.FakeGateway
import com.drkkr.strategos.fixtures.FakeLlmClient
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.error401
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.networkDown
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.textReply
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.toolCall
import com.drkkr.strategos.fixtures.Fx
import com.drkkr.strategos.llm.LlmClient
import com.drkkr.strategos.llm.LlmRequest
import com.drkkr.strategos.llm.LlmResult
import com.drkkr.strategos.llm.ModelListResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentOrchestratorTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var settings: SettingsRepository
    private lateinit var gateway: FakeGateway
    private lateinit var cid: String
    private val fake = FakeLlmClient()

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        db.scenarioDao().upsert(ScenarioEntity("pd", "PD", "DRAFT", 1, 1L, 1L, "{}"))
        conversations = ConversationRepository(db)
        settings = SettingsRepository(db)
        settings.setUiMode(UiMode.EXPERT)
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, STORED_KEY))
        gateway = FakeGateway(Fx.pd())
        cid = conversations.getOrCreateActive("pd", "m", "v").id
    }

    @After fun tearDown() { db.close() }

    private fun orchestrator(llm: LlmClient = fake) = AgentOrchestrator(
        llm = llm, tools = { JsonArray(emptyList()) }, dispatcher = ToolDispatcher.default(),
        assembler = ContextAssembler({ "PROMPT" }, conversations, ChangeSetRepository(db), MemoryRepository(db), settings, { gateway.scenario }),
        conversations = conversations, settings = settings
    )

    private suspend fun ctx(turnId: String) = ToolContext(
        cid, turnId, "pd", gateway, AnalysisPipeline(), conversations, ChangeSetRepository(db), MemoryRepository(db),
        db.whatIfDao(), conversations.userTexts(cid)
    )

    private suspend fun turn(text: String = "Help me decide", llm: LlmClient = fake, trigger: TurnTrigger = TurnTrigger.User(text)) =
        orchestrator(llm).runTurn(cid, "pd", trigger, ::ctx) {}

    private suspend fun roles() = conversations.messages(cid).map { it.role }

    @Test
    fun TC_ORCH_01_textOnly() = runBlocking {
        fake.enqueue(textReply("Hi"))
        assertEquals(TurnResult.Done, turn())
        assertEquals(listOf("USER", "ASSISTANT"), roles())
        assertEquals("Hi", conversations.messages(cid).last().text)
    }

    @Test
    fun TC_ORCH_02_toolsThenForcedText() = runBlocking {
        val propose = """{"summary":"Add regulator","ops":[{"type":"add_stakeholder","ref":"reg","name":"Regulator","rationale":"r"}]}"""
        fake.enqueue(toolCall("get_scenario", callId = "c1"))
            .enqueue(toolCall("propose_changes", propose, callId = "c2"))
            .enqueue(textReply("Review the card."))
        val before = gateway.scenario
        assertEquals(TurnResult.Done, turn())
        assertEquals(3, fake.requests.size)
        assertEquals(listOf("auto", "auto", "none"), fake.requests.map { it.toolChoice })
        assertEquals(listOf("USER", "TOOL_CALL", "TOOL_RESULT", "TOOL_CALL", "TOOL_RESULT", "ASSISTANT"), roles())
        assertSame(before, gateway.scenario)
        val card = conversations.messages(cid).single { it.uiKind == UiKind.CHANGE_SET.name }
        assertTrue(card.refId != null)
        // The second request echoes the first call and carries its output.
        val second = fake.requests[1].input.map { it.toString() }
        assertTrue(second.any { it.contains("function_call_output") && it.contains("c1") })
    }

    @Test
    fun TC_ORCH_03_ninthRoundForcedNone() = runBlocking {
        repeat(AgentConfig.MAX_TOOL_ROUNDS + 1) { fake.enqueue(toolCall("get_scenario", callId = "c$it")) }
        assertEquals(TurnResult.Done, turn())
        assertEquals(AgentConfig.MAX_TOOL_ROUNDS + 1, fake.requests.size)
        assertEquals("none", fake.requests[AgentConfig.MAX_TOOL_ROUNDS].toolChoice)
        assertTrue(fake.requests.dropLast(1).all { it.toolChoice == "auto" })
        assertEquals(AgentOrchestrator.NO_REPLY, conversations.messages(cid).last().text)
    }

    @Test
    fun TC_ORCH_04_unauthorized() = runBlocking {
        fake.enqueue(error401())
        val r = turn() as TurnResult.Failed
        assertFalse(r.retryable)
        val last = conversations.messages(cid).last()
        assertEquals("ERROR", last.role)
        assertEquals(r.userMessage, last.text)
    }

    @Test
    fun TC_ORCH_05_offline() = runBlocking {
        fake.enqueue(networkDown())
        val before = gateway.scenario
        val r = turn() as TurnResult.Failed
        assertTrue(r.retryable)
        assertSame(before, gateway.scenario)
    }

    @Test
    fun TC_ORCH_06_cancelMidTurn() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val hanging = object : LlmClient {
            override suspend fun create(req: LlmRequest): LlmResult { started.complete(Unit); awaitCancellation() }
            override suspend fun listModels(apiKey: String?) = ModelListResult.Failed("n/a")
        }
        val job = launch(Dispatchers.Default) { turn(llm = hanging) }
        started.await()
        job.cancel()
        job.join()
        val last = conversations.messages(cid).last()
        assertEquals("EVENT", last.role)
        assertEquals(AgentOrchestrator.STOPPED_TEXT, last.text)
    }

    @Test
    fun TC_ORCH_07_noKeyInRequests() = runBlocking {
        fake.enqueue(toolCall("get_scenario")).enqueue(textReply("ok"))
        turn("my key is not here")
        assertTrue(fake.requests.isNotEmpty())
        assertTrue(fake.requests.none { it.toString().contains(STORED_KEY) })
    }

    @Test
    fun TC_ORCH_08_retryDoesNotDuplicateUserMessage() = runBlocking {
        fake.enqueue(networkDown()).enqueue(textReply("Back again"))
        turn("What should I do?")
        assertEquals(TurnResult.Done, turn(trigger = TurnTrigger.Retry))
        assertEquals(1, conversations.messages(cid).count { it.role == MessageRole.USER.name })
        val lastInput = fake.requests.last().input.last().jsonObject
        assertEquals("What should I do?", lastInput.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun askUserRefIdIsItsResultMessage() = runBlocking {
        fake.enqueue(toolCall("ask_user", """{"questions":[{"id":"q1","text":"Who decides?"}]}""")).enqueue(textReply("…"))
        turn()
        val q = conversations.messages(cid).single { it.uiKind == UiKind.QUESTIONS.name }
        assertEquals(q.id, q.refId)
    }

    private companion object { const val STORED_KEY = "gsk_TEST_SECRET_KEY_123" }
}
