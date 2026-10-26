package com.drkkr.strategos.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.drkkr.strategos.data.db.entities.*
import kotlinx.coroutines.flow.Flow

@Dao interface ScenarioDao {
    @Query("SELECT * FROM scenario ORDER BY updated_at DESC") fun observeAll(): Flow<List<ScenarioEntity>>
    @Query("SELECT * FROM scenario ORDER BY updated_at DESC") suspend fun getAll(): List<ScenarioEntity>
    @Query("SELECT * FROM scenario WHERE id = :id") suspend fun get(id: String): ScenarioEntity?
    @Upsert suspend fun upsert(e: ScenarioEntity)
    @Query("DELETE FROM scenario WHERE id = :id") suspend fun delete(id: String)
}

@Dao interface SnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(e: SnapshotEntity)
    @Query("SELECT * FROM analysis_snapshot WHERE scenario_id = :scenarioId ORDER BY timestamp DESC") suspend fun forScenario(scenarioId: String): List<SnapshotEntity>
}

@Dao interface RecoverableDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(e: List<RecoverableRecordEntity>)
    @Query("SELECT * FROM recoverable_record") fun observeAll(): Flow<List<RecoverableRecordEntity>>
}

@Dao interface ConversationDao {
    /** Messages, change sets and summaries go with it (cascade). Scenarios and memory stay. */
    @Query("DELETE FROM conversation") suspend fun deleteAll()
    @Query("SELECT * FROM conversation WHERE scenario_id = :scenarioId AND status = 'ACTIVE' ORDER BY updated_at DESC LIMIT 1") suspend fun activeFor(scenarioId: String): ConversationEntity?
    @Query("SELECT * FROM conversation WHERE id = :id") suspend fun get(id: String): ConversationEntity?
    @Upsert suspend fun upsert(e: ConversationEntity)
    @Query("SELECT * FROM conversation WHERE scenario_id = :scenarioId AND status = 'ACTIVE' ORDER BY is_pinned DESC, updated_at DESC")
    fun observeThreadsForScenario(scenarioId: String): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversation WHERE scenario_id = :scenarioId AND status = 'ACTIVE' ORDER BY is_pinned DESC, updated_at DESC")
    suspend fun listThreadsForScenario(scenarioId: String): List<ConversationEntity>
    @Query("UPDATE conversation SET title = :title, topic_tag = :topicTag, updated_at = :updatedAt WHERE id = :id")
    suspend fun renameThread(id: String, title: String, topicTag: String, updatedAt: Long)
}

