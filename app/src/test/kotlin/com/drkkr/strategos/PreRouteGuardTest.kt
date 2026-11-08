package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.agent.CounselController
import com.drkkr.strategos.agent.CounselDeps
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.db.entities.SettingEntity
import com.drkkr.strategos.data.repo.*
import com.drkkr.strategos.data.secure.XorBase64TestCipher
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.EngineEchoLlm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Main's pre-routing call (merged 8 Nov) may answer a message before the full extraction. These are the guards the merge put
 * on it: a message the on-device safety screen flags never takes a shortcut, and "too thin" only asks on an empty thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreRouteGuardTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val start = Scenario(id = "raise", title = "Raise", description = "I want to ask my manager for a raise.")

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        db.scenarioDao().upsert(ScenarioEntity("raise", "Raise", "DRAFT", 1, 1L, 1L, "{}"))
        conversations = ConversationRepository(db)
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private val sketch = """{"goal":"A fair raise","archetype":"ULTIMATUM","trust_delta":0,"tension_delta":2,
      "people":[{"name":"You","role":"player"},{"name":"Priya","role":"player","wants":"a calm team"}],
      "options":[
        {"who":"You","do":"Ask with a plan and a date","posture":"repair"},
        {"who":"You","do":"Hold the line until the review","posture":"boundary"},
        {"who":"You","do":"Threaten to leave","posture":"reactive"},
        {"who":"Priya","do":"Engage with the request","posture":"receptive"},
        {"who":"Priya","do":"Put it off again","posture":"defensive"}],
      "dims":["LONG_TERM_VALUE","RELATIONSHIPS","COST"],
      "outcomes":[
        {"when":["Ask with a plan and a date","Engage with the request"],"for_you":[8,8,4],"for_them":[7,8,3]},
        {"when":["Ask with a plan and a date","Put it off again"],"for_you":[5,5,3],"for_them":[5,6,3]},
        {"when":["Hold the line until the review","Engage with the request"],"for_you":[7,6,4],"for_them":[6,6,3]},
        {"when":["Hold the line until the review","Put it off again"],"for_you":[5,4,3],"for_them":[5,6,3]},
        {"when":["Threaten to leave","Engage with the request"],"for_you":[4,2,5],"for_them":[3,3,6]},
        {"when":["Threaten to leave","Put it off again"],"for_you":[2,1,6],"for_them":[4,3,5]}]}"""

    private val thin = """{"route":"thin","clarify":["What's the situation you're facing?"]}"""

    private fun controllerWith(llm: EngineEchoLlm) = CounselController(scope, CounselDeps(
        llm = llm, systemPrompt = { "unused" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
        conversations = conversations, changeSets = ChangeSetRepository(db), memory = MemoryRepository(db),
        settings = SettingsRepository(db).apply { runBlocking { setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION) } },
        whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true,
        trajectory = TrajectoryRepository(db, ScenarioRepository(db, null)),
        journal = JournalRepository(db, XorBase64TestCipher(), MemoryRepository(db), conversations)
    ), CommittingGateway(start))

    @Test
    fun aMessageTheSafetyScreenFlagsSkipsThePreRouterAndGoesToTheFullExtraction() = runBlocking {
        val llm = EngineEchoLlm(listOf(sketch)).apply { preRouteReply = thin }
        val controller = controllerWith(llm)
        controller.bindScenario("raise").join()
        controller.send("He hits me.")!!.join(); controller.awaitIdle()
        assertTrue("no pre-route call: ${llm.preRoutes.size}", llm.preRoutes.isEmpty())
        assertEquals("TURN", llm.requests.first().purpose)
    }

    @Test
    fun aThinFirstMessageIsAnsweredWithOneQuestion_beforeAnyExtraction() = runBlocking {
        val llm = EngineEchoLlm(listOf(sketch)).apply { preRouteReply = thin }
        val controller = controllerWith(llm)
        controller.bindScenario("raise").join()
        controller.send("hi")!!.join(); controller.awaitIdle()
        assertEquals(1, llm.preRoutes.size)
        assertTrue("no extraction: ${llm.requests.map { it.purpose }}", llm.requests.isEmpty())
        val reply = conversations.messages(controller.state.value.conversationId!!).last { it.role == "ASSISTANT" }.text
        assertTrue(reply, reply.contains("What's the situation you're facing?"))
    }

    @Test
    fun midThreadAThinVerdictDoesNotRestartTheConversation() = runBlocking {
        val llm = EngineEchoLlm(listOf(sketch))
        val controller = controllerWith(llm)
        controller.bindScenario("raise").join()
        controller.send("I want to ask my manager Priya for a raise.")!!.join(); controller.awaitIdle()
        val before = llm.requests.count { it.purpose == "TURN" }
        llm.preRouteReply = thin
        controller.send("ok")!!.join(); controller.awaitIdle()
        assertEquals("the second message still went through the extraction", before + 1, llm.requests.count { it.purpose == "TURN" })
        val reply = conversations.messages(controller.state.value.conversationId!!).last { it.role == "ASSISTANT" }.text
        assertTrue(reply, !reply.contains("What's the situation you're facing?"))
    }
}
