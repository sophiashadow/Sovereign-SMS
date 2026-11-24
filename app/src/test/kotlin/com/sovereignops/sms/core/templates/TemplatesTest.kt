package com.sovereignops.sms.core.templates

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplatesTest {
    @Test
    fun `insert into empty draft`() {
        assertEquals("Hello there" to 11, Templates.insert("", 0, 0, "Hello there"))
    }

    @Test
    fun `insert at cursor adds a separating space`() {
        assertEquals("Hi Hello" to 8, Templates.insert("Hi", 2, 2, "Hello"))
        assertEquals("Hi Hello" to 8, Templates.insert("Hi ", 3, 3, "Hello"))
    }

    @Test
    fun `insert replaces selection and tolerates reversed or out-of-range indices`() {
        assertEquals("a X d" to 3, Templates.insert("a bc d", 4, 2, "X"))
        assertEquals("abc X" to 5, Templates.insert("abc", 99, 99, "X"))
    }

    @Test
    fun `text form round trips multi-line bodies`() {
        val t = listOf(Template("Info", "Line one\n\nLine three"), Template("Decline", "No thanks."))
        assertEquals(t, Templates.parse(Templates.format(t)))
        assertEquals(emptyList<Template>(), Templates.parse("Title only\n---\n"))
    }
}
