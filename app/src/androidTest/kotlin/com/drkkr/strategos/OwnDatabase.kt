package com.drkkr.strategos

import android.content.Context
import java.io.File
import java.security.KeyStore

/** Deletes a test's own database, its key file and its Keystore alias (TC_ID_E2E_02). */
object OwnDatabase {
    fun delete(context: Context, dbName: String, keyFile: String, alias: String) {
        context.deleteDatabase(dbName)
        File(context.noBackupFilesDir, keyFile).delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }
}
