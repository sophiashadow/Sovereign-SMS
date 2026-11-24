package com.sovereignops.sms.core.rules

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Text form of schedule rules, one per line, edited on the settings screen:
 *
 *     After hours: weekdays 21:00-09:00 professional,unknown held
 *     Weekend: weekends 00:00-00:00 professional silent      (start = end: whole day)
 *     #off Lunch: mon,wed,fri 12:00-13:00 all silent         (kept, disabled)
 *
 * Days: `daily`, `weekdays`, `weekends`, `mon,tue`, `mon-fri`. Categories: `personal`,
 * `professional`, `unknown` (comma-separated) or `all`. Action: `alert`, `silent`, `held`.
 * A line that does not parse is reported by [invalid] and never silently dropped.
 */
object ScheduleText {
    private const val OFF = "#off "
    private val DAY = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
    )
    private val TIME = Regex("^(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})$")

    private fun lines(text: String) = text.lineSequence().map(String::trim).filter { it.isNotEmpty() && (!it.startsWith("#") || it.startsWith(OFF)) }

    fun parse(text: String): List<ScheduleRule> = lines(text).mapNotNull(::parseLine).toList()

    /** Lines that are not comments and do not parse. */
    fun invalid(text: String): List<String> = lines(text).filter { parseLine(it) == null }.toList()

    fun parseLine(raw: String): ScheduleRule? {
        val enabled = !raw.startsWith(OFF)
        val line = if (enabled) raw else raw.removePrefix(OFF).trim()
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val name = line.substring(0, colon).trim()
        val words = line.substring(colon + 1).trim().split(Regex("\\s+"))
        if (words.size != 4) return null
        val days = days(words[0]) ?: return null
        val m = TIME.matchEntire(words[1]) ?: return null
        val (h1, m1, h2, m2) = m.destructured.toList().map(String::toInt)
        if (h1 > 23 || h2 > 23 || m1 > 59 || m2 > 59) return null
        val cats = categories(words[2]) ?: return null
        val action = Alert.entries.firstOrNull { it.name.equals(words[3], true) } ?: return null
        return ScheduleRule(name, TimeWindow(days, LocalTime.of(h1, m1), LocalTime.of(h2, m2)), cats, action, enabled)
    }

    private fun days(w: String): Set<DayOfWeek>? = when (w.lowercase()) {
        "daily" -> DayOfWeek.entries.toSet()
        "weekdays" -> (DayOfWeek.MONDAY..DayOfWeek.FRIDAY)
        "weekends" -> setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        else -> w.lowercase().split(',').flatMap { part ->
            val r = part.split('-')
            when (r.size) {
                1 -> listOf(DAY[r[0]] ?: return null)
                2 -> (DAY[r[0]] ?: return null)..(DAY[r[1]] ?: return null)
                else -> return null
            }
        }.toSet().ifEmpty { null }
    }

    private operator fun DayOfWeek.rangeTo(end: DayOfWeek): Set<DayOfWeek> {
        val out = LinkedHashSet<DayOfWeek>()
        var d = this
        while (true) {
            out += d
            if (d == end) break
            d = d.plus(1)
        }
        return out
    }

    private fun categories(w: String): Set<Category>? =
        if (w.equals("all", true)) Category.entries.toSet()
        else w.split(',').map { c -> Category.entries.firstOrNull { it.name.equals(c.trim(), true) } ?: return null }.toSet()

    private fun formatDays(d: Set<DayOfWeek>): String = when (d) {
        DayOfWeek.entries.toSet() -> "daily"
        setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) -> "weekdays"
        setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "weekends"
        else -> d.sortedBy { it.value }.joinToString(",") { day -> DAY.entries.first { it.value == day }.key }
    }

    private fun t(x: LocalTime) = "%02d:%02d".format(x.hour, x.minute)

    fun format(rules: List<ScheduleRule>): String = rules.joinToString("\n") { r ->
        val cats = if (r.categories == Category.entries.toSet()) "all" else r.categories.sortedBy { it.ordinal }.joinToString(",") { it.name.lowercase() }
        (if (r.enabled) "" else OFF) + "${r.name}: ${formatDays(r.window.days)} ${t(r.window.start)}-${t(r.window.end)} $cats ${r.action.name.lowercase()}"
    }
}
