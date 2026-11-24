package com.sovereignops.sms.core.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Synthetic strings only ("Ashford", "MERIDIAN", example.invalid).
class DraftGuardTest {
    private fun kw(name: String, t: String) = GuardRule(name, Pattern(t, MatchKind.KEYWORD))

    @Test
    fun `empty rule set never warns`() {
        assertFalse(DraftGuard.check("anything at all", emptyList()).warn)
    }

    @Test
    fun `keyword match is case-insensitive and reports every span`() {
        val r = DraftGuard.check("Ashford then ashford", listOf(kw("Protected", "ASHFORD")))
        assertEquals(listOf(0..6, 13..19), r.hits.single().spans)
        assertEquals(listOf("Ashford", "ashford"), r.hits.single().matched)
    }

    @Test
    fun `regex rule matches`() {
        val rule = GuardRule("Email", Pattern("[\\w.]+@[\\w.]+", MatchKind.REGEX))
        assertEquals(listOf("sam@example.invalid"), DraftGuard.check("mail sam@example.invalid", listOf(rule)).hits.single().matched)
    }

    @Test
    fun `pair rule needs both sides`() {
        val rule = GuardRule("Price + term", Pattern("\\$\\d+", MatchKind.REGEX), Pattern("MERIDIAN", MatchKind.KEYWORD))
        assertFalse(DraftGuard.check("it is \$40", listOf(rule)).warn)
        assertFalse(DraftGuard.check("about MERIDIAN", listOf(rule)).warn)
        val hit = DraftGuard.check("MERIDIAN is \$40", listOf(rule)).hits.single()
        assertEquals(listOf("MERIDIAN", "\$40"), hit.matched)
    }

    @Test
    fun `invalid regex is reported and skipped, not thrown`() {
        val bad = GuardRule("Broken", Pattern("([unclosed", MatchKind.REGEX))
        val r = DraftGuard.check("text", listOf(bad, kw("Ok", "text")))
        assertEquals(listOf(bad), r.invalid)
        assertEquals("Ok", r.hits.single().rule.name)
    }

    @Test
    fun `disabled rules do not fire`() {
        assertFalse(DraftGuard.check("Ashford", listOf(kw("x", "Ashford").copy(enabled = false))).warn)
    }

    @Test
    fun `text form round trips including pairs, regex and disabled`() {
        val rules = listOf(
            kw("Protected", "Ashford"),
            GuardRule("Email", Pattern("[\\w.]+@[\\w.]+", MatchKind.REGEX)),
            GuardRule("Pair", Pattern("\\$\\d+", MatchKind.REGEX), Pattern("MERIDIAN", MatchKind.KEYWORD), enabled = false),
        )
        assertEquals(rules, GuardText.parse(GuardText.format(rules)))
        assertTrue(GuardText.parse("# comment\nno colon here\n").isEmpty())
    }
}
