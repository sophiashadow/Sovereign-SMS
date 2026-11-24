package com.sovereignops.sms.core.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPolicyTest {
    @Test
    fun `timeouts map onto the upstream lock`() {
        assertEquals(0L, LockTimeout.IMMEDIATE.upstreamTimeoutMs)
        assertEquals(60_000L, LockTimeout.ONE_MINUTE.upstreamTimeoutMs)
        assertEquals(300_000L, LockTimeout.FIVE_MINUTES.upstreamTimeoutMs)
        assertEquals(LockTimeout.SCREEN_OFF_HOLD_MS, LockTimeout.SCREEN_OFF.upstreamTimeoutMs)
    }

    @Test
    fun `screen off locks immediate and screen-off modes only`() {
        assertTrue(LockTimeout.IMMEDIATE.locksOnScreenOff)
        assertTrue(LockTimeout.SCREEN_OFF.locksOnScreenOff)
        assertFalse(LockTimeout.ONE_MINUTE.locksOnScreenOff)
        assertFalse(LockTimeout.FIVE_MINUTES.locksOnScreenOff)
    }

    @Test
    fun `unknown stored value falls back to immediate`() {
        assertEquals(LockTimeout.IMMEDIATE, LockTimeout.parse("nonsense"))
        assertEquals(LockTimeout.IMMEDIATE, LockTimeout.parse(null))
        assertEquals(LockTimeout.SCREEN_OFF, LockTimeout.parse("SCREEN_OFF"))
    }
}
