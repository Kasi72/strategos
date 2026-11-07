package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.*
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.entities.SettingEntity
import com.drkkr.strategos.data.repo.*
import com.drkkr.strategos.data.secure.XorBase64TestCipher
import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.PlainResult
import com.drkkr.strategos.domain.agent.SketchHuman
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.human.DeliveryTags
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.llm.ChatCompletionsClient
import com.drkkr.strategos.llm.DebugLog
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Live (skipped without DEEPSEEK_API_KEY), kept small on purpose (two turns): companion mode. A message that only thinks aloud
 * saves nothing, so the options stay as they were and the reply restates the move (coach rule 34).
 *   set -a; source .env; set +a; unset XAI_API_KEY; sh ./gradlew :app:testDebugUnitTest --tests "*CompanionLiveTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionLiveTest {
    private val key = System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }

    @Test
    fun thinkingAloudKeepsTheMove() = runBlocking {
        Assume.assumeTrue("DEEPSEEK_API_KEY not set; live run skipped", key != null)
        val db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scenarios = ScenarioRepository(db, null).also { it.load() }
            val saved = (scenarios.save(Scenario(id = "choir", title = "Choir friend")) as SaveResult.Success).saved
            val conversations = ConversationRepository(db)
            val settings = SettingsRepository(db).apply { setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION) }
            db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
            val llm = RecordingLlm(ChatCompletionsClient(keyProvider = { key }))
            val gateway = CommittingGateway(saved)
            val controller = CounselController(scope, CounselDeps(
                llm = llm, systemPrompt = { "unused" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
                conversations = conversations, changeSets = ChangeSetRepository(db), memory = MemoryRepository(db),
                settings = settings, whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true, coach = true,
                trajectory = TrajectoryRepository(db, scenarios), journal = JournalRepository(db, XorBase64TestCipher(), MemoryRepository(db), conversations)
            ), gateway)
            controller.bindScenario("choir").join()
            val log = mutableListOf<String>()
            DebugLog.sink = { m -> if (m.startsWith("HOLD_COURSE")) log += m }
            suspend fun say(text: String): String {
                controller.send(text)!!.join(); controller.awaitIdle()
                assertNull("turn error: ${controller.state.value.error}", controller.state.value.error)
                return conversations.messages(controller.state.value.conversationId!!).last { it.role == MessageRole.ASSISTANT.name }.text
            }
            val first = say("I reconnected with Meera from my college choir after six years. She agreed to meet for coffee when she's next in town in two weeks. I'd like this to become serious. Should I text her this week or wait?")
            val optionsBefore = gateway.activeScenario()!!.actions.map { it.label }.sorted()
            val reply = say("Hmm, I keep going back and forth on this in my head. Part of me thinks I'm overthinking it. I don't know, should I really just do what you said?")
            val call1 = SketchParser.parse(llm.calls.last { it.purpose == "TURN" }.text)!!
            println("COMPANION|kind|${call1.messageKind}")
            println("COMPANION|first|" + first.lineSequence().firstOrNull { l -> PlainResult.MOVE_LABELS.any { l.startsWith(it) } })
            println("COMPANION|reply|" + reply.replace("\n", " ⏎ "))
            println("COMPANION|calls|${llm.calls.size}")
            assertEquals("thinking_aloud", call1.messageKind)
            assertTrue("logged $log", log.isNotEmpty())
            assertEquals("nothing saved", optionsBefore, gateway.activeScenario()!!.actions.map { it.label }.sorted())
        } finally { DebugLog.sink = null; scope.cancel(); db.close() }
    }
}
