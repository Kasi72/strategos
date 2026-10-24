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
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    // Argon: several ACTIVE rows per scenario are named threads of one project.
    @ColumnInfo(name = "title", defaultValue = "'Main Thread'") val title: String = "Main Thread",
    @ColumnInfo(name = "topic_tag", defaultValue = "'GENERAL'") val topicTag: String = "GENERAL",
    @ColumnInfo(name = "is_pinned", defaultValue = "0") val isPinned: Boolean = false
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

/** Space 2: private reflection journal entry. `encryptedText` is written through JournalCrypto, never as plain text. */
@Entity(
    tableName = "journal_entry",
    indices = [Index("created_at"), Index("linked_scenario_id"), Index("sync_status")]
)
data class JournalEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "linked_scenario_id") val linkedScenarioId: String? = null,   // null = global entry
    @ColumnInfo(name = "encrypted_text") val encryptedText: String,
    @ColumnInfo(name = "mood_tag") val moodTag: String = "NEUTRAL",                  // CALM | ANXIOUS | FRUSTRATED | HOPEFUL | DRAINED | NEUTRAL
    @ColumnInfo(name = "recorded_ack") val recordedAck: String = "Recorded privately 🍃",
    @ColumnInfo(name = "sync_status") val syncStatus: String = "RECORDED",           // RECORDED | SCREENED | PENDING_SCREEN
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/** A journal insight attached to one project. */
@Entity(
    tableName = "journal_post_note",
    foreignKeys = [
        ForeignKey(entity = JournalEntryEntity::class, parentColumns = ["id"], childColumns = ["journal_entry_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ScenarioEntity::class, parentColumns = ["id"], childColumns = ["scenario_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("journal_entry_id"), Index("scenario_id"), Index("dismissed")]
)
data class JournalInsightPostNoteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "journal_entry_id") val journalEntryId: String,
    @ColumnInfo(name = "scenario_id") val scenarioId: String,
    val significance: String,                                                        // HIGH | MODERATE
    @ColumnInfo(name = "habit_or_trigger") val habitOrTrigger: String,
    @ColumnInfo(name = "insight_headline") val insightHeadline: String,
    @ColumnInfo(name = "practical_approach") val practicalApproach: String,
    @ColumnInfo(name = "do_this") val doThis: String,
    @ColumnInfo(name = "dont_do_this") val dontDoThis: String,
    @ColumnInfo(name = "target_dimension") val targetDimension: String? = null,
    @ColumnInfo(name = "weight_delta") val weightDelta: Double = 0.0,
    val applied: Boolean = false,
    val dismissed: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/** Space 3: one point of the project's trajectory series. */
@Entity(
    tableName = "trajectory_point",
    foreignKeys = [ForeignKey(entity = ScenarioEntity::class, parentColumns = ["id"], childColumns = ["scenario_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("scenario_id"), Index("timestamp")]
)
data class TrajectoryPointEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "scenario_id") val scenarioId: String,
    @ColumnInfo(name = "thread_id") val threadId: String? = null,
    val source: String,                                                              // INITIAL | THREAD_TURN | QUICK_CHECK | JOURNAL_SHIFT
    @ColumnInfo(name = "trust_score") val trustScore: Double,
    @ColumnInfo(name = "tension_score") val tensionScore: Double,
    @ColumnInfo(name = "sigmoid_progress") val sigmoidProgress: Double,
    @ColumnInfo(name = "momentum_delta") val momentumDelta: Double,
    @ColumnInfo(name = "consistency_streak") val consistencyStreak: Int,
    @ColumnInfo(name = "active_archetype") val activeArchetype: String,
    val note: String = "",
    val timestamp: Long
)

/** Space 3: the 10-second quick-check log. */
@Entity(
    tableName = "quick_check_feedback",
    foreignKeys = [ForeignKey(entity = ScenarioEntity::class, parentColumns = ["id"], childColumns = ["scenario_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("scenario_id"), Index("created_at")]
)
data class QuickCheckFeedbackEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "scenario_id") val scenarioId: String,
    @ColumnInfo(name = "thread_id") val threadId: String? = null,
    @ColumnInfo(name = "recommended_action_label") val recommendedActionLabel: String,
    @ColumnInfo(name = "execution_choice") val executionChoice: String,
    @ColumnInfo(name = "counterpart_reaction") val counterpartReaction: String,
    @ColumnInfo(name = "self_feeling") val selfFeeling: String,
    @ColumnInfo(name = "reflection_note") val reflectionNote: String = "",
    @ColumnInfo(name = "trust_delta") val trustDelta: Double,
    @ColumnInfo(name = "tension_delta") val tensionDelta: Double,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
