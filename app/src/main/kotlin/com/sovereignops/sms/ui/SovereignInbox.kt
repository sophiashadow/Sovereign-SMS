package com.sovereignops.sms.ui

import android.app.Activity
import com.google.android.material.chip.ChipGroup
import com.sovereignops.sms.Sovereign
import com.sovereignops.sms.core.rules.WorkspaceMode
import com.sovereignops.sms.core.workspace.Workspace
import org.fossify.messages.R
import org.fossify.messages.models.Conversation

/** Workspace chips on the inbox and the filter they apply. Drafts and threads are untouched. */
object SovereignInbox {
    private val chips = mapOf(
        R.id.sv_mode_all to WorkspaceMode.ALL,
        R.id.sv_mode_personal to WorkspaceMode.PERSONAL,
        R.id.sv_mode_professional to WorkspaceMode.PROFESSIONAL,
        R.id.sv_mode_focus to WorkspaceMode.FOCUS,
    )

    fun setup(activity: Activity, group: ChipGroup, refilter: () -> Unit) {
        val store = Sovereign.store(activity)
        chips.entries.firstOrNull { it.value == store.workspaceMode }?.let { group.check(it.key) }
        group.setOnCheckedStateChangeListener { _, ids ->
            val mode = ids.firstOrNull()?.let(chips::get) ?: return@setOnCheckedStateChangeListener
            if (mode != store.workspaceMode) {
                store.workspaceMode = mode
                refilter()
            }
        }
    }

    fun filter(activity: Activity, conversations: List<Conversation>): List<Conversation> {
        val store = Sovereign.store(activity)
        return Workspace.filter(
            conversations,
            store.workspaceMode,
            category = { c -> Workspace.resolve(store.category(c.threadId), c.isGroupConversation || !Workspace.titleLooksUnsaved(c.title)) },
            policy = { c -> store.conversationPolicy(c.threadId) },
        )
    }
}
