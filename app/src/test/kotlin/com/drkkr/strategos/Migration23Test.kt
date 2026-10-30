package com.drkkr.strategos

import com.drkkr.strategos.data.db.DbFactory
import org.junit.Assert.*
import org.junit.Test

/**
 * JVM-only migration test for 2 -> 3.
 *
 * Executes MIGRATION_2_3_SQL against an in-memory SQLite database that already
 * has the v2 schema (applied via MIGRATION_1_2_SQL on top of the v1 baseline),
 * then verifies the new tables and their indexes exist.
 *
 * No Android runtime needed: uses java.sql.DriverManager directly (bundled SQLite JVM driver via robolectric deps).
 */
class Migration23Test {

    // Minimal v1 baseline: just the tables MIGRATION_1_2 depends on (scenario, conversation).
    private val V1_BASELINE_SQL = listOf(
        "CREATE TABLE IF NOT EXISTS `scenario` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `body_json` TEXT NOT NULL, `version` INTEGER NOT NULL, `lifecycle_stage` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `analysis_snapshot` (`id` TEXT NOT NULL, `scenario_id` TEXT NOT NULL, `scenario_version` INTEGER NOT NULL, `report_json` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `recoverable_record` (`id` TEXT NOT NULL, `raw_json` TEXT, `error` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `conversation` (`id` TEXT NOT NULL, `scenario_id` TEXT NOT NULL, `status` TEXT NOT NULL, `model_id` TEXT NOT NULL, `agent_version` TEXT NOT NULL, `rolling_summary` TEXT NOT NULL DEFAULT '', `summarized_through_seq` INTEGER NOT NULL DEFAULT 0, `last_report_digest_json` TEXT, `last_report_scenario_version` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `message` (`id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `seq` INTEGER NOT NULL, `turn_id` TEXT NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL DEFAULT '', `tool_name` TEXT, `call_id` TEXT, `payload_json` TEXT, `ui_kind` TEXT NOT NULL DEFAULT 'NONE', `ref_id` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `change_set` (`id` TEXT NOT NULL, `scenario_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `status` TEXT NOT NULL, `summary` TEXT NOT NULL, `ops_json` TEXT NOT NULL, `ref_ids_json` TEXT NOT NULL, `decisions_json` TEXT, `apply_report_json` TEXT, `base_scenario_version` INTEGER NOT NULL, `applied_scenario_version` INTEGER, `created_at` INTEGER NOT NULL, `decided_at` INTEGER, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `memory_item` (`id` TEXT NOT NULL, `scenario_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, `fact_status` TEXT NOT NULL, `source_message_id` TEXT, `created_at` INTEGER NOT NULL, `resolved_at` INTEGER, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `what_if_run` (`id` TEXT NOT NULL, `scenario_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `label` TEXT NOT NULL, `ops_json` TEXT NOT NULL, `base_scenario_version` INTEGER NOT NULL, `result_json` TEXT NOT NULL, `promoted_change_set_id` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `llm_call` (`id` TEXT NOT NULL, `conversation_id` TEXT, `turn_id` TEXT, `purpose` TEXT NOT NULL, `model` TEXT NOT NULL, `input_tokens` INTEGER NOT NULL, `cached_tokens` INTEGER NOT NULL, `output_tokens` INTEGER NOT NULL, `reasoning_tokens` INTEGER NOT NULL, `latency_ms` INTEGER NOT NULL, `http_status` INTEGER NOT NULL, `error_code` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `app_setting` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))"
    )

    @Test
    fun `MIGRATION_2_3_SQL creates game_model table`() {
        withMigratedDb { conn ->
            val tables = tablesIn(conn)
            assertTrue("game_model must exist after v3 migration", "game_model" in tables)
        }
    }

    @Test
    fun `MIGRATION_2_3_SQL creates evidence_record table`() {
        withMigratedDb { conn ->
            assertTrue("evidence_record must exist after v3 migration", "evidence_record" in tablesIn(conn))
        }
    }

    @Test
    fun `MIGRATION_2_3_SQL creates decision_certificate table`() {
        withMigratedDb { conn ->
            assertTrue("decision_certificate must exist after v3 migration", "decision_certificate" in tablesIn(conn))
        }
    }

