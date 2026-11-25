package com.sovereignops.sms.core.address

import com.sovereignops.sms.core.address.AddressKind.*
import com.sovereignops.sms.core.rules.Addresses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic identifiers only: 555 numbers, example.invalid, made-up IDs. */
class AddressClassifierTest {

    private fun kind(s: String) = AddressClassifier.classify(s)

    @Test fun phoneNumbersInCommonFormats() {
        for (s in listOf("9195550142", "+19195550142", "+1 (919) 555-0142", "919.555.0142", "555-0142", "+44 20 7946 0958")) {
            assertEquals(s, PHONE, kind(s))
        }
    }

    @Test fun shortCodes() {
        assertEquals(SHORT_CODE, kind("72345"))
        assertEquals(SHORT_CODE, kind("503501"))
        assertEquals(SHORT_CODE, kind("611"))
        assertEquals(SHORT_CODE, kind("*611"))
    }

    @Test fun emailAddresses() {
        assertEquals(EMAIL, kind("ashford@example.invalid"))
        assertEquals(EMAIL, kind("m.e+tag@mail.example.invalid"))
    }

    @Test fun alphanumericSenderIds() {
        assertEquals(ALPHA_SENDER, kind("MERIDIAN"))
        assertEquals(ALPHA_SENDER, kind("AB-CD0"))
    }

    @Test fun opaqueIdentifiersAreNotPhoneNumbers() {
        assertEquals(OPAQUE, kind("3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f")) // UUID shape
        assertEquals(OPAQUE, kind("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3")) // 40 hex
        assertEquals(OPAQUE, kind("Xk2pQ9vLmN4rT7wYz1aB")) // 20 mixed alphanumerics
        assertEquals(OPAQUE, kind("919555014291955501429195")) // 24 digits: beyond E.164
        assertEquals(OPAQUE, kind("insert-address-token")) // MMS placeholder
        assertEquals(OPAQUE, kind("12")) // too short to be anything
    }

    @Test fun uris() {
        assertEquals(URI, kind("sip:conf-01@example.invalid"))
        assertEquals(URI, kind("tel:+19195550142"))
    }

    @Test fun emptyAndBlank() {
        assertEquals(EMPTY, kind(""))
        assertEquals(EMPTY, kind("   "))
    }

    @Test fun onlyPhoneShortCodeAndEmailAreSendable() {
        assertTrue(AddressClassifier.isSendable("+1 919 555 0142"))
        assertTrue(AddressClassifier.isSendable("72345"))
        assertTrue(AddressClassifier.isSendable("ashford@example.invalid"))
        assertFalse(AddressClassifier.isSendable("MERIDIAN"))
        assertFalse(AddressClassifier.isSendable("3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f"))
        assertFalse(AddressClassifier.isSendable("919555014291955501429195"))
        assertFalse(AddressClassifier.isSendable("sip:conf-01@example.invalid"))
        assertFalse(AddressClassifier.isSendable(""))
    }

    @Test fun allSendableNeedsEveryAddress() {
        assertTrue(AddressClassifier.allSendable(listOf("9195550142", "9195550143")))
        assertFalse(AddressClassifier.allSendable(listOf("9195550142", "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3")))
        assertFalse(AddressClassifier.allSendable(listOf("9195550142", "")))
    }

    @Test fun displayableKeepsRealAddressesAndHidesOpaqueOnes() {
        assertTrue(AddressClassifier.isDisplayable("9195550142"))
        assertTrue(AddressClassifier.isDisplayable("MERIDIAN"))
        assertTrue(AddressClassifier.isDisplayable("ashford@example.invalid"))
        assertFalse(AddressClassifier.isDisplayable("Xk2pQ9vLmN4rT7wYz1aB"))
        assertFalse(AddressClassifier.isDisplayable(""))
    }

    @Test fun shapeNeverContainsTheAddress() {
        val inputs = listOf(
            "3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f", "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3",
            "Xk2pQ9vLmN4rT7wYz1aB", "919555014291955501429195", "sip:conf-01@example.invalid",
            "+19195550142", "ashford@example.invalid", "MERIDIAN", "",
        )
        for (s in inputs) {
            val shape = AddressClassifier.shape(s)
            // The URI scheme ("sip:") is shown on purpose; the identifying part never is.
            if (s.length >= 8) assertFalse("$s leaked into '$shape'", shape.contains(s.substring(4, 8)))
            assertFalse(shape.any(Char::isDigit) && shape.contains(Regex("\\d{4,}")))
        }
        assertEquals("UUID-shaped, 36 chars", AddressClassifier.shape("3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f"))
        assertEquals("hex, 40 chars", AddressClassifier.shape("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3"))
        assertEquals("letters and digits, 20 chars", AddressClassifier.shape("Xk2pQ9vLmN4rT7wYz1aB"))
        assertEquals("digits only, 24 chars", AddressClassifier.shape("919555014291955501429195"))
        assertEquals("sip: URI", AddressClassifier.shape("sip:conf-01@example.invalid"))
        assertEquals("phone number", AddressClassifier.shape("+19195550142"))
        assertEquals("empty", AddressClassifier.shape(""))
    }

    // Regression: the digits inside an opaque ID must not match a real number in rules.
    @Test fun sameNumberIgnoresDigitsInsideOpaqueIds() {
        assertFalse(Addresses.sameNumber("rcs9195550142x", "9195550142"))
        assertFalse(Addresses.sameNumber("3f9195550142aa0c-0000", "+1 919 555 0142"))
        assertTrue(Addresses.sameNumber("+1 (919) 555-0142", "9195550142"))
    }
}
