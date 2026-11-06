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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SQLCipher + Keystore path that Robolectric cannot run. TC_ID_E2E_02: each test has its own database file, key file and
 * Keystore alias, deleted in @After, so the tests pass alone, together and in any order, and never touch the app's own database.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tag = "enc_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    private val dbName = "$tag.db"
    private fun keys() = DatabaseKeyManager(context, "$tag.key", "${tag}_alias")
    private fun open(pass: ByteArray) = DbFactory.openEncrypted(context, pass, dbName)

    @After fun wipe() = OwnDatabase.delete(context, dbName, "$tag.key", "${tag}_alias")

    @Test
    fun TC_ID_E2E_02_passphraseIsStableAcrossCalls() {
        val a = keys().getOrCreatePassphrase()
        val b = keys().getOrCreatePassphrase()
        assertEquals(32, a.size)
        assertArrayEquals(a, b)
    }

    @Test
    fun TC_ID_E2E_02_dataSurvivesReopenAndFileIsEncrypted() = runBlocking {
        val pass = keys().getOrCreatePassphrase()
        val db1 = open(pass)
        val saved = (ScenarioRepository(db1, null).save(Scenario(id = "enc1", title = "Very Secret Title 12345")) as SaveResult.Success).saved
        db1.close()

        val db2 = open(keys().getOrCreatePassphrase())
        val repo2 = ScenarioRepository(db2, null).also { it.load() }
        assertEquals(saved.version, repo2.find("enc1")!!.version)
        db2.close()

        val bytes = context.getDatabasePath(dbName).readBytes()
        assertFalse("not a plain SQLite file", String(bytes.copyOf(16), Charsets.ISO_8859_1).startsWith("SQLite format 3"))
        assertFalse("title must not appear in the file", String(bytes, Charsets.ISO_8859_1).contains("Very Secret Title 12345"))
    }

    @Test
    fun TC_ID_E2E_02_wrongPassphraseCannotOpenTheDatabase() = runBlocking {
        val pass = keys().getOrCreatePassphrase()
        val db = open(pass)
        ScenarioRepository(db, null).save(Scenario(id = "x", title = "t"))
        db.close()
        try {
            val bad = open(ByteArray(32) { 7 })
            ScenarioRepository(bad, null).load()
            bad.scenarioDao().getAll()
            fail("a wrong passphrase must not read the data")
        } catch (expected: Exception) { }
    }
}
