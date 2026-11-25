package com.sovereignops.sms.core.rules

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/*
 * Sovereign SMS notification rules: a deterministic, local, pure-Kotlin engine.
 *
 * Invariant: a decision only changes how a message is *announced*. Every message
 * is stored in the Telephony provider whatever the decision; nothing here can
 * drop, delete, forward or answer a message.
 */

/** How a received message is announced. */
enum class Alert {
    /** Normal notification with sound/vibration per channel. */
    ALERT,

    /** Notification posted without sound or vibration. */
    SILENT,

    /** No per-message notification; counted in one quiet "held messages" summary. */
    HELD,
}

/** Per-conversation notification preference chosen by the user. */
enum class ConversationPolicy { DEFAULT, PRIORITY, MUTED }

/** Which side of a shared SIM a conversation belongs to. Assigned per thread by the user. */
enum class Category { PERSONAL, PROFESSIONAL, UNKNOWN }

/**
 * The inbox view the user has chosen. It also shapes alerts:
 * FOCUS holds everything except priority and one-time codes; PERSONAL (off duty)
 * applies [RuleConfig.offDutyAction] to professional and unknown conversations.
 */
enum class WorkspaceMode { ALL, PERSONAL, PROFESSIONAL, QUARANTINE, FOCUS }

enum class KeywordEffect {
    /** Tag only. */
    NONE,

    /** Alert even during quiet hours, focus or cooldown. */
    PRIORITY,

    /** Post silently. */
    SILENT,
}

/** Identifies which rule decided, so the UI can explain and the user can reverse it. */
enum class RuleId {
    RULES_OFF, QUARANTINED, COVER,
    ONE_TIME_CODE, KEYWORD_PRIORITY,
    PRIORITY_CONTACT, CONVERSATION_PRIORITY, PERSONAL,
    CONVERSATION_MUTED,
    FOCUS, WORKSPACE_FOCUS, OFF_DUTY,
    QUIET_HOURS, SCHEDULE,
    UNKNOWN_SENDER, KEYWORD_SILENT, FLAG_SILENT, COOLDOWN,
    DEFAULT,
    ;

    /** The precedence tier shown in the trace log; lower wins. */
    val tier: Int
        get() = when (this) {
            RULES_OFF, QUARANTINED, COVER -> 0
            ONE_TIME_CODE, KEYWORD_PRIORITY -> 1
            PRIORITY_CONTACT, CONVERSATION_PRIORITY, PERSONAL -> 2
            CONVERSATION_MUTED -> 3
            FOCUS, WORKSPACE_FOCUS, OFF_DUTY -> 4
            QUIET_HOURS, SCHEDULE -> 5
            UNKNOWN_SENDER, KEYWORD_SILENT, FLAG_SILENT, COOLDOWN -> 6
            DEFAULT -> 7
        }
}

/**
 * A named schedule: during [window], messages in [categories] get [action]. Several can
 * be stacked (for a shared SIM: professional held overnight, silent at weekends). They
 * rank with quiet hours; the first enabled match in list order decides between them.
 */
data class ScheduleRule(
    val name: String,
    val window: TimeWindow,
    val categories: Set<Category>,
    val action: Alert,
    val enabled: Boolean = true,
)

/**
 * A weekly window. [days] are the days the window *starts* on. When [end] is not
 * after [start] the window runs past midnight into the next day; start == end
 * means the whole day.
 */
data class TimeWindow(val days: Set<DayOfWeek>, val start: LocalTime, val end: LocalTime) {
    fun contains(t: LocalDateTime): Boolean {
        val time = t.toLocalTime()
        val day = t.dayOfWeek
        return when {
            start == end -> day in days
            start < end -> day in days && time >= start && time < end
            else -> (day in days && time >= start) || (day.minus(1) in days && time < end)
        }
    }

    fun describe(): String {
        val f = DateTimeFormatter.ofPattern("HH:mm")
        return "${start.format(f)}–${end.format(f)}"
    }
}

data class KeywordRule(
    val id: String,
    val keyword: String,
    val tag: String?,
    val effect: KeywordEffect,
    val enabled: Boolean = true,
) {
    fun matches(body: String): Boolean {
        val k = keyword.trim()
        return enabled && k.isNotEmpty() && body.contains(k, ignoreCase = true)
    }
}

/** User-editable rule configuration. Defaults reproduce upstream behaviour (always alert). */
data class RuleConfig(
    val enabled: Boolean = true,
    val quietHours: List<TimeWindow> = emptyList(),
    val quietHoursAction: Alert = Alert.SILENT,
    val unknownSenderAction: Alert = Alert.ALERT,
    val priorityAddresses: Set<String> = emptySet(),
    val keywordRules: List<KeywordRule> = emptyList(),
    val cooldownMinutes: Int = 0,
    /** Focus mode is active while the message time is before this instant (device local time). */
    val focusUntil: LocalDateTime? = null,
    val focusAction: Alert = Alert.HELD,
    val alertOneTimeCodes: Boolean = true,
    /** Categories quiet hours apply to; drop PERSONAL so personal contacts ring through. */
    val quietHoursCategories: Set<Category> = Category.entries.toSet(),
    /** In PERSONAL workspace mode, what happens to professional and unknown messages. */
    val offDutyAction: Alert = Alert.SILENT,
    val schedules: List<ScheduleRule> = emptyList(),
)

