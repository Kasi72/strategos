package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.agent.ContextAssembler
import com.drkkr.strategos.agent.CounselController
import com.drkkr.strategos.agent.CounselDeps
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.JournalInsightPostNoteEntity
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.db.entities.SettingEntity
import com.drkkr.strategos.data.repo.*
import com.drkkr.strategos.data.secure.XorBase64TestCipher
import com.drkkr.strategos.domain.agent.TokenEstimator
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.FakeLlmClient
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.textReply
import com.drkkr.strategos.fixtures.Sketches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArgonContextTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var settings: SettingsRepository
    private lateinit var scenarios: ScenarioRepository
    private lateinit var trajectory: TrajectoryRepository
    private lateinit var journal: JournalRepository
    private lateinit var assembler: ContextAssembler
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        db.scenarioDao().upsert(ScenarioEntity("loan", "Loan", "DRAFT", 1, 1L, 1L, "{}"))
        conversations = ConversationRepository(db)
        settings = SettingsRepository(db).apply { setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION) }
        scenarios = ScenarioRepository(db, null)
        trajectory = TrajectoryRepository(db, scenarios)
        journal = JournalRepository(db, XorBase64TestCipher(), MemoryRepository(db), conversations)
        assembler = ContextAssembler({ "STATIC PROMPT" }, conversations, ChangeSetRepository(db), MemoryRepository(db), settings,
            { Scenario(id = "loan", title = "Loan") }, journal = journal, trajectory = trajectory)
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun role(o: JsonElement) = o.jsonObject.getValue("role").jsonPrimitive.content
    private fun content(o: JsonElement) = o.jsonObject.getValue("content").jsonPrimitive.content

    @Test fun TC_CTX2_01_prefixOrderStaticFirstDynamicLast() = runBlocking {
        val a = conversations.createNamedThread("loan", "Thread A")
        val b = conversations.createNamedThread("loan", "Thread B")
        conversations.saveSummary(b.id, "Talked about the weekend plan and who drives.", 0)
        conversations.append(a.id, "t1", MessageRole.USER, "earlier question")
        conversations.append(a.id, "t1", MessageRole.ASSISTANT, "earlier answer")
        conversations.append(a.id, "t2", MessageRole.USER, "newest message")
        journal.recordEntry("x", "ANXIOUS", "loan")
        val entry = db.journalDao().allEntries().single()
        journal.addPostNote(JournalInsightPostNoteEntity("n1", entry.id, "loan", "HIGH", "Silence", "Based on your journal entry: x", "p", "Wait a day", "Don't double-text", createdAt = 1L))
        trajectory.recordTurn("loan", a.id, 6.0, -8.0, "STAG_HUNT")

        val input = assembler.build(a.id, "loan")

        assertEquals("STATIC PROMPT", content(input.first()))
        assertEquals("system", role(input[0])); assertEquals("system", role(input[1]))
        val ctx = Json.parseToJsonElement(content(input[1]).substringAfter("```json\n").substringBeforeLast("\n```")).jsonObject
        assertEquals(52.0, ctx.getValue("trajectory").jsonObject.getValue("trust").jsonPrimitive.double, 0.0)
        assertEquals("Wait a day", ctx.getValue("active_journal_post_notes").jsonArray.single().jsonObject.getValue("do_this").jsonPrimitive.content)
        val sib = ctx.getValue("sibling_thread_summaries").jsonArray.single().jsonObject
        assertEquals("Thread B", sib.getValue("title").jsonPrimitive.content)
        assertTrue(sib.getValue("summary").jsonPrimitive.content.startsWith("Talked about the weekend"))
        assertEquals("newest message", content(input.last())); assertEquals("user", role(input.last()))
        assertEquals(listOf("earlier question", "earlier answer"), input.drop(2).dropLast(1).map(::content))
    }

    @Test fun TC_CTX2_02_enforcesRequestTokenBudget() = runBlocking {
        val a = conversations.createNamedThread("loan", "Thread A")
        repeat(3) { i -> conversations.createNamedThread("loan", "Sibling $i").also { conversations.saveSummary(it.id, "long ".repeat(200), 0) } }
        repeat(15) { i ->
            conversations.append(a.id, "t$i", MessageRole.USER, "word ".repeat(120))
            conversations.append(a.id, "t$i", MessageRole.ASSISTANT, "reply ".repeat(120))
        }
        conversations.append(a.id, "last", MessageRole.USER, "final question")
        val input = assembler.build(a.id, "loan")
        assertTrue(TokenEstimator.estimate(input.joinToString("\n") { content(it) }) <= AgentConfig.MAX_REQUEST_TOKEN_BUDGET + input.size)  // +1 per joined line from rounding
        assertEquals("final question", content(input.last()))
    }

    private fun controllerWith(llm: com.drkkr.strategos.llm.LlmClient): CounselController {
        val gateway = CommittingGateway(Scenario(id = "loan", title = "Loan", description = "My brother wants me to co-sign a loan."))
        return CounselController(scope, CounselDeps(
            llm = llm, systemPrompt = { "unused" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
            conversations = conversations, changeSets = ChangeSetRepository(db), memory = MemoryRepository(db), settings = settings,
            whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true, trajectory = trajectory, journal = journal
        ), gateway)
    }

    private val extraction = Sketches.loanStory.dropLast(1) + ""","archetype":"STAG_HUNT","trust_delta":6,"tension_delta":-8}"""

    @Test fun TC_CTX2_03_oneShotTurnUpdatesTrajectoryAndCoachGuide() = runBlocking {
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
        val llm = com.drkkr.strategos.fixtures.EngineEchoLlm(listOf(extraction))
        val controller = controllerWith(llm)
        controller.bindScenario("loan").join()

        controller.send("My brother wants me to co-sign a loan.")!!.join(); controller.awaitIdle()

        // Call 1: extraction only, small and nearly deterministic, with no advice in its prompt.
        val c1 = llm.requests.first { it.purpose == "TURN" }
        assertTrue(c1.jsonObjectMode); assertEquals(3_000, c1.maxOutputTokens); assertEquals(0.1, c1.temperature, 0.0)
        assertTrue(c1.input.first().jsonObject.getValue("content").jsonPrimitive.content.contains("extraction step"))
        // Call 2: gets the engine's directive and may only implement it.
        val c2 = llm.requests.first { it.purpose == "COACH" }
        assertEquals(600, c2.maxOutputTokens); assertEquals(0.3, c2.temperature, 0.0)
        val directive = llm.lastDirective!!
        val pick = directive.getValue("move_to_implement").jsonPrimitive.content
        assertTrue(pick.isNotBlank())
        assertTrue(directive.getValue("blocked_or_dominated_moves").jsonArray.isNotEmpty())
        // The visible reply implements the engine's pick. Phase G.4: the static "Engine: ..." badge is no longer
        // appended to the chat text (it read as boilerplate); the move still leads the coach reply.
        val reply = conversations.messages(controller.state.value.conversationId!!).last().text
        assertTrue(reply, reply.contains("Clear-win move: Do this: $pick"))
        assertFalse("no engine badge in the chat text", reply.lines().any { it.startsWith("Engine: ") })
        assertFalse(reply.contains("Options to consider"))
        // Trajectory from call 1's deltas, damped.
        val points = trajectory.points("loan")
        assertEquals("THREAD_TURN", points.single().source)
        assertEquals(52.0, points.single().trustScore, 0.0); assertEquals(48.0, points.single().tensionScore, 0.0)
        assertEquals("STAG_HUNT", points.single().activeArchetype)
    }

    @Test fun aReplyThatIgnoresTheEnginePickIsNotShown() = runBlocking {
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
        val llm = com.drkkr.strategos.fixtures.EngineEchoLlm(listOf(extraction), wrongMove = "Send ten messages tonight")
        val controller = controllerWith(llm)
        controller.bindScenario("loan").join()
        controller.send("My brother wants me to co-sign a loan.")!!.join(); controller.awaitIdle()
        assertEquals(2, llm.requests.count { it.purpose == "COACH" })                    // tried twice, then gave up
        val reply = conversations.messages(controller.state.value.conversationId!!).last().text
        assertFalse(reply.contains("Send ten messages tonight"))
        assertTrue("falls back to the engine's own text", reply.contains("Options to consider"))
        assertFalse("no engine badge in the chat text", reply.lines().any { it.startsWith("Engine: ") })
    }
}
