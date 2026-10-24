package com.drkkr.strategos

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.*
import com.drkkr.strategos.data.secure.XorBase64TestCipher
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
class JournalDbTest {
    private lateinit var db: StrategosDatabase

    @Before fun setUp() { db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext()) }
    @After fun tearDown() { db.close() }

    private fun scenario(id: String = "s1") = ScenarioEntity(id, "T", "DRAFT", 1, 1L, 1L, "{}")
    private fun thread(id: String, title: String) =
        ConversationEntity(id, "s1", "ACTIVE", "m", "v", createdAt = 1L, updatedAt = 1L, title = title)
    private fun entry(id: String = "j1", text: String = "x") = JournalEntryEntity(id, null, text, createdAt = 1L)
    private fun note(id: String = "n1") = JournalInsightPostNoteEntity(
        id, "j1", "s1", "HIGH", "h", "Based on your journal entry: x", "p", "do", "dont", createdAt = 1L)
    private fun point(id: String, t: Long) = TrajectoryPointEntity(id, "s1", null, "INITIAL", 50.0, 50.0, 50.0, 0.0, 0, "CHICKEN_GAME", timestamp = t)

    @Test
    fun TC_DB2_01_scenarioCascadeDeletesJournalPostNotesAndTrajectory() = runBlocking {
        db.scenarioDao().upsert(scenario())
        db.conversationDao().upsert(thread("c1", "A")); db.conversationDao().upsert(thread("c2", "B"))
        db.journalDao().insertEntry(entry())
        db.journalDao().insertPostNote(note())
        db.trajectoryDao().insert(point("p1", 1)); db.trajectoryDao().insert(point("p2", 2))
        db.quickCheckDao().insert(QuickCheckFeedbackEntity("q1", "s1", null, "a", "DID_AS_SUGGESTED", "DE_ESCALATED", "GROUNDED", trustDelta = 8.0, tensionDelta = -12.0, createdAt = 1L))

        db.scenarioDao().delete("s1")

        assertTrue(db.conversationDao().listThreadsForScenario("s1").isEmpty())
        assertNull(db.journalDao().getPostNote("n1"))
        assertTrue(db.trajectoryDao().forScenario("s1").isEmpty())
        assertTrue(db.quickCheckDao().forScenario("s1").isEmpty())
        assertEquals(1, db.journalDao().allEntries().size)          // the global entry stays
    }

    @Test
    fun TC_DB2_02_journalCryptoEncryptsAtRest() = runBlocking {
        val cipher = XorBase64TestCipher()
        val plain = "I felt panicked when he didn't text back"
        db.journalDao().insertEntry(entry(text = cipher.encrypt(plain)))
        val stored = db.journalDao().getEntry("j1")!!.encryptedText
        assertFalse(stored.contains("panicked")); assertFalse(stored.contains("text back"))
        assertEquals(plain, cipher.decrypt(stored))
    }

    @Test
    fun TC_DB2_03_trajectorySeriesOrdering() = runBlocking {
        db.scenarioDao().upsert(scenario())
        db.trajectoryDao().insert(point("a", 1000)); db.trajectoryDao().insert(point("b", 3000)); db.trajectoryDao().insert(point("c", 2000))
        assertEquals(listOf(1000L, 2000L, 3000L), db.trajectoryDao().forScenario("s1").map { it.timestamp })
        assertEquals(3000L, db.trajectoryDao().latestForScenario("s1")!!.timestamp)
    }

    @Test
    fun TC_DB2_04_postNoteLifecycle() = runBlocking {
        db.scenarioDao().upsert(scenario()); db.journalDao().insertEntry(entry())
        db.journalDao().insertPostNote(note("n1")); db.journalDao().insertPostNote(note("n2"))
        db.journalDao().markPostNoteApplied("n1")
        db.journalDao().dismissPostNote("n2")
        assertEquals(listOf("n1"), db.journalDao().activePostNotes("s1").map { it.id })
        assertTrue(db.journalDao().getPostNote("n1")!!.applied)
        db.journalDao().dismissPostNote("n1")
        assertTrue(db.journalDao().activePostNotes("s1").isEmpty())
        assertTrue(db.journalDao().getPostNote("n1")!!.applied)
    }

    @Test
    fun TC_DB2_05_migration1To2PreservesData() {
        val v1 = SQLiteDatabase.create(null)
        v1.execSQL("CREATE TABLE IF NOT EXISTS `conversation` (`id` TEXT NOT NULL, `scenario_id` TEXT NOT NULL, `status` TEXT NOT NULL, `model_id` TEXT NOT NULL, `agent_version` TEXT NOT NULL, `rolling_summary` TEXT NOT NULL, `summarized_through_seq` INTEGER NOT NULL, `last_report_digest_json` TEXT, `last_report_scenario_version` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        v1.execSQL("INSERT INTO conversation VALUES ('c1','s1','ACTIVE','m','v','',0,NULL,NULL,1,1)")
        DbFactory.MIGRATION_1_2_SQL.forEach { v1.execSQL(it) }
        v1.rawQuery("SELECT title, topic_tag, is_pinned FROM conversation", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("Main Thread", it.getString(0)); assertEquals("GENERAL", it.getString(1)); assertEquals(0, it.getInt(2))
        }
        val tables = mutableSetOf<String>()
        v1.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { while (it.moveToNext()) tables += it.getString(0) }
        assertTrue(tables.containsAll(listOf("journal_entry", "journal_post_note", "trajectory_point", "quick_check_feedback")))
        v1.close()
    }
}
