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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Live (skipped without DEEPSEEK_API_KEY): the stage guard on the real model. A synthetic story shaped like the Bumble project
 * (7 Nov): a warm reconnection, then "I want to tell her upfront that I'm serious". Call 1 must give a `not_yet` option beside the
 * `steps_up` one, and the reply is a "Clear-win move" only when the engine's confidence is HIGH.
 *   set -a; source .env; set +a; unset XAI_API_KEY; ./gradlew :app:testDebugUnitTest --tests "*StageGuardLiveTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageGuardLiveTest {
    private val key = System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }

    @Test
    fun aStepUpGetsANotYetOptionBesideIt() = runBlocking {
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
            val guard = mutableListOf<String>()
            DebugLog.sink = { m -> if (m.startsWith("STAGE_GUARD") || m.startsWith("DIRECTIVE ")) guard += m.take(160) }
            suspend fun say(text: String): String {
                controller.send(text)!!.join(); controller.awaitIdle()
                assertNull("turn error: ${controller.state.value.error}", controller.state.value.error)
                return conversations.messages(controller.state.value.conversationId!!).last { it.role == MessageRole.ASSISTANT.name }.text
            }
            say("I reconnected with Meera, a friend from my college choir, after six years. Her dating profile says she wants a life partner, and I'd like this to become serious, maybe marriage.")
            say("We've been texting. She said yes to meeting for coffee sometime, but she lives in another city and only visits home now and then.")
            guard.clear()
            val reply = say("Before we meet, I want to set an expectation upfront about my intention and tell her I'm serious about her. What do you think?")

            val call1 = SketchParser.parse(llm.calls.last { it.purpose == "TURN" }.text)!!
            val tags = SketchHuman.optionTags(call1)
            val s = gateway.activeScenario()!!
            val you = IntakeChecklist.self(s)!!.id
            val savedTags = s.actionsFor(you).associate { it.label to DeliveryTags.of(it) }
            println("STAGE|options|" + call1.options.filter { it.owner.equals("You", true) }.joinToString(" / ") { "${it.label} ${tags[it.label.trim().lowercase()]} revisits=${it.revisits}" })
            println("STAGE|saved|$savedTags")
            println("STAGE|ruledOut|${s.humanContext?.ruledOut}")
            guard.forEach { println("STAGE|log|$it") }
            println("STAGE|reply|" + reply.replace("\n", " ⏎ "))
            assertTrue("a not_yet option for You in $savedTags", savedTags.values.any { "not_yet" in it })
            assertTrue("the reply carries a move label", PlainResult.MOVE_LABELS.any { reply.contains("$it:") })
        } finally { DebugLog.sink = null; scope.cancel(); db.close() }
    }
}
