package com.sovereignops.sms.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

// All numbers are synthetic (555-01xx range); no real correspondence.
class RuleEngineTest {

    private val known = "+19195550142"
    private val stranger = "+19195550199"

    // Wednesday 2026-11-25
    private fun at(hour: Int, minute: Int = 0, day: Int = 25) = LocalDateTime.of(2026, 11, day, hour, minute)

    private fun msg(
        address: String = known,
        body: String = "hello",
        knownContact: Boolean = true,
        policy: ConversationPolicy = ConversationPolicy.DEFAULT,
        time: LocalDateTime = at(12),
        lastAlertAt: LocalDateTime? = null,
    ) = IncomingMessage(address, knownContact, body, policy, time, lastAlertAt)

    private val weeknights = TimeWindow(
        days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        start = LocalTime.of(23, 0),
        end = LocalTime.of(7, 0),
    )

    @Test
    fun `empty config alerts like upstream`() {
        val d = RuleEngine.evaluate(RuleConfig(), msg())
        assertEquals(Alert.ALERT, d.alert)
        assertEquals(RuleId.DEFAULT, d.decidedBy)
    }

    @Test
    fun `rules switched off always alert even when muted`() {
        val d = RuleEngine.evaluate(RuleConfig(enabled = false), msg(policy = ConversationPolicy.MUTED))
        assertEquals(Alert.ALERT, d.alert)
        assertEquals(RuleId.RULES_OFF, d.decidedBy)
    }

    @Test
    fun `overnight quiet hours cover both sides of midnight`() {
        val cfg = RuleConfig(quietHours = listOf(weeknights))
        assertEquals(Alert.SILENT, RuleEngine.evaluate(cfg, msg(time = at(23, 30))).alert)
        // Thursday 03:00 belongs to Wednesday's window
        assertEquals(Alert.SILENT, RuleEngine.evaluate(cfg, msg(time = at(3, 0, day = 26))).alert)
        assertEquals(Alert.ALERT, RuleEngine.evaluate(cfg, msg(time = at(7, 0, day = 26))).alert)
        assertEquals(Alert.ALERT, RuleEngine.evaluate(cfg, msg(time = at(22, 59))).alert)
    }

    @Test
    fun `overnight window that starts friday does not leak into sunday morning`() {
        val fridayOnly = TimeWindow(setOf(DayOfWeek.FRIDAY), LocalTime.of(22, 0), LocalTime.of(6, 0))
        val cfg = RuleConfig(quietHours = listOf(fridayOnly))
        // Saturday 2026-11-28 02:00 -> inside (started Friday)
        assertEquals(Alert.SILENT, RuleEngine.evaluate(cfg, msg(time = at(2, 0, day = 28))).alert)
        // Sunday 2026-11-29 02:00 -> outside
        assertEquals(Alert.ALERT, RuleEngine.evaluate(cfg, msg(time = at(2, 0, day = 29))).alert)
    }

    @Test
    fun `quiet hours can hold into a summary instead of silent per-message`() {
        val cfg = RuleConfig(quietHours = listOf(weeknights), quietHoursAction = Alert.HELD)
        val d = RuleEngine.evaluate(cfg, msg(time = at(23, 30)))
        assertEquals(Alert.HELD, d.alert)
        assertEquals(RuleId.QUIET_HOURS, d.decidedBy)
    }

    @Test
    fun `priority contact breaks through quiet hours focus and cooldown`() {
        val cfg = RuleConfig(
            quietHours = listOf(weeknights),
            focusUntil = at(23, 59),
            cooldownMinutes = 30,
            priorityAddresses = setOf("(919) 555-0142"),
        )
        val d = RuleEngine.evaluate(cfg, msg(time = at(23, 30), lastAlertAt = at(23, 29)))
        assertEquals(Alert.ALERT, d.alert)
        assertEquals(RuleId.PRIORITY_CONTACT, d.decidedBy)
        assertTrue(RuleId.QUIET_HOURS in d.alsoMatched)
        assertTrue(RuleId.FOCUS in d.alsoMatched)
        assertTrue(RuleId.COOLDOWN in d.alsoMatched)
    }

    @Test
    fun `conversation priority flag also breaks through`() {
        val cfg = RuleConfig(focusUntil = at(23))
        val d = RuleEngine.evaluate(cfg, msg(policy = ConversationPolicy.PRIORITY))
        assertEquals(Alert.ALERT, d.alert)
    }

    @Test
    fun `muted conversation is silent but still stored`() {
        val d = RuleEngine.evaluate(RuleConfig(), msg(policy = ConversationPolicy.MUTED))
        assertEquals(Alert.SILENT, d.alert)
        assertEquals(RuleId.CONVERSATION_MUTED, d.decidedBy)
    }

