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
import com.drkkr.strategos.domain.negotiation.NegotiationView
import com.drkkr.strategos.fixtures.EngineEchoLlm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Negotiation Numbers, full turns through the Argon flow (extraction -> engine -> coach). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NegotiationFlowTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var settings: SettingsRepository
    private lateinit var trajectory: TrajectoryRepository
    private lateinit var journal: JournalRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val start = Scenario(id = "deal", title = "Deal", description = "A client wants to buy my design work.")

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        db.scenarioDao().upsert(ScenarioEntity("deal", "Deal", "DRAFT", 1, 1L, 1L, "{}"))
        conversations = ConversationRepository(db)
        settings = SettingsRepository(db).apply { setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION) }
        val scenarios = ScenarioRepository(db, null)
        trajectory = TrajectoryRepository(db, scenarios)
        journal = JournalRepository(db, XorBase64TestCipher(), MemoryRepository(db), conversations)
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    /** A seller: walk-away 100k, target 130k, their limit 120k. "Accept 95k" scores best on the outcomes alone. */
    private val sketch = """{"goal":"Sell the design work for a fair price","trust_delta":0,"tension_delta":0,
      "people":[{"name":"You","role":"player"},{"name":"Asha","role":"player","wants":"a good price"}],
      "options":[
        {"who":"You","do":"Accept 95k","posture":"repair"},
        {"who":"You","do":"Counter at 130k","posture":"boundary"},
        {"who":"You","do":"Ask for a week","posture":"boundary"},
        {"who":"Asha","do":"Agree","posture":"receptive"},
        {"who":"Asha","do":"Walk away","posture":"defensive"}],
      "dims":["LONG_TERM_VALUE","RELATIONSHIPS","COST"],
      "outcomes":[
        {"when":["Accept 95k","Agree"],"for_you":[8,9,3],"for_them":[9,8,2]},
        {"when":["Accept 95k","Walk away"],"for_you":[6,7,3],"for_them":[4,5,3]},
        {"when":["Counter at 130k","Agree"],"for_you":[7,6,4],"for_them":[6,6,5]},
        {"when":["Counter at 130k","Walk away"],"for_you":[3,4,4],"for_them":[5,4,3]},
        {"when":["Ask for a week","Agree"],"for_you":[6,6,4],"for_them":[6,6,4]},
        {"when":["Ask for a week","Walk away"],"for_you":[4,5,4],"for_them":[5,5,4]}],
      "negotiation":{"if_no_deal":"another client at 105k","lowest_acceptable":"100k","ideal":"130k","their_limit":"120k"},
      "relationship":"client"}"""

    private fun controllerWith(llm: com.drkkr.strategos.llm.LlmClient, gateway: CommittingGateway) = CounselController(scope, CounselDeps(
        llm = llm, systemPrompt = { "unused" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
        conversations = conversations, changeSets = ChangeSetRepository(db), memory = MemoryRepository(db), settings = settings,
        whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true, trajectory = trajectory, journal = journal
    ), gateway)

    @Test
    fun TC_NG_VIEW_04_aTurnWithAFrameSavesNoWalkAwayLimit() = runBlocking {
        val gateway = CommittingGateway(start)
        val llm = EngineEchoLlm(listOf(sketch))
        val controller = controllerWith(llm, gateway)
        controller.bindScenario("deal").join()
        controller.send("Asha wants my design work. I won't go below 100k, I'd love 130k, I think she can go to 120k. I have another client at 105k.")!!.join()
        controller.awaitIdle()

        val saved = gateway.inner.scenario!!
        assertEquals("100k", saved.negotiationContext?.reservationPoint)
        assertTrue(saved.constraints.toString(), saved.constraints.none { it.id == NegotiationView.CONSTRAINT_ID || it.label.startsWith("Walk-away") })
        assertTrue(saved.constraintAssessments.toString(), saved.constraintAssessments.none { it.constraintId == NegotiationView.CONSTRAINT_ID })

        // ... while the engine did use it: the pick is not the move that crosses the walk-away
        val directive = llm.lastDirective
        assertNotNull("calls: ${llm.requests.map { it.purpose }}", directive)
        assertNotEquals("Accept 95k", directive!!.getValue("move_to_implement").jsonPrimitive.content)
        // ruled out through the walk-away limit in the view (not only the old label backstop)
        val blocked = directive.getValue("blocked_or_dominated_moves").toString()
        assertTrue(blocked, Regex("""Accept 95k[^}]*crosses your walk-away""").containsMatchIn(blocked))
        val coachInput = llm.requests.first { it.purpose == "COACH" }.input.toString()
        assertFalse(coachInput.contains("Walk-away: 100,000"))
        // the deal block reached the coach
        assertTrue(directive.toString(), directive.containsKey("deal"))
    }

    @Test
    fun TC_NG_CHECK_01_aReplyThatCountersBelowTheWalkAwayIsNeverShown_theEnginesTextIs() = runBlocking {
        val gateway = CommittingGateway(start)
        val llm = EngineEchoLlm(listOf(sketch), exactWords = "Counter at 95k and close it today.")
        val controller = controllerWith(llm, gateway)
        controller.bindScenario("deal").join()
        controller.send("Asha wants my design work. I won't go below 100k, I'd love 130k, I think she can go to 120k. I have another client at 105k.")!!.join()
        controller.awaitIdle()
        assertEquals("tried twice, then gave up", 2, llm.requests.count { it.purpose == "COACH" })
        val reply = conversations.messages(controller.state.value.conversationId!!).last { it.role == "ASSISTANT" }.text
        assertFalse(reply, reply.contains("Counter at 95k"))
        // "Accept 95k" may be listed as one of their options or warned against, never offered
        val offered = reply.lines().filter { "Accept 95k" in it && !it.startsWith("• Your options") && !it.startsWith("Not advised") }
        assertTrue(offered.toString(), offered.isEmpty())
        // the engine's own text never offers the option that crosses the walk-away, nor states the walk-away or the fallback
        assertFalse(reply, Regex("""100,000|100k|105,000|105k""").containsMatchIn(reply))
    }
}
