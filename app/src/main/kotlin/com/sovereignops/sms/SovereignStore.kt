package com.sovereignops.sms

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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

    /** Incoming red-flag rules, encrypted like the guard; empty until the user adds some. */
    var flagRules: List<GuardRule>
        get() = sealed.get(KEY_FLAGS)?.let { PackCodec.decode(it).getOrNull()?.flags } ?: emptyList()
        set(value) { sealed.put(KEY_FLAGS, PackCodec.encode(Pack(flags = value))) }

    /** Ask for a second tap (or long-press) before sending a draft the guard flagged. */
    var confirmFlaggedSend: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_SEND, true)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_SEND, value).apply()

    var templates: List<Template>
        get() = sealed.get(KEY_TEMPLATES)?.let { PackCodec.decode(it).getOrNull()?.templates } ?: emptyList()
        set(value) { sealed.put(KEY_TEMPLATES, PackCodec.encode(Pack(templates = value))) }

    fun exportPack(): String = PackCodec.encode(Pack(ruleConfig, guardRules, templates, flagRules))

    /** Replaces only the sections the file contains; returns an error message if rejected. */
    fun importPack(json: String): String? = PackCodec.decode(json).fold(
        onSuccess = { p ->
            p.rules?.let { ruleConfig = it }
            p.guard?.let { guardRules = it }
            p.templates?.let { templates = it }
            p.flags?.let { flagRules = it }
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

    /** Tags added on receipt by keyword and flag rules, per conversation. */
    fun threadTags(threadId: Long): Set<String> = prefs.getStringSet("$KEY_TAGS$threadId", null).orEmpty()

    fun addThreadTags(threadId: Long, tags: Set<String>) {
        if (tags.isEmpty()) return
        val now = threadTags(threadId)
        if (now.containsAll(tags)) return
        prefs.edit().putStringSet("$KEY_TAGS$threadId", (now + tags).toSet()).apply()
    }

    fun clearThreadTags(threadId: Long) = prefs.edit().remove("$KEY_TAGS$threadId").apply()

    /** Quick Cover: Personal only; Professional and Quarantine need re-authentication. */
    var coverActive: Boolean
        get() = prefs.getBoolean(KEY_COVER, false)
        set(value) = prefs.edit().putBoolean(KEY_COVER, value).apply()

    /** "Copy code" action on one-time-code notifications. */
    var copyCodeAction: Boolean
        get() = prefs.getBoolean(KEY_COPY_CODE, true)
        set(value) = prefs.edit().putBoolean(KEY_COPY_CODE, value).apply()

    /** Timeout for upstream's app lock (Settings → Password protection). */
    var lockTimeout: com.sovereignops.sms.core.lock.LockTimeout
        get() = com.sovereignops.sms.core.lock.LockTimeout.parse(prefs.getString(KEY_LOCK_TIMEOUT, null))
        set(value) = prefs.edit().putString(KEY_LOCK_TIMEOUT, value.name).apply()

    /** FLAG_SECURE: blocks screenshots and hides content in Recents. */
    var secureScreen: Boolean
        get() = prefs.getBoolean(KEY_SECURE, false)
        set(value) = prefs.edit().putBoolean(KEY_SECURE, value).apply()

    /** Daily summaries: off by default. Turning them on starts per-day counting from now. */
    var summaryEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUM_ON, false)
        set(value) = prefs.edit { putBoolean(KEY_SUM_ON, value) }

    /** When counting started (epoch millis), or 0 when it never has. */
    var summarySince: Long
        get() = prefs.getLong(KEY_SUM_SINCE, 0L)
        set(value) = prefs.edit { putLong(KEY_SUM_SINCE, value) }

    var summarySchedule: com.sovereignops.sms.core.summary.SummarySchedule
        get() {
            val d = com.sovereignops.sms.core.summary.SummarySchedule.DEFAULT
            val minute = prefs.getInt(KEY_SUM_MINUTE, d.time.hour * 60 + d.time.minute).coerceIn(0, 24 * 60 - 1)
            val mask = prefs.getInt(KEY_SUM_DAYS, 0x7F)
            return com.sovereignops.sms.core.summary.SummarySchedule(
                java.time.LocalTime.of(minute / 60, minute % 60),
                java.time.DayOfWeek.entries.filter { mask and (1 shl it.ordinal) != 0 }.toSet(),
            )
        }
        set(value) = prefs.edit {
            putInt(KEY_SUM_MINUTE, value.time.hour * 60 + value.time.minute)
            putInt(KEY_SUM_DAYS, value.days.fold(0) { m, d -> m or (1 shl d.ordinal) })
        }

    /** Local date (epoch day) of the last delivered summary; one per local date at most. */
    var summaryLastDay: java.time.LocalDate?
        get() = prefs.getLong(KEY_SUM_LAST, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { java.time.LocalDate.ofEpochDay(it) }
        set(value) = prefs.edit { if (value == null) remove(KEY_SUM_LAST) else putLong(KEY_SUM_LAST, value.toEpochDay()) }

    /** Ledger wording the owner chose; null = the neutral defaults. Device-local, not in backups. */
    var ledgerUnitTerm: String?
        get() = prefs.getString(KEY_LEDGER_UNIT, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { if (value.isNullOrBlank()) remove(KEY_LEDGER_UNIT) else putString(KEY_LEDGER_UNIT, value.trim().take(TERM_MAX)) }

    var ledgerServiceTerm: String?
        get() = prefs.getString(KEY_LEDGER_SERVICE, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { if (value.isNullOrBlank()) remove(KEY_LEDGER_SERVICE) else putString(KEY_LEDGER_SERVICE, value.trim().take(TERM_MAX)) }

    var heldCount: Int
        get() = prefs.getInt(KEY_HELD, 0)
        set(value) = prefs.edit().putInt(KEY_HELD, value.coerceAtLeast(0)).apply()

    /** Optional user-supplied own numbers (encrypted) when SIM/eSIM omits line1Number. */
    var ownNumbers: List<String>
        get() = sealed.get(KEY_OWN_NUMBERS)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { com.sovereignops.sms.core.address.AddressClassifier.isPhoneLike(it) }
            ?.distinct()
            ?: emptyList()
        set(value) {
            val clean = value.map { it.trim() }
                .filter { com.sovereignops.sms.core.address.AddressClassifier.isPhoneLike(it) }
                .distinct()
            sealed.put(KEY_OWN_NUMBERS, clean.joinToString(","))
        }

    companion object {
        const val PREFS = "sovereign_sms"
        private const val KEY_LEDGER_UNIT = "ledger_unit_term"
        private const val KEY_LEDGER_SERVICE = "ledger_service_term"
        private const val TERM_MAX = 40
        private const val KEY_PRESERVE = "preserve_screened"
        private const val KEY_RULES = "rules_json"
        private const val KEY_POLICY = "policy_"
        private const val KEY_LAST_ALERT = "last_alert_"
        private const val KEY_HELD = "held_count"
        private const val KEY_CATEGORY = "category_"
        private const val KEY_MODE = "workspace_mode"
        private const val KEY_GUARD = "guard"
        private const val KEY_TEMPLATES = "templates"
        private const val KEY_FLAGS = "flags"
        private const val KEY_TAGS = "tags_"
        private const val KEY_CONFIRM_SEND = "confirm_flagged_send"
        private const val KEY_CHECKLIST = "checklist_items"
        private const val KEY_ALIAS_NOTIF = "alias_in_notifications"
        private const val KEY_COVER = "quick_cover"
        private const val KEY_COPY_CODE = "copy_code_action"
        private const val KEY_LOCK_TIMEOUT = "app_lock_timeout"
        private const val KEY_SECURE = "secure_screen"
        private const val KEY_SUM_ON = "summary_enabled"
        private const val KEY_SUM_SINCE = "summary_since"
        private const val KEY_SUM_MINUTE = "summary_minute"
        private const val KEY_SUM_DAYS = "summary_days"
        private const val KEY_SUM_LAST = "summary_last_day"
        private const val KEY_OWN_NUMBERS = "own_numbers"
    }
}
