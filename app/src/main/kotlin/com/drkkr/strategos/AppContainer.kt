package com.drkkr.strategos

import android.content.Context
import com.drkkr.strategos.data.db.DatabaseKeyManager
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase

/** Manual wiring (no DI framework). Everything is lazy so startup stays fast. */
class AppContainer(private val context: Context) {

    private class DbHolder(val db: StrategosDatabase?, val error: String?)

    // BLUEPRINT-QUESTION: DatabaseKeyManager throws the existing data.storage.StorageUnavailableException (the blueprint names one class; this reuses it).
    private val dbHolder: DbHolder by lazy {
        try {
            DbHolder(DbFactory.open(context, DatabaseKeyManager(context).getOrCreatePassphrase()), null)
        } catch (e: Exception) {
            DbHolder(null, e.message ?: "Secure storage unavailable")
        }
    }

    /** Null when secure storage could not be opened. Callers must treat that as "storage unavailable". */
    val database: StrategosDatabase? get() = dbHolder.db
    val storageError: String? get() = dbHolder.error
}
