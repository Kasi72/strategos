package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.db.entities.SettingEntity
import com.drkkr.strategos.data.repo.*
import com.drkkr.strategos.domain.model.FactStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoriesTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        db.scenarioDao().upsert(ScenarioEntity("s1", "T", "DRAFT", 1, 1L, 1L, "{}"))
        conversations = ConversationRepository(db)
    }
    @After fun tearDown() { db.close() }

    @Test
    fun TC_DB_02_concurrentAppendsGiveSeq1To50WithoutGaps() = runBlocking {
        val c = conversations.getOrCreateActive("s1", "m", "v")
        (1..2).map { w ->
            async(Dispatchers.Default) { repeat(25) { i -> conversations.append(c.id, "t$w", MessageRole.USER, "w$w-$i") } }
        }.awaitAll()
        assertEquals((1..50).toList(), conversations.messages(c.id).map { it.seq })
    }

    @Test
    fun getOrCreateActiveReturnsTheSameConversation() = runBlocking {
        val a = conversations.getOrCreateActive("s1", "m", "v")
        val b = conversations.getOrCreateActive("s1", "other", "v2")
        assertEquals(a.id, b.id)
    }

    @Test
    fun userTextsExcludesEventsAndAssistant() = runBlocking {
        val c = conversations.getOrCreateActive("s1", "m", "v")
        conversations.append(c.id, "t", MessageRole.USER, "I am worried")
        conversations.append(c.id, "t", MessageRole.EVENT, "Applied 2 changes")
        conversations.append(c.id, "t", MessageRole.ASSISTANT, "OK")
        assertEquals(listOf("I am worried"), conversations.userTexts(c.id))
    }

    @Test
    fun clearDeletesMessagesAndSummaryButKeepsMemory() = runBlocking {
        val c = conversations.getOrCreateActive("s1", "m", "v")
        conversations.append(c.id, "t", MessageRole.USER, "hi")
        conversations.saveSummary(c.id, "summary", 1)
        val memory = MemoryRepository(db)
        memory.add("s1", MemoryKind.FACT, "Brother asked for money", FactStatus.USER_ESTIMATE)

        conversations.clear(c.id)

        assertTrue(conversations.messages(c.id).isEmpty())
        assertEquals("", db.conversationDao().get(c.id)!!.rollingSummary)
        assertEquals(1, memory.open("s1").size)
    }

    @Test
    fun memoryResolveAndStatus() = runBlocking {
        val memory = MemoryRepository(db)
        val a = memory.add("s1", MemoryKind.OPEN_QUESTION, "Who signs?", FactStatus.UNKNOWN)
        val b = memory.add("s1", MemoryKind.FACT, "Wife can veto", FactStatus.USER_ESTIMATE)
        assertEquals(1, memory.resolve("s1", listOf(a)))
        assertEquals(listOf(b), memory.open("s1").map { it.id })
        memory.setStatus(b, FactStatus.AI_INFERRED)
        assertEquals("AI_INFERRED", memory.open("s1").single().factStatus)
    }

    @Test
    fun changeSetLifecycle() = runBlocking {
        val c = conversations.getOrCreateActive("s1", "m", "v")
        val repo = ChangeSetRepository(db)
        val cs = repo.create("s1", c.id, "Add brother", "[]", "{}", baseScenarioVersion = 1)
        assertEquals(listOf(cs.id), repo.pending("s1").map { it.id })
        repo.markDecided(cs.id, ChangeSetStatus.APPLIED, "[]", null, appliedVersion = 2)
        assertTrue(repo.pending("s1").isEmpty())
        val done = repo.get(cs.id)!!
        assertEquals("APPLIED", done.status)
        assertEquals(2, done.appliedScenarioVersion)
        assertNotNull(done.decidedAt)
    }

    @Test
    fun settingsDefaultsAndAiReady() = runBlocking {
        val s = SettingsRepository(db)
        assertFalse(s.aiEnabled())
        assertEquals(AgentConfig.DEFAULT_MODEL, s.model())
        assertEquals(UiMode.GUIDED, s.uiMode())
        assertTrue(s.includeMemory() && s.autoContinue())
        assertFalse(s.aiReady())

        s.setAiEnabled(true); s.setConsentVersion(AgentConfig.CONSENT_VERSION)
        assertFalse("no key yet", s.aiReady())
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
        assertTrue(s.aiReady())

        s.setConsentVersion(AgentConfig.CONSENT_VERSION - 1)
        assertFalse("stale consent", s.aiReady())
        assertTrue(s.supportText().contains("14416"))
    }
}
