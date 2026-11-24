package com.sovereignops.sms.core.ledger

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * One working day, entered by hand. Money is kept in cents so nothing rounds; hours are
 * decimals. Nothing here reads messages: the ledger is only what the owner types in.
 */
data class LedgerEntry(
    val id: Long = 0,
    val date: LocalDate,
    val roomCostCents: Long,
    val clientsBooked: Int,
    val clientsShowed: Int,
    val grossCents: Long,
    val sessionHours: Double,
    val overheadHours: Double,
    val note: String? = null,
) {
    val dayOfWeek: DayOfWeek get() = date.dayOfWeek
    val netCents: Long get() = grossCents - roomCostCents
    val totalHours: Double get() = sessionHours + overheadHours

    /** Showed / booked, or null when nothing was booked. */
    val showRate: Double? get() = if (clientsBooked > 0) clientsShowed.toDouble() / clientsBooked else null

    /** BCR = (gross − room cost) / (session hours + overhead hours), in cents per hour; null with no hours. */
    val bcrCentsPerHour: Double? get() = if (totalHours > 0) netCents / totalHours else null
}

data class LedgerSummary(
    val days: Int,
    val booked: Int,
    val showed: Int,
    val grossCents: Long,
    val roomCostCents: Long,
    val sessionHours: Double,
    val overheadHours: Double,
) {
    val netCents get() = grossCents - roomCostCents
    val totalHours get() = sessionHours + overheadHours
    val showRate: Double? get() = if (booked > 0) showed.toDouble() / booked else null
    val bcrCentsPerHour: Double? get() = if (totalHours > 0) netCents / totalHours else null

    /** Share of worked hours that were sessions rather than overhead. */
    val sessionShare: Double? get() = if (totalHours > 0) sessionHours / totalHours else null
}

object Ledger {
    val CSV_HEADER = listOf(
        "date", "day_of_week", "room_cost", "clients_booked", "clients_showed", "gross",
        "session_hours", "overhead_hours", "note", "show_rate", "net", "bcr",
    )

    fun summarize(entries: List<LedgerEntry>) = LedgerSummary(
        days = entries.size,
        booked = entries.sumOf { it.clientsBooked },
        showed = entries.sumOf { it.clientsShowed },
        grossCents = entries.sumOf { it.grossCents },
        roomCostCents = entries.sumOf { it.roomCostCents },
        sessionHours = entries.sumOf { it.sessionHours },
        overheadHours = entries.sumOf { it.overheadHours },
    )

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
    private fun hours(h: Double) = "%.2f".format(Locale.ROOT, h).trimEnd('0').trimEnd('.')
    private fun day(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    private fun csvCell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** RFC 4180 CSV, oldest first, including the derived columns. */
    fun toCsv(entries: List<LedgerEntry>): String = buildString {
        append(CSV_HEADER.joinToString(",")).append("\r\n")
        entries.sortedBy { it.date }.forEach { e ->
            val row = listOf(
                e.date.toString(), day(e.dayOfWeek), money(e.roomCostCents), e.clientsBooked.toString(), e.clientsShowed.toString(),
                money(e.grossCents), hours(e.sessionHours), hours(e.overheadHours), e.note.orEmpty(),
                e.showRate?.let { "%.4f".format(Locale.ROOT, it) }.orEmpty(), money(e.netCents),
                e.bcrCentsPerHour?.let { money(Math.round(it)) }.orEmpty(),
            )
            append(row.joinToString(",") { csvCell(it) }).append("\r\n")
        }
    }

    private fun mdCell(s: String) = s.replace("|", "\\|").replace("\n", " ")

    fun toMarkdown(entries: List<LedgerEntry>): String = buildString {
        val sorted = entries.sortedBy { it.date }
        val s = summarize(sorted)
        append("# Ledger\n\n")
        append("| Date | Day | Room | Booked | Showed | Gross | Session h | Overhead h | Show rate | Net | BCR | Note |\n")
        append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n")
        sorted.forEach { e ->
            append("| ${e.date} | ${day(e.dayOfWeek)} | ${money(e.roomCostCents)} | ${e.clientsBooked} | ${e.clientsShowed} | ${money(e.grossCents)} | ")
            append("${hours(e.sessionHours)} | ${hours(e.overheadHours)} | ${percent(e.showRate)} | ${money(e.netCents)} | ${perHour(e.bcrCentsPerHour)} | ${mdCell(e.note.orEmpty())} |\n")
        }
        append("\n## Totals\n\n")
        append("- Days: ${s.days}\n- Show rate: ${percent(s.showRate)} (${s.showed}/${s.booked})\n")
        append("- Gross: ${money(s.grossCents)} · Room: ${money(s.roomCostCents)} · Net: ${money(s.netCents)}\n")
        append("- Hours: ${hours(s.sessionHours)} session + ${hours(s.overheadHours)} overhead · Session share: ${percent(s.sessionShare)}\n")
        append("- BCR: ${perHour(s.bcrCentsPerHour)}\n")
        val dow = byDayOfWeek(sorted)
        if (dow.isNotEmpty()) {
            append("\n## By weekday\n\n| Day | Days | Show rate | Net | Hours | BCR |\n|---|---:|---:|---:|---:|---:|\n")
            dow.forEach { (d, x) -> append("| ${day(d)} | ${x.days} | ${percent(x.showRate)} | ${money(x.netCents)} | ${hours(x.totalHours)} | ${perHour(x.bcrCentsPerHour)} |\n") }
        }
    }
}
