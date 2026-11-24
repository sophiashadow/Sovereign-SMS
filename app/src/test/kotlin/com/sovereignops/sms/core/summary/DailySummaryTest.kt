package com.sovereignops.sms.core.summary

import com.sovereignops.sms.core.insights.Stamp
import com.sovereignops.sms.core.rules.Alert
import com.sovereignops.sms.core.rules.Category
import com.sovereignops.sms.core.rules.Decision
import com.sovereignops.sms.core.rules.RuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class DailySummaryTest {
    private val ny = ZoneId.of("America/New_York")
    private val la = ZoneId.of("America/Los_Angeles")
    private val min = 60_000L
    private val hour = 60 * min

    private fun decision(alert: Alert, by: RuleId) = Decision(alert, by, emptyList(), emptySet(), listOf("synthetic"))
    private fun at(date: String, time: String, zone: ZoneId = ny) = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone)
    private fun ms(date: String, time: String) = at(date, time).toInstant().toEpochMilli()

    // ---- what each decision counts

    @Test
    fun quarantinedCountsOnlyAsQuarantined() {
        assertEquals(listOf(Metric.QUARANTINED), SummaryCounters.metricsFor(decision(Alert.SILENT, RuleId.QUARANTINED), Category.PROFESSIONAL))
    }

    @Test
    fun heldOnlyWhenTheRuleKeptTheAlertFromSounding() {
        assertEquals(listOf(Metric.PROFESSIONAL, Metric.HELD_FOCUS), SummaryCounters.metricsFor(decision(Alert.HELD, RuleId.WORKSPACE_FOCUS), Category.PROFESSIONAL))
        assertEquals(listOf(Metric.PERSONAL, Metric.HELD_FOCUS), SummaryCounters.metricsFor(decision(Alert.SILENT, RuleId.OFF_DUTY), Category.PERSONAL))
        assertEquals(listOf(Metric.UNKNOWN, Metric.HELD_SCHEDULE), SummaryCounters.metricsFor(decision(Alert.SILENT, RuleId.SCHEDULE), Category.UNKNOWN))
        assertEquals(listOf(Metric.PERSONAL, Metric.HELD_QUIET), SummaryCounters.metricsFor(decision(Alert.HELD, RuleId.QUIET_HOURS), Category.PERSONAL))
        // A rule that let the alert through, or a silence from another rule, isn't "held by focus/schedule/quiet hours".
        assertEquals(listOf(Metric.PERSONAL), SummaryCounters.metricsFor(decision(Alert.ALERT, RuleId.QUIET_HOURS), Category.PERSONAL))
        assertEquals(listOf(Metric.UNKNOWN), SummaryCounters.metricsFor(decision(Alert.SILENT, RuleId.UNKNOWN_SENDER), Category.UNKNOWN))
        assertEquals(listOf(Metric.PROFESSIONAL), SummaryCounters.metricsFor(decision(Alert.ALERT, RuleId.DEFAULT), Category.PROFESSIONAL))
    }

    // ---- counter keys

    @Test
    fun keysRoundTripAndPruneByDate() {
        val day = LocalDate.parse("2026-11-24")
        val key = SummaryCounters.key(day, Metric.HELD_QUIET)
        assertEquals("sum:2026-11-24:HELD_QUIET", key)
        assertEquals(day to Metric.HELD_QUIET, SummaryCounters.parse(key))
        assertNull(SummaryCounters.parse("sum:not-a-date:PERSONAL"))
        assertNull(SummaryCounters.parse("sum:2026-11-24:NOPE"))
        assertNull(SummaryCounters.parse("other"))
        val floor = SummaryCounters.pruneFloor(day)
        assertTrue(SummaryCounters.key(day.minusDays(36), Metric.PERSONAL) < floor)
        assertFalse(SummaryCounters.key(day.minusDays(35), Metric.PERSONAL) < floor)
        assertFalse(key < floor)
    }

    @Test
    fun totalsOnlyTheRequestedDays() {
        val c = mapOf(
            "sum:2026-11-23:PERSONAL" to 2L, "sum:2026-11-24:PERSONAL" to 3L, "sum:2026-11-24:QUARANTINED" to 1L,
            "sum:2026-11-25:PERSONAL" to 9L, "unrelated" to 50L,
        )
        val t = SummaryCounters.total(c, LocalDate.parse("2026-11-24"), LocalDate.parse("2026-11-24"))
        assertEquals(3L, t[Metric.PERSONAL])
        assertEquals(1L, t[Metric.QUARANTINED])
        assertEquals(0L, t[Metric.PROFESSIONAL])
    }

    // ---- scheduling

    @Test
    fun nextSlotRespectsSelectedDays() {
        val weekdays = SummarySchedule(LocalTime.of(20, 0), setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        // 2026-11-24 is a Tuesday: the next slot is Friday 20:00.
        assertEquals(at("2026-11-27", "20:00"), SummaryTiming.next(at("2026-11-24", "09:00"), weekdays))
        // Exactly at the slot, the next one is the following selected day.
        assertEquals(at("2026-11-30", "20:00"), SummaryTiming.next(at("2026-11-27", "20:00"), weekdays))
        assertEquals(at("2026-11-24", "20:00"), SummaryTiming.next(at("2026-11-24", "19:59"), SummarySchedule.DEFAULT))
        assertNull(SummaryTiming.next(at("2026-11-24", "09:00"), SummarySchedule(LocalTime.NOON, emptySet())))
    }

    @Test
    fun daylightSavingGapAndOverlap() {
        // Spring forward 2026-03-08: 02:30 doesn't exist in New York; the slot moves after the gap.
        val gap = SummaryTiming.next(at("2026-03-08", "00:00"), SummarySchedule(LocalTime.of(2, 30), DayOfWeek.entries.toSet()))!!
        assertEquals(LocalTime.of(3, 30), gap.toLocalTime())
        assertEquals(LocalDate.parse("2026-03-08"), gap.toLocalDate())
        // Fall back 2026-11-01: 01:30 happens twice; the slot is the first (EDT, UTC−4), so it fires once.
        val overlap = SummaryTiming.slot(LocalDate.parse("2026-11-01"), SummarySchedule(LocalTime.of(1, 30), DayOfWeek.entries.toSet()), ny)!!
        assertEquals(ZoneOffset.ofHours(-4), overlap.offset)
        val second = overlap.withLaterOffsetAtOverlap()
        assertFalse(SummaryTiming.due(second, SummarySchedule(LocalTime.of(1, 30), DayOfWeek.entries.toSet()), LocalDate.parse("2026-11-01")))
    }

    @Test
    fun dueCatchesUpTheSameDayOnly() {
        val s = SummarySchedule.DEFAULT
        assertFalse(SummaryTiming.due(at("2026-11-24", "19:59"), s, null))
        assertTrue(SummaryTiming.due(at("2026-11-24", "20:00"), s, null))
        // A late alarm or a reboot at 23:00 still delivers today's.
        assertTrue(SummaryTiming.due(at("2026-11-24", "23:00"), s, LocalDate.parse("2026-11-23")))
        // Already delivered today.
        assertFalse(SummaryTiming.due(at("2026-11-24", "23:00"), s, LocalDate.parse("2026-11-24")))
        // The next morning, yesterday's missed one is not sent; today's slot hasn't come.
        assertFalse(SummaryTiming.due(at("2026-11-25", "07:00"), s, LocalDate.parse("2026-11-23")))
        // A day that is switched off is never due.
        assertFalse(SummaryTiming.due(at("2026-11-24", "21:00"), SummarySchedule(LocalTime.of(20, 0), setOf(DayOfWeek.MONDAY)), null))
    }

    @Test
    fun westwardZoneChangeDoesNotRepeatTheDay() {
        val s = SummarySchedule.DEFAULT
        // Delivered 20:00 in New York, then the phone moves to Los Angeles: 20:00 local comes round again the same date.
        val laEvening = at("2026-11-24", "20:05", la)
        assertFalse(SummaryTiming.due(laEvening, s, LocalDate.parse("2026-11-24")))
        // Eastward the date can roll over; the new date's summary is due once its slot passes.
        assertTrue(SummaryTiming.due(at("2026-11-25", "20:01", ZoneId.of("Europe/London")), s, LocalDate.parse("2026-11-24")))
    }

    @Test
    fun morningSlotReportsYesterdayIncludingShortDstDay() {
        val p = SummaryTiming.period(at("2026-03-09", "08:00"))
        assertEquals(Period.Kind.YESTERDAY, p.kind)
        assertEquals(LocalDate.parse("2026-03-08"), p.firstDay)
        assertEquals(23 * hour - 1, p.toMillis - p.fromMillis) // spring-forward day has 23 hours
        val evening = SummaryTiming.period(at("2026-11-24", "20:00"))
        assertEquals(Period.Kind.TODAY_SO_FAR, evening.kind)
        assertEquals(ms("2026-11-24", "00:00"), evening.fromMillis)
        assertEquals(ms("2026-11-24", "20:00"), evening.toMillis)
    }

    // ---- figures

    private val today = Period.todaySoFar(at("2026-11-24", "20:00"))

    @Test
    fun emptyDatasetHasNoInventedFigures() {
        val s = DailySummary.build(today, emptyList(), emptyMap(), countingSince = null, zone = ny)
        assertEquals(0, s.incoming)
        assertEquals(0, s.outgoing)
        assertEquals(0, s.activeConversations)
        assertEquals(0, s.newConversations)
        assertEquals(0, s.awaitingResponse)
        assertNull(s.medianResponseMillis)
        assertNull(s.counts) // counting was off: unknown, not zero
        assertFalse(s.partialCounts)
        assertTrue(s.isEmpty)
    }

    @Test
    fun figuresFromTimestampsOnly() {
        val stamps = listOf(
            // Thread 1: known for weeks, answered today three times (10, 20, 30 min).
            Stamp(1, true, ms("2026-11-10", "12:00")),
            Stamp(1, true, ms("2026-11-24", "09:00")), Stamp(1, false, ms("2026-11-24", "09:10")),
            Stamp(1, true, ms("2026-11-24", "11:00")), Stamp(1, false, ms("2026-11-24", "11:20")),
            Stamp(1, true, ms("2026-11-24", "13:00")), Stamp(1, false, ms("2026-11-24", "13:30")),
            // Thread 2: first seen today, still waiting.
            Stamp(2, true, ms("2026-11-24", "18:00")),
            // Thread 3: waiting since yesterday, quiet today.
            Stamp(3, true, ms("2026-11-23", "18:00")),
            // Tomorrow's message is outside the period.
            Stamp(4, true, ms("2026-11-25", "08:00")),
        )
        val s = DailySummary.build(today, stamps, emptyMap(), countingSince = null, zone = ny)
        assertEquals(4, s.incoming)
        assertEquals(3, s.outgoing)
        assertEquals(2, s.activeConversations)
        assertEquals(1, s.newConversations)
        assertEquals(2, s.awaitingResponse) // threads 2 and 3
        assertEquals(3, s.responses)
        assertEquals(20 * min, s.medianResponseMillis)
    }

    @Test
    fun medianNeedsEnoughReplies() {
        val stamps = listOf(
            Stamp(1, true, ms("2026-11-24", "09:00")), Stamp(1, false, ms("2026-11-24", "09:10")),
            Stamp(2, true, ms("2026-11-24", "10:00")), Stamp(2, false, ms("2026-11-24", "10:05")),
        )
        val s = DailySummary.build(today, stamps, emptyMap(), null, ny)
        assertEquals(2, s.responses)
        assertNull(s.medianResponseMillis)
    }

    @Test
    fun countsMarkedPartialWhenCountingStartedMidPeriod() {
        val counters = mapOf("sum:2026-11-24:PROFESSIONAL" to 4L, "sum:2026-11-24:HELD_FOCUS" to 2L)
        val partial = DailySummary.build(today, emptyList(), counters, countingSince = ms("2026-11-24", "12:00"), zone = ny)
        assertEquals(4L, partial.counts!![Metric.PROFESSIONAL])
        assertTrue(partial.partialCounts)
        val full = DailySummary.build(today, emptyList(), counters, countingSince = ms("2026-11-01", "00:00"), zone = ny)
        assertFalse(full.partialCounts)
        // Turned on after the period ended: no counts for it at all.
        assertNull(DailySummary.build(today, emptyList(), counters, countingSince = ms("2026-11-24", "21:00"), zone = ny).counts)
    }

    // ---- notification privacy

    @Test
    fun noticeCarriesCountsOnlyWhenNothingIsProtected() {
        val stamps = listOf(Stamp(2, true, ms("2026-11-24", "18:00")))
        val s = DailySummary.build(today, stamps, emptyMap(), null, ny)
        assertEquals(DailySummary.Notice.Counts(received = 1, awaiting = 1), DailySummary.notice(s, coverActive = false, appLocked = false))
        assertEquals(DailySummary.Notice.Generic, DailySummary.notice(s, coverActive = true, appLocked = false))
        assertEquals(DailySummary.Notice.Generic, DailySummary.notice(s, coverActive = false, appLocked = true))
        // The notice type has no field that could hold a body, name or address.
        val fields = DailySummary.Notice.Counts::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.type }
        assertTrue(fields.all { it == Int::class.javaPrimitiveType })
    }
}
