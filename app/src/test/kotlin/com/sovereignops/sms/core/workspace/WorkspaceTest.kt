package com.sovereignops.sms.core.workspace

import com.sovereignops.sms.core.rules.Alert
import com.sovereignops.sms.core.rules.Category
import com.sovereignops.sms.core.rules.ConversationPolicy
import com.sovereignops.sms.core.rules.Decision
import com.sovereignops.sms.core.rules.RuleId
import com.sovereignops.sms.core.rules.WorkspaceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTest {
    private data class Row(val id: Int, val category: Category, val policy: ConversationPolicy = ConversationPolicy.DEFAULT)

    private val rows = listOf(
        Row(1, Category.PERSONAL),
        Row(2, Category.PROFESSIONAL),
        Row(3, Category.UNKNOWN),
        Row(4, Category.PROFESSIONAL, ConversationPolicy.PRIORITY),
        Row(5, Category.PERSONAL, ConversationPolicy.MUTED),
    )

    private fun ids(mode: WorkspaceMode) = Workspace.filter(rows, mode, { it.category }, { it.policy }).map { it.id }

    @Test
    fun `category filtering per workspace mode`() {
        assertEquals(listOf(1, 2, 3, 4, 5), ids(WorkspaceMode.ALL))
        assertEquals(listOf(1, 5), ids(WorkspaceMode.PERSONAL))
        assertEquals(listOf(2, 3, 4), ids(WorkspaceMode.PROFESSIONAL))
        assertEquals(listOf(4), ids(WorkspaceMode.FOCUS))
    }

    @Test
    fun `assignment wins over contact status`() {
        assertEquals(Category.PROFESSIONAL, Workspace.resolve(Category.PROFESSIONAL, isKnownContact = true))
        assertEquals(Category.PERSONAL, Workspace.resolve(null, isKnownContact = true))
        assertEquals(Category.UNKNOWN, Workspace.resolve(null, isKnownContact = false))
    }

    @Test
    fun `row is known when the title is a contact name, not the number`() {
        val n = "+19195550142"
        assertFalse(Workspace.rowIsKnown("+1 (919) 555-0142", n, isGroup = false, usesCustomTitle = false, hasCard = false))
        assertFalse(Workspace.rowIsKnown("72345", "72345", false, false, false))
        assertFalse(Workspace.rowIsKnown("MERIDIAN", "MERIDIAN", false, false, false)) // alphanumeric sender ID
        // Regression: a fallback title has letters, but an unrecognized address is still unknown.
        assertFalse(Workspace.rowIsKnown("Unsupported conversation", "3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f", false, false, false))
        assertFalse(Workspace.rowIsKnown("Unrecognized address", "", false, false, false))
        assertTrue(Workspace.rowIsKnown("Sam Example", n, false, false, false))
        assertFalse(Workspace.rowIsKnown("", n, false, false, false))
    }

    @Test
    fun `a client card makes a sender known and professional without touching Contacts`() {
        val n = "+19195550142"
        assertTrue(Workspace.rowIsKnown(n, n, isGroup = false, usesCustomTitle = false, hasCard = true))
        assertEquals(Category.PROFESSIONAL, Workspace.resolve(null, isKnownContact = true, hasCard = true))
        assertEquals(Category.PERSONAL, Workspace.resolve(Category.PERSONAL, isKnownContact = true, hasCard = true))
    }

    private fun d(alert: Alert, by: RuleId) = Decision(alert, by, emptyList(), emptySet(), listOf(""))

    @Test
    fun `channel follows decision then category`() {
        assertEquals(ChannelKind.QUIET, Workspace.channel(d(Alert.SILENT, RuleId.QUIET_HOURS), Category.PERSONAL))
        assertEquals(ChannelKind.PRIORITY, Workspace.channel(d(Alert.ALERT, RuleId.PRIORITY_CONTACT), Category.UNKNOWN))
        assertEquals(ChannelKind.OTP, Workspace.channel(d(Alert.ALERT, RuleId.ONE_TIME_CODE), Category.UNKNOWN))
        assertEquals(ChannelKind.PERSONAL, Workspace.channel(d(Alert.ALERT, RuleId.DEFAULT), Category.PERSONAL))
        assertEquals(ChannelKind.PROFESSIONAL, Workspace.channel(d(Alert.ALERT, RuleId.DEFAULT), Category.PROFESSIONAL))
        assertEquals(ChannelKind.UNKNOWN, Workspace.channel(d(Alert.ALERT, RuleId.DEFAULT), Category.UNKNOWN))
    }
}

class QuarantineWorkspaceTest {
    @Test
    fun `quarantined threads show only in quarantine mode`() {
        com.sovereignops.sms.core.rules.WorkspaceMode.entries.forEach { mode ->
            val shown = Workspace.visible(mode, com.sovereignops.sms.core.rules.Category.PERSONAL, com.sovereignops.sms.core.rules.ConversationPolicy.PRIORITY, quarantined = true)
            org.junit.Assert.assertEquals(mode.name, mode == com.sovereignops.sms.core.rules.WorkspaceMode.QUARANTINE, shown)
        }
        org.junit.Assert.assertFalse(Workspace.visible(com.sovereignops.sms.core.rules.WorkspaceMode.QUARANTINE, com.sovereignops.sms.core.rules.Category.PERSONAL, com.sovereignops.sms.core.rules.ConversationPolicy.DEFAULT))
    }
}
