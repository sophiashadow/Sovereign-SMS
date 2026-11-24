package com.sovereignops.sms.core.ledger

import com.sovereignops.sms.core.rules.FocusTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Synthetic figures only.
class LedgerTest {
    private val mon = LedgerEntry(1, LocalDate.of(2026, 11, 23), 5000, 4, 3, 60000, 3.0, 1.0, "Ashford, room B")
    private val wed = LedgerEntry(2, LocalDate.of(2026, 11, 25), 0, 0, 0, 0, 0.0, 2.0, "admin \"only\"")

    @Test
    fun `show rate, net and BCR`() {
        assertEquals(0.75, mon.showRate!!, 1e-9)
        assertEquals(55000, mon.netCents)
        assertEquals(13750.0, mon.bcrCentsPerHour!!, 1e-9) // (600 − 50) / 4 h = 137.50/h
        assertEquals(DayOfWeek.MONDAY, mon.dayOfWeek)
        assertNull(wed.showRate)
        assertEquals(0.0, wed.bcrCentsPerHour!!, 1e-9)
        assertNull(wed.copy(overheadHours = 0.0).bcrCentsPerHour)
    }

    @Test
    fun `summary and weekday workload`() {
        val s = Ledger.summarize(listOf(mon, wed))
        assertEquals(55000, s.netCents)
        assertEquals(6.0, s.totalHours, 1e-9)
        assertEquals(0.5, s.sessionShare!!, 1e-9)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), Ledger.byDayOfWeek(listOf(wed, mon)).map { it.first })
    }

    @Test
    fun `money parsing is exact and rejects bad input`() {
        assertEquals(123450L, Ledger.parseMoney("$1,234.50"))
        assertEquals(0L, Ledger.parseMoney(" "))
        assertNull(Ledger.parseMoney("-5"))
        assertNull(Ledger.parseMoney("1.005"))
        assertNull(Ledger.parseMoney("ten"))
        assertEquals("-0.05", Ledger.money(-5))
    }

    @Test
    fun `CSV has the eight fields plus note and derived columns, quoted per RFC 4180`() {
        val csv = Ledger.toCsv(listOf(wed, mon)).split("\r\n")
        assertEquals("date,day_of_week,room_cost,clients_booked,clients_showed,gross,session_hours,overhead_hours,note,show_rate,net,bcr", csv[0])
        assertEquals("2026-11-23,Mon,50.00,4,3,600.00,3,1,\"Ashford, room B\",0.7500,550.00,137.50", csv[1])
        assertEquals("2026-11-25,Wed,0.00,0,0,0.00,0,2,\"admin \"\"only\"\"\",,0.00,0.00", csv[2])
    }

    @Test
    fun `markdown export has totals and weekday table`() {
        val md = Ledger.toMarkdown(listOf(mon, wed))
        assertTrue(md.contains("- BCR: 91.67/h"))
        assertTrue(md.contains("| Mon | 1 | 75% | 550.00 | 4 | 137.50/h |"))
    }
}

class FocusTimerTest {
    private val now = LocalDateTime.of(2026, 11, 25, 22, 0)

    @Test
    fun `presets, custom clock and the five-minute warning`() {
        assertEquals(listOf(30, 60, 90), FocusTimer.presetMinutes)
        assertEquals(now.plusMinutes(90), FocusTimer.until(now, 90))
        assertEquals(now.plusDays(1).withHour(8), FocusTimer.untilClock(now, LocalTime.of(8, 0)))
        assertEquals(now.withHour(23), FocusTimer.untilClock(now, LocalTime.of(23, 0)))
        assertEquals(now.plusMinutes(25), FocusTimer.warnAt(now, FocusTimer.until(now, 30)))
        assertNull(FocusTimer.warnAt(now, now.plusMinutes(4)))
        assertNull(FocusTimer.warnAt(now, null))
    }
}
