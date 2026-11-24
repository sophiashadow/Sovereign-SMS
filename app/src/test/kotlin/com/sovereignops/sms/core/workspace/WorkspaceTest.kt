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
    fun `unsaved titles are raw numbers`() {
        assertTrue(Workspace.titleLooksUnsaved("+1 (919) 555-0142"))
        assertTrue(Workspace.titleLooksUnsaved("72345"))
        assertFalse(Workspace.titleLooksUnsaved("Sam Example"))
        assertFalse(Workspace.titleLooksUnsaved(""))
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
