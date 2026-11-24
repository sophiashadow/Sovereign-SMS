package com.sovereignops.sms.core.crm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrmTest {
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `unknown stage names fall back to New, never crash`() {
        assertEquals(Stage.NEW, Stage.parse("FUTURE_STAGE"))
        assertEquals(Stage.REGULAR, Stage.parse("REGULAR"))
        assertTrue(Stage.VERIFICATION_PENDING.unscreened)
        assertFalse(Stage.VERIFIED.unscreened)
    }

    @Test
    fun `blank card is detected`() {
        assertTrue(Card(1).isBlank)
        assertFalse(Card(1, alias = "MERIDIAN").isBlank)
        assertFalse(Card(1, stage = Stage.REGULAR).isBlank)
    }

    @Test
    fun `checklist progress ignores ticks for removed or switched-off items`() {
        val items = Checklist.parse(" a \n\nb\na\n#off c\n# comment")
        assertEquals(listOf(Checklist.Item("a"), Checklist.Item("b"), Checklist.Item("c", false)), items)
        assertEquals(1 to 2, Checklist.progress(items, setOf("a", "c", "gone")))
        assertEquals(items, Checklist.parse(Checklist.format(items)))
    }

    @Test
    fun `terms item ships switched off`() {
        val terms = Checklist.DEFAULT.single { it.label.startsWith("Deposit") }
        assertFalse(terms.enabled)
        assertFalse("Deposit / Terms confirmed" in Checklist.shown(Checklist.DEFAULT))
    }

    @Test
    fun `derived facts from timestamps`() {
        assertEquals(Derived(10, 30, 3), Derived.from(listOf(20, 10, 30)))
        assertEquals(Derived(null, null, 0), Derived.from(emptyList()))
    }

    @Test
    fun `cooldown warns on recency and on frequency`() {
        val now = 100 * day
        val c = Cooldown()
        assertNull(c.warning(emptyList(), now))
        assertNull(c.warning(listOf(now - 8 * day), now))
        assertEquals("Contacted 2 day(s) ago", c.warning(listOf(now - 2 * day), now))
        assertEquals("Contacted 4 times in the last 30 days", c.warning(listOf(now - 25 * day, now - 20 * day, now - 15 * day, now - 10 * day), now))
        assertEquals(listOf(now - 10 * day), c.prune(listOf(now - 40 * day, now - 10 * day), now))
    }
}
