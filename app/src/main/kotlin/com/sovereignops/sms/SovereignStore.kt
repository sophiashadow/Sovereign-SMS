package com.sovereignops.sms

import android.content.Context
import android.content.SharedPreferences
import com.sovereignops.sms.core.rules.ConversationPolicy
import com.sovereignops.sms.core.rules.RuleConfig
import com.sovereignops.sms.core.rules.RuleConfigCodec
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Sovereign SMS application metadata, kept apart from upstream's Config and from the
 * Telephony provider. Holds settings and small per-conversation flags only, never
 * message content. Excluded from Android cloud backup (see backup rules).
 */
class SovereignStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Keep messages that block/keyword/unknown filters catch, instead of upstream's discard. */
    var preserveScreened: Boolean
        get() = prefs.getBoolean(KEY_PRESERVE, true)
        set(value) = prefs.edit().putBoolean(KEY_PRESERVE, value).apply()

    /** Last stored config; a corrupt document falls back to defaults (always alert), never crashes. */
    var ruleConfig: RuleConfig
        get() = prefs.getString(KEY_RULES, null)?.let { RuleConfigCodec.decode(it).getOrNull() } ?: RuleConfig()
        set(value) = prefs.edit().putString(KEY_RULES, RuleConfigCodec.encode(value)).apply()

    /** Store a user-supplied rules document; returns the error message if it is rejected. */
    fun importRules(json: String): String? =
        RuleConfigCodec.decode(json).fold(onSuccess = { ruleConfig = it; null }, onFailure = { it.message ?: "Invalid rules file" })

    fun exportRules(): String = RuleConfigCodec.encode(ruleConfig)

    fun conversationPolicy(threadId: Long): ConversationPolicy =
        prefs.getString("$KEY_POLICY$threadId", null)
            ?.let { runCatching { ConversationPolicy.valueOf(it) }.getOrNull() } ?: ConversationPolicy.DEFAULT

    fun setConversationPolicy(threadId: Long, policy: ConversationPolicy) {
        prefs.edit().apply {
            if (policy == ConversationPolicy.DEFAULT) remove("$KEY_POLICY$threadId") else putString("$KEY_POLICY$threadId", policy.name)
        }.apply()
    }

    fun lastAlertAt(threadId: Long): LocalDateTime? =
        prefs.getLong("$KEY_LAST_ALERT$threadId", 0L).takeIf { it > 0 }
            ?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) }

    fun markAlerted(threadId: Long, epochMillis: Long) {
        prefs.edit().putLong("$KEY_LAST_ALERT$threadId", epochMillis).apply()
    }

    var heldCount: Int
        get() = prefs.getInt(KEY_HELD, 0)
        set(value) = prefs.edit().putInt(KEY_HELD, value.coerceAtLeast(0)).apply()

    companion object {
        const val PREFS = "sovereign_sms"
        private const val KEY_PRESERVE = "preserve_screened"
        private const val KEY_RULES = "rules_json"
        private const val KEY_POLICY = "policy_"
        private const val KEY_LAST_ALERT = "last_alert_"
        private const val KEY_HELD = "held_count"
    }
}
