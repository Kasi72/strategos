package com.drkkr.strategos

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.*
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
class DaoTest {
    private lateinit var db: StrategosDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StrategosDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() { db.close() }

    private fun scenario(id: String = "s1") = ScenarioEntity(id, "T", "DRAFT", 1, 1L, 1L, "{}")
    private fun conversation(id: String = "c1", sid: String = "s1") =
        ConversationEntity(id, sid, "ACTIVE", "m", "v", createdAt = 1L, updatedAt = 1L)
    private fun message(n: Int, cid: String = "c1") =
        MessageEntity("m$n", cid, n, "t1", "USER", "hi", createdAt = n.toLong())

    @Test
    fun TC_DB_01_deletingScenarioCascadesToEveryChild() = runBlocking {
        db.scenarioDao().upsert(scenario())
        db.conversationDao().upsert(conversation())
        (1..3).forEach { db.messageDao().insert(message(it)) }
        db.changeSetDao().insert(ChangeSetEntity("cs1", "s1", "c1", "PENDING", "sum", "[]", "{}", baseScenarioVersion = 1, createdAt = 1L))
        db.memoryDao().insert(MemoryItemEntity("mem1", "s1", "FACT", "x", "USER_ESTIMATE", createdAt = 1L))
        db.whatIfDao().insert(WhatIfEntity("w1", "s1", "c1", "l", "[]", 1, "{}", createdAt = 1L))
        db.snapshotDao().insert(SnapshotEntity("snap1", "s1", 1L, "{}"))

        db.scenarioDao().delete("s1")

        assertNull(db.conversationDao().get("c1"))
        assertTrue(db.messageDao().all("c1").isEmpty())
        assertNull(db.changeSetDao().get("cs1"))
        assertTrue(db.memoryDao().open("s1").isEmpty())
        assertNull(db.whatIfDao().get("w1"))
        assertTrue(db.snapshotDao().forScenario("s1").isEmpty())
    }

    @Test
    fun messageSeqIsUniquePerConversation() = runBlocking {
        db.scenarioDao().upsert(scenario())
        db.conversationDao().upsert(conversation())
        db.messageDao().insert(message(1))
        try {
            db.messageDao().insert(message(1).copy(id = "other"))
            fail("duplicate (conversation_id, seq) must be rejected")
        } catch (expected: SQLiteConstraintException) { }
        assertEquals(1, db.messageDao().maxSeq("c1"))
    }

    @Test
    fun settingRoundTrip() = runBlocking {
        db.settingDao().put(SettingEntity("ui.mode", "GUIDED"))
        assertEquals("GUIDED", db.settingDao().get("ui.mode"))
        db.settingDao().remove("ui.mode")
        assertNull(db.settingDao().get("ui.mode"))
    }
}
