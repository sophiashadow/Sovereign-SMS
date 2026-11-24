package com.sovereignops.sms

import android.content.Context
import android.content.SharedPreferences
import com.sovereignops.sms.core.guard.GuardRule
import com.sovereignops.sms.core.pack.Pack
import com.sovereignops.sms.core.pack.PackCodec
import com.sovereignops.sms.core.rules.Category
import com.sovereignops.sms.core.rules.ConversationPolicy
import com.sovereignops.sms.core.rules.WorkspaceMode
import com.sovereignops.sms.core.templates.Template
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
    private val sealed = SealedPrefs(context)

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

    /** User-assigned side of the SIM; null means "decide from contact status". */
    fun category(threadId: Long): Category? =
        prefs.getString("$KEY_CATEGORY$threadId", null)?.let { runCatching { Category.valueOf(it) }.getOrNull() }

    fun setCategory(threadId: Long, category: Category?) {
        prefs.edit().apply {
            if (category == null) remove("$KEY_CATEGORY$threadId") else putString("$KEY_CATEGORY$threadId", category.name)
        }.apply()
    }

    var workspaceMode: WorkspaceMode
        get() = prefs.getString(KEY_MODE, null)?.let { runCatching { WorkspaceMode.valueOf(it) }.getOrNull() } ?: WorkspaceMode.ALL
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** Draft-guard rules and templates live encrypted; both are empty until the user adds some. */
    var guardRules: List<GuardRule>
        get() = sealed.get(KEY_GUARD)?.let { PackCodec.decode(it).getOrNull()?.guard } ?: emptyList()
        set(value) { sealed.put(KEY_GUARD, PackCodec.encode(Pack(guard = value))) }

    var templates: List<Template>
        get() = sealed.get(KEY_TEMPLATES)?.let { PackCodec.decode(it).getOrNull()?.templates } ?: emptyList()
        set(value) { sealed.put(KEY_TEMPLATES, PackCodec.encode(Pack(templates = value))) }

    fun exportPack(): String = PackCodec.encode(Pack(ruleConfig, guardRules, templates))

    /** Replaces only the sections the file contains; returns an error message if rejected. */
    fun importPack(json: String): String? = PackCodec.decode(json).fold(
        onSuccess = { p ->
            p.rules?.let { ruleConfig = it }
            p.guard?.let { guardRules = it }
            p.templates?.let { templates = it }
            null
        },
        onFailure = { it.message ?: "Invalid file" },
    )

    /** Checklist items shown on every client card; user-editable. */
    var checklistItems: List<com.sovereignops.sms.core.crm.Checklist.Item>
        get() = prefs.getString(KEY_CHECKLIST, null)?.let(com.sovereignops.sms.core.crm.Checklist::parse)?.takeIf { it.isNotEmpty() }
            ?: com.sovereignops.sms.core.crm.Checklist.DEFAULT
        set(value) = prefs.edit().putString(KEY_CHECKLIST, com.sovereignops.sms.core.crm.Checklist.format(value)).apply()

    /** Show the local alias as the notification sender name. */
    var aliasInNotifications: Boolean
        get() = prefs.getBoolean(KEY_ALIAS_NOTIF, true)
        set(value) = prefs.edit().putBoolean(KEY_ALIAS_NOTIF, value).apply()

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
        private const val KEY_CATEGORY = "category_"
        private const val KEY_MODE = "workspace_mode"
        private const val KEY_GUARD = "guard"
        private const val KEY_TEMPLATES = "templates"
        private const val KEY_CHECKLIST = "checklist_items"
        private const val KEY_ALIAS_NOTIF = "alias_in_notifications"
    }
}