@Dao interface MessageDao {
    @Query("SELECT * FROM message WHERE conversation_id = :cid ORDER BY seq ASC") fun observe(cid: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM message WHERE conversation_id = :cid ORDER BY seq ASC") suspend fun all(cid: String): List<MessageEntity>
    @Query("SELECT COALESCE(MAX(seq), 0) FROM message WHERE conversation_id = :cid") suspend fun maxSeq(cid: String): Int
    @Query("SELECT * FROM message WHERE conversation_id = :cid AND role IN ('USER','ASSISTANT') AND text != '' ORDER BY seq DESC LIMIT 1") suspend fun lastVisible(cid: String): MessageEntity?
    @Query("SELECT * FROM message WHERE conversation_id = :cid AND role = 'ASSISTANT' AND payload_json IS NOT NULL ORDER BY seq DESC LIMIT 1") suspend fun lastGuided(cid: String): MessageEntity?
    @Query("SELECT COUNT(*) FROM message m JOIN conversation c ON m.conversation_id = c.id WHERE c.scenario_id = :sid AND m.role IN ('USER','ASSISTANT') AND m.text != ''") suspend fun countVisibleForScenario(sid: String): Int
    @Insert suspend fun insert(e: MessageEntity)
    @Query("UPDATE message SET ref_id = :refId WHERE id = :id") suspend fun setRefId(id: String, refId: String)
    @Query("DELETE FROM message WHERE conversation_id = :cid") suspend fun deleteAll(cid: String)
}

@Dao interface ChangeSetDao {
    @Insert suspend fun insert(e: ChangeSetEntity)
    @Update suspend fun update(e: ChangeSetEntity)
    @Query("SELECT * FROM change_set WHERE id = :id") suspend fun get(id: String): ChangeSetEntity?
    @Query("SELECT * FROM change_set WHERE scenario_id = :sid AND status = 'PENDING' ORDER BY created_at") suspend fun pending(sid: String): List<ChangeSetEntity>
    @Query("SELECT * FROM change_set WHERE conversation_id = :cid") fun observeForConversation(cid: String): Flow<List<ChangeSetEntity>>
}

@Dao interface MemoryDao {
    @Query("SELECT * FROM memory_item WHERE scenario_id = :sid AND resolved_at IS NULL ORDER BY created_at") fun observeOpen(sid: String): Flow<List<MemoryItemEntity>>
    @Query("SELECT * FROM memory_item WHERE scenario_id = :sid AND resolved_at IS NULL ORDER BY created_at") suspend fun open(sid: String): List<MemoryItemEntity>
    @Insert suspend fun insert(e: MemoryItemEntity)
    @Query("UPDATE memory_item SET resolved_at = :at WHERE id IN (:ids) AND scenario_id = :sid") suspend fun resolve(sid: String, ids: List<String>, at: Long): Int
    @Query("UPDATE memory_item SET fact_status = :status WHERE id = :id") suspend fun setStatus(id: String, status: String)
    @Query("UPDATE memory_item SET text = :text WHERE id = :id") suspend fun setText(id: String, text: String)
    @Query("DELETE FROM memory_item WHERE id = :id") suspend fun delete(id: String)
}

@Dao interface WhatIfDao {
    @Insert suspend fun insert(e: WhatIfEntity)
    @Update suspend fun update(e: WhatIfEntity)
    @Query("SELECT * FROM what_if_run WHERE id = :id") suspend fun get(id: String): WhatIfEntity?
}

@Dao interface LlmCallDao {
    @Insert suspend fun insert(e: LlmCallEntity)
    @Query("SELECT * FROM llm_call WHERE created_at >= :since") suspend fun since(since: Long): List<LlmCallEntity>
}

@Dao interface SettingDao {
    @Query("SELECT value FROM app_setting WHERE `key` = :key") suspend fun get(key: String): String?
    @Query("SELECT value FROM app_setting WHERE `key` = :key") fun observe(key: String): Flow<String?>
    @Upsert suspend fun put(e: SettingEntity)
    @Query("DELETE FROM app_setting WHERE `key` = :key") suspend fun remove(key: String)
}

@Dao interface JournalDao {
    @Insert suspend fun insertEntry(e: JournalEntryEntity)
    @Query("UPDATE journal_entry SET sync_status = :status, mood_tag = :moodTag WHERE id = :id") suspend fun updateEntryStatus(id: String, status: String, moodTag: String)
    @Query("SELECT * FROM journal_entry WHERE (:scenarioId IS NULL OR linked_scenario_id = :scenarioId) ORDER BY created_at DESC")
    fun observeEntries(scenarioId: String?): Flow<List<JournalEntryEntity>>
    @Query("SELECT * FROM journal_entry ORDER BY created_at DESC") suspend fun allEntries(): List<JournalEntryEntity>
    @Query("SELECT * FROM journal_entry WHERE id = :id") suspend fun getEntry(id: String): JournalEntryEntity?
    @Query("SELECT * FROM journal_entry WHERE sync_status = 'PENDING_SCREEN' ORDER BY created_at ASC") suspend fun pendingScreenEntries(): List<JournalEntryEntity>
    @Insert suspend fun insertPostNote(e: JournalInsightPostNoteEntity)
    @Query("SELECT * FROM journal_post_note WHERE scenario_id = :scenarioId AND dismissed = 0 ORDER BY created_at DESC")
    fun observeActivePostNotes(scenarioId: String): Flow<List<JournalInsightPostNoteEntity>>
    @Query("SELECT * FROM journal_post_note WHERE scenario_id = :scenarioId AND dismissed = 0 ORDER BY created_at DESC")
    suspend fun activePostNotes(scenarioId: String): List<JournalInsightPostNoteEntity>
    @Query("SELECT * FROM journal_post_note WHERE id = :id") suspend fun getPostNote(id: String): JournalInsightPostNoteEntity?
    @Query("SELECT * FROM journal_post_note WHERE scenario_id = :scenarioId AND applied = 1 AND dismissed = 0 ORDER BY created_at DESC") suspend fun appliedPostNotes(scenarioId: String): List<JournalInsightPostNoteEntity>
    @Query("UPDATE journal_post_note SET applied = 1 WHERE id = :id") suspend fun markPostNoteApplied(id: String)
    @Query("UPDATE journal_post_note SET dismissed = 1 WHERE id = :id") suspend fun dismissPostNote(id: String)
}

@Dao interface TrajectoryDao {
    @Insert suspend fun insert(point: TrajectoryPointEntity)
    @Query("SELECT * FROM trajectory_point WHERE scenario_id = :scenarioId ORDER BY timestamp ASC, rowid ASC") fun observeForScenario(scenarioId: String): Flow<List<TrajectoryPointEntity>>
    @Query("SELECT * FROM trajectory_point WHERE scenario_id = :scenarioId ORDER BY timestamp ASC, rowid ASC") suspend fun forScenario(scenarioId: String): List<TrajectoryPointEntity>
    @Query("SELECT * FROM trajectory_point WHERE scenario_id = :scenarioId ORDER BY timestamp DESC, rowid DESC LIMIT 1") suspend fun latestForScenario(scenarioId: String): TrajectoryPointEntity?
}

@Dao interface QuickCheckDao {
    @Insert suspend fun insert(item: QuickCheckFeedbackEntity)
    @Query("SELECT * FROM quick_check_feedback WHERE scenario_id = :scenarioId ORDER BY created_at ASC") suspend fun forScenario(scenarioId: String): List<QuickCheckFeedbackEntity>
}
