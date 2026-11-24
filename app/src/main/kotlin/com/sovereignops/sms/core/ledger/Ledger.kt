package com.sovereignops.sms.core.ledger

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * How a ledger day's money was recorded.
 *  - [LEGACY]: saved before vault schema 3, as gross and room cost. Kept exactly as entered and
 *    reported as "gross − room", never as realized cash: gross may have included money not yet
 *    received, and room cost may not have been the only expense.
 *  - [CASH]: cash received, cash refunded and attributable cash expenses, with receivables and
 *    restricted credits recorded beside them but never counted as realized.
 */
enum class Basis { LEGACY, CASH }

/**
 * One working day, entered by hand. Money is kept in cents so nothing rounds; hours are
 * decimals. Nothing here reads messages: the ledger is only what the owner types in.
 *
 * [sessionHours] is service or production time; [overheadHours] is all other work time
 * (travel, admin, preparation, waiting). Labor hours are their sum.
 */
data class LedgerEntry(
    val id: Long = 0,
    val date: LocalDate,
    val roomCostCents: Long = 0,
    val clientsBooked: Int = 0,
    val clientsShowed: Int = 0,
    val grossCents: Long = 0,
    val sessionHours: Double = 0.0,
    val overheadHours: Double = 0.0,
    val note: String? = null,
    val basis: Basis = Basis.CASH,
    val cashReceivedCents: Long = 0,
    val refundCents: Long = 0,
    val expenseCents: Long = 0,
    /** Owed but not received. Not realized cash. */
    val receivableCents: Long = 0,
    /** Prepaid credit, deposits held or vouchers that can't yet be spent freely. Not realized cash. */
    val restrictedCreditCents: Long = 0,
) {
    val dayOfWeek: DayOfWeek get() = date.dayOfWeek
    val laborHours: Double get() = sessionHours + overheadHours

    /** Attended / booked, or null when nothing was booked. */
    val showRate: Double? get() = if (clientsBooked > 0) clientsShowed.toDouble() / clientsBooked else null

    /** Cash received − cash refunds − attributable cash expenses; null on a legacy day. */
    val netRealizedCents: Long? get() = if (basis == Basis.CASH) cashReceivedCents - refundCents - expenseCents else null

    /** Net realized cash ÷ labor hours, in cents per hour; null on a legacy day or with no hours (undefined, not zero). */
    val netCashPerLaborHour: Double? get() = netRealizedCents?.let { n -> if (laborHours > 0) n / laborHours else null }

    /** A legacy day's gross − room cost; null on a cash day. */
    val legacyNetCents: Long? get() = if (basis == Basis.LEGACY) grossCents - roomCostCents else null

    /** A legacy day's (gross − room cost) ÷ labor hours (the old "BCR"); null with no hours. */
    val legacyPerHour: Double? get() = legacyNetCents?.let { n -> if (laborHours > 0) n / laborHours else null }
}

data class LedgerSummary(
    val days: Int,
    val booked: Int,
    val showed: Int,
    val sessionHours: Double,
    val overheadHours: Double,
    val cashDays: Int,
    val cashReceivedCents: Long,
    val refundCents: Long,
    val expenseCents: Long,
    val receivableCents: Long,
    val restrictedCreditCents: Long,
    val cashLaborHours: Double,
    val legacyDays: Int,
    val legacyGrossCents: Long,
    val legacyRoomCents: Long,
    val legacyLaborHours: Double,
) {
    val laborHours get() = sessionHours + overheadHours
    val showRate: Double? get() = if (booked > 0) showed.toDouble() / booked else null
    val netRealizedCents get() = cashReceivedCents - refundCents - expenseCents

    /** Over cash-basis days only, so legacy hours never dilute it; null with no such hours. */
    val netCashPerLaborHour: Double? get() = if (cashDays > 0 && cashLaborHours > 0) netRealizedCents / cashLaborHours else null
    val legacyNetCents get() = legacyGrossCents - legacyRoomCents
    val legacyPerHour: Double? get() = if (legacyDays > 0 && legacyLaborHours > 0) legacyNetCents / legacyLaborHours else null

    /** Share of labor hours that were service rather than overhead. */
    val serviceShare: Double? get() = if (laborHours > 0) sessionHours / laborHours else null
}

