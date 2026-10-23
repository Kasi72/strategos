package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.agent.ChatItem
import com.drkkr.strategos.agent.CounselController
import com.drkkr.strategos.agent.CounselDeps
import com.drkkr.strategos.agent.CounselGateway
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.db.entities.SettingEntity
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ChangeSetStatus
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.data.repo.UiMode
import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpType
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.FakeGateway
import com.drkkr.strategos.fixtures.FakeLlmClient
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.textReply
import com.drkkr.strategos.fixtures.Fx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Gateway whose commit bumps the version like ScenarioRepository.save does. */
class CommittingGateway(scenario: Scenario?) : CounselGateway {
    val inner = FakeGateway(scenario)
    var commits = 0
    override fun activeScenario() = inner.scenario
    override suspend fun analyzeAndPublish() = inner.analyzeAndPublish()
    override fun lastResults() = inner.lastResults()
    override suspend fun commitAgentChange(updated: Scenario): SaveResult {
        commits++
        val saved = updated.copy(version = updated.version + 1)
        inner.scenario = saved
        return SaveResult.Success(saved)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CounselControllerTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var changeSets: ChangeSetRepository
    private lateinit var settings: SettingsRepository
    private lateinit var gateway: CommittingGateway
    private lateinit var controller: CounselController
    private val fake = FakeLlmClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        db.scenarioDao().upsert(ScenarioEntity("pd", "PD", "DRAFT", 1, 1L, 1L, "{}"))
        conversations = ConversationRepository(db)
        changeSets = ChangeSetRepository(db)
        settings = SettingsRepository(db)
        settings.setAiEnabled(true)
        settings.setConsentVersion(AgentConfig.CONSENT_VERSION)
        settings.setUiMode(UiMode.EXPERT)
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
        gateway = CommittingGateway(Fx.pd())
        val deps = CounselDeps(
            llm = fake, systemPrompt = { "PROMPT" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
            conversations = conversations, changeSets = changeSets, memory = MemoryRepository(db), settings = settings,
            whatIfs = db.whatIfDao(), hasKey = { true }
        )
        controller = CounselController(scope, deps, gateway)
        controller.bindScenario("pd").join()
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun cid() = controller.state.value.conversationId!!

    private suspend fun waitFor(what: String, check: () -> Boolean) = withTimeout(5_000) {
        while (!check()) delay(20)
    }

    /** Six ops on Fx.pd(): five new influencers and one update of p2. */
    private suspend fun pendingSet(): String {
        val ops = (1..5).map { ChangeOp(OpType.ADD_STAKEHOLDER, ref = "s$it", name = "Person $it", rationale = "r") } +
            ChangeOp(OpType.UPDATE_STAKEHOLDER, targetId = "p2", canVeto = true, rationale = "r")
        val v = ChangeSetValidator.validate(gateway.inner.scenario!!, ops)
        return changeSets.create(
            "pd", cid(), "Add five people", Json.encodeToString(ListSerializer(ChangeOp.serializer()), v.accepted.map { it.op }),
            Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), v.refIds), 1
        ).id
    }

    @Test
    fun TC_CTRL_01_partialApplyAutoContinues() = runBlocking {
        val id = pendingSet()
        fake.enqueue(textReply("Thanks, noted."))
        val decisions = (0..4).map { OpDecision(it, Decision.ACCEPT) } + OpDecision(5, Decision.REJECT)
        controller.applyChangeSet(id, decisions).join()
        controller.awaitIdle()

        assertEquals(ChangeSetStatus.PARTIAL.name, changeSets.get(id)!!.status)
        assertEquals(1, gateway.commits)
        assertEquals(7, gateway.inner.scenario!!.stakeholders.size)
        val event = conversations.messages(cid()).single { it.role == MessageRole.EVENT.name }
        assertTrue(event.text, event.text.startsWith("User applied 5 of 6 proposed changes (\"Add five people\")"))
        assertTrue(event.text, event.text.contains("Update P2 (rejected)"))
        assertEquals(1, fake.requests.size)
        assertEquals("ASSISTANT", conversations.messages(cid()).last().role)
    }

    @Test
    fun TC_CTRL_02_noAutoContinue() = runBlocking {
        settings.setAutoContinue(false)
        val id = pendingSet()
        controller.applyChangeSet(id, (0..5).map { OpDecision(it, Decision.ACCEPT) }).join()
        controller.awaitIdle()
        assertEquals(ChangeSetStatus.APPLIED.name, changeSets.get(id)!!.status)
        assertTrue(fake.requests.isEmpty())
        assertEquals(1, conversations.messages(cid()).count { it.role == MessageRole.EVENT.name })
    }

    @Test
    fun TC_CTRL_03_rejectAll() = runBlocking {
        settings.setAutoContinue(false)
        val id = pendingSet()
        val before = gateway.inner.scenario
        controller.rejectChangeSet(id).join()
        assertEquals(ChangeSetStatus.REJECTED.name, changeSets.get(id)!!.status)
        assertTrue(before === gateway.inner.scenario)
        assertEquals("User rejected the proposed changes (\"Add five people\").", conversations.messages(cid()).last().text)
    }

    @Test
    fun TC_CTRL_04_answerQuestion() = runBlocking {
        conversations.append(cid(), "t0", MessageRole.USER, "Help")
        conversations.append(cid(), "t0", MessageRole.TOOL_CALL, toolName = "ask_user", callId = "q",
            payloadJson = """{"questions":[{"id":"q1","text":"Who decides?","options":["Me","Them"]}]}""")
        conversations.append(cid(), "t0", MessageRole.TOOL_RESULT, "Asked a question", toolName = "ask_user", callId = "q",
            payloadJson = """{"status":"shown"}""", uiKind = UiKind.QUESTIONS)
        waitFor("question card") { controller.state.value.items.any { it is ChatItem.Questions } }
        assertTrue(controller.state.value.items.filterIsInstance<ChatItem.Questions>().none { it.answered })

        fake.enqueue(textReply("Got it."))
        controller.answerQuestion("q1", "Who decides?", "Me")!!.join()
        assertTrue(conversations.messages(cid()).any { it.role == "USER" && it.text == "Re: Who decides? → Me" })
        waitFor("answered") { controller.state.value.items.filterIsInstance<ChatItem.Questions>().any { it.answered } }
    }

    @Test
    fun sendIsNoOpWhenAiNotReady() = runBlocking {
        settings.setAiEnabled(false)
        controller.send("hello")?.join()
        assertTrue(fake.requests.isEmpty())
        assertTrue(conversations.messages(cid()).isEmpty())
    }
}