    @Test
    fun `game_model table has expected columns`() {
        withMigratedDb { conn ->
            val cols = columnsOf(conn, "game_model")
            for (col in listOf("id", "scenario_id", "scenario_version", "game_class", "player_ids_json",
                    "strategy_counts_json", "utility_json", "coverage_pct", "decision_state", "created_at", "updated_at")) {
                assertTrue("game_model must have column $col", col in cols)
            }
        }
    }

    @Test
    fun `evidence_record table has expected columns`() {
        withMigratedDb { conn ->
            val cols = columnsOf(conn, "evidence_record")
            for (col in listOf("id", "scenario_id", "game_model_id", "kind", "text", "confidence", "source", "created_at")) {
                assertTrue("evidence_record must have column $col", col in cols)
            }
        }
    }

    @Test
    fun `decision_certificate table has expected columns`() {
        withMigratedDb { conn ->
            val cols = columnsOf(conn, "decision_certificate")
            for (col in listOf("id", "scenario_id", "game_model_id", "equilibrium_type",
                    "recommended_action_json", "deviation_gain_residual", "integrity", "rationale", "created_at")) {
                assertTrue("decision_certificate must have column $col", col in cols)
            }
        }
    }

    @Test
    fun `can insert and query game_model row`() {
        withMigratedDb { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("""
                    INSERT INTO scenario(id,title,body_json,version,lifecycle_stage,created_at,updated_at)
                    VALUES('s1','T','{}',1,'ACTIVE',0,0)
                """.trimIndent())
                st.executeUpdate("""
                    INSERT INTO game_model(id,scenario_id,scenario_version,game_class,player_ids_json,strategy_counts_json,utility_json,coverage_pct,decision_state,created_at,updated_at)
                    VALUES('gm1','s1',1,'FINITE_NORMAL_FORM','["p1","p2"]','[2,2]','[[]]',100,'EXPLORATORY',1000,1000)
                """.trimIndent())
            }
            conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT decision_state FROM game_model WHERE id='gm1'")
                assertTrue(rs.next())
                assertEquals("EXPLORATORY", rs.getString(1))
            }
        }
    }

    @Test
    fun `can insert decision_certificate linked to game_model`() {
        withMigratedDb { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("INSERT INTO scenario(id,title,body_json,version,lifecycle_stage,created_at,updated_at) VALUES('s2','T','{}',1,'ACTIVE',0,0)")
                st.executeUpdate("INSERT INTO game_model(id,scenario_id,scenario_version,game_class,player_ids_json,strategy_counts_json,utility_json,coverage_pct,decision_state,created_at,updated_at) VALUES('gm2','s2',1,'ZERO_SUM_2P','[]','[]','[]',100,'VERIFIED',1000,1000)")
                st.executeUpdate("INSERT INTO decision_certificate(id,scenario_id,game_model_id,equilibrium_type,recommended_action_json,deviation_gain_residual,integrity,rationale,created_at) VALUES('dc1','s2','gm2','MIXED_NASH','{}',0.0,'CERTIFIED','Nash equilibrium verified',2000)")
            }
            conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT integrity FROM decision_certificate WHERE id='dc1'")
                assertTrue(rs.next())
                assertEquals("CERTIFIED", rs.getString(1))
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun withMigratedDb(block: (java.sql.Connection) -> Unit) {
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            conn.createStatement().use { st ->
                V1_BASELINE_SQL.forEach { st.executeUpdate(it) }
                DbFactory.MIGRATION_1_2_SQL.forEach { st.executeUpdate(it) }
                DbFactory.MIGRATION_2_3_SQL.forEach { st.executeUpdate(it) }
            }
            block(conn)
        } finally {
            conn.close()
        }
    }

    private fun tablesIn(conn: java.sql.Connection): Set<String> {
        val rs = conn.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type='table'")
        val names = mutableSetOf<String>()
        while (rs.next()) names += rs.getString(1)
        return names
    }

    private fun columnsOf(conn: java.sql.Connection, table: String): Set<String> {
        val rs = conn.createStatement().executeQuery("PRAGMA table_info(`$table`)")
        val cols = mutableSetOf<String>()
        while (rs.next()) cols += rs.getString("name")
        return cols
    }
}
