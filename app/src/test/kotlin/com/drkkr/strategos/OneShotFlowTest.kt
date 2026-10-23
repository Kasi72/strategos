package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.agent.CounselController
import com.drkkr.strategos.agent.CounselDeps
import com.drkkr.strategos.agent.OneShotTurn
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
import com.drkkr.strategos.domain.agent.PayoffCoverage
import com.drkkr.strategos.domain.agent.TokenEstimator
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.FakeLlmClient
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.networkDown
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.textReply
import com.drkkr.strategos.fixtures.Sketches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SIMPLE PROFILE: one message in, one result out; a follow-up updates and re-runs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OneShotFlowTest {
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
        val s = Scenario(id = "loan", title = "Loan", description = "My brother wants me to co-sign a loan.")
        db.scenarioDao().upsert(ScenarioEntity(s.id, s.title, "DRAFT", 1, 1L, 1L, "{}"))
        conversations = ConversationRepository(db)
        changeSets = ChangeSetRepository(db)
        settings = SettingsRepository(db).apply { setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION) }
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
        gateway = CommittingGateway(s)
        controller = CounselController(scope, CounselDeps(
            llm = fake, systemPrompt = { "unused" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
            conversations = conversations, changeSets = changeSets, memory = MemoryRepository(db), settings = settings,
            whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true
        ), gateway)
        controller.bindScenario("loan").join()
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun cid() = controller.state.value.conversationId!!
    private fun scenario() = gateway.inner.scenario!!
    private suspend fun send(text: String) { controller.send(text)!!.join(); controller.awaitIdle() }
    private suspend fun roles() = conversations.messages(cid()).map { it.role }

    @Test
    fun firstMessageGivesAModelAndAPlainResult() = runBlocking {
        fake.enqueue(textReply(Sketches.loanStory))
        send("My brother wants me to co-sign a loan for his shop. My wife is against it.")

        assertEquals(1, fake.requests.size)                                    // one request, no tools, no loop
        assertTrue(fake.requests.single().tools == null)
        assertEquals(listOf("USER", "TOOL_RESULT", "ASSISTANT"), roles())
        val s = scenario()
        assertEquals(4, s.stakeholders.size)
        assertEquals(6, s.actions.size)
        assertEquals(18, PayoffCoverage.of(s).filled)
        val reply = conversations.messages(cid()).last().text
        assertTrue(reply, reply.contains("Options to consider"))
        assertTrue(reply.contains("What I understood"))
        assertEquals(UiKind.RECOMMENDATION.name, conversations.messages(cid()).single { it.role == "TOOL_RESULT" }.uiKind)
        assertEquals(ChangeSetStatus.APPLIED.name, changeSets.observe(cid()).first().single().status)
        assertTrue(controller.canUndo)
        val request = TokenEstimator.estimate(fake.requests.single().input.toString()) + fake.requests.single().maxOutputTokens
        assertTrue("request ~$request tokens", request <= 3_200)
    }

    @Test
    fun followUpUpdatesTheSavedModelAndRunsAgain() = runBlocking {
        fake.enqueue(textReply(Sketches.loanStory)).enqueue(textReply(Sketches.followUp))
        send("My brother wants me to co-sign a loan for his shop.")
        val firstPeople = scenario().stakeholders.size
        val versionBefore = scenario().version
        send("I decided to wait and told him. He said he will ask our parents.")

        assertEquals(2, fake.requests.size)
        assertEquals(firstPeople, scenario().stakeholders.size)                // nobody duplicated
        assertTrue(scenario().version > versionBefore)
        assertEquals(2, conversations.messages(cid()).count { it.role == MessageRole.ASSISTANT.name })
        // The follow-up request carries the saved model (the memory) and the earlier message.
        val second = fake.requests[1].input.toString()
        assertTrue(second.contains("Brother") && second.contains("co-sign a loan for his shop"))
    }

    @Test
    fun anIncompleteReplyIsRepairedWithOneTargetedRequest() = runBlocking {
        fake.enqueue(textReply(Sketches.loanStoryIncomplete)).enqueue(textReply(Sketches.loanRepair))
        send("My brother wants me to co-sign a loan for his shop. My wife is against it.")
        assertEquals(2, fake.requests.size)
        val repair = fake.requests[1].input.toString()
        assertTrue(repair.contains("incomplete") && repair.contains("Brother has fewer than 2 options"))
        assertFalse(repair.contains("\"by\""))                                    // sketch digest names owners, no ids
        val s = scenario()
        assertEquals(4, s.actions.size)
        assertEquals(8, PayoffCoverage.of(s).filled)
        val reply = conversations.messages(cid()).last().text
        assertTrue(reply, reply.contains("Options to consider"))
        assertTrue(reply.contains("Brother could: Take the loan, Give up the shop"))
    }

    @Test
    fun ifTheRepairFailsTheAnswerSaysItCannotCompareYet() = runBlocking {
        fake.enqueue(textReply(Sketches.loanStoryIncomplete)).enqueue(networkDown())
        send("My brother wants me to co-sign a loan for his shop. My wife is against it.")
        val reply = conversations.messages(cid()).last().text
        assertTrue(reply, reply.contains("I can't compare your options yet"))
        assertFalse(reply.contains("best fit"))
        assertTrue(reply.contains("Your options so far: Co-sign, Wait"))
        assertEquals(null, controller.state.value.error)                          // the first part was saved; no error shown
        assertEquals(3, scenario().stakeholders.size)
    }

    @Test
    fun aThinStoryGetsOneQuestionAndSavesNothing() = runBlocking {
        fake.enqueue(textReply(Sketches.ASK))
        val before = scenario()
        send("Help")
        assertEquals("Who else is affected, and what could you do?", conversations.messages(cid()).last().text)
        assertTrue(before === scenario())
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun anUnreadableReplyIsRetriedOnceThenReported() = runBlocking {
        fake.enqueue(textReply("I'm sorry, I can't help with that.")).enqueue(textReply("still not json"))
        send("My brother wants a loan.")
        assertEquals(2, fake.requests.size)
        assertEquals(OneShotTurn.COULD_NOT_READ, controller.state.value.error)
        assertTrue(controller.state.value.canRetry)
        // Retry re-runs without a second USER message.
        fake.enqueue(textReply(Sketches.loanStory))
        controller.retryLastTurn()!!.join(); controller.awaitIdle()
        assertEquals(1, conversations.messages(cid()).count { it.role == MessageRole.USER.name })
        assertEquals(4, scenario().stakeholders.size)
    }

    @Test
    fun offlineLeavesTheModelUntouched() = runBlocking {
        fake.enqueue(networkDown())
        val before = scenario()
        send("My brother wants a loan.")
        assertTrue(before === scenario())
        assertTrue(controller.state.value.canRetry)
        assertFalse(roles().contains("ASSISTANT"))
    }

    @Test
    fun TC_KEY_07_unauthorizedDuringATurnFlagsTheKey() = runBlocking {
        fake.enqueue(com.drkkr.strategos.fixtures.FakeLlmClient.error401())
        send("My brother wants a loan.")
        assertTrue(settings.keyInvalid())
        withTimeout(5_000) { while (!controller.state.value.keyInvalid) kotlinx.coroutines.delay(20) }
        assertFalse(controller.state.value.canRetry)
    }

    @Test
    fun TC_MODEL_07_retiredModelSwitchesOnceAndTellsTheUser() = runBlocking {
        fake.models = com.drkkr.strategos.llm.ModelListResult.Ok(listOf(com.drkkr.strategos.llm.ModelInfo("openai/gpt-oss-120b"), com.drkkr.strategos.llm.ModelInfo("whisper-large-v3")))
        settings.setModel("openai/gpt-oss-20b")
        fake.enqueue(com.drkkr.strategos.llm.LlmResult.HttpError(404, "model_not_found")).enqueue(textReply(Sketches.loanStory))
        send("My brother wants me to co-sign a loan for his shop.")
        assertEquals(listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b"), fake.requests.map { it.model })
        assertEquals("openai/gpt-oss-120b", settings.model())
        val note = conversations.messages(cid()).single { it.role == "EVENT" }.text
        assertEquals("The model you chose isn't available anymore. Switched to openai/gpt-oss-120b. Change it in Settings.", note)
        assertEquals(4, scenario().stakeholders.size)                                // the retried turn worked
    }

    @Test
    fun theSupportFlagShowsTheSupportCard() = runBlocking {
        fake.enqueue(textReply("""{"support":true,"ask":"I'm here. Are you safe right now?"}"""))
        send("I can't take this anymore")      // not in the keyword list, so only the model's flag can trigger the card
        assertTrue(controller.state.value.supportVisible)
    }
}
