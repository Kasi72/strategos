package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.agent.ChatItem
import com.drkkr.strategos.agent.CounselController
import com.drkkr.strategos.agent.CounselDeps
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.db.entities.SettingEntity
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ChangeSetStatus
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.data.repo.UiMode
import com.drkkr.strategos.domain.agent.SafetyKeywordScreen
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.FakeLlmClient
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.networkDown
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.textReply
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.toolCall
import com.drkkr.strategos.fixtures.Fx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuidedModeTest {
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
        settings.setAutoContinue(false)
        assertEquals(UiMode.GUIDED, settings.uiMode())   // the default
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
        gateway = CommittingGateway(Fx.pd())
        controller = CounselController(scope, CounselDeps(
            llm = fake, systemPrompt = { "PROMPT" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
            conversations = conversations, changeSets = changeSets, memory = MemoryRepository(db), settings = settings,
            whatIfs = db.whatIfDao(), hasKey = { true }
        ), gateway)
        controller.bindScenario("pd").join()
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun cid() = controller.state.value.conversationId!!
    private fun scenario(): Scenario = gateway.inner.scenario!!

    private val addBoss = """{"summary":"Add your manager","ops":[
        {"type":"add_stakeholder","ref":"boss","name":"Manager","game_role":"INFLUENCER","rationale":"Mentioned by the user"},
        {"type":"add_stakeholder","ref":"hr","name":"HR","rationale":"Usually involved","source":"user_stated","quote":"HR always gets involved here"}]}"""

    private suspend fun guidedTurn(userText: String = "My manager decides. HR always gets involved here.") {
        fake.enqueue(toolCall("propose_changes", addBoss, callId = "p1")).enqueue(textReply("Who else is involved?"))
        controller.send(userText)!!.join()
        controller.awaitIdle()
    }

    @Test
    fun TC_GUIDED_01_autoApplyInSameCall() = runBlocking {
        guidedTurn()
        assertTrue(scenario().stakeholders.any { it.name == "Manager" })
        val set = changeSets.observeAll()
        assertEquals(ChangeSetStatus.APPLIED.name, set.single().status)
        assertEquals(listOf("auto", "auto"), fake.requests.map { it.toolChoice })   // endsTurn = false
        val result = conversations.messages(cid()).single { it.role == MessageRole.TOOL_RESULT.name }
        assertEquals("applied", Json.parseToJsonElement(result.payloadJson!!).jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(UiKind.NONE.name, result.uiKind)
        assertTrue(conversations.messages(cid()).none { it.role == MessageRole.EVENT.name })
    }

    @Test
    fun TC_GUIDED_02_provenanceUnchangedByMode() = runBlocking {
        guidedTurn()
        assertEquals(FactStatus.AI_INFERRED, scenario().stakeholders.single { it.name == "Manager" }.factStatus)
        assertEquals(FactStatus.USER_ESTIMATE, scenario().stakeholders.single { it.name == "HR" }.factStatus)
    }

    @Test
    fun TC_GUIDED_03_looksRightPromotesSinceLastRecap() = runBlocking {
        guidedTurn()
        // A recap card, then "Looks right".
        conversations.append(cid(), "t", MessageRole.TOOL_CALL, toolName = "show_understanding", callId = "u1",
            payloadJson = """{"bullets":["Your manager decides","HR is involved"]}""")
        conversations.append(cid(), "t", MessageRole.TOOL_RESULT, "Showed a recap", toolName = "show_understanding", callId = "u1",
            payloadJson = """{"status":"shown"}""", uiKind = UiKind.UNDERSTANDING)
        val payoffsBefore = scenario().payoffs
        fake.enqueue(textReply("Thanks."))
        controller.confirmUnderstanding().join()
        controller.awaitIdle()
        assertEquals(FactStatus.USER_ESTIMATE, scenario().stakeholders.single { it.name == "Manager" }.factStatus)
        assertEquals(payoffsBefore, scenario().payoffs)
        assertTrue(conversations.messages(cid()).any { it.role == "USER" && it.text == CounselController.LOOKS_RIGHT })
    }

    @Test
    fun TC_GUIDED_04_undoRestoresPreApply() = runBlocking {
        val before = scenario()
        guidedTurn()
        assertTrue(controller.canUndo)
        controller.undoLastChange().join()
        val after = scenario()
        fun strip(s: Scenario) = s.copy(version = 0, updatedAt = 0, changeHistory = emptyList())
        assertEquals(strip(before), strip(after))
        val added = after.changeHistory - before.changeHistory.toSet()
        assertTrue(added.any { it.summary == "Undid the last AI change" })
    }

    @Test
    fun TC_SAFE_01_englishPhraseShowsCardBeforeLlm() = runBlocking {
        settings.setAiEnabled(false)    // no LLM call possible at all
        controller.send("Some days I just want to die")
        assertTrue(controller.state.value.supportVisible)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun TC_SAFE_02_tamilPhrase() = runBlocking {
        settings.setAiEnabled(false)
        assertTrue(SafetyKeywordScreen.check("எனக்கு தற்கொலை எண்ணம் வருகிறது"))
        controller.send("எனக்கு தற்கொலை எண்ணம் வருகிறது")
        assertTrue(controller.state.value.supportVisible)
        assertFalse(SafetyKeywordScreen.check("My manager wants a decision by Friday"))
    }

    @Test
    fun TC_SAFE_03_cardShownWhenOffline() = runBlocking {
        fake.enqueue(networkDown())
        controller.send("He hits me when he is angry")!!.join()
        controller.awaitIdle()
        assertTrue(controller.state.value.supportVisible)
        assertTrue(controller.state.value.error != null)
        withTimeout(5_000) { while (controller.state.value.supportText.isEmpty()) delay(20) }
    }

    @Test
    fun TC_SAFE_04_showSupportToolRendersCard() = runBlocking {
        fake.enqueue(toolCall("show_support", """{"reason":"distress"}""")).enqueue(textReply("I'm here with you."))
        controller.send("I can't cope any more")!!.join()
        controller.awaitIdle()
        assertEquals(listOf("auto", "none"), fake.requests.map { it.toolChoice })   // endsTurn = true
        withTimeout(5_000) { while (controller.state.value.items.none { it is ChatItem.Support }) delay(20) }
    }

    private suspend fun ChangeSetRepository.observeAll() = observe(cid()).first()
}