object Ledger {
    /** The first twelve columns are the original export's, unchanged; the cash columns follow. */
    val CSV_HEADER = listOf(
        "date", "day_of_week", "room_cost", "clients_booked", "clients_showed", "gross",
        "session_hours", "overhead_hours", "note", "show_rate", "net", "bcr",
        "basis", "cash_received", "refunds", "expenses", "receivables", "restricted_credits",
        "net_realized_cash", "labor_hours", "net_cash_per_labor_hour",
    )

    fun summarize(entries: List<LedgerEntry>): LedgerSummary {
        val cash = entries.filter { it.basis == Basis.CASH }
        val legacy = entries.filter { it.basis == Basis.LEGACY }
        return LedgerSummary(
            days = entries.size,
            booked = entries.sumOf { it.clientsBooked },
            showed = entries.sumOf { it.clientsShowed },
            sessionHours = entries.sumOf { it.sessionHours },
            overheadHours = entries.sumOf { it.overheadHours },
            cashDays = cash.size,
            cashReceivedCents = cash.sumOf { it.cashReceivedCents },
            refundCents = cash.sumOf { it.refundCents },
            expenseCents = cash.sumOf { it.expenseCents },
            receivableCents = cash.sumOf { it.receivableCents },
            restrictedCreditCents = cash.sumOf { it.restrictedCreditCents },
            cashLaborHours = cash.sumOf { it.laborHours },
            legacyDays = legacy.size,
            legacyGrossCents = legacy.sumOf { it.grossCents },
            legacyRoomCents = legacy.sumOf { it.roomCostCents },
            legacyLaborHours = legacy.sumOf { it.laborHours },
        )
    }

    /** Workload by weekday, Monday first; weekdays with no entries are left out. */
    fun byDayOfWeek(entries: List<LedgerEntry>): List<Pair<DayOfWeek, LedgerSummary>> =
        entries.groupBy { it.dayOfWeek }.toSortedMap().map { (d, e) -> d to summarize(e) }

    /** "1234.5" -> 123450 cents; accepts a leading currency sign and thousands commas. Null if unreadable or negative. */
    fun parseMoney(text: String): Long? {
        val t = text.trim().removePrefix("$").replace(",", "")
        if (t.isEmpty()) return 0
        val v = t.toBigDecimalOrNull() ?: return null
        if (v.signum() < 0 || v.scale() > 2) return null
        return v.movePointRight(2).longValueExact()
    }

