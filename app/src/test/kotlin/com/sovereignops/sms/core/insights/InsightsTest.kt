package com.sovereignops.sms.core.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

class InsightsTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour

    // Monday 2026-11-23 09:00 UTC.
    private val monday9 = Instant.parse("2026-11-23T09:00:00Z").toEpochMilli()
    private val now = monday9 + 6 * day // Sunday 2026-11-29 09:00 UTC

    private fun s(thread: Long, incoming: Boolean, at: Long) = Stamp(thread, incoming, at)
    private fun compute(vararg st: Stamp, days: Int = 30) = Insights.compute(st.toList(), now, ZoneOffset.UTC, days)

    @Test
    fun `empty input has no figures`() {
        val i = compute()
        assertEquals(0, i.incoming); assertEquals(0, i.outgoing)
        assertNull(i.medianResponseMillis); assertNull(i.busiestHour); assertNull(i.busiestWeekday)
        assertEquals(emptyList<Waiting>(), i.waiting)
    }

    @Test
    fun `counts only the window and buckets by local hour and weekday`() {
        val i = compute(
            s(1, true, monday9), s(1, true, monday9 + 20 * min), s(2, true, monday9 + 2 * hour), s(1, false, monday9 + hour),
            s(3, true, now - 40 * day), // outside a 30 day window
            days = 30,
        )
        assertEquals(3, i.incoming)
        assertEquals(1, i.outgoing)
        assertEquals(2, i.incomingByHour[9]); assertEquals(1, i.incomingByHour[11])
        assertEquals(9, i.busiestHour)
        assertEquals(DayOfWeek.MONDAY, i.busiestWeekday)
        assertEquals(3, i.incomingByWeekday[DayOfWeek.MONDAY])
    }

    @Test
    fun `response time runs from the first unanswered message of a run to the reply`() {
        val i = compute(
            s(1, true, monday9), s(1, true, monday9 + 5 * min), s(1, false, monday9 + 10 * min),   // 10 min
            s(1, true, monday9 + hour), s(1, false, monday9 + hour + 30 * min),                      // 30 min
            s(2, true, monday9), s(2, false, monday9 + 50 * min),                                    // 50 min
        )
        assertEquals(3, i.responses)
        assertEquals(30 * min, i.medianResponseMillis) // nearest rank of [10, 30, 50]
        assertEquals(50 * min, i.p90ResponseMillis)
    }

    @Test
    fun `replies after a day are counted as slow, not averaged in`() {
        val i = compute(
            s(1, true, monday9), s(1, false, monday9 + 10 * min),
            s(2, true, monday9), s(2, false, monday9 + 2 * day),
        )
        assertEquals(1, i.responses); assertEquals(1, i.slowReplies)
        assertEquals(10 * min, i.medianResponseMillis)
    }

    @Test
    fun `waiting lists threads whose last message is incoming, oldest first`() {
        val i = compute(
            s(1, true, monday9 + day), s(2, true, monday9), s(2, false, monday9 + hour), // answered
            s(3, true, monday9 + 2 * hour), s(4, true, monday9 + 3 * day), s(4, true, monday9 + 3 * day + hour),
        )
        assertEquals(listOf(3L, 1L, 4L), i.waiting.map { it.threadId })
        assertEquals(now - (monday9 + 2 * hour), i.waiting.first().waitingMillis)
        assertEquals(now - (monday9 + 3 * day), i.waiting.last().waitingMillis) // since the first unanswered message
    }

    @Test
    fun `unsorted input is handled and outgoing-first threads are not responses`() {
        val i = compute(s(1, false, monday9), s(1, true, monday9 + hour), s(1, false, monday9 + hour + 5 * min), s(1, true, monday9 - hour))
        assertEquals(2, i.responses) // in@-1h -> out@0 (60 min), in@+1h -> out@+65min (5 min)
        assertEquals(5 * min, i.medianResponseMillis)
    }
}
