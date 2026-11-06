package com.drkkr.strategos.data.db

import android.content.Context
import com.drkkr.strategos.data.secure.KeystoreCipher
import com.drkkr.strategos.data.storage.StorageUnavailableException
import java.io.File
import java.security.SecureRandom

/** [keyFile] and [alias] default to the app's own; the instrumented tests pass their own so they never touch the app's key. */
class DatabaseKeyManager(private val context: Context, private val keyFile: String = KEY_FILE, private val alias: String = ALIAS) {

    /** Returns the SQLCipher passphrase. Never creates a new key over an existing database. */
    fun getOrCreatePassphrase(): ByteArray {
        val file = File(context.noBackupFilesDir, keyFile)
        val cipher = KeystoreCipher(alias)
        if (file.exists()) {
            return try {
                cipher.decrypt(file.readBytes())
            } catch (e: Exception) {
                throw StorageUnavailableException(e)
            }
        }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        file.writeBytes(cipher.encrypt(bytes))
        return bytes
    }

    companion object {
        const val KEY_FILE = "db.key"
        const val ALIAS = "strategos_db_v1"
    }
}
