package com.sovereignops.sms

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted values (draft-guard rules, templates) in a private preferences file.
 * One Android Keystore AES-256-GCM key, non-exportable, no user-auth binding, so it is
 * not invalidated by biometric changes. Standard platform crypto only.
 *
 * If a value cannot be decrypted (key lost, e.g. after a device restore), it is copied
 * aside under an `unreadable_` name before anything overwrites it, and treated as unset.
 */
class SealedPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
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

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV))
            String(cipher.doFinal(raw, IV, raw.size - IV), Charsets.UTF_8)
        }.getOrElse {
            if (!prefs.contains("unreadable_$name")) prefs.edit().putString("unreadable_$name", stored).apply()
            null
        }
    }

    /** Returns false if encryption failed; the previous value is then left untouched. */
    fun put(name: String, value: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }.isSuccess

    companion object {
        private const val FILE = "sovereign_sealed"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "sovereign_settings_v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV = 12
    }
}
