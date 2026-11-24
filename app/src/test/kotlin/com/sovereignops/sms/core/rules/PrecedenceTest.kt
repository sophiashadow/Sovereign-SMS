package com.sovereignops.sms.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

// Synthetic numbers only (555-01xx).
class PrecedenceTest {
    private val wed = LocalDateTime.of(2026, 11, 25, 22, 0) // Wednesday 22:00
    private val allDay = TimeWindow(DayOfWeek.entries.toSet(), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)

    private fun msg(
        body: String = "hello",
        category: Category = Category.PROFESSIONAL,
        policy: ConversationPolicy = ConversationPolicy.DEFAULT,
        mode: WorkspaceMode = WorkspaceMode.ALL,
        known: Boolean = true,
    ) = IncomingMessage("+19195550142", known, body, policy, wed, null, category, mode)

    private val busy = RuleConfig(
        quietHours = listOf(allDay),
        quietHoursAction = Alert.SILENT,
        focusUntil = wed.plusHours(1),
        focusAction = Alert.HELD,
        unknownSenderAction = Alert.HELD,
        keywordRules = listOf(KeywordRule("k1", "urgent", null, KeywordEffect.PRIORITY), KeywordRule("k2", "promo", null, KeywordEffect.SILENT)),
        schedules = listOf(ScheduleRule("Nights", allDay, setOf(Category.PROFESSIONAL, Category.UNKNOWN), Alert.HELD)),
    )

    @Test
    fun `OTP beats a muted thread and focus`() {
        val d = RuleEngine.evaluate(busy, msg("Your code is 482913", policy = ConversationPolicy.MUTED))
        assertEquals(RuleId.ONE_TIME_CODE, d.decidedBy)
        assertEquals(Alert.ALERT, d.alert)
        assertTrue(RuleId.CONVERSATION_MUTED in d.alsoMatched)
    }

    @Test
    fun `break-through keyword is tier 1`() {
        assertEquals(RuleId.KEYWORD_PRIORITY, RuleEngine.evaluate(busy, msg("urgent: running late", policy = ConversationPolicy.MUTED)).decidedBy)
    }

    @Test
    fun `priority beats thread mute`() {
        val c = busy.copy(priorityAddresses = setOf("919-555-0142"))
        assertEquals(RuleId.PRIORITY_CONTACT, RuleEngine.evaluate(c, msg(policy = ConversationPolicy.MUTED)).decidedBy)
    }

    @Test
    fun `personal rings through focus and quiet only when quiet hours exclude personal`() {
        val scoped = busy.copy(quietHoursCategories = setOf(Category.PROFESSIONAL, Category.UNKNOWN))
        assertEquals(RuleId.PERSONAL, RuleEngine.evaluate(scoped, msg(category = Category.PERSONAL)).decidedBy)
        assertEquals(RuleId.FOCUS, RuleEngine.evaluate(busy, msg(category = Category.PERSONAL)).decidedBy)
        // A muted personal thread stays muted.
        assertEquals(RuleId.CONVERSATION_MUTED, RuleEngine.evaluate(scoped, msg(category = Category.PERSONAL, policy = ConversationPolicy.MUTED)).decidedBy)
    }

    @Test
    fun `thread mute beats focus, focus beats schedules, schedules beat unknown and keywords`() {
        assertEquals(RuleId.CONVERSATION_MUTED, RuleEngine.evaluate(busy, msg(policy = ConversationPolicy.MUTED)).decidedBy)
        assertEquals(RuleId.FOCUS, RuleEngine.evaluate(busy, msg()).decidedBy)
        val noFocus = busy.copy(focusUntil = null)
        val d = RuleEngine.evaluate(noFocus, msg("promo", known = false))
        assertEquals(RuleId.QUIET_HOURS, d.decidedBy)
        assertEquals(listOf(RuleId.SCHEDULE, RuleId.UNKNOWN_SENDER, RuleId.KEYWORD_SILENT), d.alsoMatched)
        val noQuiet = noFocus.copy(quietHours = emptyList())
        assertEquals(RuleId.SCHEDULE, RuleEngine.evaluate(noQuiet, msg("promo", known = false)).decidedBy)
        assertEquals(RuleId.UNKNOWN_SENDER, RuleEngine.evaluate(noQuiet.copy(schedules = emptyList()), msg("promo", known = false)).decidedBy)
    }

