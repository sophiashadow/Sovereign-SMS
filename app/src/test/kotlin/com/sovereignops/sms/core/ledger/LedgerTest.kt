package com.sovereignops.sms.core.ledger

import com.sovereignops.sms.core.rules.FocusTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Synthetic figures only.
class LedgerTest {
    // Rows saved before schema 3: gross and room cost, no cash fields. They stay LEGACY.
    private val mon = LedgerEntry(1, LocalDate.of(2026, 11, 23), 5000, 4, 3, 60000, 3.0, 1.0, "Ashford, room B", basis = Basis.LEGACY)
    private val wed = LedgerEntry(2, LocalDate.of(2026, 11, 25), 0, 0, 0, 0, 0.0, 2.0, "admin \"only\"", basis = Basis.LEGACY)

    // A cash-basis day: 800 received, 50 refunded, 90 attributable expenses; 200 owed and 150 in
    // restricted credit are recorded but are not realized cash.
    private val tue = LedgerEntry(
        id = 3, date = LocalDate.of(2026, 11, 24), clientsBooked = 5, clientsShowed = 4, sessionHours = 4.0, overheadHours = 2.0,
        note = "MERIDIAN", basis = Basis.CASH, cashReceivedCents = 80000, refundCents = 5000, expenseCents = 9000,
        receivableCents = 20000, restrictedCreditCents = 15000,
    )

    @Test
    fun `net realized cash is received minus refunds minus expenses, receivables and credits excluded`() {
        assertEquals(66000L, tue.netRealizedCents)
        assertEquals(6.0, tue.laborHours, 1e-9)
        assertEquals(11000.0, tue.netCashPerLaborHour!!, 1e-9) // 660.00 / 6 h
        assertEquals(66000L, tue.copy(receivableCents = 999_999, restrictedCreditCents = 999_999).netRealizedCents)
        assertEquals(-4000L, tue.copy(cashReceivedCents = 10000, refundCents = 5000, expenseCents = 9000).netRealizedCents)
        assertNull(tue.legacyNetCents)
    }

    @Test
    fun `zero labor hours is undefined, not zero`() {
        val none = tue.copy(sessionHours = 0.0, overheadHours = 0.0)
        assertNull(none.netCashPerLaborHour)
        assertEquals(0.0, tue.copy(cashReceivedCents = 0, refundCents = 0, expenseCents = 0).netCashPerLaborHour!!, 1e-9)
        assertEquals("—", Ledger.perHour(none.netCashPerLaborHour))
    }

    @Test
    fun `legacy rows keep gross minus room and are not read as realized cash`() {
        assertEquals(0.75, mon.showRate!!, 1e-9)
        assertEquals(55000L, mon.legacyNetCents)
        assertEquals(13750.0, mon.legacyPerHour!!, 1e-9) // (600 − 50) / 4 h
        assertNull(mon.netRealizedCents)
        assertNull(mon.netCashPerLaborHour)
        assertEquals(DayOfWeek.MONDAY, mon.dayOfWeek)
        assertNull(wed.showRate)
        assertEquals(0.0, wed.legacyPerHour!!, 1e-9)
        assertNull(wed.copy(overheadHours = 0.0).legacyPerHour)
    }

    @Test
    fun `summary keeps the two bases apart`() {
        val s = Ledger.summarize(listOf(mon, wed, tue))
        assertEquals(3, s.days)
        assertEquals(1, s.cashDays)
        assertEquals(2, s.legacyDays)
        assertEquals(66000L, s.netRealizedCents)
        assertEquals(6.0, s.cashLaborHours, 1e-9)
        assertEquals(11000.0, s.netCashPerLaborHour!!, 1e-9) // legacy hours never dilute the cash figure
        assertEquals(20000L, s.receivableCents)
        assertEquals(15000L, s.restrictedCreditCents)
        assertEquals(55000L, s.legacyNetCents)
        assertEquals(6.0, s.legacyLaborHours, 1e-9)
        assertEquals(12.0, s.laborHours, 1e-9)
        assertEquals(7.0 / 12.0, s.serviceShare!!, 1e-9)
        assertEquals(7.0 / 9.0, s.showRate!!, 1e-9)
        assertNull(Ledger.summarize(listOf(mon, wed)).netCashPerLaborHour)
        assertNull(Ledger.summarize(emptyList()).netCashPerLaborHour)
        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY),
            Ledger.byDayOfWeek(listOf(wed, tue, mon)).map { it.first },
        )
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
    fun `CSV keeps the original twelve columns and appends the cash columns, quoted per RFC 4180`() {
        val csv = Ledger.toCsv(listOf(wed, tue, mon)).split("\r\n")
        assertEquals(
            "date,day_of_week,room_cost,clients_booked,clients_showed,gross,session_hours,overhead_hours,note,show_rate,net,bcr," +
                "basis,cash_received,refunds,expenses,receivables,restricted_credits,net_realized_cash,labor_hours,net_cash_per_labor_hour",
            csv[0],
        )
        // Legacy rows: the first twelve columns exactly as before; cash columns empty, never 0.
        assertEquals("2026-11-23,Mon,50.00,4,3,600.00,3,1,\"Ashford, room B\",0.7500,550.00,137.50,legacy,,,,,,,4,", csv[1])
        assertEquals("2026-11-24,Tue,0.00,5,4,0.00,4,2,MERIDIAN,0.8000,,,cash,800.00,50.00,90.00,200.00,150.00,660.00,6,110.00", csv[2])
        assertEquals("2026-11-25,Wed,0.00,0,0,0.00,0,2,\"admin \"\"only\"\"\",,0.00,0.00,legacy,,,,,,,2,", csv[3])
    }

    @Test
    fun `markdown export separates realized cash from legacy rows`() {
        val md = Ledger.toMarkdown(listOf(mon, wed, tue))
        assertTrue(md.contains("- Net realized cash: 660.00 = received 800.00 − refunds 50.00 − expenses 90.00"))
        assertTrue(md.contains("- Net cash per labor hour: 110.00/h over 6 labor hours (cash-basis days)"))
        assertTrue(md.contains("- Not realized: receivables 200.00 · restricted credits 150.00"))
        assertTrue(md.contains("- Legacy days: 2 · gross − room 550.00 · 91.67/h (not cash basis)"))
        assertTrue(md.contains("| 2026-11-24 | Tue | 5 | 4 | 800.00 | 50.00 | 90.00 | 660.00 | 200.00 | 150.00 | 4 | 2 | 110.00/h | MERIDIAN |"))
        assertTrue(md.contains("| 2026-11-23 | Mon | 50.00 | 4 | 3 | 600.00 | 3 | 1 | 550.00 | 137.50/h | Ashford, room B |"))
        assertTrue(md.contains("| Tue | 1 | 80% | 660.00 | 6 | 110.00/h |"))
        assertFalse(Ledger.toMarkdown(listOf(tue)).contains("Legacy"))
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
        assertNull(FocusTimer.warnAt(now, null))
        assertNull(FocusTimer.warnAt(now, now.plusMinutes(4)))
    }
}
