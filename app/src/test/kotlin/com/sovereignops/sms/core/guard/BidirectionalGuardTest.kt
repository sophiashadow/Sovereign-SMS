package com.sovereignops.sms.core.guard

import com.sovereignops.sms.core.crm.Stage
import com.sovereignops.sms.core.rules.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BidirectionalGuardTest {
    private fun kw(t: String) = Pattern(t, MatchKind.KEYWORD)

    @Test
    fun `types are derived from fields`() {
        assertEquals(GuardType.FORBIDDEN_TERM, GuardRule("a", kw("x")).type)
        assertEquals(GuardType.PAIR_MATCH, GuardRule("a", kw("x"), kw("y")).type)
        assertEquals(GuardType.CONTEXT_GUARD, GuardRule("a", kw("x"), stages = setOf(Stage.NEW)).type)
    }

    @Test
    fun `pair with window finds A in recent history and B in the draft`() {
        val rule = GuardRule("Pair", kw("MERIDIAN"), kw("tonight"), window = 2)
        val ctx = GuardContext(history = listOf("old MERIDIAN", "hi", "ok"))
        // MERIDIAN is 3 messages back: outside the window of 2.
        assertFalse(DraftGuard.check("see you tonight", listOf(rule), ctx).warn)
        val near = GuardContext(history = listOf("hi", "about MERIDIAN", "ok"))
        val hit = DraftGuard.check("see you tonight", listOf(rule), near).hits.single()
        assertEquals(listOf("tonight"), hit.matched)
        assertEquals(listOf("MERIDIAN"), hit.earlier)
    }

    @Test
    fun `pair without window ignores history`() {
        val rule = GuardRule("Pair", kw("MERIDIAN"), kw("tonight"))
        assertFalse(DraftGuard.check("tonight", listOf(rule), GuardContext(listOf("MERIDIAN"))).warn)
        assertTrue(DraftGuard.check("MERIDIAN tonight", listOf(rule)).warn)
    }

    @Test
    fun `context guard fires only for the listed stages`() {
        val rule = GuardRule("Ctx", kw("Ashford"), stages = setOf(Stage.NEW, Stage.VERIFICATION_PENDING))
        assertTrue(DraftGuard.check("meet at Ashford", listOf(rule), GuardContext(stage = Stage.NEW)).warn)
        assertFalse(DraftGuard.check("meet at Ashford", listOf(rule), GuardContext(stage = Stage.REGULAR)).warn)
        assertFalse(DraftGuard.check("meet at Ashford", listOf(rule), GuardContext(stage = null)).warn)
    }

    @Test
    fun `highest severity and badges`() {
        val r = DraftGuard.check(
            "link and code",
            listOf(GuardRule("i", kw("link"), severity = Severity.INFO), GuardRule("h", kw("code"), severity = Severity.HIGH_RISK)),
        )
        assertEquals(Severity.HIGH_RISK, r.highest)
        assertTrue(DraftGuard.summary(r).contains("⛔ h"))
        assertNull(DraftGuard.check("", emptyList()).highest)
    }

    @Test
    fun `options round trip through the text form`() {
        val rules = listOf(
            GuardRule("Pair", kw("MERIDIAN"), Pattern("\\d+", MatchKind.REGEX), window = 5),
            GuardRule("Ctx", kw("Ashford"), stages = setOf(Stage.NEW, Stage.VERIFICATION_PENDING), enabled = false),
            GuardRule("Flag", Pattern("https?://\\S+", MatchKind.REGEX), severity = Severity.HIGH_RISK, silent = true, tag = "links", category = Category.PROFESSIONAL),
            GuardRule("Plain", kw("x"), severity = Severity.INFO),
        )
        val text = GuardText.format(rules)
        assertTrue(text, text.contains("Pair: MERIDIAN && /\\d+/ | window=5"))
        assertEquals(rules, GuardText.parse(text))
    }

    @Test
    fun `old lines without options still parse`() {
        assertEquals(listOf(GuardRule("A", kw("x"), kw("y"))), GuardText.parse("A: x && y"))
    }

    @Test
    fun `a bar followed by non-options stays part of the pattern`() {
        val r = GuardText.parse("R: /a | b/").single()
        assertEquals("a | b", r.pattern.text)
        assertEquals(Severity.CAUTION, r.severity)
        val k = GuardText.parse("K: this | that").single()
        assertEquals("this | that", k.pattern.text)
    }

    @Test
    fun `unknown stage makes the options invalid, not the rule silently wider`() {
        val r = GuardText.parse("C: Ashford | stages=nope").single()
        assertTrue(r.stages.isEmpty())
        assertEquals("Ashford | stages=nope", r.pattern.text)
    }
}
