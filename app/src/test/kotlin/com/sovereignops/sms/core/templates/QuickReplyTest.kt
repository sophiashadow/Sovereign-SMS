package com.sovereignops.sms.core.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickReplyTest {
    private val text = """
        Hours | category=initial_info favorite
        Hi {alias}, MERIDIAN hours are on the site.
        ---
        Not this week | category=deflection
        Thanks, not available this week.
        ---
        Plain title
        Body only.
        ---
        Odd | title with bar
        Kept whole.
    """.trimIndent()

    @Test
    fun `title options parse and round-trip`() {
        val t = Templates.parse(text)
        assertEquals(4, t.size)
        assertEquals(TemplateCategory.INITIAL_INFO, t[0].category)
        assertTrue(t[0].favorite)
        assertEquals("Hours", t[0].title)
        assertEquals(TemplateCategory.DEFLECTION, t[1].category)
        assertFalse(t[1].favorite)
        assertEquals("Plain title", t[2].title)
        assertEquals("Odd | title with bar", t[3].title)
        assertEquals(null, t[3].category)
        assertEquals(t, Templates.parse(Templates.format(t)))
    }

    @Test
    fun `bar lists favorites then used categories in fixed order`() {
        val (fav, cats) = Templates.bar(Templates.parse(text))
        assertEquals(listOf("Hours"), fav.map { it.title })
        assertEquals(listOf(TemplateCategory.INITIAL_INFO, TemplateCategory.DEFLECTION), cats)
    }

    @Test
    fun `placeholders fill from card and report missing`() {
        val body = "Hi {alias}, {time_window} on {date} works. {other} stays."
        val values = mapOf("alias" to "Ashford", "time_window" to " ", "date" to null)
        assertEquals("Hi Ashford, {time_window} on {date} works. {other} stays.", Placeholders.fill(body, values))
        assertEquals(listOf("time_window", "date"), Placeholders.missing(body, values))
        assertEquals(emptyList<String>(), Placeholders.names("no tokens {x}"))
    }

    @Test
    fun `every category label is distinct and parses from its key`() {
        TemplateCategory.entries.forEach { assertEquals(it, TemplateCategory.parse(it.key)) }
        assertEquals(TemplateCategory.entries.size, TemplateCategory.entries.map { it.label }.toSet().size)
    }
}
