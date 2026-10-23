package com.drkkr.strategos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drkkr.strategos.data.db.DatabaseKeyManager
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.data.repo.ScenarioRepository
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The SQLCipher + Keystore path that Robolectric cannot run. */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before @After fun wipe() {
        context.deleteDatabase("strategos.db")
        File(context.noBackupFilesDir, "db.key").delete()
    }

    @Test
    fun passphraseIsStableAcrossCalls() {
        val a = DatabaseKeyManager(context).getOrCreatePassphrase()
        val b = DatabaseKeyManager(context).getOrCreatePassphrase()
        assertEquals(32, a.size)
        assertArrayEquals(a, b)
    }

    @Test
    fun dataSurvivesReopenAndFileIsEncrypted() = runBlocking {
        val pass = DatabaseKeyManager(context).getOrCreatePassphrase()
        val db1 = DbFactory.open(context, pass)
        val saved = (ScenarioRepository(db1, null).save(Scenario(id = "enc1", title = "Very Secret Title 12345")) as SaveResult.Success).saved
        db1.close()

        val db2 = DbFactory.open(context, DatabaseKeyManager(context).getOrCreatePassphrase())
        val repo2 = ScenarioRepository(db2, null).also { it.load() }
        assertEquals(saved.version, repo2.find("enc1")!!.version)
        db2.close()

        val bytes = context.getDatabasePath("strategos.db").readBytes()
        assertFalse("not a plain SQLite file", String(bytes.copyOf(16), Charsets.ISO_8859_1).startsWith("SQLite format 3"))
        assertFalse("title must not appear in the file", String(bytes, Charsets.ISO_8859_1).contains("Very Secret Title 12345"))
    }

    @Test
    fun wrongPassphraseCannotOpenTheDatabase() = runBlocking {
        val pass = DatabaseKeyManager(context).getOrCreatePassphrase()
        val db = DbFactory.open(context, pass)
        ScenarioRepository(db, null).save(Scenario(id = "x", title = "t"))
        db.close()
        try {
            val bad = DbFactory.open(context, ByteArray(32) { 7 })
            ScenarioRepository(bad, null).load()
            bad.scenarioDao().getAll()
            fail("a wrong passphrase must not read the data")
        } catch (expected: Exception) { }
    }
}
