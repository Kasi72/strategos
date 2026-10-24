package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.*
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.entities.SettingEntity
import com.drkkr.strategos.data.repo.*
import com.drkkr.strategos.data.secure.XorBase64TestCipher
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.llm.ChatCompletionsClient
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Live end-to-end run of the whole Talk flow (controller, context, real DeepSeek, parse, apply, engine, answer), on the JVM.
 * Skipped without DEEPSEEK_API_KEY. Prints statuses and counts only; on failure the assistant's visible text is in the message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepSeekLiveFlowTest {
    private val key = System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }

    @Test
    fun firstMessageGivesOptionsAndCoachGuideThenAFollowUpWorks() = runBlocking {
        Assume.assumeTrue("DEEPSEEK_API_KEY not set; live flow skipped", key != null)
        val db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scenarios = ScenarioRepository(db, null).also { it.load() }
            val s = Scenario(id = "loan", title = "Loan", description = "My brother wants me to co-sign a loan.")
            val saved = (scenarios.save(s) as SaveResult.Success).saved
            val conversations = ConversationRepository(db)
            val settings = SettingsRepository(db).apply { setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION) }
            db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
            val trajectory = TrajectoryRepository(db, scenarios)
            val journal = JournalRepository(db, XorBase64TestCipher(), MemoryRepository(db), conversations)
            val controller = CounselController(scope, CounselDeps(
                llm = ChatCompletionsClient(keyProvider = { key }), systemPrompt = { "unused" }, tools = { JsonArray(emptyList()) },
                agentVersion = { "2.0.0" }, conversations = conversations, changeSets = ChangeSetRepository(db), memory = MemoryRepository(db),
                settings = settings, whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true, coach = true,
                trajectory = trajectory, journal = journal
            ), CommittingGateway(saved))
            controller.bindScenario("loan").join()

            suspend fun say(text: String): String {
                controller.send(text)!!.join(); controller.awaitIdle()
                val items = controller.state.value.items
                println("FLOW|turn|error=${controller.state.value.error}|items=${items.size}|points=${trajectory.points("loan").size}")
                return items.filterIsInstance<ChatItem.AssistantText>().lastOrNull()?.text.orEmpty()
            }

            val first = say("My brother wants me to co-sign a big loan for his new shop. My wife is strongly against it. My parents always take his side.")
            assertNull("turn error: ${controller.state.value.error}", controller.state.value.error)
            assertFalse("got the failure message instead of an answer:\n$first", first.contains("couldn't turn that into options"))
            assertTrue("no options in:\n$first", first.contains("Options to consider") || first.contains("Your options so far"))
            assertTrue("no coach guide in:\n$first", first.contains("Clear-win move:"))

            val second = say("I decided to wait and told him. He said he will ask our parents.")
            assertNull("follow-up error: ${controller.state.value.error}", controller.state.value.error)
            assertFalse("follow-up failed:\n$second", second.contains("couldn't turn that into options"))
            println("FLOW|PASS|first=${first.length} chars|second=${second.length} chars")
        } finally { scope.cancel(); db.close() }
    }

    /** Replays a list of messages (a JSON array of strings in FLOW_MESSAGES_FILE) one by one and writes every answer to FLOW_OUT_FILE. For manual runs; the text never enters the repo. */
    @Test
    fun replayMessagesFromFile() = runBlocking {
        val inFile = System.getenv("FLOW_MESSAGES_FILE")?.takeIf { it.isNotBlank() }
        val outFile = System.getenv("FLOW_OUT_FILE")?.takeIf { it.isNotBlank() }
        Assume.assumeTrue("FLOW_MESSAGES_FILE / FLOW_OUT_FILE / DEEPSEEK_API_KEY not set", key != null && inFile != null && outFile != null)
        val messages = kotlinx.serialization.json.Json.parseToJsonElement(java.io.File(inFile!!).readText()).let { it as JsonArray }
            .map { (it as kotlinx.serialization.json.JsonPrimitive).content }
        val db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val out = StringBuilder()
        try {
            val scenarios = ScenarioRepository(db, null).also { it.load() }
            val saved = (scenarios.save(Scenario(id = "s1", title = "New scenario")) as SaveResult.Success).saved
            val conversations = ConversationRepository(db)
            val settings = SettingsRepository(db).apply { setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION) }
            db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
            val trajectory = TrajectoryRepository(db, scenarios)
            val journal = JournalRepository(db, XorBase64TestCipher(), MemoryRepository(db), conversations)
            val controller = CounselController(scope, CounselDeps(
                llm = ChatCompletionsClient(keyProvider = { key }), systemPrompt = { "unused" }, tools = { JsonArray(emptyList()) },
                agentVersion = { "2.0.0" }, conversations = conversations, changeSets = ChangeSetRepository(db), memory = MemoryRepository(db),
                settings = settings, whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true, coach = true,
                trajectory = trajectory, journal = journal
            ), CommittingGateway(saved))
            controller.bindScenario("s1").join()
            for ((i, m) in messages.withIndex()) {
                controller.send(m)!!.join(); controller.awaitIdle()
                val st = controller.state.value
                val reply = st.items.lastOrNull()
                out.append("===== MESSAGE ${i + 1} (${m.length} chars) =====\n")
                out.append("error=${st.error} support=${st.supportVisible} trajectory=${st.trajectory?.let { "trust ${it.trustScore.toInt()} tension ${it.tensionScore.toInt()} | ${it.band} | ${it.phaseLabel}" }}\n")
                out.append("reply_chars=${(reply as? ChatItem.AssistantText)?.text?.length}\n\n")
                out.append(when (reply) { is ChatItem.AssistantText -> reply.text; is ChatItem.Error -> "ERROR ITEM: ${reply.text}"; else -> "(last item: $reply)" }).append("\n\n")
                java.io.File(outFile!!).writeText(out.toString())
            }
        } finally { scope.cancel(); db.close() }
    }
}