    @Test
    fun `one-time codes alert during focus by default`() {
        val cfg = RuleConfig(focusUntil = at(18))
        val d = RuleEngine.evaluate(cfg, msg(address = "72345", knownContact = false, body = "Your verification code is 482913"))
        assertEquals(Alert.ALERT, d.alert)
        assertEquals(RuleId.ONE_TIME_CODE, d.decidedBy)
    }

    @Test
    fun `one-time code bypass can be turned off`() {
        val cfg = RuleConfig(focusUntil = at(18), alertOneTimeCodes = false)
        val d = RuleEngine.evaluate(cfg, msg(body = "Your login code: 4829"))
        assertEquals(Alert.HELD, d.alert)
    }

    @Test
    fun `focus mode expires on its own`() {
        val cfg = RuleConfig(focusUntil = at(11))
        assertEquals(Alert.ALERT, RuleEngine.evaluate(cfg, msg(time = at(12))).alert)
        assertEquals(Alert.HELD, RuleEngine.evaluate(cfg, msg(time = at(10))).alert)
    }

    @Test
    fun `unknown sender policy applies only to non-contacts`() {
        val cfg = RuleConfig(unknownSenderAction = Alert.SILENT)
        assertEquals(Alert.SILENT, RuleEngine.evaluate(cfg, msg(address = stranger, knownContact = false)).alert)
        assertEquals(Alert.ALERT, RuleEngine.evaluate(cfg, msg()).alert)
    }

    @Test
    fun `keyword rules tag case-insensitively and explain themselves`() {
        val cfg = RuleConfig(
            keywordRules = listOf(
                KeywordRule(id = "k1", keyword = "Invoice", tag = "billing", effect = KeywordEffect.NONE),
                KeywordRule(id = "k2", keyword = "unused", tag = "never", effect = KeywordEffect.NONE),
            ),
        )
        val d = RuleEngine.evaluate(cfg, msg(body = "your INVOICE is attached"))
        assertEquals(setOf("billing"), d.tags)
        assertEquals(Alert.ALERT, d.alert)
        assertTrue(d.explanation.any { it.contains("Invoice") })
    }

    @Test
    fun `priority keyword breaks through quiet hours, silent keyword quiets`() {
        val cfg = RuleConfig(
            quietHours = listOf(weeknights),
            keywordRules = listOf(
                KeywordRule("u", "urgent", null, KeywordEffect.PRIORITY),
                KeywordRule("p", "promo", null, KeywordEffect.SILENT),
            ),
        )
        assertEquals(Alert.ALERT, RuleEngine.evaluate(cfg, msg(body = "URGENT call me", time = at(23, 30))).alert)
        val promo = RuleEngine.evaluate(cfg, msg(body = "promo today"))
        assertEquals(Alert.SILENT, promo.alert)
        assertEquals(RuleId.KEYWORD_SILENT, promo.decidedBy)
    }

    @Test
    fun `disabled keyword rules do nothing`() {
        val cfg = RuleConfig(keywordRules = listOf(KeywordRule("p", "promo", "ads", KeywordEffect.SILENT, enabled = false)))
        val d = RuleEngine.evaluate(cfg, msg(body = "promo"))
        assertEquals(Alert.ALERT, d.alert)
        assertTrue(d.tags.isEmpty())
    }

    @Test
    fun `blank keyword never matches everything`() {
        val cfg = RuleConfig(keywordRules = listOf(KeywordRule("b", "  ", "all", KeywordEffect.SILENT)))
        assertEquals(Alert.ALERT, RuleEngine.evaluate(cfg, msg()).alert)
    }

    @Test
    fun `cooldown silences repeat alerts in the same conversation`() {
        val cfg = RuleConfig(cooldownMinutes = 5)
        assertEquals(Alert.SILENT, RuleEngine.evaluate(cfg, msg(time = at(12, 4), lastAlertAt = at(12, 0))).alert)
        assertEquals(Alert.ALERT, RuleEngine.evaluate(cfg, msg(time = at(12, 5), lastAlertAt = at(12, 0))).alert)
        assertEquals(Alert.ALERT, RuleEngine.evaluate(cfg, msg(time = at(12, 1), lastAlertAt = null)).alert)
    }

    @Test
    fun `decision always carries a human explanation`() {
        val cfg = RuleConfig(quietHours = listOf(weeknights))
        val d = RuleEngine.evaluate(cfg, msg(time = at(23, 30)))
        assertFalse(d.explanation.isEmpty())
        assertTrue(d.explanation.first().contains("Quiet hours"))
    }

