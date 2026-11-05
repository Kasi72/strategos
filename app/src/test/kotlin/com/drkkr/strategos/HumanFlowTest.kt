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
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.GuidanceCodec
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.human.BannedWords
import com.drkkr.strategos.domain.human.HumanizedView
import com.drkkr.strategos.domain.human.TacticId
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Human Layer, full turns through the Argon flow (extraction -> engine -> coach). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HumanFlowTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var settings: SettingsRepository
    private lateinit var trajectory: TrajectoryRepository
    private lateinit var journal: JournalRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val start = Scenario(id = "raise", title = "Raise", description = "I want to ask my manager for a raise.")

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        db.scenarioDao().upsert(ScenarioEntity("raise", "Raise", "DRAFT", 1, 1L, 1L, "{}"))
        conversations = ConversationRepository(db)
        settings = SettingsRepository(db).apply { setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION) }
        val scenarios = ScenarioRepository(db, null)
        trajectory = TrajectoryRepository(db, scenarios)
        journal = JournalRepository(db, XorBase64TestCipher(), MemoryRepository(db), conversations)
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
        {"when":["Threaten to leave","Put it off again"],"for_you":[2,1,6],"for_them":[4,3,5]}],
      "negotiation":{"if_no_deal":"I stay and keep looking","lowest_acceptable":"a 5 percent raise","ideal":"10 percent","opening_ask":"10 percent"},
      "relationship":"manager","power":{"relative":"they_higher"},"deadline":"none","status_quo_for_them":"keeps_something",
      "option_tags":{"Ask with a plan and a date":["asks_them_to_give_up","public","corrects_them"]}}"""

    private fun controllerWith(llm: com.drkkr.strategos.llm.LlmClient, gateway: CommittingGateway) = CounselController(scope, CounselDeps(
        llm = llm, systemPrompt = { "unused" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
        conversations = conversations, changeSets = ChangeSetRepository(db), memory = MemoryRepository(db), settings = settings,
        whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true, trajectory = trajectory, journal = journal
    ), gateway)

    @Test
    fun TC_HL_VIEW_12_aFullTurnSavesTheSketchAndNothingFromTheHumanisedView() = runBlocking {
        val gateway = CommittingGateway(start)
        val llm = EngineEchoLlm(listOf(sketch))
        val controller = controllerWith(llm, gateway)
        controller.bindScenario("raise").join()
        controller.send("I want to ask my manager Priya for a raise.")!!.join(); controller.awaitIdle()

        val saved = gateway.inner.scenario!!
        // What the sketch alone would have saved, through the same convert -> normalise -> validate -> apply path.
        val ops = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse(sketch)!!, start).ops, start)
        val v = ChangeSetValidator.validate(start, ops)
        val accepted = v.accepted.map { it.op }
        val expected = ChangeSetApplier.apply(start, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario

        // ids are fresh UUIDs on every apply, so compare by names and labels
        fun values(s: Scenario) = s.payoffs.map { e ->
            Triple(s.stakeholders.first { it.id == e.stakeholderId }.name,
                e.actionProfile.entries.map { (p, a) -> s.stakeholders.first { it.id == p }.name to s.actions.first { it.id == a }.label }.sortedBy { it.first },
                e.payoff.values)
        }.sortedBy { it.toString() }
        assertEquals(6 * 2, saved.payoffs.size)
        assertEquals("the saved payoffs are the sketch's, untouched by the view", values(expected), values(saved))
        // ... and the view WOULD have changed them, so this test can tell if it ever leaks (face cost, loss aversion).
        assertNotNull(saved.humanContext)
        assertEquals("manager", saved.humanContext!!.relationship)
        assertNotSame("the view really does differ from what is saved", saved, HumanizedView.apply(saved))
        assertTrue("calls: ${llm.requests.map { it.purpose }}, reply: ${conversations.messages(controller.state.value.conversationId!!).last().text.take(200)}", llm.requests.any { it.purpose == "COACH" })
    }

    @Test
    fun TC_HL_DIR_05_aFullTurnGivesTheCoachTheDeliveryHintsOnly_andThePillIsUnchanged() = runBlocking {
        val gateway = CommittingGateway(start)
        val llm = EngineEchoLlm(listOf(sketch))
        val controller = controllerWith(llm, gateway)
        controller.bindScenario("raise").join()
        controller.send("I want to ask my manager Priya for a raise.")!!.join(); controller.awaitIdle()

        val directive = llm.lastDirective!!
        val delivery = directive["delivery"]!!.jsonObject
        assertEquals("a correction of a higher-status person: private", "PRIVATE", delivery.getValue("channel").jsonPrimitive.content)
        val coachInput = llm.requests.first { it.purpose == "COACH" }.input.toString()
        assertFalse(BannedWords.containedIn(directive.toString()))
        for (id in TacticId.values()) assertFalse(id.name, coachInput.contains(id.name, ignoreCase = true))
        // the pill is still: move · pattern · Robust|Fragile
        val last = conversations.messages(controller.state.value.conversationId!!).last { it.role == "ASSISTANT" }
        val pill = GuidanceCodec.decode(last.payloadJson!!)!!.pill
        assertTrue(pill, pill.startsWith(directive.getValue("move_to_implement").jsonPrimitive.content))
        assertTrue(pill, pill.endsWith("Robust") || pill.endsWith("Fragile"))
        assertFalse(pill, delivery.getValue("tactics").jsonArray.any { pill.contains(it.jsonPrimitive.content) })
    }

    @Test
    fun TC_HL_COACH_03_aReplyThatNamesALawIsNeverShown_theEnginesOwnTextIs() = runBlocking {
        val gateway = CommittingGateway(start)
        val llm = EngineEchoLlm(listOf(sketch), exactWords = "As Law 31 says, offer her two options.")
        val controller = controllerWith(llm, gateway)
        controller.bindScenario("raise").join()
        controller.send("I want to ask my manager Priya for a raise.")!!.join(); controller.awaitIdle()
        assertEquals("tried twice, then gave up", 2, llm.requests.count { it.purpose == "COACH" })
        val reply = conversations.messages(controller.state.value.conversationId!!).last().text
        assertFalse(reply, reply.contains("Law 31"))
        assertTrue("falls back to the engine's own text: $reply", reply.contains("Options to consider"))
    }

    /** A furious first message where every calm move is irreversible, so whichever the engine picks must wait. */
    private val hotSketch = sketch.replace(
        "\"option_tags\":{\"Ask with a plan and a date\":[\"asks_them_to_give_up\",\"public\",\"corrects_them\"]}",
        "\"your_state\":\"furious\",\"option_tags\":{\"Ask with a plan and a date\":[\"irreversible\"],\"Hold the line until the review\":[\"irreversible\"]}"
    )

    @Test
    fun TC_HL_HOT_06_aHeldMoveIsRememberedUntilTheyReportHowItWent() = runBlocking {
        val gateway = CommittingGateway(start)
        val reported = hotSketch.replace("\"your_state\":\"furious\"", "\"your_state\":\"calm\"").dropLast(1) + ""","counterpart_observed_reaction":"softened","user_last_move_posture":"repair"}"""
        // the stand-in model answers EVERY non-coach call from this list in order, the short red-team check included
        val llm = EngineEchoLlm(listOf(hotSketch, "Is that fair?", hotSketch, "Is that fair?", reported, "Is that fair?"))
        val controller = controllerWith(llm, gateway)
        controller.bindScenario("raise").join()

        controller.send("I'm furious. I want to tell my manager Priya exactly what I think about the raise.")!!.join(); controller.awaitIdle()
        val first = llm.lastDirective!!
        assertEquals("AFTER_COOLING", first["delivery"]!!.jsonObject.getValue("timing").jsonPrimitive.content)
        assertNull("nothing pending yet when the turn starts", first["pending_cooling"])
        val held = gateway.inner.scenario!!.humanContext!!.pendingCooling
        assertEquals(first.getValue("move_to_implement").jsonPrimitive.content, held)

        // next message, no reaction reported: the live context carries it and so does the coach's directive
        controller.send("Still thinking about it.")!!.join(); controller.awaitIdle()
        val secondTurn = llm.requests.filter { it.purpose == "TURN" }[1].input.toString()
        assertTrue("the live context says what is pending", secondTurn.contains("pending_cooling"))
        assertEquals("the coach is told what was held last time", held, llm.lastDirective!!["pending_cooling"]?.jsonPrimitive?.content)
        // still hot and the pick changed, so the newer held move replaces it; something is still pending
        assertNotNull(gateway.inner.scenario!!.humanContext!!.pendingCooling)

        // a turn that reports how it went clears it
        controller.send("I sent it the next morning and she softened.")!!.join(); controller.awaitIdle()
        assertNull(gateway.inner.scenario!!.humanContext!!.pendingCooling)
        assertNull(llm.lastDirective!!["pending_cooling"])
    }
}
