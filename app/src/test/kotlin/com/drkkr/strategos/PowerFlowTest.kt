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
import com.drkkr.strategos.domain.human.power.DeclineNotes
import com.drkkr.strategos.domain.human.power.StrategicCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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

/** Power Mode, full turns through the Argon flow (extraction -> engine -> coach). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PowerFlowTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var settings: SettingsRepository
    private lateinit var trajectory: TrajectoryRepository
    private lateinit var journal: JournalRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val start = Scenario(id = "deal", title = "CRM", description = "I am buying a CRM from a vendor.")

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

    private fun controllerWith(llm: com.drkkr.strategos.llm.LlmClient, gateway: CommittingGateway) = CounselController(scope, CounselDeps(
        llm = llm, systemPrompt = { "unused" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
        conversations = conversations, changeSets = ChangeSetRepository(db), memory = MemoryRepository(db), settings = settings,
        whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true, trajectory = trajectory, journal = journal
    ), gateway)

    /** A buyer: walk-away 40000, ideal 35000, another vendor as the fallback; "Hold firm" scores best on its own. */
    private fun sketch(asks: String = "none") = """{"goal":"Buy the CRM at a fair price","trust_delta":0,"tension_delta":0,
      "people":[{"name":"You","role":"player"},{"name":"Karthik","role":"player"}],
      "options":[
        {"who":"You","do":"Hold firm on the price","posture":"boundary"},
        {"who":"You","do":"Ask for a week to decide","posture":"repair"},
        {"who":"You","do":"Accept the quote today","posture":"reactive"},
        {"who":"Karthik","do":"Lower the price","posture":"receptive"},
        {"who":"Karthik","do":"Hold the quote","posture":"defensive"}],
      "dims":["COST","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[
        {"when":["Hold firm on the price","Lower the price"],"for_you":[8,6,8],"for_them":[5,6,6]},
        {"when":["Hold firm on the price","Hold the quote"],"for_you":[4,5,5],"for_them":[6,5,5]},
        {"when":["Ask for a week to decide","Lower the price"],"for_you":[7,7,7],"for_them":[5,7,6]},
        {"when":["Ask for a week to decide","Hold the quote"],"for_you":[5,6,5],"for_them":[6,6,5]},
        {"when":["Accept the quote today","Lower the price"],"for_you":[3,7,4],"for_them":[8,7,7]},
        {"when":["Accept the quote today","Hold the quote"],"for_you":[2,6,3],"for_them":[9,6,7]}],
      "negotiation":{"if_no_deal":"go with another vendor","lowest_acceptable":"40000","ideal":"35000"},
      "relationship":"vendor","power":{"relative":"equal","your_alternative":"strong","their_alternative":"weak"},
      "user_asks_for":"$asks"}"""

    private var llmRequestsOfLastTurn = ""

    private val message = "I'm buying a CRM from Karthik's company. I won't pay more than 40000, I'd like 35000, and I can go with another vendor."

    private suspend fun turn(powerOn: Boolean, asks: String = "none"): Pair<JsonObject, EngineEchoLlm> {
        val gateway = CommittingGateway(start)
        val llm = EngineEchoLlm(listOf(sketch(asks)))
        val controller = controllerWith(llm, gateway)
        controller.bindScenario("deal").join()
        val cid = controller.state.value.conversationId!!
        settings.setPowerMode(cid, powerOn)
        controller.send(message)!!.join(); controller.awaitIdle()
        assertEquals("the turn stayed in one thread", cid, controller.state.value.conversationId)
        llmRequestsOfLastTurn = llm.requests.filter { it.purpose == "COACH" }.joinToString("\n") { it.input.toString() }
        return (llm.lastDirective ?: error("no coach call: ${llm.requests.map { it.purpose }}")) to llm
    }

    private fun hints(d: JsonObject) = d["delivery"]?.jsonObject?.get("tactics")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private val strategicHints = StrategicCatalog.tactics.map { it.tactic.coachHint.substringBefore("{") }

    @Test
    fun TC_PM_WIRE_01_powerModeAddsAHardballHintAndKeepsTheEnginesPick() = runBlocking {
        val (on, _) = turn(powerOn = true)
        val (off, _) = turn(powerOn = false)
        assertTrue(hints(on).toString(), hints(on).any { h -> strategicHints.any { h.startsWith(it) } })
        assertTrue(hints(on).size <= 3)
        assertEquals("I-H1: the pick is the engine's either way", off.getValue("move_to_implement"), on.getValue("move_to_implement"))
    }

    @Test
    fun TC_PM_WIRE_02_offMeansNoHardballHint() = runBlocking {
        val (off, _) = turn(powerOn = false)
        assertFalse(hints(off).toString(), hints(off).any { h -> strategicHints.any { h.startsWith(it) } })
        assertFalse(conversations.messages(conversations.listThreads("deal").first().id).any { it.uiKind == "POWER_READ" })
    }

    @Test
    fun TC_PM_WIRE_03_aManipulativeAskGetsTheDeclineNoteEvenWithPowerModeOff() = runBlocking {
        val (d, _) = turn(powerOn = false, asks = "false_facts")
        assertEquals(DeclineNotes.of("false_facts"), d["decline_note"]?.jsonPrimitive?.content)
        val (plain, _) = turn(powerOn = false)
        assertFalse(plain.containsKey("decline_note"))
    }

    @Test
    fun TC_PM_READ_04_aPowerReadComesBeforeTheReplyWhenPowerModeIsOn() = runBlocking {
        turn(powerOn = true)
        val msgs = conversations.messages(conversations.listThreads("deal").first().id)
        val read = msgs.indexOfFirst { it.role == "TOOL_RESULT" && it.uiKind == "POWER_READ" }
        val reply = msgs.indexOfLast { it.role == "ASSISTANT" }
        assertTrue(msgs.map { "${it.role}:${it.uiKind}" }.toString(), read in 0 until reply)
        // the card carries laws and names; Call 2 never does
        assertFalse(llmRequestsOfLastTurn, Regex("""\bL\d|48 Laws|Nudge|Greene""").containsMatchIn(llmRequestsOfLastTurn))
    }

    @Test
    fun TC_PM_READ_04b_noPowerReadWhenPowerModeIsOff() = runBlocking {
        turn(powerOn = false)
        assertFalse(conversations.messages(conversations.listThreads("deal").first().id).any { it.uiKind == "POWER_READ" })
    }
}
