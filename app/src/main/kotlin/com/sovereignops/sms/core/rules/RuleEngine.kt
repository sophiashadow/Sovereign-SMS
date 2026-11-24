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
    RULES_OFF, PRIORITY_CONTACT, CONVERSATION_PRIORITY, CONVERSATION_MUTED, ONE_TIME_CODE,
    KEYWORD_PRIORITY, FOCUS, QUIET_HOURS, UNKNOWN_SENDER, KEYWORD_SILENT, COOLDOWN, DEFAULT,
}

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
)

data class IncomingMessage(
    val address: String,
    val isKnownContact: Boolean,
    val body: String,
    val conversationPolicy: ConversationPolicy,
    val receivedAt: LocalDateTime,
    /** When this conversation last produced an audible alert, if known. */
    val lastAlertAt: LocalDateTime?,
)

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
     * reported in [Decision.alsoMatched] so a preview can show what *would* have applied.
     *
     *  1. priority contact / priority conversation  -> ALERT
     *  2. muted conversation                       -> SILENT
     *  3. one-time code (if enabled)               -> ALERT
     *  4. priority keyword                         -> ALERT
     *  5. focus mode                               -> focusAction
     *  6. quiet hours                              -> quietHoursAction
     *  7. unknown sender                           -> unknownSenderAction (if not ALERT)
     *  8. silent keyword                           -> SILENT
     *  9. cooldown                                 -> SILENT
     * 10. default                                  -> ALERT
     */
    fun evaluate(config: RuleConfig, message: IncomingMessage): Decision {
        val keywordHits = config.keywordRules.filter { it.matches(message.body) }
        val tags = keywordHits.mapNotNull { it.tag?.trim()?.takeIf(String::isNotEmpty) }.toSet()
        val keywordLines = keywordHits.map { "Keyword “${it.keyword.trim()}” matched" + (it.tag?.let { t -> " → tag “$t”" } ?: "") }

        if (!config.enabled) {
            return Decision(Alert.ALERT, RuleId.RULES_OFF, emptyList(), tags, listOf("Rules are switched off") + keywordLines)
        }

        val candidates = ArrayList<Triple<RuleId, Alert, String>>()
        val t = message.receivedAt

        if (config.priorityAddresses.any { Addresses.sameNumber(it, message.address) }) {
            candidates += Triple(RuleId.PRIORITY_CONTACT, Alert.ALERT, "Priority contact")
        }
        if (message.conversationPolicy == ConversationPolicy.PRIORITY) {
            candidates += Triple(RuleId.CONVERSATION_PRIORITY, Alert.ALERT, "Conversation marked priority")
        }
        if (message.conversationPolicy == ConversationPolicy.MUTED) {
            candidates += Triple(RuleId.CONVERSATION_MUTED, Alert.SILENT, "Conversation muted")
        }
        if (config.alertOneTimeCodes && OneTimeCodes.looksLikeCode(message.body)) {
            candidates += Triple(RuleId.ONE_TIME_CODE, Alert.ALERT, "Looks like a one-time code")
        }
        keywordHits.firstOrNull { it.effect == KeywordEffect.PRIORITY }?.let {
            candidates += Triple(RuleId.KEYWORD_PRIORITY, Alert.ALERT, "Priority keyword “${it.keyword.trim()}”")
        }
        config.focusUntil?.let { until ->
            if (t.isBefore(until)) {
                candidates += Triple(RuleId.FOCUS, config.focusAction, "Focus mode until ${until.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))}")
            }
        }
        config.quietHours.firstOrNull { it.contains(t) }?.let {
            candidates += Triple(RuleId.QUIET_HOURS, config.quietHoursAction, "Quiet hours ${it.describe()}")
        }
        if (!message.isKnownContact && config.unknownSenderAction != Alert.ALERT) {
            candidates += Triple(RuleId.UNKNOWN_SENDER, config.unknownSenderAction, "Sender is not in contacts")
        }
        keywordHits.firstOrNull { it.effect == KeywordEffect.SILENT }?.let {
            candidates += Triple(RuleId.KEYWORD_SILENT, Alert.SILENT, "Quiet keyword “${it.keyword.trim()}”")
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
}
