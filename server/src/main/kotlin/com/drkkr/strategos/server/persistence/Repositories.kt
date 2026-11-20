package com.drkkr.strategos.server.persistence

import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

enum class DomainType { PERSONAL_RELATIONSHIP, COMMERCIAL_NEGOTIATION, COALITION_ORG, GENERAL }

@Serializable
data class Project(
    val id: String, val title: String, val domainType: DomainType, val scenario: Scenario,
    val trust: Double, val tension: Double, val createdAt: Long, val updatedAt: Long, val archived: Boolean,
)

@Serializable
data class TopicThread(
    val id: String, val projectId: String, val title: String, val rollingSummary: String, val summarizedThrough: Int,
    val turnCount: Int, val lastActiveAt: Long, val lastReportJson: String? = null,
)

@Serializable
data class TurnRecord(
    val id: String, val threadId: String, val projectId: String, val turnIndex: Int, val userMessage: String,
    val extractedSketchJson: String?, val engineDirectiveJson: String?, val assistantReply: String,
    val trustScore: Double, val tensionScore: Double, val timestamp: Long,
)

@Serializable
data class JournalEntry(
    val id: String, val projectId: String?, val content: String, val affectedProjectIds: List<String>,
    val postNoteDo: String?, val postNoteDont: String?, val isSignificantShift: Boolean, val createdAt: Long,
)

@Serializable
data class PostNote(
    val id: String, val projectId: String, val journalId: String, val habitOrTrigger: String,
    val doThis: String, val dontDoThis: String, val createdAt: Long,
)

val StoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/** Projects and their authoritative scenario (the engine's model), with the EMA-smoothed trust and tension. */
class ProjectRepository(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    fun create(title: String, domainType: DomainType, description: String = ""): Project {
        val now = clock()
        val id = UUID.randomUUID().toString()
        val p = Project(id, title, domainType, Scenario(id = id, title = title, description = description), 50.0, 50.0, now, now, false)
        db.update("INSERT INTO projects VALUES (?,?,?,?,?,?,?,?,?)", p.id, p.title, p.domainType.name,
            StoreJson.encodeToString(Scenario.serializer(), p.scenario), p.trust, p.tension, now, now, false)
        return p
    }

    fun get(id: String): Project? = db.one("SELECT * FROM projects WHERE id = ?", id, map = ::row)
    fun list(includeArchived: Boolean = false): List<Project> =
        db.query("SELECT * FROM projects ${if (includeArchived) "" else "WHERE archived = 0"} ORDER BY updated_at DESC", map = ::row)

    fun saveScenario(id: String, scenario: Scenario) {
        db.update("UPDATE projects SET scenario_json = ?, updated_at = ? WHERE id = ?",
            StoreJson.encodeToString(Scenario.serializer(), scenario), clock(), id)
    }

    fun saveTrajectory(id: String, trust: Double, tension: Double) {
        db.update("UPDATE projects SET trust = ?, tension = ?, updated_at = ? WHERE id = ?", trust, tension, clock(), id)
    }

    fun patch(id: String, title: String?, domainType: DomainType?, archived: Boolean?): Project? {
        val p = get(id) ?: return null
        db.update("UPDATE projects SET title = ?, domain_type = ?, archived = ?, updated_at = ? WHERE id = ?",
            title ?: p.title, (domainType ?: p.domainType).name, archived ?: p.archived, clock(), id)
        return get(id)
    }

    fun delete(id: String): Boolean = db.update("DELETE FROM projects WHERE id = ?", id) > 0

    private fun row(rs: java.sql.ResultSet) = Project(
        rs.getString("id"), rs.getString("title"), DomainType.valueOf(rs.getString("domain_type")),
        StoreJson.decodeFromString(Scenario.serializer(), rs.getString("scenario_json")),
        rs.getDouble("trust"), rs.getDouble("tension"), rs.getLong("created_at"), rs.getLong("updated_at"), rs.getInt("archived") == 1,
    )
}

class ThreadRepository(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    fun create(projectId: String, title: String): TopicThread {
        val t = TopicThread(UUID.randomUUID().toString(), projectId, title, "", 0, 0, clock())
        db.update("INSERT INTO threads (id, project_id, title, last_active_at) VALUES (?,?,?,?)", t.id, projectId, title, t.lastActiveAt)
        return t
    }

    fun get(id: String): TopicThread? = db.one("SELECT * FROM threads WHERE id = ?", id, map = ::row)
    fun list(projectId: String): List<TopicThread> =
        db.query("SELECT * FROM threads WHERE project_id = ? ORDER BY last_active_at DESC", projectId, map = ::row)

    fun touch(id: String, turnCount: Int, lastReportJson: String?) {
        db.update("UPDATE threads SET turn_count = ?, last_active_at = ?, last_report_json = COALESCE(?, last_report_json) WHERE id = ?",
            turnCount, clock(), lastReportJson, id)
    }

    fun saveSummary(id: String, summary: String, through: Int) {
        db.update("UPDATE threads SET rolling_summary = ?, summarized_through = ? WHERE id = ?", summary, through, id)
    }

    private fun row(rs: java.sql.ResultSet) = TopicThread(
        rs.getString("id"), rs.getString("project_id"), rs.getString("title"), rs.getString("rolling_summary"),
        rs.getInt("summarized_through"), rs.getInt("turn_count"), rs.getLong("last_active_at"), rs.getString("last_report_json"),
    )
}

