package com.sovereignops.sms.core.screening

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreeningTest {
    private fun input(number: Boolean = false, keyword: Boolean = false, unknown: Boolean = false, blockUnknown: Boolean = false) =
        ScreeningInput(number, keyword, unknown, blockUnknown)

    @Test
    fun `clean message is delivered`() {
        assertEquals(Disposition.Deliver, Screening.decide(input(unknown = true), preserveScreened = true))
    }

    @Test
    fun `blocked keyword is kept by default, never discarded`() {
        assertEquals(
            Disposition.Screen(listOf(ScreenReason.BLOCKED_KEYWORD)),
            Screening.decide(input(keyword = true), preserveScreened = true),
        )
    }

    @Test
    fun `unknown sender only screened when blocking unknown is on`() {
        assertEquals(Disposition.Deliver, Screening.decide(input(unknown = true, blockUnknown = false), true))
        assertEquals(
            Disposition.Screen(listOf(ScreenReason.UNKNOWN_SENDER)),
            Screening.decide(input(unknown = true, blockUnknown = true), true),
        )
    }

    @Test
    fun `all reasons are reported`() {
        val d = Screening.decide(input(number = true, keyword = true, unknown = true, blockUnknown = true), true)
        assertEquals(
            Disposition.Screen(listOf(ScreenReason.BLOCKED_NUMBER, ScreenReason.BLOCKED_KEYWORD, ScreenReason.UNKNOWN_SENDER)),
            d,
        )
    }

    @Test
    fun `discard only when the user explicitly turned preservation off`() {
        assertEquals(
            Disposition.Discard(listOf(ScreenReason.BLOCKED_NUMBER)),
            Screening.decide(input(number = true), preserveScreened = false),
        )
    }
}