    @Test
    fun `decided-by tier is never worse than any also-matched tier`() {
        val bodies = listOf("hello", "Your code is 482913", "urgent", "promo")
        for (b in bodies) for (p in ConversationPolicy.entries) for (cat in Category.entries) for (m in WorkspaceMode.entries) {
            val d = RuleEngine.evaluate(busy, msg(b, cat, p, m, known = cat != Category.UNKNOWN))
            assertTrue("$b $p $cat $m", d.alsoMatched.all { it.tier >= d.decidedBy.tier })
        }
    }

    @Test
    fun `schedule applies only to its categories, window and when enabled`() {
        val night = TimeWindow(setOf(DayOfWeek.WEDNESDAY), LocalTime.of(21, 0), LocalTime.of(9, 0))
        val c = RuleConfig(schedules = listOf(ScheduleRule("Night", night, setOf(Category.PROFESSIONAL), Alert.HELD)))
        assertEquals(RuleId.SCHEDULE, RuleEngine.evaluate(c, msg()).decidedBy)
        assertEquals(Alert.HELD, RuleEngine.evaluate(c, msg()).alert)
        assertEquals(RuleId.DEFAULT, RuleEngine.evaluate(c, msg(category = Category.PERSONAL)).decidedBy)
        assertEquals(RuleId.DEFAULT, RuleEngine.evaluate(c.copy(schedules = c.schedules.map { it.copy(enabled = false) }), msg()).decidedBy)
        val thursMorning = IncomingMessage("+19195550142", true, "hi", ConversationPolicy.DEFAULT, LocalDateTime.of(2026, 11, 26, 8, 0), null, Category.PROFESSIONAL)
        assertEquals(RuleId.SCHEDULE, RuleEngine.evaluate(c, thursMorning).decidedBy)
    }
}

class ScheduleTextTest {
    private val text = """
        After hours: weekdays 21:00-09:00 professional,unknown held
        Weekend: weekends 00:00-00:00 professional silent
        #off Lunch: mon,wed-fri 12:00-13:00 all silent
        # comment
        Broken: someday 25:00-09:00 professional held
    """.trimIndent()

    @Test
    fun `parses days, ranges, categories and actions and round-trips`() {
        val r = ScheduleText.parse(text)
        assertEquals(3, r.size)
        assertEquals(5, r[0].window.days.size)
        assertEquals(setOf(Category.PROFESSIONAL, Category.UNKNOWN), r[0].categories)
        assertEquals(Alert.HELD, r[0].action)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), r[2].window.days)
        assertEquals(Category.entries.toSet(), r[2].categories)
        assertEquals(false, r[2].enabled)
        assertEquals(r, ScheduleText.parse(ScheduleText.format(r)))
    }

    @Test
    fun `invalid lines are reported, not dropped silently`() {
        assertEquals(listOf("Broken: someday 25:00-09:00 professional held"), ScheduleText.invalid(text))
    }

    @Test
    fun `schedules survive the JSON codec`() {
        val c = RuleConfig(schedules = ScheduleText.parse(text))
        assertEquals(c, RuleConfigCodec.decode(RuleConfigCodec.encode(c)).getOrThrow())
    }

    @Test
    fun `shared-SIM preset schedules are generic and disabled`() {
        val s = com.sovereignops.sms.core.pack.Presets.sharedSimSchedules
        assertEquals(3, s.size)
        assertTrue(s.none { it.enabled })
    }
}
