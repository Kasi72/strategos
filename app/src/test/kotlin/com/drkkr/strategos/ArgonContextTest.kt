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
        assertEquals(56.0, ctx.getValue("trajectory").jsonObject.getValue("trust").jsonPrimitive.double, 0.0)
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

    @Test fun TC_CTX2_03_oneShotTurnUpdatesTrajectoryAndCoachGuide() = runBlocking {
        val fake = FakeLlmClient()
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
        val gateway = CommittingGateway(Scenario(id = "loan", title = "Loan", description = "My brother wants me to co-sign a loan."))
        val controller = CounselController(scope, CounselDeps(
            llm = fake, systemPrompt = { "unused" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
            conversations = conversations, changeSets = ChangeSetRepository(db), memory = MemoryRepository(db), settings = settings,
            whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true, trajectory = trajectory, journal = journal
        ), gateway)
        controller.bindScenario("loan").join()
        val withGuide = Sketches.loanStory.dropLast(1) + ""","archetype":"STAG_HUNT","trust_delta":6,"tension_delta":-8,
            "coach_guide":{"what_happened":"They felt cornered.","clear_win_move":"Pause for a day.","exact_words":"Let's talk tomorrow.",
            "do_this":["Give space"],"dont_do_this":["Don't double-text"]}}"""
        fake.enqueue(textReply(withGuide))

        controller.send("My brother wants me to co-sign a loan.")!!.join(); controller.awaitIdle()

        val req = fake.requests.first()
        assertTrue(req.jsonObjectMode)
        assertTrue(req.input.first().jsonObject.getValue("content").jsonPrimitive.content.startsWith("\nYou are Strategos Counsel") ||
            req.input.first().jsonObject.getValue("content").jsonPrimitive.content.contains("Strategos Counsel"))
        val points = trajectory.points("loan")
        assertEquals("THREAD_TURN", points.single().source)
        assertEquals(56.0, points.single().trustScore, 0.0); assertEquals(42.0, points.single().tensionScore, 0.0)
        assertEquals("STAG_HUNT", points.single().activeArchetype)
        val reply = conversations.messages(controller.state.value.conversationId!!).last().text
        assertTrue(reply, reply.contains("Do this:") && reply.contains("Don't do this:") && reply.contains("Clear-win move: Pause for a day."))
    }
}
