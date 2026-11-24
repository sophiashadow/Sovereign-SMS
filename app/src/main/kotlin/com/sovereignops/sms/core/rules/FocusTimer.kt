package com.sovereignops.sms.core.rules

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/** Timed focus: preset lengths, a custom end time, and the discreet warning before the end. */
object FocusTimer {
    val presetMinutes = listOf(30, 60, 90)
    val WARNING: Duration = Duration.ofMinutes(5)

    fun until(now: LocalDateTime, minutes: Int): LocalDateTime = now.plusMinutes(minutes.toLong())

    /** A custom clock time: today if still ahead, otherwise tomorrow. */
    fun untilClock(now: LocalDateTime, end: LocalTime): LocalDateTime {
        val today = now.toLocalDate().atTime(end)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** When to warn: five minutes before the end, or null if that moment has already passed. */
    fun warnAt(now: LocalDateTime, until: LocalDateTime?): LocalDateTime? {
        val at = until?.minus(WARNING) ?: return null
        return if (at.isAfter(now)) at else null
    }
}
