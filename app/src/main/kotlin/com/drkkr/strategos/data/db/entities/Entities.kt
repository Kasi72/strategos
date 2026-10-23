package com.drkkr.strategos.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Enums are stored as their name in String columns. Timestamps are epoch milliseconds.

@Entity(tableName = "scenario", indices = [Index("updated_at")])
data class ScenarioEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "lifecycle_stage") val lifecycleStage: String,
    val version: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val json: String                       // full Scenario via kotlinx.serialization
)

@Entity(tableName = "analysis_snapshot",
    foreignKeys = [ForeignKey(entity = ScenarioEntity::class, parentColumns = ["id"], childColumns = ["scenario_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("scenario_id")])
data class SnapshotEntity(
    @PrimaryKey @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @ColumnInfo(name = "scenario_id") val scenarioId: String,
    val timestamp: Long,
    val json: String                       // full AnalysisSnapshot
)

@Entity(tableName = "recoverable_record")
data class RecoverableRecordEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "raw_json") val rawJson: String?,
    val error: String
)

@Entity(tableName = "conversation",
    foreignKeys = [ForeignKey(entity = ScenarioEntity::class, parentColumns = ["id"], childColumns = ["scenario_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("scenario_id")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "scenario_id") val scenarioId: String,
    val status: String,                    // ACTIVE | ARCHIVED
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "agent_version") val agentVersion: String,
    @ColumnInfo(name = "rolling_summary") val rollingSummary: String = "",
    @ColumnInfo(name = "summarized_through_seq") val summarizedThroughSeq: Int = 0,
    @ColumnInfo(name = "last_report_digest_json") val lastReportDigestJson: String? = null,
    @ColumnInfo(name = "last_report_scenario_version") val lastReportScenarioVersion: Int? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(tableName = "message",
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["conversation_id", "seq"], unique = true), Index("turn_id")])
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val seq: Int,                          // 1,2,3… per conversation
    @ColumnInfo(name = "turn_id") val turnId: String,
    val role: String,                      // USER | ASSISTANT | TOOL_CALL | TOOL_RESULT | EVENT | ERROR
    val text: String = "",                 // USER/ASSISTANT/EVENT/ERROR display text
    @ColumnInfo(name = "tool_name") val toolName: String? = null,
    @ColumnInfo(name = "call_id") val callId: String? = null,
    @ColumnInfo(name = "payload_json") val payloadJson: String? = null,  // TOOL_CALL arguments or TOOL_RESULT output
    @ColumnInfo(name = "ui_kind") val uiKind: String = "NONE",           // NONE | CHANGE_SET | QUESTIONS | RECOMMENDATION | WHAT_IF | STRESS_TEST
    @ColumnInfo(name = "ref_id") val refId: String? = null,              // change_set.id / what_if_run.id
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "change_set",
    foreignKeys = [
        ForeignKey(entity = ScenarioEntity::class, parentColumns = ["id"], childColumns = ["scenario_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("scenario_id"), Index("conversation_id"), Index("status")])
data class ChangeSetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "scenario_id") val scenarioId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val status: String,                    // PENDING | APPLIED | PARTIAL | REJECTED
    val summary: String,
    @ColumnInfo(name = "ops_json") val opsJson: String,                  // List<ChangeOp>
    @ColumnInfo(name = "ref_ids_json") val refIdsJson: String,           // Map<ref, uuid>
    @ColumnInfo(name = "decisions_json") val decisionsJson: String? = null, // List<OpDecision>
    @ColumnInfo(name = "apply_report_json") val applyReportJson: String? = null, // skipped ops + reasons
    @ColumnInfo(name = "base_scenario_version") val baseScenarioVersion: Int,
    @ColumnInfo(name = "applied_scenario_version") val appliedScenarioVersion: Int? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "decided_at") val decidedAt: Long? = null
)

@Entity(tableName = "memory_item",
    foreignKeys = [ForeignKey(entity = ScenarioEntity::class, parentColumns = ["id"], childColumns = ["scenario_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("scenario_id")])
data class MemoryItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "scenario_id") val scenarioId: String,
    val kind: String,                      // FACT | OPEN_QUESTION | PREFERENCE | DECISION_NOTE
    val text: String,
    @ColumnInfo(name = "fact_status") val factStatus: String,            // FactStatus name
    @ColumnInfo(name = "source_message_id") val sourceMessageId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long? = null
)

@Entity(tableName = "what_if_run",
    foreignKeys = [ForeignKey(entity = ScenarioEntity::class, parentColumns = ["id"], childColumns = ["scenario_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("scenario_id")])
data class WhatIfEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "scenario_id") val scenarioId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val label: String,
    @ColumnInfo(name = "ops_json") val opsJson: String,
    @ColumnInfo(name = "base_scenario_version") val baseScenarioVersion: Int,
    @ColumnInfo(name = "result_json") val resultJson: String,            // WhatIfComparison
    @ColumnInfo(name = "promoted_change_set_id") val promotedChangeSetId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "llm_call", indices = [Index("created_at")])
data class LlmCallEntity(                  // NO content columns (I8)
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String?,
    @ColumnInfo(name = "turn_id") val turnId: String?,
    val purpose: String,                   // TURN | SUMMARY | TEST
    val model: String,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int,
    @ColumnInfo(name = "cached_tokens") val cachedTokens: Int,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int,
    @ColumnInfo(name = "reasoning_tokens") val reasoningTokens: Int,
    @ColumnInfo(name = "latency_ms") val latencyMs: Long,
    @ColumnInfo(name = "http_status") val httpStatus: Int,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "app_setting")
data class SettingEntity(@PrimaryKey val key: String, val value: String)