    @Test
    fun `address matching ignores formatting and country code`() {
        assertTrue(Addresses.sameNumber("+1 (919) 555-0142", "9195550142"))
        assertTrue(Addresses.sameNumber("919.555.0142", "+19195550142"))
        assertFalse(Addresses.sameNumber("9195550142", "9195550143"))
        assertTrue(Addresses.sameNumber("72345", "72345"))
        assertFalse(Addresses.sameNumber("72345", "172345"))
        assertFalse(Addresses.sameNumber("", ""))
    }

    @Test
    fun `one-time code detector`() {
        assertTrue(OneTimeCodes.looksLikeCode("Your verification code is 482913"))
        assertTrue(OneTimeCodes.looksLikeCode("G-482913 is your sign-in code"))
        assertTrue(OneTimeCodes.looksLikeCode("Use 4829 as your OTP"))
        assertFalse(OneTimeCodes.looksLikeCode("See you at 1130"))
        assertFalse(OneTimeCodes.looksLikeCode("code review tomorrow"))
    }
}

class FlagRulesTest {
    private val base = IncomingMessage("+19195550142", false, "hi", ConversationPolicy.DEFAULT, java.time.LocalDateTime.of(2026, 1, 5, 12, 0), null)

    @Test
    fun `silent flag silences and tags`() {
        val d = RuleEngine.evaluate(RuleConfig(), base.copy(flags = listOf(FlagSignal("Link", silent = true, tag = "link"))))
        org.junit.Assert.assertEquals(Alert.SILENT, d.alert)
        org.junit.Assert.assertEquals(RuleId.FLAG_SILENT, d.decidedBy)
        org.junit.Assert.assertEquals(setOf("link"), d.tags)
    }

    @Test
    fun `badge-only flag does not change the alert`() {
        val d = RuleEngine.evaluate(RuleConfig(), base.copy(flags = listOf(FlagSignal("Link", silent = false, tag = null))))
        org.junit.Assert.assertEquals(Alert.ALERT, d.alert)
        org.junit.Assert.assertTrue(d.explanation.any { it.contains("Flag “Link”") })
    }

    @Test
    fun `priority conversation beats a silent flag`() {
        val d = RuleEngine.evaluate(RuleConfig(), base.copy(conversationPolicy = ConversationPolicy.PRIORITY, flags = listOf(FlagSignal("x", true, null))))
        org.junit.Assert.assertEquals(Alert.ALERT, d.alert)
    }
}

class QuarantineCoverTest {
    private val t = java.time.LocalDateTime.of(2026, 1, 5, 12, 0)
    private fun msg(body: String = "hi", category: Category = Category.PROFESSIONAL, policy: ConversationPolicy = ConversationPolicy.DEFAULT) =
        IncomingMessage("+19195550142", true, body, policy, t, null, category = category)

    @Test
    fun `quarantine mutes even a priority conversation and with rules off`() {
        val d = RuleEngine.evaluate(RuleConfig(enabled = false), msg(policy = ConversationPolicy.PRIORITY).copy(quarantined = true))
        org.junit.Assert.assertEquals(Alert.SILENT, d.alert)
        org.junit.Assert.assertEquals(RuleId.QUARANTINED, d.decidedBy)
    }

    @Test
    fun `cover holds professional, lets personal and codes through`() {
        org.junit.Assert.assertEquals(RuleId.COVER, RuleEngine.evaluate(RuleConfig(), msg(policy = ConversationPolicy.PRIORITY).copy(cover = true)).decidedBy)
        org.junit.Assert.assertEquals(Alert.HELD, RuleEngine.evaluate(RuleConfig(), msg(category = Category.UNKNOWN).copy(cover = true)).alert)
        org.junit.Assert.assertEquals(Alert.ALERT, RuleEngine.evaluate(RuleConfig(), msg(category = Category.PERSONAL).copy(cover = true)).alert)
        org.junit.Assert.assertEquals(RuleId.ONE_TIME_CODE, RuleEngine.evaluate(RuleConfig(), msg("Your login code is 482913").copy(cover = true)).decidedBy)
    }

    @Test
    fun `extracts the code nearest the keyword`() {
        org.junit.Assert.assertEquals("482913", OneTimeCodes.extract("Order 1234 shipped. Your verification code: 482913"))
        org.junit.Assert.assertEquals("5521", OneTimeCodes.extract("5521 is your sign-in PIN"))
        org.junit.Assert.assertNull(OneTimeCodes.extract("See you at 1930"))
    }
}
