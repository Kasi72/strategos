package com.drkkr.strategos.data.db

import android.content.Context
import com.drkkr.strategos.data.secure.KeystoreCipher
import com.drkkr.strategos.data.storage.StorageUnavailableException
import java.io.File
import java.security.SecureRandom

class DatabaseKeyManager(private val context: Context) {

    /** Returns the SQLCipher passphrase. Never creates a new key over an existing database. */
    fun getOrCreatePassphrase(): ByteArray {
        val file = File(context.noBackupFilesDir, "db.key")
        val cipher = KeystoreCipher("strategos_db_v1")
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
}
