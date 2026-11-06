package com.drkkr.strategos.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.concurrent.thread

/**
 * DEBUG BUILDS ONLY (ID0.5). A one-way, unencrypted export of the live database, so a developer can read conversations with
 * `scripts/pull-debug-db.sh`. The app never reads it: the live database is always the encrypted `strategos.db`.
 *
 * - [afterOpen] first retires a plain file left by a `power-mode` debug build (4 Nov to ID0.5), where that file WAS the live
 *   database: it is renamed once, with its -wal and -shm, to `…pre-id05.db`, never deleted and never read.
 * - Then, on a background thread, it copies every table's schema and rows over Room's own connection (no ATTACH: under SQLCipher
 *   the attachment was lost between pooled connections) into a temp file, and renames that into place. A failure logs one line
 *   and changes nothing else.
 */
object DebugDbExport {
    /** The file names one export works with; the instrumented tests use their own base name. */
    class Files(context: Context, base: String = "strategos_plain_debug") {
        val plain: File = context.getDatabasePath("$base.db")
        val retired: File = context.getDatabasePath("$base.pre-id05.db")
        val temp: File = context.getDatabasePath("$base.export-tmp.db")
        /** Written once the retire check has run, so a later start never renames this branch's own export. */
        val marker: File = context.getDatabasePath("$base.id05")
    }

    /** The copy itself; replaceable so a test can force it to throw (TC_ID_E2E_04 d). */
    @Volatile var copy: (StrategosDatabase, File) -> Unit = ::copyInto

    private const val TAG = "StrategosDebug"
    private val SIDE = listOf("", "-wal", "-shm", "-journal")

    fun afterOpen(context: Context, db: StrategosDatabase, files: Files = Files(context), background: Boolean = true) {
        retireOnce(files)
        if (background) thread(name = "debug-db-export", isDaemon = true) { export(db, files) } else export(db, files)
    }

    /** Renames a pre-ID0.5 plain database once (with its WAL, which may hold its newest rows). Never deletes or reads it. */
    fun retireOnce(f: Files) {
        if (f.marker.exists()) return
        try {
            if (f.plain.exists() && !f.retired.exists()) {
                SIDE.forEach { s -> File(f.plain.path + s).takeIf { it.exists() }?.renameTo(File(f.retired.path + s)) }
                android.util.Log.i(TAG, "kept the old plain debug database as ${f.retired.name}; the app no longer reads it")
            }
            f.marker.parentFile?.mkdirs(); f.marker.createNewFile()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "could not retire the old plain debug database (${e.javaClass.simpleName})")
        }
    }

    fun export(db: StrategosDatabase, f: Files) {
        try {
            SIDE.forEach { File(f.temp.path + it).delete() }
            copy(db, f.temp)
            SIDE.drop(1).forEach { File(f.plain.path + it).delete() }   // the export uses a rollback journal: no WAL of its own
            if (!f.temp.renameTo(f.plain)) error("rename failed")
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "debug export skipped (${e.javaClass.simpleName}); the live database is unaffected")
            SIDE.forEach { File(f.temp.path + it).delete() }
        }
    }

    /** Copies the schema (tables first, then indexes, views and triggers), every row, and the schema version into [out]. */
    private fun copyInto(db: StrategosDatabase, out: File) {
        val src = db.openHelper.readableDatabase
        val dest = SQLiteDatabase.openOrCreateDatabase(out, null)
        try {
            val schema = mutableListOf<Triple<String, String, String>>()
            src.query("SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'").use { c ->
                while (c.moveToNext()) schema += Triple(c.getString(0), c.getString(1), c.getString(2))
            }
            dest.beginTransaction()
            try {
                for ((_, name, sql) in schema.filter { it.first == "table" }) {
                    dest.execSQL(sql)
                    src.query("SELECT * FROM `${name.replace("`", "``")}`").use { c ->
                        while (c.moveToNext()) dest.insertOrThrow("`$name`", null, row(c))
                    }
                }
                schema.filter { it.first != "table" }.forEach { dest.execSQL(it.third) }
                dest.version = src.version
                dest.setTransactionSuccessful()
            } finally { dest.endTransaction() }
        } finally { dest.close() }
    }

    private fun row(c: Cursor) = ContentValues().apply {
        for (i in 0 until c.columnCount) {
            val k = c.getColumnName(i)
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> putNull(k)
                Cursor.FIELD_TYPE_INTEGER -> put(k, c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> put(k, c.getDouble(i))
                Cursor.FIELD_TYPE_BLOB -> put(k, c.getBlob(i))
                else -> put(k, c.getString(i))
            }
        }
    }
}
