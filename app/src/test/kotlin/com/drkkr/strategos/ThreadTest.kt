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
class ThreadTest {
    private lateinit var db: StrategosDatabase
    private lateinit var repo: ConversationRepository

    @Before fun setUp() {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        repo = ConversationRepository(db)
        runBlocking { db.scenarioDao().upsert(ScenarioEntity("S1", "T", "DRAFT", 1, 1L, 1L, "{}")) }
    }
    @After fun tearDown() { db.close() }

    @Test
    fun TC_THREAD_01_createMultipleNamedThreadsPerProject() = runBlocking {
        val a = repo.createNamedThread("S1", "Weekend Plan", "SCHEDULE")
        val b = repo.createNamedThread("S1", "In-Laws Boundary", "FAMILY")
        val list = repo.listThreads("S1")
        assertEquals(setOf("Weekend Plan", "In-Laws Boundary"), list.map { it.title }.toSet())
        assertEquals(setOf(a.id, b.id), list.map { it.id }.toSet())
        assertEquals("FAMILY", list.first { it.id == b.id }.topicTag)
    }

    @Test
    fun TC_THREAD_02_isolatedMessagesSharedScenario() = runBlocking {
        val a = repo.createNamedThread("S1", "A"); val b = repo.createNamedThread("S1", "B")
        repo.append(a.id, "t1", MessageRole.USER, "in A")
        repo.append(b.id, "t2", MessageRole.USER, "in B")
        assertEquals(listOf("in A"), repo.messages(a.id).map { it.text })
        assertEquals(listOf("in B"), repo.messages(b.id).map { it.text })
        assertEquals("S1", repo.get(a.id)!!.scenarioId); assertEquals("S1", repo.get(b.id)!!.scenarioId)
    }

    @Test
    fun TC_THREAD_03_renameAndPinThread() = runBlocking {
        val a = repo.createNamedThread("S1", "A"); val b = repo.createNamedThread("S1", "B")
        repo.renameThread(a.id, "Resolved Weekend Plan", "SCHEDULE")
        repo.setPinned(b.id, true)
        val list = repo.listThreads("S1")
        assertEquals(b.id, list.first().id)
        assertEquals("Resolved Weekend Plan", list.first { it.id == a.id }.title)
    }

    @Test
    fun TC_THREAD_04_legacyGetOrCreateActiveStillWorks() = runBlocking {
        val created = repo.getOrCreateActive("S1", "m", "v")
        assertEquals("Main Thread", created.title)
        assertEquals(created.id, repo.getOrCreateActive("S1", "m", "v").id)
        val other = repo.createNamedThread("S1", "Other")
        repo.switchThread(created.id)
        assertEquals(created.id, repo.getOrCreateActive("S1", "m", "v").id)
        repo.switchThread(other.id)
        assertEquals(other.id, repo.getOrCreateActive("S1", "m", "v").id)
    }
}
