package com.drkkr.strategos

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drkkr.strategos.data.db.DatabaseKeyManager
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.DebugDbExport
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * TC_ID_E2E_04 (ID0.5): debug builds use the encrypted strategos.db as the live database, like release; the plain file is a
 * one-way export the app never reads. Each test has its own database, key, alias and export base name (deleted in @After), and
 * runs the same open path as [AppContainer]'s default: [DbFactory] then [DebugDbExport.afterOpen].
 */
@RunWith(AndroidJUnit4::class)
class DebugDatabaseInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tag = "dbg_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    private val dbName = "$tag.db"
    private val files get() = DebugDbExport.Files(context, "${tag}_plain")
    private val realCopy = DebugDbExport.copy

    private fun newContainer(background: Boolean = false) = AppContainer(context, openDatabase = { c ->
        DbFactory.openEncrypted(c, DatabaseKeyManager(c, "$tag.key", "${tag}_alias").getOrCreatePassphrase(), dbName)
            .also { DebugDbExport.afterOpen(c, it, DebugDbExport.Files(c, "${tag}_plain"), background) }
    })

    @After fun wipe() {
        DebugDbExport.copy = realCopy
        OwnDatabase.delete(context, dbName, "$tag.key", "${tag}_alias")
        val f = files
        listOf(f.plain, f.retired, f.temp, f.marker).forEach { base -> listOf("", "-wal", "-shm", "-journal").forEach { File(base.path + it).delete() } }
    }

    private fun isPlainSqlite(f: File) = String(f.readBytes().copyOf(16), Charsets.ISO_8859_1).startsWith("SQLite format 3")

    /** One project with one conversation, saved through the real repositories; returns the container's own project list after reopen. */
    private fun seed(): String = runBlocking {
        val c = newContainer(); c.prepareStorage(); c.scenarioRepository.load()
        val saved = (c.scenarioRepository.save(Scenario(title = "Kept project")) as SaveResult.Success).saved
        val conv = ConversationRepository(c.database!!).getOrCreateActive(saved.id, "m", "v")
        ConversationRepository(c.database!!).append(conv.id, "t1", MessageRole.USER, "hello")
        c.database!!.close()
        saved.id
    }

    private fun reopenAndRead(): Pair<List<String>, Int> = runBlocking {
        val c = newContainer(); c.prepareStorage()
        val titles = c.scenarioRepository.also { it.load() }.scenarios.value.map { it.title }
        val id = c.scenarioRepository.scenarios.value.firstOrNull { it.title == "Kept project" }?.id
        val msgs = id?.let { sid -> ConversationRepository(c.database!!).listThreads(sid).sumOf { ConversationRepository(c.database!!).messages(it.id).size } } ?: 0
        c.database!!.close()
        titles to msgs
    }

    @Test fun TC_ID_E2E_04_a_theAppsLiveDatabaseIsTheEncryptedFile() {
        assumeTrue("debug builds only", BuildConfig.DEBUG)
        val app = (context.applicationContext as StrategosApplication).container
        val helper = app.database!!.openHelper
        assertEquals(DbFactory.DB_NAME, helper.databaseName)
        assertTrue("opened through SQLCipher: ${helper.javaClass.name}", helper.javaClass.name.startsWith("net.zetetic"))
        // An earlier test's "Delete everything" may have removed the file under the open handle; check it when it is there.
        val live = context.getDatabasePath(DbFactory.DB_NAME)
        if (live.exists()) {
            assertFalse("strategos.db is encrypted", isPlainSqlite(live))
            val opened = runCatching { SQLiteDatabase.openDatabase(live.path, null, SQLiteDatabase.OPEN_READONLY).use { it.rawQuery("SELECT count(*) FROM sqlite_master", null).use { c -> c.moveToFirst() } } }
            assertTrue("it can't be opened without the passphrase", opened.isFailure)
        }
    }

    @Test fun TC_ID_E2E_04_a_aFreshInstallIsEncryptedAndExportsInTheBackground() {
        val c = newContainer(background = true)
        runBlocking { c.prepareStorage(); c.scenarioRepository.load() }
        assertFalse(isPlainSqlite(context.getDatabasePath(dbName)))
        val deadline = System.currentTimeMillis() + 15_000
        while (!files.plain.exists() && System.currentTimeMillis() < deadline) Thread.yield()
        assertTrue("the export was written", files.plain.exists())
        assertTrue(isPlainSqlite(files.plain))
        assertFalse("no temp file left", files.temp.exists())
        c.database!!.close()
    }

    @Test fun TC_ID_E2E_04_b_anEncryptedDatabaseIsShownAndExported() {
        seed()
        val (titles, msgs) = reopenAndRead()
        assertEquals(listOf("Kept project"), titles)
        assertEquals(1, msgs)
        SQLiteDatabase.openDatabase(files.plain.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT count(*) FROM scenario", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.rawQuery("SELECT count(*) FROM message", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            assertTrue("schema version copied", db.version > 0)
        }
    }

    @Test fun TC_ID_E2E_04_c_anOldPlainFileIsRenamedOnceNeverReadNeverDeleted() {
        seed()
        // the old file from a power-mode debug build: a plain database holding a project the live database doesn't have
        files.marker.delete(); files.plain.delete()   // as before ID0.5: no marker, and the plain file is the old live database
        SQLiteDatabase.openOrCreateDatabase(files.plain, null).use { it.execSQL("CREATE TABLE scenario(id TEXT, title TEXT)"); it.execSQL("INSERT INTO scenario VALUES('old','Old plain project')") }
        val wal = File(files.plain.path + "-wal").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val before = files.plain.readBytes()
        val (titles, msgs) = reopenAndRead()
        assertEquals("never read into the live database", listOf("Kept project"), titles)
        assertEquals(1, msgs)
        assertArrayEquals("renamed as it was", before, files.retired.readBytes())
        assertArrayEquals("its WAL kept with it", byteArrayOf(1, 2, 3), File(files.retired.path + "-wal").readBytes())
        assertFalse(wal.exists())
        // once: the next start leaves the retired file alone and does not rename this branch's own export
        val export = files.plain.readBytes()
        reopenAndRead()
        assertArrayEquals(before, files.retired.readBytes())
        assertTrue(files.plain.exists())
        assertTrue(isPlainSqlite(files.plain) && export.isNotEmpty())
    }

    @Test fun TC_ID_E2E_04_d_aFailingExportChangesNothingElse() {
        seed()
        files.plain.delete()
        DebugDbExport.copy = { _, out -> out.writeBytes(byteArrayOf(9)); throw IllegalStateException("forced") }
        val (titles, msgs) = reopenAndRead()
        assertEquals(listOf("Kept project"), titles)
        assertEquals(1, msgs)
        assertFalse("no export", files.plain.exists())
        assertFalse("no temp file left", files.temp.exists())
        assertFalse("nothing retired", files.retired.exists())
    }
}
