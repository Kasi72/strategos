package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.*
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.SettingEntity
import com.drkkr.strategos.data.repo.*
import com.drkkr.strategos.data.secure.XorBase64TestCipher
import com.drkkr.strategos.fixtures.FakeLlmClient
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.textReply
import com.drkkr.strategos.fixtures.Fx
import com.drkkr.strategos.fixtures.Sketches
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArgonControllerTest {
    private lateinit var db: StrategosDatabase
    private lateinit var controller: CounselController
    private lateinit var conversations: ConversationRepository
    private lateinit var journal: JournalRepository
    private lateinit var memory: MemoryRepository
    private val fake = FakeLlmClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        val scenarios = ScenarioRepository(db, null).also { it.load() }
        val saved = (scenarios.save(Fx.loanFamily()) as SaveResult.Success).saved
        conversations = ConversationRepository(db)
        memory = MemoryRepository(db)
        val settings = SettingsRepository(db).apply { setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION) }
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
        val trajectory = TrajectoryRepository(db, scenarios)
        journal = JournalRepository(db, XorBase64TestCipher(), memory, conversations, aiEnabled = { true })
        val screener = JournalScreenerService(fake, journal, scenarios, trajectory, { true to "deepseek-chat" }, scope)
        controller = CounselController(scope, CounselDeps(
            llm = fake, systemPrompt = { "unused" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
            conversations = conversations, changeSets = ChangeSetRepository(db), memory = memory, settings = settings,
            whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true,
            trajectory = trajectory, journal = journal, journalScreener = screener
        ), CommittingGateway(saved))
        controller.bindScenario("loan").join()
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private suspend fun waitFor(check: suspend () -> Boolean) = withTimeout(5_000) { while (!check()) delay(20) }
    private suspend fun send(text: String) { controller.send(text)!!.join(); controller.awaitIdle() }
    private fun texts() = controller.state.value.items.filterIsInstance<ChatItem.UserText>().map { it.text }

    private val high = """{"mood_tag":"ANXIOUS","impacts":[{"scenario_id":"loan","significance":"HIGH","habit_or_trigger":"Silence",
        "insight_headline":"Silence triggers urgency","practical_approach":"p","do_this":"Wait a day","dont_do_this":"Don't double-text"}]}"""

    @Test fun TC_CTRL2_01_createAndSwitchNamedThreadsInController() = runBlocking {
        waitFor { controller.state.value.threads.size == 1 }
        val main = controller.state.value.conversationId!!
        assertEquals("Main Thread", controller.state.value.threads.single().title)
        fake.enqueue(textReply(Sketches.ASK))
        controller.createNamedThread("Holiday Boundary", "FAMILY").join()
        waitFor { controller.state.value.threads.size == 2 }
        val holiday = controller.state.value.conversationId!!
        assertNotEquals(main, holiday)
        send("About the holiday")
        waitFor { texts() == listOf("About the holiday") }

        controller.selectThread(main).join()
        waitFor { controller.state.value.conversationId == main && texts().isEmpty() }
        assertEquals(setOf("Main Thread", "Holiday Boundary"), controller.state.value.threads.map { it.title }.toSet())
        assertEquals(2, conversations.listThreads("loan").size)
        assertEquals("loan", conversations.get(main)!!.scenarioId)           // one shared project
    }

    @Test fun TC_CTRL2_02_overviewStateExposesTrajectoryAndPostNotes() = runBlocking {
        fake.enqueue(textReply(high))
        controller.recordJournalEntry("I hate when they go silent", "ANXIOUS", "loan")
        waitFor { controller.state.value.postNotes.isNotEmpty() }
        assertTrue(controller.state.value.postNotes.single().insightHeadline.startsWith("Based on your journal entry"))
        controller.submitQuickCheck("DID_AS_SUGGESTED", "DE_ESCALATED", "GROUNDED", "ok").join()
        waitFor { controller.state.value.trajectory?.trustScore == 58.0 }
        assertEquals(1, controller.state.value.trajectory!!.consistencyStreak)
        waitFor { controller.state.value.trajectoryPoints.any { it.source == "QUICK_CHECK" } }
    }

    @Test fun TC_CTRL2_03_applyPostNoteFromController() = runBlocking {
        fake.enqueue(textReply(high))
        // >= 2 words so the Phase G.5 trivial-entry pre-filter screens it; the post-note content comes from the reply.
        controller.recordJournalEntry("I keep checking my phone", "ANXIOUS", "loan")
        waitFor { controller.state.value.postNotes.isNotEmpty() }
        val id = controller.state.value.postNotes.single().id
        controller.applyJournalPostNote(id).join()
        assertTrue(db.journalDao().getPostNote(id)!!.applied)
        waitFor { controller.state.value.postNotes.isEmpty() }
        assertTrue(memory.open("loan").any { it.kind == "PREFERENCE" && it.text.contains("DO Wait a day") })

        fake.enqueue(textReply(Sketches.ASK))
        send("Next question")
        val sent = fake.requests.last().input.toString()
        assertTrue(sent, sent.contains("DO Wait a day"))
    }

    @Test fun TC_CTRL2_04_submitQuickCheckFromController() = runBlocking {
        controller.submitQuickCheck("DID_AS_SUGGESTED", "DE_ESCALATED", "GROUNDED", "Went well").join()
        assertEquals(58.0, controller.state.value.trajectory!!.trustScore, 0.0)
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun TC_CTX2_04_journalSafetyKeywordShowsSupportCardImmediately() = runBlocking {
        fake.enqueue(FakeLlmClient.networkDown())
        val r = controller.recordJournalEntry("I want to end my life", "DRAINED", null)!!
        assertTrue(r.safetyTriggered)
        assertTrue(controller.state.value.supportVisible)
        assertEquals("Recorded privately 🍃", controller.state.value.journalAck)
        waitFor { db.journalDao().getEntry(r.entryId)!!.syncStatus == "PENDING_SCREEN" }
    }
}
