package com.sovereignops.sms.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks migration 2→3 against the schemas Room generates from the entities (app/schemas):
 * every other table is byte-for-byte unchanged, no ledger column is dropped or altered, and
 * each added column is exactly what the migration's ALTER adds (type, NOT NULL, default).
 * Room validates the same three properties when it opens a migrated database.
 */
class VaultMigrationTest {
    private data class Col(val type: String, val notNull: Boolean, val default: String?)

    private fun schema(v: Int): Map<String, JsonObject> {
        val dir = listOf(File("schemas"), File("app/schemas")).first { it.exists() }
        val doc = Json.parseToJsonElement(File(dir, "com.sovereignops.sms.vault.VaultDatabase/$v.json").readText()).jsonObject
        return doc.getValue("database").jsonObject.getValue("entities").jsonArray
            .associate { it.jsonObject.getValue("tableName").jsonPrimitive.content to it.jsonObject }
    }

    private fun columns(t: JsonObject) = t.getValue("fields").jsonArray.associate { f ->
        val o = f.jsonObject
        o.getValue("columnName").jsonPrimitive.content to Col(
            o.getValue("affinity").jsonPrimitive.content,
            o["notNull"]?.jsonPrimitive?.boolean ?: false,
            o["defaultValue"]?.jsonPrimitive?.content,
        )
    }

    private val alter = Regex("""ALTER TABLE `(\w+)` ADD COLUMN `(\w+)` (\w+)( NOT NULL)?(?: DEFAULT (\S+))?""")

    @Test
    fun `migration 2 to 3 is additive and matches the generated schema`() {
        val v2 = schema(2)
        val v3 = schema(3)
        assertEquals(v2.keys, v3.keys)
        for (t in v2.keys - "ledger") assertEquals("$t changed", v2.getValue(t)["createSql"], v3.getValue(t)["createSql"])

        val before = columns(v2.getValue("ledger"))
        val after = columns(v3.getValue("ledger"))
        for ((name, col) in before) assertEquals("ledger.$name must not change", col, after[name])

        val added = VaultMigrations.SQL_2_3.associate { sql ->
            val m = alter.matchEntire(sql) ?: error("not an additive ALTER: $sql")
            assertEquals("ledger", m.groupValues[1])
            m.groupValues[2] to Col(m.groupValues[3], m.groupValues[4].isNotEmpty(), m.groupValues[5].ifEmpty { null })
        }
        assertEquals(after.keys - before.keys, added.keys)
        for ((name, col) in added) {
            assertEquals("ledger.$name", after.getValue(name), col)
            assertTrue("ledger.$name needs a default so existing rows stay valid", !col.notNull || col.default != null)
        }
    }

    @Test
    fun `existing ledger rows become legacy days`() {
        val basis = VaultMigrations.SQL_2_3.single { "`basis`" in it }
        assertTrue(basis.endsWith("DEFAULT ${LedgerRow.BASIS_LEGACY}"))
    }
}
