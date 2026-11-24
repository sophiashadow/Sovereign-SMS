package com.sovereignops.sms.core.crm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelinkTest {
    private fun conv(id: Long, vararg n: String) = ConversationNumbers(id, n.toList())

    @Test
    fun `finds the conversation with the same set of numbers, ignoring format and order`() {
        val cs = listOf(conv(10, "+19195550142"), conv(11, "919-555-0143", "919-555-0144"))
        assertEquals(10L, Relink.find(listOf("(919) 555-0142"), cs))
        assertEquals(11L, Relink.find(listOf("+19195550144", "9195550143"), cs))
    }

    @Test
    fun `a different group size, an empty list or a stranger never matches`() {
        val cs = listOf(conv(11, "919-555-0143", "919-555-0144"))
        assertNull(Relink.find(listOf("919-555-0143"), cs))
        assertNull(Relink.find(listOf("919-555-0143", "919-555-0144", "919-555-0145"), cs))
        assertNull(Relink.find(emptyList(), cs))
        assertNull(Relink.find(listOf("919-555-0199"), cs))
    }

    @Test
    fun `plan links each conversation once and skips ones that already have a card`() {
        val cs = listOf(conv(10, "919-555-0142"), conv(11, "919-555-0143"), conv(12, "919-555-0144"))
        val moves = Relink.plan(
            unlinked = listOf(-1L to listOf("919-555-0142"), -2L to listOf("919-555-0142"), -3L to listOf("919-555-0143"), -4L to listOf("919-555-0144"), -5L to listOf("919-555-0150")),
            taken = setOf(11L),
            conversations = cs,
        )
        // -2 loses to -1 (same conversation), -3's conversation already has a card, -5 has no conversation.
        assertEquals(listOf(Relink.Move(-1, 10), Relink.Move(-4, 12)), moves)
    }
}