    fun money(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val a = Math.abs(cents)
        return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}"
    }

    fun percent(v: Double?) = v?.let { "%.0f%%".format(Locale.ROOT, it * 100) } ?: "—"
    fun perHour(v: Double?) = v?.let { money(Math.round(it)) + "/h" } ?: "—"
    fun hours(h: Double) = "%.2f".format(Locale.ROOT, h).trimEnd('0').trimEnd('.')
    private fun day(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    private fun csvCell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** RFC 4180 CSV, oldest first, including the derived columns. A figure that doesn't apply is empty, never 0. */
    fun toCsv(entries: List<LedgerEntry>): String = buildString {
        append(CSV_HEADER.joinToString(",")).append("\r\n")
        entries.sortedBy { it.date }.forEach { e ->
            val cash = e.basis == Basis.CASH
            fun c(v: Long) = if (cash) money(v) else ""
            val row = listOf(
                e.date.toString(), day(e.dayOfWeek), money(e.roomCostCents), e.clientsBooked.toString(), e.clientsShowed.toString(),
                money(e.grossCents), hours(e.sessionHours), hours(e.overheadHours), e.note.orEmpty(),
                e.showRate?.let { "%.4f".format(Locale.ROOT, it) }.orEmpty(), e.legacyNetCents?.let(::money).orEmpty(),
                e.legacyPerHour?.let { money(Math.round(it)) }.orEmpty(),
                e.basis.name.lowercase(Locale.ROOT), c(e.cashReceivedCents), c(e.refundCents), c(e.expenseCents),
                c(e.receivableCents), c(e.restrictedCreditCents), e.netRealizedCents?.let(::money).orEmpty(), hours(e.laborHours),
                e.netCashPerLaborHour?.let { money(Math.round(it)) }.orEmpty(),
            )
            append(row.joinToString(",") { csvCell(it) }).append("\r\n")
        }
    }

    private fun mdCell(s: String) = s.replace("|", "\\|").replace("\n", " ")

    fun toMarkdown(entries: List<LedgerEntry>): String = buildString {
        val sorted = entries.sortedBy { it.date }
        val cash = sorted.filter { it.basis == Basis.CASH }
        val legacy = sorted.filter { it.basis == Basis.LEGACY }
        val s = summarize(sorted)
        append("# Ledger\n\n")
        if (cash.isNotEmpty()) {
            append("| Date | Day | Booked | Attended | Received | Refunds | Expenses | Net realized | Receivables | Restricted credits | Service h | Overhead h | Net/labor h | Note |\n")
            append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n")
            cash.forEach { e ->
                append("| ${e.date} | ${day(e.dayOfWeek)} | ${e.clientsBooked} | ${e.clientsShowed} | ${money(e.cashReceivedCents)} | ${money(e.refundCents)} | ")
                append("${money(e.expenseCents)} | ${money(e.netRealizedCents!!)} | ${money(e.receivableCents)} | ${money(e.restrictedCreditCents)} | ")
                append("${hours(e.sessionHours)} | ${hours(e.overheadHours)} | ${perHour(e.netCashPerLaborHour)} | ${mdCell(e.note.orEmpty())} |\n")
            }
        }
        append("\n## Totals\n\n")
        append("- Days: ${s.days} (cash basis ${s.cashDays}, legacy ${s.legacyDays})\n")
        append("- Attendance: ${percent(s.showRate)} (${s.showed}/${s.booked})\n")
        append("- Net realized cash: ${money(s.netRealizedCents)} = received ${money(s.cashReceivedCents)} − refunds ${money(s.refundCents)} − expenses ${money(s.expenseCents)}\n")
        append("- Net cash per labor hour: ${perHour(s.netCashPerLaborHour)} over ${hours(s.cashLaborHours)} labor hours (cash-basis days)\n")
        append("- Not realized: receivables ${money(s.receivableCents)} · restricted credits ${money(s.restrictedCreditCents)}\n")
        append("- Hours: ${hours(s.sessionHours)} service + ${hours(s.overheadHours)} overhead · Service share: ${percent(s.serviceShare)}\n")
        if (legacy.isNotEmpty()) {
            append("- Legacy days: ${s.legacyDays} · gross − room ${money(s.legacyNetCents)} · ${perHour(s.legacyPerHour)} (not cash basis)\n")
            append("\n## Legacy days (gross − room, entered before cash fields)\n\n")
            append("| Date | Day | Room | Booked | Attended | Gross | Service h | Overhead h | Gross − room | Per labor h | Note |\n")
            append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|\n")
            legacy.forEach { e ->
                append("| ${e.date} | ${day(e.dayOfWeek)} | ${money(e.roomCostCents)} | ${e.clientsBooked} | ${e.clientsShowed} | ${money(e.grossCents)} | ")
                append("${hours(e.sessionHours)} | ${hours(e.overheadHours)} | ${money(e.legacyNetCents!!)} | ${perHour(e.legacyPerHour)} | ${mdCell(e.note.orEmpty())} |\n")
            }
        }
        val dow = byDayOfWeek(sorted)
        if (dow.isNotEmpty()) {
            append("\n## By weekday (cash basis)\n\n| Day | Days | Attendance | Net realized | Labor h | Net/labor h |\n|---|---:|---:|---:|---:|---:|\n")
            dow.forEach { (d, x) ->
                val net = if (x.cashDays > 0) money(x.netRealizedCents) else "—"
                append("| ${day(d)} | ${x.days} | ${percent(x.showRate)} | $net | ${hours(x.cashLaborHours)} | ${perHour(x.netCashPerLaborHour)} |\n")
            }
        }
    }
}
