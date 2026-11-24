package com.sovereignops.sms.core.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexBudgetTest {
    @Test
    fun catastrophicPatternIsStoppedAndReportedInvalid() {
        // A quantified group with a greedy wildcard backtracks combinatorially on a near-match
        // (about 0.4 s at 24 characters on this JDK, far longer at 40).
        val evil = GuardRule("synthetic evil", Pattern("(.*a){12}x", MatchKind.REGEX))
        val body = "a".repeat(40) + "!"
        val start = System.nanoTime()
        val r = DraftGuard.check(body, listOf(evil))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 2_000)
        assertEquals(listOf(evil), r.invalid)
        assertTrue(r.hits.isEmpty())
        // Not run again: the second check is immediate.
        val again = System.nanoTime()
        assertEquals(listOf(evil), DraftGuard.check(body, listOf(evil)).invalid)
        assertTrue((System.nanoTime() - again) / 1_000_000 < 50)
    }

    @Test
    fun ordinaryPatternsStillMatchAndOthersStillRun() {
        val evil = GuardRule("synthetic evil 2", Pattern("(.*a){13}x", MatchKind.REGEX))
        val ok = GuardRule("rate", Pattern("\\brate\\b", MatchKind.REGEX))
        val r = DraftGuard.check("a".repeat(40) + "! what is your rate", listOf(evil, ok))
        assertEquals(listOf("rate"), r.hits.single().matched)
        assertEquals(listOf(evil), r.invalid)
    }
}
