package com.sovereignops.sms.core.address

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadTitleTest {
    private val opaqueA = "Xk2pQ9vLmN4rT7wYz1aB"
    private val opaqueB = "3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f"

    @Test fun namesAreKept() {
        assertEquals("Ashford, Blake", ThreadTitle.build(listOf("9195550142" to "Ashford", "9195550143" to "Blake")))
    }

    @Test fun unknownNumbersShowTheNumber() {
        assertEquals("Ashford, 9195550143", ThreadTitle.build(listOf("9195550142" to "Ashford", "9195550143" to "9195550143")))
    }

    @Test fun opaqueParticipantNeverAppearsVerbatim() {
        assertEquals("Ashford, 1 unrecognized address", ThreadTitle.build(listOf("9195550142" to "Ashford", opaqueA to opaqueA)))
        assertEquals(
            "Ashford, 2 unrecognized addresses",
            ThreadTitle.build(listOf("9195550142" to "Ashford", opaqueA to opaqueA, opaqueB to opaqueB)),
        )
    }

    @Test fun allOpaque() {
        assertEquals(ThreadTitle.UNSUPPORTED_ONE, ThreadTitle.build(listOf(opaqueA to opaqueA)))
        assertEquals("Unsupported group (2 unrecognized addresses)", ThreadTitle.build(listOf(opaqueA to opaqueA, opaqueB to opaqueB)))
    }

    @Test fun missingCanonicalAddressDoesNotLeaveABlankName() {
        assertEquals("Ashford, 1 unrecognized address", ThreadTitle.build(listOf("9195550142" to "Ashford", "" to "")))
    }

    @Test fun blankNameFallsBackToTheAddress() {
        assertEquals("9195550142", ThreadTitle.build(listOf("9195550142" to "")))
    }

    @Test fun participantLabel() {
        assertEquals("Ashford", ThreadTitle.participantName("9195550142", "Ashford"))
        assertEquals(ThreadTitle.UNRECOGNIZED, ThreadTitle.participantName(opaqueA, opaqueA))
    }

    @Test fun empty() {
        assertEquals("", ThreadTitle.build(emptyList()))
    }
}
