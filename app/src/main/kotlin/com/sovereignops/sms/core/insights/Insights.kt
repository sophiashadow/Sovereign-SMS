package com.sovereignops.sms.core.insights

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * One message reduced to what workload figures need: which thread, which direction, when.
 * No body and no address, so nothing here can leak message content.
 */
data class Stamp(val threadId: Long, val incoming: Boolean, val millis: Long)

/** Row shape of the DAO's insights query: Telephony type 1 is received, 2 is sent; date is in seconds. */
data class StampRow(val threadId: Long, val type: Int, val date: Int) {
    fun toStamp() = Stamp(threadId, incoming = type == 1, millis = date * 1000L)
}

/** A thread whose latest message is incoming and unanswered, with the age of its first unanswered message. */
data class Waiting(val threadId: Long, val waitingMillis: Long)

data class Insights(
    val days: Int,
    val incoming: Int,
    val outgoing: Int,
    val incomingByHour: IntArray,
    val incomingByWeekday: Map<DayOfWeek, Int>,
    val busiestHour: Int?,
    val busiestWeekday: DayOfWeek?,
    /** Replies sent within [Insights.REPLY_WINDOW_MILLIS] of the first unanswered message. */
    val responses: Int,
    /** Replies that took longer than the window; kept out of the median so one old thread can't skew it. */
    val slowReplies: Int,
    val medianResponseMillis: Long?,
    val p90ResponseMillis: Long?,
    /** Oldest first. */
    val waiting: List<Waiting>,
) {
    companion object {
        const val REPLY_WINDOW_MILLIS = 24L * 60 * 60 * 1000

        /** Computes figures for the last [days] days ending at [now]. Input order doesn't matter. */
        fun compute(stamps: List<Stamp>, now: Long, zone: ZoneId, days: Int): Insights =
            window(stamps, now - days * 24L * 60 * 60 * 1000, now, zone).copy(days = days)

        /**
         * Figures for messages in [from]..[to] (inclusive). Waiting ages are measured to [to].
         * [Insights.days] is the window length rounded down to whole days.
         */
        fun window(stamps: List<Stamp>, from: Long, to: Long, zone: ZoneId): Insights {
            val now = to
            val days = ((to - from) / (24L * 60 * 60 * 1000)).toInt()
            val inWindow = stamps.filter { it.millis in from..now }
            val byHour = IntArray(24)
            val byDay = DayOfWeek.entries.associateWith { 0 }.toMutableMap()
            var incoming = 0
            var outgoing = 0
            for (s in inWindow) {
                if (!s.incoming) { outgoing++; continue }
                incoming++
                val t = Instant.ofEpochMilli(s.millis).atZone(zone)
                byHour[t.hour]++
                byDay[t.dayOfWeek] = byDay.getValue(t.dayOfWeek) + 1
            }

            val times = ArrayList<Long>()
            var slow = 0
            val waiting = ArrayList<Waiting>()
            for ((thread, list) in inWindow.groupBy { it.threadId }) {
                var firstUnanswered: Long? = null
                for (s in list.sortedBy { it.millis }) {
                    if (s.incoming) {
                        if (firstUnanswered == null) firstUnanswered = s.millis
                    } else if (firstUnanswered != null) {
                        val took = s.millis - firstUnanswered
                        if (took <= REPLY_WINDOW_MILLIS) times += took else slow++
                        firstUnanswered = null
                    }
                }
                firstUnanswered?.let { waiting += Waiting(thread, now - it) }
            }
            times.sort()
            return Insights(
                days = days, incoming = incoming, outgoing = outgoing,
                incomingByHour = byHour, incomingByWeekday = byDay,
                busiestHour = byHour.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index,
                busiestWeekday = byDay.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key,
                responses = times.size, slowReplies = slow,
                medianResponseMillis = rank(times, 0.5), p90ResponseMillis = rank(times, 0.9),
                waiting = waiting.sortedByDescending { it.waitingMillis },
            )
        }

        /** Nearest-rank percentile of a sorted list. */
        private fun rank(sorted: List<Long>, p: Double): Long? =
            if (sorted.isEmpty()) null else sorted[(Math.ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
    }

    // IntArray makes the generated equals/hashCode reference-based; compare figures, not arrays.
    override fun equals(other: Any?) = other is Insights && days == other.days && incoming == other.incoming && outgoing == other.outgoing &&
        incomingByHour.contentEquals(other.incomingByHour) && incomingByWeekday == other.incomingByWeekday && responses == other.responses &&
        slowReplies == other.slowReplies && medianResponseMillis == other.medianResponseMillis && p90ResponseMillis == other.p90ResponseMillis &&
        waiting == other.waiting
    override fun hashCode() = listOf(days, incoming, outgoing, incomingByHour.contentHashCode(), responses, slowReplies, waiting).hashCode()
}
