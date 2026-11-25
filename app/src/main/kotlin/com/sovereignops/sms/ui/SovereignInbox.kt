package com.sovereignops.sms.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import com.google.android.material.chip.ChipGroup
import com.sovereignops.sms.QuickCoverTile
import com.sovereignops.sms.Sovereign
import com.sovereignops.sms.SovereignCover
import com.sovereignops.sms.core.rules.Category
import com.sovereignops.sms.core.rules.WorkspaceMode
import com.sovereignops.sms.core.workspace.Workspace
import com.sovereignops.sms.vault.VaultCache
import org.fossify.messages.R
import org.fossify.messages.models.Conversation

/** Workspace chips on the inbox and the filter they apply. Drafts and threads are untouched. */
object SovereignInbox {
    private val chips = mapOf(
        R.id.sv_mode_all to WorkspaceMode.ALL,
        R.id.sv_mode_personal to WorkspaceMode.PERSONAL,
        R.id.sv_mode_professional to WorkspaceMode.PROFESSIONAL,
        R.id.sv_mode_focus to WorkspaceMode.FOCUS,
        R.id.sv_mode_quarantine to WorkspaceMode.QUARANTINE,
    )

    private fun chipFor(mode: WorkspaceMode) = chips.entries.first { it.value == mode }.key

    fun setup(activity: FragmentActivity, group: ChipGroup, refilter: () -> Unit) {
        val store = Sovereign.store(activity)
        if (store.coverActive) store.workspaceMode = WorkspaceMode.PERSONAL
        group.check(chipFor(store.workspaceMode))
        group.setOnCheckedStateChangeListener { _, ids ->
            val mode = ids.firstOrNull()?.let(chips::get) ?: return@setOnCheckedStateChangeListener
            if (mode == store.workspaceMode) return@setOnCheckedStateChangeListener
            if (store.coverActive && mode != WorkspaceMode.PERSONAL) {
                // Quick Cover: stay on Personal until the user re-authenticates.
                group.check(chipFor(WorkspaceMode.PERSONAL))
                SovereignCover.turnOff(activity) { group.check(chipFor(mode)) }
                return@setOnCheckedStateChangeListener
            }
            store.workspaceMode = mode
            refilter()
        }
    }

    /** Toolbar Quick Cover button. */
    fun toggleCover(activity: FragmentActivity, group: ChipGroup, refilter: () -> Unit) {
        if (SovereignCover.isOn(activity)) {
            SovereignCover.turnOff(activity, refilter)
        } else {
            SovereignCover.turnOn(activity)
            group.check(chipFor(WorkspaceMode.PERSONAL))
            refilter()
        }
    }

    /** The Quick Settings tile opens the inbox with this extra to leave cover. */
    fun handleIntent(activity: FragmentActivity, intent: Intent?, refilter: () -> Unit) {
        if (intent?.getBooleanExtra(QuickCoverTile.EXTRA_LEAVE_COVER, false) != true) return
        intent.removeExtra(QuickCoverTile.EXTRA_LEAVE_COVER)
        if (SovereignCover.isOn(activity)) SovereignCover.turnOff(activity, refilter)
    }

    /** Conversation row title: local alias and screening dot from the vault cache. */
    fun title(c: Conversation): CharSequence = SovereignCard.decorate(c.threadId, c.title)

    fun filter(activity: Activity, conversations: List<Conversation>): List<Conversation> {
        val store = Sovereign.store(activity)
        return Workspace.filter(
            conversations,
            if (store.coverActive) WorkspaceMode.PERSONAL else store.workspaceMode,
            category = { c ->
                val hasCard = VaultCache.card(c.threadId) != null
                Workspace.resolve(store.category(c.threadId), Workspace.rowIsKnown(c.title, c.phoneNumber, c.isGroupConversation, c.usesCustomTitle, hasCard), hasCard)
            },
            policy = { c -> store.conversationPolicy(c.threadId) },
            quarantined = { c -> VaultCache.isQuarantined(c.phoneNumber) },
        )
    }

    /** True when Quick Cover is active and [threadId] resolves to Professional or Quarantine. */
    fun isThreadHiddenByCover(context: Context, threadId: Long, phoneNumber: String = ""): Boolean {
        val store = Sovereign.store(context)
        if (!store.coverActive || threadId <= 0L) return false
        if (phoneNumber.isNotEmpty() && VaultCache.isQuarantined(phoneNumber)) return true
        val hasCard = VaultCache.card(threadId) != null
        val explicit = store.category(threadId)
        return explicit == Category.PROFESSIONAL || (explicit == null && hasCard)
    }
}
