package com.sovereignops.sms.core.pack

import com.sovereignops.sms.core.guard.GuardRule
import com.sovereignops.sms.core.guard.MatchKind
import com.sovereignops.sms.core.guard.Pattern
import com.sovereignops.sms.core.rules.RuleConfig
import com.sovereignops.sms.core.rules.RuleConfigCodec
import com.sovereignops.sms.core.templates.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackTest {
    @Test
    fun `full pack round trips`() {
        val p = Pack(
            rules = RuleConfig(cooldownMinutes = 15),
            guard = listOf(GuardRule("Protected", Pattern("Ashford", MatchKind.KEYWORD))),
            templates = listOf(Template("Info", "Please send your name.")),
        )
        assertEquals(p, PackCodec.decode(PackCodec.encode(p)).getOrThrow())
    }

    @Test
    fun `partial pack leaves other sections null so import keeps them`() {
        val p = PackCodec.decode("""{"format":"sovereign-sms/pack","version":1,"templates":[{"title":"T","body":"B"}]}""").getOrThrow()
        assertNull(p.rules)
        assertNull(p.guard)
        assertEquals(listOf(Template("T", "B")), p.templates)
    }

    @Test
    fun `plain rules file imports as a pack`() {
        val p = PackCodec.decode(RuleConfigCodec.encode(RuleConfig(cooldownMinutes = 5))).getOrThrow()
        assertEquals(5, p.rules!!.cooldownMinutes)
    }

    @Test
    fun `bad files fail without throwing`() {
        listOf("", "[]", "{}", """{"format":"other"}""", """{"format":"sovereign-sms/pack","version":99}""", "{not json")
            .forEach { assertTrue(it, PackCodec.decode(it).isFailure) }
    }

    @Test
    fun `starters are neutral and ship disabled`() {
        assertTrue(Starters.guard.none { it.enabled })
        assertTrue(Starters.keywords.none { it.enabled })
        val digits = Regex("\\d{7,}")
        (Starters.templates.map { it.body } + Starters.guard.map { it.pattern.text }).forEach { assertTrue(it, !digits.containsMatchIn(it)) }
    }
}

class OwnerTemplateTest {
    @Test
    fun `shipped owner-rules template imports cleanly`() {
        val file = listOf("../docs/owner-rules.template.json", "docs/owner-rules.template.json").map { java.io.File(it) }.first { it.exists() }
        val p = PackCodec.decode(file.readText()).getOrThrow()
        assertEquals(2, p.guard!!.size)
        assertEquals(setOf(com.sovereignops.sms.core.rules.Category.PROFESSIONAL, com.sovereignops.sms.core.rules.Category.UNKNOWN), p.rules!!.quietHoursCategories)
        assertEquals(1, p.templates!!.size)
    }
}
