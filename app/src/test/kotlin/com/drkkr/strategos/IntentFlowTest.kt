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
import com.drkkr.strategos.domain.intent.IdentifyGaps
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.EngineEchoLlm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Intent Dispatch ID2.4, whole turns: the D3 rule, the one question, and Kotlin (not Call 1) deciding readiness. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntentFlowTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var settings: SettingsRepository
    private lateinit var trajectory: TrajectoryRepository
    private lateinit var journal: JournalRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val start = Scenario(id = "s", title = "Talk", description = "A situation I need help with, described in a few words.")

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        db.scenarioDao().upsert(ScenarioEntity("s", "Talk", "DRAFT", 1, 1L, 1L, "{}"))
        conversations = ConversationRepository(db)
        settings = SettingsRepository(db).apply { setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION) }
        trajectory = TrajectoryRepository(db, ScenarioRepository(db, null))
        journal = JournalRepository(db, XorBase64TestCipher(), MemoryRepository(db), conversations)
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun controllerWith(llm: com.drkkr.strategos.llm.LlmClient, gateway: CommittingGateway) = CounselController(scope, CounselDeps(
        llm = llm, systemPrompt = { "unused" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
        conversations = conversations, changeSets = ChangeSetRepository(db), memory = MemoryRepository(db), settings = settings,
        whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true, trajectory = trajectory, journal = journal
    ), gateway)

    /** Not a negotiation: two options for You, and nothing known about what Meera wants (a gap). */
    private fun spouse(enough: Boolean) = """{"goal":"Keep money talk private","trust_delta":0,"tension_delta":0,
      "people":[{"name":"You","role":"player"},{"name":"Meera","role":"player"}],
      "options":[
        {"who":"You","do":"Tell Meera calmly what you want kept private","posture":"repair"},
        {"who":"You","do":"Pause the topic until tomorrow","posture":"boundary"},
        {"who":"Meera","do":"Listen and agree","posture":"receptive"},
        {"who":"Meera","do":"Get defensive","posture":"defensive"}],
      "dims":["WELLBEING","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[
        {"when":["Tell Meera calmly what you want kept private","Listen and agree"],"for_you":["++","++","++"],"for_them":["+","+","+"]},
        {"when":["Tell Meera calmly what you want kept private","Get defensive"],"for_you":["0","-","0"],"for_them":["0","-","0"]},
        {"when":["Pause the topic until tomorrow","Listen and agree"],"for_you":["+","+","+"],"for_them":["0","0","+"]},
        {"when":["Pause the topic until tomorrow","Get defensive"],"for_you":["0","0","0"],"for_them":["0","0","0"]}],
      "enough_to_recommend":$enough,"clarifying_questions":${if (enough) "[]" else "[\"What usually happens when you raise it?\"]"},
      "relationship":"spouse"}"""

    /** A negotiation whose best move names a figure; the fallback and the ideal are known, the walk-away is not. */
    private val vendor = """{"goal":"Buy the CRM","trust_delta":0,"tension_delta":0,
      "people":[{"name":"You","role":"player"},{"name":"Karthik","role":"player"}],
      "options":[
        {"who":"You","do":"Offer 45000 with onboarding included","posture":"repair"},
        {"who":"You","do":"Ask for a week","posture":"boundary"},
        {"who":"Karthik","do":"Accept","posture":"receptive"},
        {"who":"Karthik","do":"Refuse","posture":"defensive"}],
      "dims":["COST","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[
        {"when":["Offer 45000 with onboarding included","Accept"],"for_you":["++","++","++"],"for_them":["+","+","+"]},
        {"when":["Offer 45000 with onboarding included","Refuse"],"for_you":["0","0","0"],"for_them":["0","0","0"]},
        {"when":["Ask for a week","Accept"],"for_you":["0","0","0"],"for_them":["0","0","0"]},
        {"when":["Ask for a week","Refuse"],"for_you":["-","-","-"],"for_them":["0","0","0"]}],
      "negotiation":{"if_no_deal":"go with another vendor","ideal":"40000"},
      "relationship":"vendor"}"""

    private class Turn(val llm: EngineEchoLlm, val reply: String) { val directive: JsonObject? get() = llm.lastDirective }

    private suspend fun turn(extraction: String, message: String): Turn {
        val llm = EngineEchoLlm(listOf(extraction))
        val controller = controllerWith(llm, CommittingGateway(start))
        controller.bindScenario("s").join()
        controller.send(message)!!.join(); controller.awaitIdle()
        val reply = conversations.messages(controller.state.value.conversationId!!).last { it.role == "ASSISTANT" }.text
        return Turn(llm, reply)
    }

    @Test fun TC_ID_GAP_03_notANegotiation_theAdviceComesWithOneQuestionAtTheEnd() = runBlocking {
        val t = turn(spouse(true), "My wife told her sister about our loan. I want it kept private between us.")
        val d = assertNotNull(t.directive).let { t.directive!! }
        assertNotNull("the pick is sent", d["move_to_implement"])
        val q = d["next_question"]!!.jsonPrimitive.content
        assertEquals("What does Meera want most here, and what worries them?", q)
        assertTrue(t.reply, t.reply.trimEnd().endsWith(q))
        assertEquals("one question in the reply's own lines", 1, t.reply.count { it == '?' })
    }

    @Test fun TC_ID_GAP_04_aNegotiationMoveWithAFigureAndNoWalkAwayAsksFirst() = runBlocking {
        val t = turn(vendor, "Karthik quoted a lot for the CRM. I have another vendor and I'd love to pay 40000.")
        assertFalse("no pick sent to the coach this turn", t.llm.requests.any { it.purpose == "COACH" })
        assertTrue(t.reply, t.reply.contains(IdentifyGaps.walkAwayQuestion().text))
        assertEquals(1, t.reply.count { it == '?' })
    }

    @Test fun TC_ID_GAP_06_callOnesFlagNoLongerDecides_theDirectiveIsTheSame() = runBlocking {
        val msg = "My wife told her sister about our loan. I want it kept private between us."
        val yes = turn(spouse(true), msg)
        db.close(); setUp()                                   // a fresh thread, so "already asked" doesn't differ between the two
        val no = turn(spouse(false), msg)
        assertNotNull("answered, not held back", no.directive)
        assertEquals(yes.directive, no.directive)
        assertFalse(no.reply.contains("What usually happens when you raise it?"))
    }

    /** A second turn that asks a hypothetical, with Call 1 putting the moves in the main fields too (seen live at CP-I2). */
    private val whatIfTurn = """{"intent":"what_if","intent_detail":"she says money talk is off limits","message_kind":"situation",
      "people":[{"name":"You","role":"player"},{"name":"Meera","role":"player"}],
      "options":[{"who":"You","do":"Suggest a monthly money check-in","posture":"repair"},{"who":"You","do":"Drop it for now","posture":"boundary"},
        {"who":"Meera","do":"Listen and agree","posture":"receptive"},{"who":"Meera","do":"Get defensive","posture":"defensive"}],
      "dims":["WELLBEING","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[{"when":["Suggest a monthly money check-in","Listen and agree"],"for_you":["++","++","++"],"for_them":["+","+","+"]},
        {"when":["Suggest a monthly money check-in","Get defensive"],"for_you":["0","0","0"],"for_them":["0","0","0"]},
        {"when":["Drop it for now","Listen and agree"],"for_you":["0","0","0"],"for_them":["0","0","0"]},
        {"when":["Drop it for now","Get defensive"],"for_you":["-","-","-"],"for_them":["0","0","0"]}],
      "trust_delta":-10,"tension_delta":10,"relationship":"spouse"}"""

    @Test fun cpi2_aHypotheticalTurnSavesNothingEvenWhenCallOneRepeatsItsMovesInTheMainFields() = runBlocking {
        val gateway = CommittingGateway(start)
        val llm = EngineEchoLlm(listOf(spouse(true), whatIfTurn))
        val controller = controllerWith(llm, gateway)
        controller.bindScenario("s").join()
        controller.send("My wife told her sister about our loan. I want it kept private between us.")!!.join(); controller.awaitIdle()
        val saved = gateway.activeScenario()
        val commits = gateway.commits
        controller.send("What if she says money talk is off limits?")!!.join(); controller.awaitIdle()
        assertEquals("nothing saved on the what-if turn", saved, gateway.activeScenario())
        assertEquals(commits, gateway.commits)
        val d = llm.lastDirective!!
        assertNotNull("the hypothetical ran on a copy", d["what_if"])
        assertEquals("she says money talk is off limits", (d["what_if"] as JsonObject)["if"]!!.jsonPrimitive.content)
    }

    @Test fun cpi2_anIntentTurnMarkedAsAFollowUpStillRunsTheEngine() = runBlocking {
        val followUp = spouse(true).replace("\"relationship\":\"spouse\"}", "\"relationship\":\"spouse\",\"intent\":\"top_options\",\"message_kind\":\"followup_question\"}")
        val gateway = CommittingGateway(start)
        val llm = EngineEchoLlm(listOf(spouse(true), followUp))
        val controller = controllerWith(llm, gateway)
        controller.bindScenario("s").join()
        controller.send("My wife told her sister about our loan. I want it kept private between us.")!!.join(); controller.awaitIdle()
        val coachCalls = llm.requests.count { it.purpose == "COACH" }
        controller.send("What are my top options here?")!!.join(); controller.awaitIdle()
        assertTrue("the engine ran and the coach got a directive", llm.requests.count { it.purpose == "COACH" } > coachCalls)
        assertNotNull(llm.lastDirective!!["top_options"])
    }

    @Test fun TC_ID_XP_04_theInsightIsShownOncePerConversationPerProject() = runBlocking {
        val gateway = CommittingGateway(start).apply {
            others = listOf(Scenario(id = "other", title = "Holiday plans with Meera", stakeholders = listOf(
                com.drkkr.strategos.domain.model.Stakeholder(id = "u", name = "You"), com.drkkr.strategos.domain.model.Stakeholder(id = "m", name = "Meera"))))
        }
        val llm = EngineEchoLlm(listOf(spouse(true), spouse(true)))
        val controller = controllerWith(llm, gateway)
        controller.bindScenario("s").join()
        controller.send("My wife Meera told her sister about our loan. I want it kept private between us.")!!.join(); controller.awaitIdle()
        val first = llm.lastDirective!!["cross_project"] as JsonObject
        assertEquals("Holiday plans with Meera", first["title"]!!.jsonPrimitive.content)
        assertEquals("the same person (Meera)", first["shared"]!!.jsonPrimitive.content)
        assertTrue(settings.crossProjectShown(controller.state.value.conversationId!!, "other"))
        controller.send("She did it again today.")!!.join(); controller.awaitIdle()
        assertTrue("a second engine turn ran", llm.requests.count { it.purpose == "COACH" } >= 2)
        assertEquals(null, llm.lastDirective!!["cross_project"])
    }
}
