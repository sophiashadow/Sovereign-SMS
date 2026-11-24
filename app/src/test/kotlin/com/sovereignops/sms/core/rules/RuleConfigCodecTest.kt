package com.sovereignops.sms.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

class RuleConfigCodecTest {

    private val full = RuleConfig(
        enabled = true,
        quietHours = listOf(TimeWindow(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY), LocalTime.of(22, 30), LocalTime.of(6, 15))),
        quietHoursAction = Alert.HELD,
        unknownSenderAction = Alert.SILENT,
        priorityAddresses = setOf("+19195550142"),
        keywordRules = listOf(KeywordRule("k1", "invoice", "billing", KeywordEffect.PRIORITY, enabled = false)),
        cooldownMinutes = 10,
        focusUntil = LocalDateTime.of(2026, 11, 25, 18, 0),
        focusAction = Alert.SILENT,
        alertOneTimeCodes = false,
    )

    @Test
    fun `round trip preserves every field`() {
        assertEquals(full, RuleConfigCodec.decode(RuleConfigCodec.encode(full)).getOrThrow())
        assertEquals(RuleConfig(), RuleConfigCodec.decode(RuleConfigCodec.encode(RuleConfig())).getOrThrow())
    }

    @Test
    fun `corrupt or foreign input fails without throwing`() {
        assertTrue(RuleConfigCodec.decode("not json").isFailure)
        assertTrue(RuleConfigCodec.decode("""{"format":"drafts/backup","version":1}""").isFailure)
        assertTrue(RuleConfigCodec.decode("""{"format":"sovereign-sms/rules","version":99}""").isFailure)
        assertTrue(RuleConfigCodec.decode("""{"format":"sovereign-sms/rules","quietHoursAction":"EXPLODE"}""").isFailure)
        assertTrue(
            RuleConfigCodec.decode("""{"format":"sovereign-sms/rules","quietHours":[{"days":[1],"startMinute":1500,"endMinute":0}]}""").isFailure,
        )
    }

    @Test
    fun `unknown keys from a newer minor writer are ignored`() {
        val decoded = RuleConfigCodec.decode("""{"format":"sovereign-sms/rules","version":1,"futureField":true,"cooldownMinutes":3}""")
        assertEquals(3, decoded.getOrThrow().cooldownMinutes)
    }
}
