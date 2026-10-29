package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.agent.ConversationSummarizer
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.fixtures.FakeLlmClient
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.networkDown
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.textReply
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationSummarizerTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var cid: String
    private val fake = FakeLlmClient()
    private lateinit var summarizer: ConversationSummarizer

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        db.scenarioDao().upsert(ScenarioEntity("s", "S", "DRAFT", 1, 1L, 1L, "{}"))
        conversations = ConversationRepository(db)
        cid = conversations.getOrCreateActive("s", "m", "v").id
        summarizer = ConversationSummarizer(fake, conversations, SettingsRepository(db))
    }

    @After fun tearDown() { db.close() }

    /** Each message is [MSG_TOKENS] tokens, so the budget is exactly 10 messages and the target 5. */
    private suspend fun fill(n: Int) = repeat(n) { i ->
        val prefix = "m${i.toString().padStart(2, '0')} "
        conversations.append(cid, "t", if (i % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT, prefix + "x".repeat(MSG_TOKENS * 4 - prefix.length))
    }

    private companion object { val MSG_TOKENS = AgentConfig.HISTORY_TOKEN_BUDGET / 10 }

    @Test
    fun TC_SUM_01_underBudgetNoCall() = runBlocking {
        fill(10)                                   // exactly the budget: not over it
        summarizer.maybeSummarize(cid)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun TC_SUM_02_overBudgetSummarizes() = runBlocking {
        fill(30)
        fake.enqueue(textReply("The user runs a clinic."))
        summarizer.maybeSummarize(cid)
        assertEquals(1, fake.requests.size)
        assertNull(fake.requests.single().tools)
        val conv = conversations.get(cid)!!
        assertEquals("The user runs a clinic.", conv.rollingSummary)
        val kept = AgentConfig.HISTORY_TOKEN_TARGET / MSG_TOKENS   // 3x the budget → keep the newest TARGET worth
        assertEquals(30 - kept, conv.summarizedThroughSeq)
        val remaining = conversations.messages(cid).filter { it.seq > conv.summarizedThroughSeq }.size
        assertTrue(remaining * MSG_TOKENS <= AgentConfig.HISTORY_TOKEN_TARGET)
    }

    @Test
    fun TC_SUM_03_failureLeavesStateUnchanged() = runBlocking {
        fill(30)
        fake.enqueue(networkDown())
        summarizer.maybeSummarize(cid)
        val conv = conversations.get(cid)!!
        assertEquals("", conv.rollingSummary)
        assertEquals(0, conv.summarizedThroughSeq)
    }
}
