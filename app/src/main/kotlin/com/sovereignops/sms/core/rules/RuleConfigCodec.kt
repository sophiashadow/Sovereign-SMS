package com.sovereignops.sms.core.rules

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Versioned JSON form of [RuleConfig], used for storage and for user export/import.
 * Decoding never throws: a bad or future-format document returns a failure and the
 * caller keeps the configuration it already has.
 */
object RuleConfigCodec {
    const val FORMAT = "sovereign-sms/rules"
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(config: RuleConfig): String = json.encodeToString(RuleConfigDoc.serializer(), RuleConfigDoc.from(config))

    fun decode(text: String): Result<RuleConfig> = runCatching {
        val doc = json.decodeFromString(RuleConfigDoc.serializer(), text)
        require(doc.format == FORMAT) { "Not a Sovereign SMS rules file" }
        require(doc.version in 1..VERSION) { "Rules file version ${doc.version} is newer than this app supports" }
        doc.toConfig()
    }
}

@Serializable
internal data class WindowDoc(val days: List<Int>, val startMinute: Int, val endMinute: Int)

@Serializable
internal data class ScheduleDoc(
    val name: String,
    val days: List<Int>,
    val startMinute: Int,
    val endMinute: Int,
    val categories: List<String> = listOf(Category.PROFESSIONAL.name, Category.UNKNOWN.name),
    val action: String = Alert.HELD.name,
    val enabled: Boolean = true,
)

@Serializable
internal data class KeywordDoc(
    val id: String,
    val keyword: String,
    val tag: String? = null,
    val effect: String = KeywordEffect.NONE.name,
    val enabled: Boolean = true,
)

@Serializable
internal data class RuleConfigDoc(
    val format: String = RuleConfigCodec.FORMAT,
    val version: Int = RuleConfigCodec.VERSION,
    val enabled: Boolean = true,
    val quietHours: List<WindowDoc> = emptyList(),
    val quietHoursAction: String = Alert.SILENT.name,
    val unknownSenderAction: String = Alert.ALERT.name,
    val priorityAddresses: List<String> = emptyList(),
    val keywordRules: List<KeywordDoc> = emptyList(),
    val cooldownMinutes: Int = 0,
    val focusUntil: String? = null,
    val focusAction: String = Alert.HELD.name,
    val alertOneTimeCodes: Boolean = true,
    val quietHoursCategories: List<String> = Category.entries.map { it.name },
    val offDutyAction: String = Alert.SILENT.name,
    val schedules: List<ScheduleDoc> = emptyList(),
) {
    fun toConfig() = RuleConfig(
        enabled = enabled,
        quietHours = quietHours.map { w ->
            require(w.startMinute in 0 until 1440 && w.endMinute in 0 until 1440) { "Quiet-hours time out of range" }
            TimeWindow(
                days = w.days.map { DayOfWeek.of(it) }.toSet(),
                start = LocalTime.of(w.startMinute / 60, w.startMinute % 60),
                end = LocalTime.of(w.endMinute / 60, w.endMinute % 60),
            )
        },
        quietHoursAction = Alert.valueOf(quietHoursAction),
        unknownSenderAction = Alert.valueOf(unknownSenderAction),
        priorityAddresses = priorityAddresses.map(String::trim).filter(String::isNotEmpty).toSet(),
        keywordRules = keywordRules.map { KeywordRule(it.id, it.keyword, it.tag, KeywordEffect.valueOf(it.effect), it.enabled) },
        cooldownMinutes = cooldownMinutes.coerceIn(0, 24 * 60),
        focusUntil = focusUntil?.let(LocalDateTime::parse),
        focusAction = Alert.valueOf(focusAction),
        alertOneTimeCodes = alertOneTimeCodes,
        quietHoursCategories = quietHoursCategories.map { Category.valueOf(it) }.toSet(),
        offDutyAction = Alert.valueOf(offDutyAction),
        schedules = schedules.map { d ->
            require(d.startMinute in 0 until 1440 && d.endMinute in 0 until 1440) { "Schedule time out of range in “${d.name}”" }
            ScheduleRule(
                name = d.name.trim(),
                window = TimeWindow(d.days.map { DayOfWeek.of(it) }.toSet(), LocalTime.of(d.startMinute / 60, d.startMinute % 60), LocalTime.of(d.endMinute / 60, d.endMinute % 60)),
                categories = d.categories.map { Category.valueOf(it.uppercase()) }.toSet(),
                action = Alert.valueOf(d.action.uppercase()),
                enabled = d.enabled,
            )
        },
    )

    companion object {
        fun from(c: RuleConfig) = RuleConfigDoc(
            enabled = c.enabled,
            quietHours = c.quietHours.map { w ->
                WindowDoc(
                    days = w.days.map(DayOfWeek::getValue).sorted(),
                    startMinute = w.start.hour * 60 + w.start.minute,
                    endMinute = w.end.hour * 60 + w.end.minute,
                )
            },
            quietHoursAction = c.quietHoursAction.name,
            unknownSenderAction = c.unknownSenderAction.name,
            priorityAddresses = c.priorityAddresses.sorted(),
            keywordRules = c.keywordRules.map { KeywordDoc(it.id, it.keyword, it.tag, it.effect.name, it.enabled) },
            cooldownMinutes = c.cooldownMinutes,
            focusUntil = c.focusUntil?.toString(),
            focusAction = c.focusAction.name,
            alertOneTimeCodes = c.alertOneTimeCodes,
            quietHoursCategories = c.quietHoursCategories.map { it.name }.sorted(),
            offDutyAction = c.offDutyAction.name,
            schedules = c.schedules.map { r ->
                ScheduleDoc(
                    r.name, r.window.days.map(DayOfWeek::getValue).sorted(),
                    r.window.start.hour * 60 + r.window.start.minute, r.window.end.hour * 60 + r.window.end.minute,
                    r.categories.map { it.name }.sorted(), r.action.name, r.enabled,
                )
            },
        )
    }
}
