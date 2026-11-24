package com.sovereignops.sms.core.summary

import com.sovereignops.sms.core.insights.Insights
import com.sovereignops.sms.core.insights.Stamp
import com.sovereignops.sms.core.rules.Alert
import com.sovereignops.sms.core.rules.Category
import com.sovereignops.sms.core.rules.Decision
import com.sovereignops.sms.core.rules.RuleId
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Daily communication summaries. Two sources, both already in the app:
 *  - message timestamps and directions from the local message cache (the same rows workload insights read);
 *  - per-day counters recorded when the rules decide an incoming message, only while summaries are on.
 * Counters live in the encrypted vault's `counters` table, keyed by local date and metric, and are pruned
 * after [SummaryCounters.KEEP_DAYS]. Nothing records a body, a name or an address.
 */
enum class Metric { PERSONAL, PROFESSIONAL, UNKNOWN, HELD_FOCUS, HELD_SCHEDULE, HELD_QUIET, QUARANTINED }

object SummaryCounters {
    const val PREFIX = "sum:"
    const val KEEP_DAYS = 35L

    fun key(day: LocalDate, metric: Metric) = "$PREFIX$day:${metric.name}"

    /** Inverse of [key]; null for anything that isn't a summary counter. */
    fun parse(name: String): Pair<LocalDate, Metric>? {
        if (!name.startsWith(PREFIX)) return null
        val rest = name.removePrefix(PREFIX)
        val day = runCatching { LocalDate.parse(rest.substringBefore(':')) }.getOrNull() ?: return null
        val metric = Metric.entries.firstOrNull { it.name == rest.substringAfter(':', "") } ?: return null
        return day to metric
    }

    /** Counter names before this sort earlier than every key dated [today] − [KEEP_DAYS] or later. */
    fun pruneFloor(today: LocalDate) = "$PREFIX${today.minusDays(KEEP_DAYS)}"

    /**
     * What one rule decision adds. A quarantined message counts only as quarantined, so the
     * quarantine list doesn't inflate category volume. "Held" means the rule kept the alert from
     * sounding (silent or held), and only for the focus, schedule and quiet-hours rules.
     */
    fun metricsFor(decision: Decision, category: Category): List<Metric> {
        if (decision.decidedBy == RuleId.QUARANTINED) return listOf(Metric.QUARANTINED)
        val out = mutableListOf(
            when (category) {
                Category.PERSONAL -> Metric.PERSONAL
                Category.PROFESSIONAL -> Metric.PROFESSIONAL
                Category.UNKNOWN -> Metric.UNKNOWN
            },
        )
        if (decision.alert != Alert.ALERT) {
            when (decision.decidedBy) {
                RuleId.FOCUS, RuleId.WORKSPACE_FOCUS, RuleId.OFF_DUTY -> out += Metric.HELD_FOCUS
                RuleId.SCHEDULE -> out += Metric.HELD_SCHEDULE
                RuleId.QUIET_HOURS -> out += Metric.HELD_QUIET
                else -> Unit
            }
        }
        return out
    }

    /** Sums counters per metric over the local dates [from]..[to] inclusive. */
    fun total(counters: Map<String, Long>, from: LocalDate, to: LocalDate): Map<Metric, Long> {
        val out = Metric.entries.associateWith { 0L }.toMutableMap()
        for ((name, value) in counters) {
            val (day, metric) = parse(name) ?: continue
            if (day < from || day > to) continue
            out[metric] = out.getValue(metric) + value
        }
        return out
    }
}

/** When the summary is delivered: a local time and the weekdays it runs on. */
data class SummarySchedule(val time: LocalTime, val days: Set<DayOfWeek>) {
    companion object {
        val DEFAULT = SummarySchedule(LocalTime.of(20, 0), DayOfWeek.entries.toSet())
    }
}

object SummaryTiming {
    /**
     * The delivery slot on [date] in [zone], or null on a day that is switched off.
     * ZonedDateTime.of moves a time inside a spring-forward gap to after the gap, and
     * picks the earlier offset in an autumn overlap, so each date has exactly one slot.
     */
    fun slot(date: LocalDate, schedule: SummarySchedule, zone: ZoneId): ZonedDateTime? =
        if (date.dayOfWeek in schedule.days) ZonedDateTime.of(date, schedule.time, zone) else null

    /** The first slot strictly after [now]; null when no day is selected. */
    fun next(now: ZonedDateTime, schedule: SummarySchedule): ZonedDateTime? {
        if (schedule.days.isEmpty()) return null
        val today = now.toLocalDate()
        for (i in 0L..7L) {
            val s = slot(today.plusDays(i), schedule, now.zone) ?: continue
            if (s.isAfter(now)) return s
        }
        return null
    }

