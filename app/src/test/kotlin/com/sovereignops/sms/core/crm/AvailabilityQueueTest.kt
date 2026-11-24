package com.sovereignops.sms.core.crm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AvailabilityQueueTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    @Test
    fun `only opted-in cards, oldest contact first, cooldown hits last`() {
        val cards = listOf(
            Card(1, alias = "A", optInAvailability = true, lastContacted = now - 20 * day),
            Card(2, alias = "B", optInAvailability = false, lastContacted = now - 90 * day),
            Card(3, alias = "C", optInAvailability = true, availabilityPings = listOf(now - 2 * day)),
            Card(4, alias = "D", optInAvailability = true),
            Card(5, alias = "E", optInAvailability = true, lastContacted = now - 40 * day),
        )
        val q = AvailabilityQueue.build(cards, now)
        assertEquals(listOf(4L, 5L, 1L, 3L), q.map { it.card.threadId })
        assertNull(q[0].warning)
        assertNotNull(q.last().warning)
    }

    @Test
    fun `thread activity counts as last contact`() {
        val cards = listOf(
            Card(1, optInAvailability = true, lastContacted = now - 30 * day),
            Card(2, optInAvailability = true, lastContacted = now - 10 * day),
        )
        val q = AvailabilityQueue.build(cards, now) { if (it.threadId == 1L) now - 8 * day else null }
        assertEquals(listOf(2L, 1L), q.map { it.card.threadId })
    }

    @Test
    fun `four pings in 30 days triggers the cooldown`() {
        val pings = listOf(now - 25 * day, now - 18 * day, now - 11 * day, now - 8 * day)
        val q = AvailabilityQueue.build(listOf(Card(1, optInAvailability = true, availabilityPings = pings)), now)
        assertEquals("Contacted 4 times in the last 30 days", q.single().warning)
    }

    @Test
    fun `record send adds ping, prunes old ones and sets last contact`() {
        val c = Card(1, optInAvailability = true, availabilityPings = listOf(now - 45 * day, now - 9 * day))
        val after = AvailabilityQueue.recordSend(c, now)
        assertEquals(listOf(now - 9 * day, now), after.availabilityPings)
        assertEquals(now, after.lastContacted)
    }
}
