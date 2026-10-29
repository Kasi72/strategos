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

/** COMPANION REPLY: after the engine runs, a second request writes the answer from the engine's result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionReplyTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var gateway: CommittingGateway
    private lateinit var controller: CounselController
    private val fake = FakeLlmClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val words = "That sounds exhausting, and wanting him to come back on his own is a fair thing to want. The analysis leans towards giving him room while you stay steady, because pushing the family in could make him feel cornered. This week you could simply tell him one thing you appreciated about him. How does that feel to you?"

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        val s = Scenario(id = "loan", title = "Loan", description = "My brother wants me to co-sign a loan.")
        db.scenarioDao().upsert(ScenarioEntity(s.id, s.title, "DRAFT", 1, 1L, 1L, "{}"))
        conversations = ConversationRepository(db)
        val settings = SettingsRepository(db).apply { setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION) }
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
        gateway = CommittingGateway(s)
        controller = CounselController(scope, CounselDeps(
            llm = fake, systemPrompt = { "unused" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
            conversations = conversations, changeSets = ChangeSetRepository(db), memory = MemoryRepository(db), settings = settings,
            whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true, coach = true
        ), gateway)
        controller.bindScenario("loan").join()
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun cid() = controller.state.value.conversationId!!
    private suspend fun send(text: String) { controller.send(text)!!.join(); controller.awaitIdle() }
    private suspend fun lastReply() = conversations.messages(cid()).last().text

    @Test
    fun theAnswerStartsWithTheCompanionAndKeepsTheEngineView() = runBlocking {
        fake.enqueue(textReply(Sketches.loanStory)).enqueue(textReply(words))
        send("My brother wants me to co-sign a loan for his shop. My wife is against it.")

        assertEquals(2, fake.requests.size)
        val coach = fake.requests[1]
        assertEquals("COACH", coach.purpose)
        assertTrue(coach.tools == null)
        assertTrue(coach.maxOutputTokens <= 700)
        val sent = coach.input.toString()
        assertTrue(sent, sent.contains("WHAT THE ANALYSIS SHOWS") && sent.contains("leading option") && sent.contains("co-sign a loan"))
        assertTrue("coach request ~${TokenEstimator.estimate(sent) + coach.maxOutputTokens} tokens", TokenEstimator.estimate(sent) + coach.maxOutputTokens <= 2_200)
        val reply = lastReply()
        assertTrue(reply, reply.startsWith(words))
        assertTrue(reply, reply.contains("Options to consider") && reply.contains("Here's what I see"))
        assertEquals(null, controller.state.value.error)
    }

    @Test
    fun ifTheCompanionCallFailsThePlainViewStillAnswers() = runBlocking {
        fake.enqueue(textReply(Sketches.loanStory)).enqueue(networkDown())
        send("My brother wants me to co-sign a loan.")
        val reply = lastReply()
        assertTrue(reply, reply.contains("Here's what I see") && reply.contains("Options to consider"))
        assertEquals(null, controller.state.value.error)
    }

    @Test
    fun aReplyThatIsJsonOrEmptyIsNotShown() = runBlocking {
        fake.enqueue(textReply(Sketches.loanStory)).enqueue(textReply("{\"goal\":\"x\"}"))
        send("My brother wants me to co-sign a loan.")
        assertTrue(!lastReply().contains("{\"goal\""))
        assertTrue(lastReply().startsWith("What I understood"))
    }

    @Test
    fun aQuestionWithNothingNewToSaveIsStillAnswered() = runBlocking {
        val restated = """{"people":[{"name":"Brother","role":"PLAYER"}],"options":[{"for":"Brother","label":"Take the loan"}]}"""
        fake.enqueue(textReply(Sketches.loanStory)).enqueue(textReply(words))
            .enqueue(textReply(restated)).enqueue(textReply("Thanks for telling me. He may need time, so I'd keep things calm. What would help most right now?"))
        send("My brother wants me to co-sign a loan for his shop.")
        send("Do you think pushing him forces his hand?")
        val reply = lastReply()
        assertTrue(reply, reply.startsWith("Thanks for telling me."))
        assertTrue(reply, !reply.contains("nothing new"))
        assertEquals(4, fake.requests.size)
    }
}
