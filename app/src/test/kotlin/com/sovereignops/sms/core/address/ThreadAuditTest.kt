package com.sovereignops.sms.core.address

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadAuditTest {
    private val a = "+19195550142"
    private val b = "+19195550143"
    private val c = "+19195550144"
    private val self = "+19195550199"
    private val opaque = "3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f"

    private fun t(id: Long, vararg addr: String) = AuditThread(id, addr.toList())

    @Test fun cleanThreadsReportNothing() {
        val r = ThreadAudit.audit(listOf(t(1, a), t(2, a, b)), ownNumbers = emptyList())
        assertEquals(2, r.threads)
        assertEquals(1, r.groups)
        assertTrue(r.unrecognized.isEmpty())
        assertTrue(r.possibleDuplicates.isEmpty())
    }

    @Test fun formatVariantsOfTheSameGroupArePossibleDuplicates() {
        val r = ThreadAudit.audit(listOf(t(10, a, b), t(11, "9195550143", "(919) 555-0142"), t(12, a, c)), emptyList())
        assertEquals(listOf(listOf(10L, 11L)), r.possibleDuplicates)
    }

    @Test fun groupThatDiffersOnlyByOwnNumberIsAPossibleDuplicate() {
        val r = ThreadAudit.audit(listOf(t(20, a, b), t(21, a, b, self)), ownNumbers = listOf("9195550199"))
        assertEquals(listOf(listOf(20L, 21L)), r.possibleDuplicates)
        assertEquals(listOf(21L), r.includesOwnNumber)
    }

    @Test fun withoutOwnNumberTheSelfVariantIsNotMatched() {
        val r = ThreadAudit.audit(listOf(t(20, a, b), t(21, a, b, self)), ownNumbers = emptyList())
        assertTrue(r.possibleDuplicates.isEmpty())
        assertFalse(r.ownNumberKnown)
    }

    @Test fun unrecognizedAddressesAreCountedByShape() {
        val r = ThreadAudit.audit(listOf(t(30, a, opaque), t(31, opaque), t(32, "")), emptyList())
        assertEquals(listOf(30L, 31L, 32L), r.unrecognized.map { it.threadId })
        assertEquals(mapOf("UUID-shaped, 36 chars" to 2, "empty" to 1), r.shapes)
    }

    @Test fun opaqueThreadsAreNotMergedIntoDuplicatesByTheirDigits() {
        val r = ThreadAudit.audit(listOf(t(40, "rcs9195550142x", b), t(41, a, b)), emptyList())
        assertTrue(r.possibleDuplicates.isEmpty())
    }

    @Test fun reportHoldsNoAddresses() {
        val r = ThreadAudit.audit(listOf(t(20, a, b), t(21, a, b, self), t(30, a, opaque)), listOf(self))
        val text = ThreadAudit.render(r)
        for (s in listOf("555", "0142", "3f2a", self)) assertFalse("leaked $s", text.contains(s))
        assertTrue(text.contains("#21"))
        assertTrue(text.contains("turn off RCS chats"))
    }
}
