package com.drkkr.strategos

import com.drkkr.strategos.data.db.DbFactory
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 3 -> 4 must produce exactly the column Room expects. Room validates the migrated table against the entity and crashes the app
 * on launch ("Migration didn't properly handle") if the column definition differs, e.g. a missing DEFAULT. A fresh in-memory
 * database never runs the migration, so the crash only shows on a phone that already holds a v3 database.
 */
class Migration34Test {
    private val schema4 = File("schemas/com.drkkr.strategos.data.db.StrategosDatabase/4.json").readText()

    @Test fun `migration column definition equals the exported v4 conversation table definition`() {
        val add = DbFactory.MIGRATION_3_4_SQL.single { it.contains("prior_round_conversation_id") }
        val migrated = add.substringAfter("ADD COLUMN ").trim()
        // Room's createSql lists the column as `name` TYPE [DEFAULT x], followed by the next column or ", PRIMARY KEY".
        val exported = Regex("""`prior_round_conversation_id` [^,]*""").find(schema4)!!.value.trim()
        assertEquals(exported, migrated)
    }
}
