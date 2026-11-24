package com.sovereignops.sms.core.rules

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

// Synthetic numbers only.
class WorkspaceRulesTest {
    private val night = LocalDateTime.of(2026, 11, 25, 23, 30) // Wednesday
    private val quiet = RuleConfig(
        quietHours = listOf(TimeWindow(DayOfWeek.entries.toSet(), LocalTime.of(22, 0), LocalTime.of(7, 0))),
        quietHoursCategories = setOf(Category.PROFESSIONAL, Category.UNKNOWN),
    )

    private fun msg(category: Category, mode: WorkspaceMode = WorkspaceMode.ALL, body: String = "hi", policy: ConversationPolicy = ConversationPolicy.DEFAULT) =
        IncomingMessage("+19195550142", category == Category.PERSONAL, body, policy, night, null, category, mode)

    @Test
    fun `personal contacts bypass professional quiet hours`() {
        assertEquals(Alert.ALERT, RuleEngine.evaluate(quiet, msg(Category.PERSONAL)).alert)
        val pro = RuleEngine.evaluate(quiet, msg(Category.PROFESSIONAL))
        assertEquals(Alert.SILENT, pro.alert)
        assertEquals(RuleId.QUIET_HOURS, pro.decidedBy)
    }

    @Test
    fun `default quiet hours still cover every category`() {
        val all = quiet.copy(quietHoursCategories = Category.entries.toSet())
        assertEquals(Alert.SILENT, RuleEngine.evaluate(all, msg(Category.PERSONAL)).alert)
    }

    @Test
    fun `personal mode quiets professional and unknown but not personal`() {
        val c = RuleConfig()
        val day = { m: IncomingMessage -> m.copy(receivedAt = LocalDateTime.of(2026, 11, 25, 12, 0)) }
        assertEquals(Alert.ALERT, RuleEngine.evaluate(c, day(msg(Category.PERSONAL, WorkspaceMode.PERSONAL))).alert)
        val pro = RuleEngine.evaluate(c, day(msg(Category.PROFESSIONAL, WorkspaceMode.PERSONAL)))
        assertEquals(RuleId.OFF_DUTY, pro.decidedBy)
        assertEquals(Alert.SILENT, pro.alert)
        assertEquals(RuleId.OFF_DUTY, RuleEngine.evaluate(c, day(msg(Category.UNKNOWN, WorkspaceMode.PERSONAL))).decidedBy)
    }

    @Test
    fun `focus mode holds everything except priority and one-time codes`() {
        val c = RuleConfig()
        assertEquals(Alert.HELD, RuleEngine.evaluate(c, msg(Category.PERSONAL, WorkspaceMode.FOCUS)).alert)
        assertEquals(Alert.ALERT, RuleEngine.evaluate(c, msg(Category.PROFESSIONAL, WorkspaceMode.FOCUS, policy = ConversationPolicy.PRIORITY)).alert)
        assertEquals(Alert.ALERT, RuleEngine.evaluate(c, msg(Category.UNKNOWN, WorkspaceMode.FOCUS, body = "Your verification code is 482913")).alert)
    }

    @Test
    fun `codec round trips workspace fields and reads old files`() {
        val c = quiet.copy(offDutyAction = Alert.HELD)
        assertEquals(c, RuleConfigCodec.decode(RuleConfigCodec.encode(c)).getOrThrow())
        val old = """{"format":"sovereign-sms/rules","version":1}"""
        assertEquals(RuleConfig(), RuleConfigCodec.decode(old).getOrThrow())
    }
}
