package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MessageRole
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
class ConversationLandingTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        db.scenarioDao().upsert(ScenarioEntity("s1", "T", "DRAFT", 1, 1L, 1L, "{}"))
        conversations = ConversationRepository(db)
    }
    @After fun tearDown() { db.close() }

    @Test fun TC_LANDING_01_emptyProjectHasNoLandingThreadAndZeroCount() = runBlocking {
        assertNull(conversations.landingThread("s1"))
        assertEquals(0, conversations.messageCount("s1"))
    }

    @Test fun TC_LANDING_02_countsOnlyVisibleMessages() = runBlocking {
        val c = conversations.getOrCreateActive("s1", "m", "v")
        conversations.append(c.id, "t", MessageRole.USER, "hello")
        conversations.append(c.id, "t", MessageRole.ASSISTANT, "hi there")
        conversations.append(c.id, "t", MessageRole.TOOL_CALL, "", toolName = "get_scenario")
        conversations.append(c.id, "t", MessageRole.EVENT, "Applied 1 change")
        assertEquals(2, conversations.messageCount("s1"))
    }

    @Test fun TC_LANDING_03_landsOnMainWhenNewestThreadIsEmpty() = runBlocking {
        val main = conversations.getOrCreateActive("s1", "m", "v")
        conversations.append(main.id, "t", MessageRole.USER, "the money angle")
        // A newer, still-empty topic becomes the most-recent thread.
        val empty = conversations.createNamedThread("s1", "Fresh topic")
        conversations.switchThread(empty.id)
        assertEquals(main.id, conversations.landingThread("s1")?.id)
    }

    @Test fun TC_LANDING_04_landsOnNewestThreadThatHasMessages() = runBlocking {
        val main = conversations.getOrCreateActive("s1", "m", "v")
        conversations.append(main.id, "t", MessageRole.USER, "first")
        val second = conversations.createNamedThread("s1", "Trust angle")
        conversations.append(second.id, "t", MessageRole.USER, "what would help him feel respected?")
        conversations.switchThread(second.id)
        assertEquals(second.id, conversations.landingThread("s1")?.id)
    }

    @Test fun TC_LANDING_05_threadSummariesCarryLastLineAndOpenFlag() = runBlocking {
        val main = conversations.getOrCreateActive("s1", "m", "v")
        conversations.append(main.id, "t", MessageRole.USER, "first")
        conversations.append(main.id, "t", MessageRole.ASSISTANT, "Let him ask. Keep your answer the same.")
        val second = conversations.createNamedThread("s1", "Money angle")
        conversations.append(second.id, "t", MessageRole.ASSISTANT, "How much could you give without it hurting?")
        conversations.switchThread(second.id)

        val rows = conversations.threadSummaries("s1").associateBy { it.id }
        assertEquals("Let him ask. Keep your answer the same.", rows.getValue(main.id).lastLine)
        assertEquals("How much could you give without it hurting?", rows.getValue(second.id).lastLine)
        assertTrue(rows.getValue(second.id).isOpenNow)
        assertFalse(rows.getValue(main.id).isOpenNow)
    }

    @Test fun TC_LANDING_06_threadWithNoVisibleLineFallsBackToUpdatedAt() = runBlocking {
        val main = conversations.getOrCreateActive("s1", "m", "v")
        val row = conversations.threadSummaries("s1").single { it.id == main.id }
        assertEquals("", row.lastLine)
        assertEquals(main.updatedAt, row.lastAt)
    }
}