class TurnRepository(private val db: Database) {
    fun append(t: TurnRecord) {
        db.update("INSERT INTO turns VALUES (?,?,?,?,?,?,?,?,?,?,?)", t.id, t.threadId, t.projectId, t.turnIndex, t.userMessage,
            t.extractedSketchJson, t.engineDirectiveJson, t.assistantReply, t.trustScore, t.tensionScore, t.timestamp)
    }

    fun forThread(threadId: String): List<TurnRecord> =
        db.query("SELECT * FROM turns WHERE thread_id = ? ORDER BY turn_index", threadId, map = ::row)

    fun last(threadId: String, n: Int): List<TurnRecord> =
        db.query("SELECT * FROM turns WHERE thread_id = ? ORDER BY turn_index DESC LIMIT ?", threadId, n, map = ::row).reversed()

    fun count(threadId: String): Int = db.one("SELECT COUNT(*) FROM turns WHERE thread_id = ?", threadId) { it.getInt(1) } ?: 0

    private fun row(rs: java.sql.ResultSet) = TurnRecord(
        rs.getString("id"), rs.getString("thread_id"), rs.getString("project_id"), rs.getInt("turn_index"), rs.getString("user_message"),
        rs.getString("sketch_json"), rs.getString("directive_json"), rs.getString("assistant_reply"),
        rs.getDouble("trust"), rs.getDouble("tension"), rs.getLong("ts"),
    )
}

/** Journal entries are encrypted at rest when JOURNAL_ENCRYPTION_KEY is set (SDD §3.1). */
class JournalRepository(private val db: Database, private val cipher: JournalCipher?, private val clock: () -> Long = System::currentTimeMillis) {
    fun save(projectId: String?, content: String): JournalEntry {
        val e = JournalEntry(UUID.randomUUID().toString(), projectId, content, emptyList(), null, null, false, clock())
        db.update("INSERT INTO journal (id, project_id, content, encrypted, created_at) VALUES (?,?,?,?,?)",
            e.id, projectId, cipher?.encrypt(content) ?: content, cipher != null, e.createdAt)
        return e
    }

    fun markScreened(id: String, affected: List<String>, doThis: String?, dontDoThis: String?, significant: Boolean) {
        db.update("UPDATE journal SET affected_json = ?, post_do = ?, post_dont = ?, significant = ? WHERE id = ?",
            StoreJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(String.serializer()), affected),
            doThis, dontDoThis, significant, id)
    }

    fun get(id: String): JournalEntry? = db.one("SELECT * FROM journal WHERE id = ?", id, map = ::row)
    fun list(limit: Int = 100): List<JournalEntry> = db.query("SELECT * FROM journal ORDER BY created_at DESC LIMIT ?", limit, map = ::row)

    fun addPostNote(projectId: String, journalId: String, habit: String, doThis: String, dontDoThis: String): PostNote {
        val n = PostNote(UUID.randomUUID().toString(), projectId, journalId, habit, doThis, dontDoThis, clock())
        db.update("INSERT INTO post_notes VALUES (?,?,?,?,?,?,?)", n.id, projectId, journalId, habit, doThis, dontDoThis, n.createdAt)
        return n
    }

    fun postNotes(projectId: String): List<PostNote> = db.query("SELECT * FROM post_notes WHERE project_id = ? ORDER BY created_at DESC", projectId) { rs ->
        PostNote(rs.getString("id"), rs.getString("project_id"), rs.getString("journal_id"), rs.getString("habit"),
            rs.getString("do_this"), rs.getString("dont_do_this"), rs.getLong("created_at"))
    }

    private fun row(rs: java.sql.ResultSet): JournalEntry {
        val raw = rs.getString("content")
        val content = if (rs.getInt("encrypted") == 1) cipher?.decrypt(raw) ?: "(encrypted)" else raw
        return JournalEntry(
            rs.getString("id"), rs.getString("project_id"), content,
            StoreJson.decodeFromString<List<String>>(rs.getString("affected_json")),
            rs.getString("post_do"), rs.getString("post_dont"), rs.getInt("significant") == 1, rs.getLong("created_at"),
        )
    }
}

/** Non-secret settings only (provider, model, base URL). The API key is held in memory and never written (CLAUDE.md). */
class SettingsRepository(private val db: Database) {
    fun get(key: String): String? = db.one("SELECT v FROM settings WHERE k = ?", key) { it.getString(1) }
    fun put(key: String, value: String) {
        db.update("INSERT INTO settings (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v", key, value)
    }
}