data class IncomingMessage(
    val address: String,
    val isKnownContact: Boolean,
    val body: String,
    val conversationPolicy: ConversationPolicy,
    val receivedAt: LocalDateTime,
    /** When this conversation last produced an audible alert, if known. */
    val lastAlertAt: LocalDateTime?,
    val category: Category = if (isKnownContact) Category.PERSONAL else Category.UNKNOWN,
    val workspaceMode: WorkspaceMode = WorkspaceMode.ALL,
    /** Incoming red flags that matched (already evaluated by the guard engine). */
    val flags: List<FlagSignal> = emptyList(),
    /** Sender is on the app's own Quarantine list (not Android's block list). */
    val quarantined: Boolean = false,
    /** Quick Cover is on: only personal conversations and one-time codes may alert. */
    val cover: Boolean = false,
)

/** The parts of an incoming-flag match the rules engine acts on. */
data class FlagSignal(val name: String, val silent: Boolean, val tag: String?)

data class Decision(
    val alert: Alert,
    val decidedBy: RuleId,
    /** Other rules that matched but did not decide, in precedence order. */
    val alsoMatched: List<RuleId>,
    val tags: Set<String>,
    /** Human-readable lines; the first explains the deciding rule. */
    val explanation: List<String>,
)

object RuleEngine {

    /**
     * Precedence, highest first. The first matching rule decides; every other match is
     * reported in [Decision.alsoMatched] so the trace can show what *would* have applied.
     *
     *  0. quarantined sender -> SILENT (even with rules off); Quick Cover -> HELD for
     *     non-personal, non-code messages; rules off -> ALERT
     *  1. Emergency / OTP:      one-time code (if enabled), break-through ("priority") keyword -> ALERT
     *  2. Personal / Priority:  priority contact, priority conversation, personal
     *                           conversation when quiet hours are not scoped to personal -> ALERT
     *  3. Thread override:      muted conversation -> SILENT
     *  4. Focus:                timed focus, Focus workspace, Personal workspace (off duty)
     *  5. Scheduled quiet:      quiet hours, then schedule rules in list order
     *  6. Keyword / Unknown:    unknown sender, quiet keyword, silent flag, cooldown
     *  7. default -> ALERT
     */
    fun evaluate(config: RuleConfig, message: IncomingMessage): Decision {
        val keywordHits = config.keywordRules.filter { it.matches(message.body) }
        val tags = (keywordHits.mapNotNull { it.tag } + message.flags.mapNotNull { it.tag }).map(String::trim).filter(String::isNotEmpty).toSet()
        val keywordLines = keywordHits.map { "Keyword “${it.keyword.trim()}” matched" + (it.tag?.let { t -> " → tag “$t”" } ?: "") } +
            message.flags.map { "Flag “${it.name}” matched" + (it.tag?.let { t -> " → tag “$t”" } ?: "") }

        // Quarantine is the owner's explicit screen: kept and muted even with rules off.
        if (message.quarantined) {
            return Decision(Alert.SILENT, RuleId.QUARANTINED, emptyList(), tags, listOf("Sender is quarantined: stored, marked read, muted") + keywordLines)
        }
        val isCode = config.alertOneTimeCodes && OneTimeCodes.looksLikeCode(message.body)
        if (message.cover && message.category != Category.PERSONAL && !isCode) {
            return Decision(Alert.HELD, RuleId.COVER, emptyList(), tags, listOf("Quick Cover is on: ${message.category.name.lowercase()} conversation held") + keywordLines)
        }

        if (!config.enabled) {
            return Decision(Alert.ALERT, RuleId.RULES_OFF, emptyList(), tags, listOf("Rules are switched off") + keywordLines)
        }

        val candidates = ArrayList<Triple<RuleId, Alert, String>>()
        val t = message.receivedAt

        // 1. Emergency / OTP
        if (isCode) {
            candidates += Triple(RuleId.ONE_TIME_CODE, Alert.ALERT, "Looks like a one-time code")
        }
        keywordHits.firstOrNull { it.effect == KeywordEffect.PRIORITY }?.let {
            candidates += Triple(RuleId.KEYWORD_PRIORITY, Alert.ALERT, "Break-through keyword “${it.keyword.trim()}”")
        }
        // 2. Personal / Priority
        if (config.priorityAddresses.any { Addresses.sameNumber(it, message.address) }) {
            candidates += Triple(RuleId.PRIORITY_CONTACT, Alert.ALERT, "Priority contact")
        }
        if (message.conversationPolicy == ConversationPolicy.PRIORITY) {
            candidates += Triple(RuleId.CONVERSATION_PRIORITY, Alert.ALERT, "Conversation marked priority")
        }
        if (message.category == Category.PERSONAL && Category.PERSONAL !in config.quietHoursCategories &&
            message.conversationPolicy != ConversationPolicy.MUTED
        ) {
            candidates += Triple(RuleId.PERSONAL, Alert.ALERT, "Personal conversation rings through focus and quiet hours")
        }
        // 3. Thread override
        if (message.conversationPolicy == ConversationPolicy.MUTED) {
            candidates += Triple(RuleId.CONVERSATION_MUTED, Alert.SILENT, "Conversation muted")
        }
        // 4. Focus
        config.focusUntil?.let { until ->
            if (t.isBefore(until)) {
                candidates += Triple(RuleId.FOCUS, config.focusAction, "Focus mode until ${until.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))}")
            }
        }
        if (message.workspaceMode == WorkspaceMode.FOCUS) {
            candidates += Triple(RuleId.WORKSPACE_FOCUS, config.focusAction, "Workspace is in Focus mode")
        }
        if (message.workspaceMode == WorkspaceMode.PERSONAL && message.category != Category.PERSONAL) {
            candidates += Triple(RuleId.OFF_DUTY, config.offDutyAction, "Personal mode: ${message.category.name.lowercase()} conversation")
        }
        // 5. Scheduled quiet
        config.quietHours.firstOrNull { it.contains(t) && message.category in config.quietHoursCategories }?.let {
            candidates += Triple(RuleId.QUIET_HOURS, config.quietHoursAction, "Quiet hours ${it.describe()}")
        }
        config.schedules.firstOrNull { it.enabled && message.category in it.categories && it.window.contains(t) }?.let {
            candidates += Triple(RuleId.SCHEDULE, it.action, "Schedule “${it.name}” ${it.window.describe()}")
        }
        // 6. Keyword / Unknown
        if (!message.isKnownContact && config.unknownSenderAction != Alert.ALERT) {
            candidates += Triple(RuleId.UNKNOWN_SENDER, config.unknownSenderAction, "Sender is not in contacts")
        }
        keywordHits.firstOrNull { it.effect == KeywordEffect.SILENT }?.let {
            candidates += Triple(RuleId.KEYWORD_SILENT, Alert.SILENT, "Quiet keyword “${it.keyword.trim()}”")
        }
        message.flags.firstOrNull { it.silent }?.let {
            candidates += Triple(RuleId.FLAG_SILENT, Alert.SILENT, "Flag “${it.name}” silences on receipt")
        }
        val last = message.lastAlertAt
        if (config.cooldownMinutes > 0 && last != null && t.isBefore(last.plusMinutes(config.cooldownMinutes.toLong()))) {
            candidates += Triple(RuleId.COOLDOWN, Alert.SILENT, "Cooldown: alerted less than ${config.cooldownMinutes} min ago")
        }

        val winner = candidates.firstOrNull() ?: Triple(RuleId.DEFAULT, Alert.ALERT, "No rule matched")
        val others = candidates.drop(1)
        val explanation = listOf(winner.third) + others.map { "Also matched: ${it.third}" } + keywordLines
        return Decision(winner.second, winner.first, others.map { it.first }, tags, explanation)
    }
}

