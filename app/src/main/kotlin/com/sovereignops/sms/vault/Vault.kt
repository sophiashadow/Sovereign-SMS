package com.sovereignops.sms.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Database(
    entities = [CardRow::class, QuarantineRow::class, DismissalRow::class, TraceRow::class, LedgerRow::class, CounterRow::class, ResponseTimeRow::class],
    version = 2,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun dao(): VaultDao
}

/**
 * The encrypted vault (Room + SQLCipher).
 *
 * Keys: one Android Keystore AES-256-GCM key (the KEK, non-exportable, not bound to user
 * authentication so biometric enrolment changes do not invalidate it) wraps a random
 * 32-byte database key (the DEK). Only the wrapped DEK is stored, in a private prefs
 * file. No per-thread or per-message Keystore keys.
 *
 * Recovery: if the DEK cannot be unwrapped (Keystore reset, data restored onto another
 * device) or the database will not open with it, the old database file and wrapped key
 * are moved aside under `unreadable-<time>` names, never deleted, and a fresh vault is
 * created. [recoveredAt] tells the UI this happened.
 *
 * Backup: the database, its journal and the wrapped key are excluded from cloud backup
 * and device-to-device transfer (hardware Keystore keys never migrate, so a copied vault
 * would be unreadable anyway).
 */
object Vault {
    const val DB_NAME = "sovereign_vault.db"
    const val KEY_FILE = "sovereign_vault_key"
    private const val KEY_DEK = "dek"
    private const val KEY_RECOVERED = "recovered_at"
    private const val ALIAS = "sovereign_vault_kek_v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV = 12

    @Volatile
    private var db: VaultDatabase? = null

    fun dao(context: Context): VaultDao = database(context).dao()

    fun database(context: Context): VaultDatabase = db ?: synchronized(this) {
        db ?: open(context.applicationContext).also { db = it }
    }

    fun recoveredAt(context: Context): Long =
        context.applicationContext.getSharedPreferences(KEY_FILE, Context.MODE_PRIVATE).getLong(KEY_RECOVERED, 0L)

    fun clearRecoveredNotice(context: Context) {
        context.applicationContext.getSharedPreferences(KEY_FILE, Context.MODE_PRIVATE).edit().remove(KEY_RECOVERED).apply()
    }

    private fun open(context: Context): VaultDatabase {
        System.loadLibrary("sqlcipher")
        val dek = runCatching { loadOrCreateDek(context) }.getOrElse {
            setAside(context, "key unreadable")
            loadOrCreateDek(context)
        }
        val first = build(context, dek)
        val ok = runCatching { first.openHelper.writableDatabase.query("SELECT count(*) FROM sqlite_master").close() }.isSuccess
        if (ok) return first
        first.close()
        setAside(context, "database unreadable")
        return build(context, loadOrCreateDek(context))
    }

    /** Schema 2: nullable `cards.addresses` for unlinked restored cards. Additive; no data changes. */
    internal val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `cards` ADD COLUMN `addresses` TEXT")
        }
    }

    private fun build(context: Context, dek: ByteArray): VaultDatabase {
        // A raw 256-bit key ("x'…'") skips SQLCipher's passphrase KDF: the key is already random.
        val raw = "x'" + dek.joinToString("") { "%02x".format(it) } + "'"
        return Room.databaseBuilder(context, VaultDatabase::class.java, DB_NAME)
            .openHelperFactory(SupportOpenHelperFactory(raw.toByteArray(Charsets.US_ASCII)))
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    private fun kek(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun loadOrCreateDek(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(KEY_FILE, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEK, null)?.let { stored ->
            val wrapped = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, kek(), GCMParameterSpec(128, wrapped, 0, IV))
            return c.doFinal(wrapped, IV, wrapped.size - IV)
        }
        // No key yet. If a database exists without its key, keep it aside rather than overwrite it.
        if (context.getDatabasePath(DB_NAME).exists()) setAside(context, "database without key")
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, kek())
        val wrapped = c.iv + c.doFinal(dek)
        check(prefs.edit().putString(KEY_DEK, Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit()) { "Could not store vault key" }
        return dek
    }

    /** Move the current database files and wrapped key aside. Nothing is deleted. */
    private fun setAside(context: Context, reason: String) {
        val stamp = System.currentTimeMillis()
        val base = context.getDatabasePath(DB_NAME)
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val f = File(base.path + suffix)
            if (f.exists()) f.renameTo(File(base.parentFile, "sovereign_vault.unreadable-$stamp.db$suffix"))
        }
        val prefs = context.getSharedPreferences(KEY_FILE, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        prefs.getString(KEY_DEK, null)?.let { edit.putString("dek_unreadable_$stamp", it) }
        // commit(), not apply(): the old key must be on disk before a new vault is created.
        edit.remove(KEY_DEK).putLong(KEY_RECOVERED, stamp).putString("recovered_reason", reason).commit()
    }
}
