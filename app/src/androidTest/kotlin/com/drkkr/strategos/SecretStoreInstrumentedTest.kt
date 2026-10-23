package com.drkkr.strategos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.secure.KeystoreCipher
import com.drkkr.strategos.data.secure.SecretStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretStoreInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun TC_SECRET_01_keyRoundTripsAndIsNotStoredInPlaintext() = runBlocking {
        val db = DbFactory.inMemoryForTests(context)
        val store = SecretStore(db.settingDao(), KeystoreCipher("strategos_secret_instrumented_test"))
        assertFalse(store.hasXaiKey())

        store.putXaiKey("gsk_abc")
        assertEquals("gsk_abc", store.getXaiKey())
        assertTrue(store.hasXaiKey())
        val raw = db.settingDao().get("secret.xai_api_key")!!
        assertFalse("stored value must not contain the key", raw.contains("gsk_abc"))

        store.clearXaiKey()
        assertNull(store.getXaiKey())
        db.close()
    }

    @Test
    fun cipherRejectsTamperedCiphertext() {
        val cipher = KeystoreCipher("strategos_secret_instrumented_test")
        val blob = cipher.encrypt("hello".toByteArray())
        assertEquals("hello", String(cipher.decrypt(blob)))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        try { cipher.decrypt(blob); fail("GCM must reject a modified ciphertext") } catch (expected: Exception) { }
    }
}