    /**
     * Whether a summary is owed now: today's slot has passed and today's summary hasn't gone out.
     * A missed slot is caught up the same local day only (late alarm, reboot, clock or zone change);
     * an earlier day's missed summary is not sent, so a phone that was off doesn't get a backlog.
     * Tracking the local date, not a timestamp, means a westward zone change can't repeat a day.
     */
    fun due(now: ZonedDateTime, schedule: SummarySchedule, lastDeliveredDay: LocalDate?): Boolean {
        val s = slot(now.toLocalDate(), schedule, now.zone) ?: return false
        return !now.isBefore(s) && lastDeliveredDay != now.toLocalDate()
    }

    /** A morning slot reports yesterday in full; from noon on it reports today so far. */
    fun period(slot: ZonedDateTime): Period =
        if (slot.toLocalTime().isBefore(LocalTime.NOON)) Period.yesterday(slot) else Period.todaySoFar(slot)
}

/** The span a summary covers, as local dates and as epoch milliseconds (inclusive). */
data class Period(val kind: Kind, val firstDay: LocalDate, val lastDay: LocalDate, val fromMillis: Long, val toMillis: Long) {
    enum class Kind { TODAY_SO_FAR, YESTERDAY }

    companion object {
        fun todaySoFar(now: ZonedDateTime): Period {
            val day = now.toLocalDate()
            return Period(Kind.TODAY_SO_FAR, day, day, day.atStartOfDay(now.zone).toInstant().toEpochMilli(), now.toInstant().toEpochMilli())
        }

        fun yesterday(now: ZonedDateTime): Period {
            val day = now.toLocalDate().minusDays(1)
            val start = day.atStartOfDay(now.zone).toInstant().toEpochMilli()
            val end = now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli() - 1
            return Period(Kind.YESTERDAY, day, day, start, end)
        }
    }
}

/**
 * The figures for one period. Counter-based figures are null when counting was off for the
 * whole period; [partialCounts] is true when counting started part-way through it.
 */
data class DaySummary(
    val period: Period,
    val incoming: Int,
    val outgoing: Int,
    val activeConversations: Int,
    /** Conversations whose first message in the cache's lookback falls in this period. */
    val newConversations: Int,
    /** Conversations whose latest message, as of the period's end, is an unanswered incoming one. */
    val awaitingResponse: Int,
    val responses: Int,
    /** Only when there are at least [DailySummary.MIN_RESPONSES] replies to measure. */
    val medianResponseMillis: Long?,
    val counts: Map<Metric, Long>?,
    val partialCounts: Boolean,
) {
    val isEmpty get() = incoming + outgoing == 0 && (counts?.values?.all { it == 0L } ?: true)
}

object DailySummary {
    const val MIN_RESPONSES = 3
    const val LOOKBACK_DAYS = 30L

    /**
     * Builds a summary for [period]. [stamps] should cover [LOOKBACK_DAYS] before the period too,
     * for "new" and "awaiting". [countingSince] is when counting was turned on (null = off).
     */
    fun build(period: Period, stamps: List<Stamp>, counters: Map<String, Long>, countingSince: Long?, zone: ZoneId): DaySummary {
        val inPeriod = stamps.filter { it.millis in period.fromMillis..period.toMillis }
        val window = Insights.window(stamps, period.fromMillis, period.toMillis, zone)
        val lookbackFrom = period.fromMillis - LOOKBACK_DAYS * 86_400_000L
        val upToEnd = Insights.window(stamps, lookbackFrom, period.toMillis, zone)
        val firstSeen = stamps.filter { it.millis in lookbackFrom..period.toMillis }
            .groupBy { it.threadId }.mapValues { (_, l) -> l.minOf { it.millis } }
        val counted = countingSince != null && countingSince <= period.toMillis
        return DaySummary(
            period = period,
            incoming = window.incoming,
            outgoing = window.outgoing,
            activeConversations = inPeriod.map { it.threadId }.distinct().size,
            newConversations = firstSeen.count { it.value >= period.fromMillis },
            awaitingResponse = upToEnd.waiting.size,
            responses = window.responses,
            medianResponseMillis = window.medianResponseMillis?.takeIf { window.responses >= MIN_RESPONSES },
            counts = if (counted) SummaryCounters.total(counters, period.firstDay, period.lastDay) else null,
            partialCounts = counted && countingSince!! > period.fromMillis,
        )
    }

    /**
     * What the notification may say. It never carries a body, a name or an address. With Quick
     * Cover on or the app lock set, it doesn't carry counts either; the detail is in the app,
     * behind the lock. The lock-screen (public) version never has counts.
     */
    sealed interface Notice {
        data object Generic : Notice
        data class Counts(val received: Int, val awaiting: Int) : Notice
    }

    fun notice(summary: DaySummary, coverActive: Boolean, appLocked: Boolean): Notice =
        if (coverActive || appLocked) Notice.Generic else Notice.Counts(summary.incoming, summary.awaitingResponse)

    fun epochMillis(t: ZonedDateTime) = t.toInstant().toEpochMilli()
    fun zoned(millis: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(zone)
}
