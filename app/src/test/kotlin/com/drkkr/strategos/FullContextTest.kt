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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A pasted chat is read from start to end (12 Nov): the coach once saw only the first 1,500 characters of a message, so a
 * date confirmed at the end of a long paste ("Saturday it is") never reached the advice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullContextTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val start = Scenario(id = "date", title = "Date", description = "Planning a date with Anu.")

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        db.scenarioDao().upsert(ScenarioEntity("date", "Date", "DRAFT", 1, 1L, 1L, "{}"))
        conversations = ConversationRepository(db)
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private val sketch = """{"goal":"A good first date","archetype":"COORDINATION","trust_delta":1,"tension_delta":0,
      "people":[{"name":"You","role":"player"},{"name":"Anu","role":"player","wants":"an easy, fun evening"}],
      "options":[
        {"who":"You","do":"Plan the evening and share the details","posture":"repair"},
        {"who":"You","do":"Keep it loose and see what happens","posture":"boundary"},
        {"who":"You","do":"Cancel at the last minute","posture":"reactive"},
        {"who":"Anu","do":"Look forward to it","posture":"receptive"},
        {"who":"Anu","do":"Go quiet","posture":"defensive"}],
      "dims":["LONG_TERM_VALUE","RELATIONSHIPS","COST"],
      "outcomes":[
        {"when":["Plan the evening and share the details","Look forward to it"],"for_you":[8,9,4],"for_them":[8,9,4]},
        {"when":["Plan the evening and share the details","Go quiet"],"for_you":[5,5,4],"for_them":[5,5,4]},
        {"when":["Keep it loose and see what happens","Look forward to it"],"for_you":[6,6,5],"for_them":[6,6,5]},
        {"when":["Keep it loose and see what happens","Go quiet"],"for_you":[4,4,5],"for_them":[4,4,5]},
        {"when":["Cancel at the last minute","Look forward to it"],"for_you":[2,1,6],"for_them":[2,1,6]},
        {"when":["Cancel at the last minute","Go quiet"],"for_you":[2,1,6],"for_them":[2,1,6]}]}"""

    /** A long pasted chat, oldest first, whose last lines settle the day. */
    private val pastedChat = buildString {
        repeat(60) { i -> append("[10/11 ${10 + i % 10}:${10 + i % 50}] Anu: message $i about where to go and what to eat\n") }
        append("[11/11 21:02] Me: so Saturday works?\n")
        append("[11/11 21:03] Anu: Yes! Saturday it is, see you at 7\n")
    }

    private fun controllerWith(llm: EngineEchoLlm) = CounselController(scope, CounselDeps(
        llm = llm, systemPrompt = { "unused" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
        conversations = conversations, changeSets = ChangeSetRepository(db), memory = MemoryRepository(db),
        settings = SettingsRepository(db).apply { runBlocking { setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION) } },
        whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true,
        trajectory = TrajectoryRepository(db, ScenarioRepository(db, null)),
        journal = JournalRepository(db, XorBase64TestCipher(), MemoryRepository(db), conversations)
    ), CommittingGateway(start))

    private fun text(req: com.drkkr.strategos.llm.LlmRequest) =
        req.input.joinToString("\n") { ((it as JsonObject)["content"] as? JsonPrimitive)?.content.orEmpty() }

    @Test
    fun theCoachReadsALongPasteToItsLastLine() = runBlocking {
        assertTrue("the paste is longer than the old 1,500-character cut", pastedChat.length > 3_000)
        val llm = EngineEchoLlm(listOf(sketch))
        val controller = controllerWith(llm)
        controller.bindScenario("date").join()
        controller.send(pastedChat)!!.join(); controller.awaitIdle()
        val coach = llm.requests.filter { it.purpose == "COACH" }
        assertTrue("a coach call ran: ${llm.requests.map { it.purpose }}", coach.isNotEmpty())
        assertTrue("the coach saw the confirmation at the end", text(coach.first()).contains("Saturday it is, see you at 7"))
        assertTrue("and the start", text(coach.first()).contains("message 0 about"))
        assertTrue("the extraction saw it too", text(llm.requests.first { it.purpose == "TURN" }).contains("Saturday it is"))
    }

    @Test
    fun onTheNextTurnTheCoachStillHasThePasteInFull() = runBlocking {
        val llm = EngineEchoLlm(listOf(sketch, sketch))
        val controller = controllerWith(llm)
        controller.bindScenario("date").join()
        controller.send(pastedChat)!!.join(); controller.awaitIdle()
        val before = llm.requests.count { it.purpose == "COACH" }
        controller.send("What should I wear?")!!.join(); controller.awaitIdle()
        val second = llm.requests.filter { it.purpose == "COACH" }.drop(before)
        assertTrue("a second coach call ran", second.isNotEmpty())
        val seen = text(second.first())
        assertTrue("the earlier paste is there whole", seen.contains("message 0 about") && seen.contains("Saturday it is, see you at 7"))
        assertTrue(seen.contains(OneShotTurn.SAID_HEADER.trim()))
    }

    @Test
    fun wholeKeepsEveryRealMessageAndOnlyShortensAnAbsurdPaste_keepingItsEnd() {
        assertEquals(pastedChat, OneShotTurn.whole(pastedChat))
        val huge = "start of it\n" + "filler line\n".repeat(10_000) + "the newest line: Saturday it is\n"
        val cut = OneShotTurn.whole(huge, max = 2_000)
        assertTrue(cut.length <= 2_000)
        assertTrue(cut.startsWith("start of it"))
        assertTrue(cut.trimEnd().endsWith("the newest line: Saturday it is"))
    }

    @Test
    fun everythingSaidIsInFullOldestFirst_andDropsTheOldestOnlyPastTheBudget() {
        val said = listOf("first " + "a".repeat(2_000), "second", "third " + "b".repeat(1_000))
        assertEquals(said, OneShotTurn.everythingSaid(said))
        assertEquals(listOf("second", said[2]), OneShotTurn.everythingSaid(said, budget = 1_500))
        assertEquals(emptyList<String>(), OneShotTurn.everythingSaid(listOf(" ", "")))
    }
}
