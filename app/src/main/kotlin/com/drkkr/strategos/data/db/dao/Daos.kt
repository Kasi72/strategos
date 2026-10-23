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
    @Query("SELECT * FROM conversation WHERE scenario_id = :scenarioId AND status = 'ACTIVE' ORDER BY updated_at DESC LIMIT 1") suspend fun activeFor(scenarioId: String): ConversationEntity?
    @Query("SELECT * FROM conversation WHERE id = :id") suspend fun get(id: String): ConversationEntity?
    @Upsert suspend fun upsert(e: ConversationEntity)
}

@Dao interface MessageDao {
    @Query("SELECT * FROM message WHERE conversation_id = :cid ORDER BY seq ASC") fun observe(cid: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM message WHERE conversation_id = :cid ORDER BY seq ASC") suspend fun all(cid: String): List<MessageEntity>
    @Query("SELECT COALESCE(MAX(seq), 0) FROM message WHERE conversation_id = :cid") suspend fun maxSeq(cid: String): Int
    @Insert suspend fun insert(e: MessageEntity)
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