object Addresses {
    /** Digits only, with a leading NANP country code removed so +1 919… equals 919…. */
    fun normalize(raw: String): String {
        val digits = raw.filter(Char::isDigit)
        return if (digits.length == 11 && digits.startsWith("1")) digits.substring(1) else digits
    }

    /**
     * Conservative comparison: exact on normalized digits, or equal last 10 digits when
     * both are at least 10 long (international prefixes). Short codes must match exactly.
     */
    fun sameNumber(a: String, b: String): Boolean {
        // Digits inside a chat ID or other opaque token are not a phone number.
        if (!com.sovereignops.sms.core.address.AddressClassifier.isPhoneLike(a) ||
            !com.sovereignops.sms.core.address.AddressClassifier.isPhoneLike(b)
        ) return false
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        return x.length >= 10 && y.length >= 10 && x.takeLast(10) == y.takeLast(10)
    }
}

object OneTimeCodes {
    private val keyword = Regex(
        "\\b(code|otp|passcode|verification|verify|2fa|one[- ]time|sign[- ]?in|log[- ]?in|pin)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val digits = Regex("(?<![\\d])\\d{4,8}(?![\\d])")

    fun looksLikeCode(body: String): Boolean = keyword.containsMatchIn(body) && digits.containsMatchIn(body)

    /** The code itself, for the "Copy code" action: the digit run nearest the keyword. */
    fun extract(body: String): String? {
        if (!looksLikeCode(body)) return null
        val k = keyword.find(body)!!.range
        return digits.findAll(body).minByOrNull { m ->
            val r = m.range
            when {
                r.first > k.last -> r.first - k.last
                r.last < k.first -> k.first - r.last
                else -> 0
            }
        }?.value
    }
}
