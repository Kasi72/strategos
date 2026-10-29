package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.agent.ContextAssembler
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.data.repo.UiMode
import com.drkkr.strategos.domain.agent.TokenEstimator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * LEAN PROFILE budget: the Groq account allows 8,000 tokens per minute and reserves max_output_tokens per request.
 * A Guided request (prompt + Guided tools + live context + a short exchange + reply reserve) must stay ≲ 3,000 tokens
 * so a 2–3 request turn fits in a minute. Token counts use TokenEstimator (bytes / 4), which overestimates English.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeanBudgetTest {
    private fun asset(name: String) = listOf("src/main/assets/agent", "app/src/main/assets/agent").map { File(it, name) }.first { it.exists() }.readText()

    @Test
    fun guidedRequestFitsTheBudget() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = DbFactory.inMemoryForTests(app)
        try {
            val scenario = ShortIdsTest.loan()
            db.scenarioDao().upsert(ScenarioEntity(scenario.id, "t", "DRAFT", 1, 1L, 1L, "{}"))
            val settings = SettingsRepository(db).apply { setUiMode(UiMode.GUIDED) }
            val conversations = ConversationRepository(db)
            val cid = conversations.getOrCreateActive(scenario.id, "m", "v").id
            listOf(
                MessageRole.USER to "My brother wants me to co-sign a big loan and my wife is against it. I don't know what to do.",
                MessageRole.ASSISTANT to "That sounds hard. Who else would be affected if this goes one way or the other?",
                MessageRole.USER to "My parents, probably. They always take his side."
            ).forEach { (role, text) -> conversations.append(cid, "t", role, text) }

            val prompt = asset("system_prompt.md")
            val input = ContextAssembler({ prompt }, conversations, ChangeSetRepository(db), MemoryRepository(db), settings, { scenario })
                .build(cid, scenario.id)
            val guidedTools = JsonArray(Json.parseToJsonElement(asset("tools.json")).jsonArray
                .filter { it.jsonObject.getValue("name").jsonPrimitive.content in AgentConfig.GUIDED_TOOLS })

            val promptTokens = TokenEstimator.estimate(prompt)
            val toolTokens = TokenEstimator.estimate(guidedTools.toString())
            val inputTokens = TokenEstimator.estimate(input.toString())
            val total = inputTokens + toolTokens + AgentConfig.MAX_OUTPUT_TOKENS
            println("LEAN|prompt=$promptTokens|tools=$toolTokens|input(incl prompt)=$inputTokens|reserve=${AgentConfig.MAX_OUTPUT_TOKENS}|total=$total")
            assertTrue("prompt $promptTokens", promptTokens <= 1_300)
            assertTrue("guided tools $toolTokens", toolTokens <= 900)
            assertTrue("request $total", total <= 3_400)
        } finally {
            db.close()
        }
    }
}
