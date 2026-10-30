package com.drkkr.strategos.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object DbFactory {

    /** Statements for 1 -> 2, kept as data so a JVM test can run them on a plain SQLite database. Mirrors schemas/.../2.json. */
    val MIGRATION_1_2_SQL: List<String> = listOf(
        "ALTER TABLE `conversation` ADD COLUMN `title` TEXT NOT NULL DEFAULT 'Main Thread'",
        "ALTER TABLE `conversation` ADD COLUMN `topic_tag` TEXT NOT NULL DEFAULT 'GENERAL'",
        "ALTER TABLE `conversation` ADD COLUMN `is_pinned` INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE IF NOT EXISTS `journal_entry` (`id` TEXT NOT NULL, `linked_scenario_id` TEXT, `encrypted_text` TEXT NOT NULL, `mood_tag` TEXT NOT NULL, `recorded_ack` TEXT NOT NULL, `sync_status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_journal_entry_created_at` ON `journal_entry` (`created_at`)",
        "CREATE INDEX IF NOT EXISTS `index_journal_entry_linked_scenario_id` ON `journal_entry` (`linked_scenario_id`)",
        "CREATE INDEX IF NOT EXISTS `index_journal_entry_sync_status` ON `journal_entry` (`sync_status`)",
        "CREATE TABLE IF NOT EXISTS `journal_post_note` (`id` TEXT NOT NULL, `journal_entry_id` TEXT NOT NULL, `scenario_id` TEXT NOT NULL, `significance` TEXT NOT NULL, `habit_or_trigger` TEXT NOT NULL, `insight_headline` TEXT NOT NULL, `practical_approach` TEXT NOT NULL, `do_this` TEXT NOT NULL, `dont_do_this` TEXT NOT NULL, `target_dimension` TEXT, `weight_delta` REAL NOT NULL, `applied` INTEGER NOT NULL, `dismissed` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`journal_entry_id`) REFERENCES `journal_entry`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`scenario_id`) REFERENCES `scenario`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_journal_post_note_journal_entry_id` ON `journal_post_note` (`journal_entry_id`)",
        "CREATE INDEX IF NOT EXISTS `index_journal_post_note_scenario_id` ON `journal_post_note` (`scenario_id`)",
        "CREATE INDEX IF NOT EXISTS `index_journal_post_note_dismissed` ON `journal_post_note` (`dismissed`)",
        "CREATE TABLE IF NOT EXISTS `trajectory_point` (`id` TEXT NOT NULL, `scenario_id` TEXT NOT NULL, `thread_id` TEXT, `source` TEXT NOT NULL, `trust_score` REAL NOT NULL, `tension_score` REAL NOT NULL, `sigmoid_progress` REAL NOT NULL, `momentum_delta` REAL NOT NULL, `consistency_streak` INTEGER NOT NULL, `active_archetype` TEXT NOT NULL, `note` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`scenario_id`) REFERENCES `scenario`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_trajectory_point_scenario_id` ON `trajectory_point` (`scenario_id`)",
        "CREATE INDEX IF NOT EXISTS `index_trajectory_point_timestamp` ON `trajectory_point` (`timestamp`)",
        "CREATE TABLE IF NOT EXISTS `quick_check_feedback` (`id` TEXT NOT NULL, `scenario_id` TEXT NOT NULL, `thread_id` TEXT, `recommended_action_label` TEXT NOT NULL, `execution_choice` TEXT NOT NULL, `counterpart_reaction` TEXT NOT NULL, `self_feeling` TEXT NOT NULL, `reflection_note` TEXT NOT NULL, `trust_delta` REAL NOT NULL, `tension_delta` REAL NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`scenario_id`) REFERENCES `scenario`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_quick_check_feedback_scenario_id` ON `quick_check_feedback` (`scenario_id`)",
        "CREATE INDEX IF NOT EXISTS `index_quick_check_feedback_created_at` ON `quick_check_feedback` (`created_at`)"
    )

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) { MIGRATION_1_2_SQL.forEach { db.execSQL(it) } }
    }

    /** Statements for 2 -> 3. Adds game_model, evidence_record, decision_certificate. Mirrors schemas/.../3.json. */
    val MIGRATION_2_3_SQL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `game_model` (`id` TEXT NOT NULL, `scenario_id` TEXT NOT NULL, `scenario_version` INTEGER NOT NULL, `game_class` TEXT NOT NULL, `player_ids_json` TEXT NOT NULL, `strategy_counts_json` TEXT NOT NULL, `utility_json` TEXT NOT NULL, `coverage_pct` INTEGER NOT NULL, `decision_state` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`scenario_id`) REFERENCES `scenario`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_game_model_scenario_id` ON `game_model` (`scenario_id`)",
        "CREATE INDEX IF NOT EXISTS `index_game_model_created_at` ON `game_model` (`created_at`)",
        "CREATE TABLE IF NOT EXISTS `evidence_record` (`id` TEXT NOT NULL, `scenario_id` TEXT NOT NULL, `game_model_id` TEXT, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, `confidence` REAL NOT NULL, `source` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`scenario_id`) REFERENCES `scenario`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`game_model_id`) REFERENCES `game_model`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_evidence_record_scenario_id` ON `evidence_record` (`scenario_id`)",
        "CREATE INDEX IF NOT EXISTS `index_evidence_record_game_model_id` ON `evidence_record` (`game_model_id`)",
        "CREATE TABLE IF NOT EXISTS `decision_certificate` (`id` TEXT NOT NULL, `scenario_id` TEXT NOT NULL, `game_model_id` TEXT NOT NULL, `equilibrium_type` TEXT NOT NULL, `recommended_action_json` TEXT NOT NULL, `deviation_gain_residual` REAL NOT NULL, `integrity` TEXT NOT NULL, `rationale` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`scenario_id`) REFERENCES `scenario`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`game_model_id`) REFERENCES `game_model`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_decision_certificate_scenario_id` ON `decision_certificate` (`scenario_id`)",
        "CREATE INDEX IF NOT EXISTS `index_decision_certificate_game_model_id` ON `decision_certificate` (`game_model_id`)",
        "CREATE INDEX IF NOT EXISTS `index_decision_certificate_created_at` ON `decision_certificate` (`created_at`)"
    )

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) { MIGRATION_2_3_SQL.forEach { db.execSQL(it) } }
    }

    fun open(context: Context, passphrase: ByteArray): StrategosDatabase {
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(context, StrategosDatabase::class.java, "strategos.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    /** No SQLCipher; Robolectric only. */
    fun inMemoryForTests(context: Context): StrategosDatabase =
        Room.inMemoryDatabaseBuilder(context, StrategosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
}
