package com.drkkr.strategos.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.drkkr.strategos.data.db.dao.*
import com.drkkr.strategos.data.db.entities.*

@Database(
    entities = [
        ScenarioEntity::class, SnapshotEntity::class, RecoverableRecordEntity::class,
        ConversationEntity::class, MessageEntity::class, ChangeSetEntity::class,
        MemoryItemEntity::class, WhatIfEntity::class, LlmCallEntity::class, SettingEntity::class,
        JournalEntryEntity::class, JournalInsightPostNoteEntity::class, TrajectoryPointEntity::class, QuickCheckFeedbackEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class StrategosDatabase : RoomDatabase() {
    abstract fun scenarioDao(): ScenarioDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun recoverableDao(): RecoverableDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun changeSetDao(): ChangeSetDao
    abstract fun memoryDao(): MemoryDao
    abstract fun whatIfDao(): WhatIfDao
    abstract fun llmCallDao(): LlmCallDao
    abstract fun settingDao(): SettingDao
    abstract fun journalDao(): JournalDao
    abstract fun trajectoryDao(): TrajectoryDao
    abstract fun quickCheckDao(): QuickCheckDao
}
