package com.drkkr.strategos.server.persistence

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * One JDBC connection, SQLite locally or Postgres on a server (DATABASE_URL). The SQL here is the subset both accept
 * (TEXT/BIGINT/DOUBLE PRECISION, `ON CONFLICT ... DO UPDATE`), so moving to Postgres is a URL change.
 * Calls are serialised: SQLite allows one writer, and a single-user server needs nothing more.
 */
class Database(url: String) : AutoCloseable {
    private val conn: Connection = DriverManager.getConnection(url)
    val isSqlite = url.startsWith("jdbc:sqlite")

    init {
        if (isSqlite) conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        SCHEMA.forEach { sql -> conn.createStatement().use { it.execute(sql) } }
        // Columns added after the first release: added in place, ignored when already there.
        MIGRATIONS.forEach { sql -> runCatching { conn.createStatement().use { it.execute(sql) } } }
    }

    @Synchronized
    fun update(sql: String, vararg args: Any?): Int = conn.prepareStatement(sql).use { bind(it, args); it.executeUpdate() }

    @Synchronized
    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }

    fun <T> one(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = query(sql, *args, map = map).firstOrNull()

    @Synchronized
    fun <T> transaction(block: () -> T): T {
        val auto = conn.autoCommit
        conn.autoCommit = false
        return try { block().also { conn.commit() } } catch (e: Throwable) { conn.rollback(); throw e } finally { conn.autoCommit = auto }
    }

    fun ping(): Boolean = runCatching { one("SELECT 1") { it.getInt(1) } == 1 }.getOrDefault(false)

    override fun close() = conn.close()

    private fun bind(st: PreparedStatement, args: Array<out Any?>) = args.forEachIndexed { i, a ->
        when (a) {
            null -> st.setObject(i + 1, null)
            is Boolean -> st.setInt(i + 1, if (a) 1 else 0)
            is Int -> st.setInt(i + 1, a)
            is Long -> st.setLong(i + 1, a)
            is Double -> st.setDouble(i + 1, a)
            else -> st.setString(i + 1, a.toString())
        }
    }

    companion object {
        val MIGRATIONS = listOf("ALTER TABLE turns ADD COLUMN cards_json TEXT")
        val SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS projects (
                id TEXT PRIMARY KEY, title TEXT NOT NULL, domain_type TEXT NOT NULL, scenario_json TEXT NOT NULL,
                trust DOUBLE PRECISION NOT NULL, tension DOUBLE PRECISION NOT NULL,
                created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, archived INTEGER NOT NULL DEFAULT 0)""",
            """CREATE TABLE IF NOT EXISTS threads (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE, title TEXT NOT NULL,
                rolling_summary TEXT NOT NULL DEFAULT '', summarized_through INTEGER NOT NULL DEFAULT 0,
                turn_count INTEGER NOT NULL DEFAULT 0, last_active_at BIGINT NOT NULL, last_report_json TEXT)""",
            """CREATE TABLE IF NOT EXISTS turns (
                id TEXT PRIMARY KEY, thread_id TEXT NOT NULL REFERENCES threads(id) ON DELETE CASCADE, project_id TEXT NOT NULL,
                turn_index INTEGER NOT NULL, user_message TEXT NOT NULL, sketch_json TEXT, directive_json TEXT,
                assistant_reply TEXT NOT NULL, trust DOUBLE PRECISION NOT NULL, tension DOUBLE PRECISION NOT NULL, ts BIGINT NOT NULL)""",
            "CREATE INDEX IF NOT EXISTS turns_thread ON turns(thread_id, turn_index)",
            """CREATE TABLE IF NOT EXISTS journal (
                id TEXT PRIMARY KEY, project_id TEXT, content TEXT NOT NULL, encrypted INTEGER NOT NULL DEFAULT 0,
                affected_json TEXT NOT NULL DEFAULT '[]', post_do TEXT, post_dont TEXT, significant INTEGER NOT NULL DEFAULT 0,
                created_at BIGINT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS post_notes (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE, journal_id TEXT NOT NULL,
                habit TEXT NOT NULL, do_this TEXT NOT NULL, dont_do_this TEXT NOT NULL, created_at BIGINT NOT NULL)""",
            "CREATE TABLE IF NOT EXISTS settings (k TEXT PRIMARY KEY, v TEXT NOT NULL)",
            """CREATE TABLE IF NOT EXISTS memory_episodes (
                id TEXT PRIMARY KEY, group_id TEXT NOT NULL, name TEXT NOT NULL, body TEXT NOT NULL, source TEXT NOT NULL,
                ref_time BIGINT NOT NULL)""",
            "CREATE INDEX IF NOT EXISTS memory_episodes_group ON memory_episodes(group_id, ref_time)",
            """CREATE TABLE IF NOT EXISTS memory_facts (
                id TEXT PRIMARY KEY, group_id TEXT NOT NULL, subject TEXT NOT NULL, predicate TEXT NOT NULL, statement TEXT NOT NULL,
                valid_from BIGINT NOT NULL, invalid_at BIGINT, episode_name TEXT NOT NULL)""",
            "CREATE INDEX IF NOT EXISTS memory_facts_group ON memory_facts(group_id, subject, predicate)",
        )
    }
}
