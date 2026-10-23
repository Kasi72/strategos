package com.drkkr.strategos.data.db

import android.content.Context
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object DbFactory {

    fun open(context: Context, passphrase: ByteArray): StrategosDatabase {
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(context, StrategosDatabase::class.java, "strategos.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .build()
    }

    /** No SQLCipher; Robolectric only. */
    fun inMemoryForTests(context: Context): StrategosDatabase =
        Room.inMemoryDatabaseBuilder(context, StrategosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
}
