package com.sovereignops.sms.core.workspace

import com.sovereignops.sms.core.rules.Alert
import com.sovereignops.sms.core.rules.Category
import com.sovereignops.sms.core.rules.ConversationPolicy
import com.sovereignops.sms.core.rules.Decision
import com.sovereignops.sms.core.rules.RuleId
import com.sovereignops.sms.core.rules.WorkspaceMode

/** Notification channels Sovereign posts on, one per category plus priority, codes and quiet. */
enum class ChannelKind { PERSONAL, PROFESSIONAL, UNKNOWN, PRIORITY, OTP, QUIET, QUARANTINE }

object Workspace {

    /**
     * A user assignment wins. Otherwise a sender with a client card (alias or stage, kept
     * out of Android Contacts on purpose) is professional, a saved contact is personal,
     * and everyone else is unknown.
     */
    fun resolve(assigned: Category?, isKnownContact: Boolean, hasCard: Boolean = false): Category =
        assigned ?: when {
            hasCard -> Category.PROFESSIONAL
            isKnownContact -> Category.PERSONAL
            else -> Category.UNKNOWN
        }

    /** Inbox filter for a workspace mode. FOCUS shows only priority conversations. */
    fun visible(mode: WorkspaceMode, category: Category, policy: ConversationPolicy, quarantined: Boolean = false): Boolean = when {
        mode == WorkspaceMode.QUARANTINE -> quarantined
        quarantined -> false // quarantined threads appear only under Quarantine
        else -> when (mode) {
            WorkspaceMode.ALL -> true
            WorkspaceMode.PERSONAL -> category == Category.PERSONAL
            WorkspaceMode.PROFESSIONAL -> category == Category.PROFESSIONAL || category == Category.UNKNOWN
            WorkspaceMode.FOCUS -> policy == ConversationPolicy.PRIORITY
            WorkspaceMode.QUARANTINE -> false
        }
    }

    /** Filter any list of conversations; the lambdas read each item's state. */
    fun <T> filter(
        items: List<T>,
        mode: WorkspaceMode,
        category: (T) -> Category,
        policy: (T) -> ConversationPolicy,
        quarantined: (T) -> Boolean = { false },
    ): List<T> = items.filter { visible(mode, category(it), policy(it), quarantined(it)) }

    /**
     * Whether an inbox row's sender counts as known. Upstream titles a thread with the
     * contact's name when the number is saved and with the number itself otherwise, so a
     * title that is the same number means "not in contacts". A client card, a group or a
     * user-renamed thread always counts as known.
     */
    fun rowIsKnown(title: String, phoneNumber: String, isGroup: Boolean, usesCustomTitle: Boolean, hasCard: Boolean): Boolean = when {
        hasCard || isGroup || usesCustomTitle -> true
        title.isBlank() -> false
        title.trim().equals(phoneNumber.trim(), ignoreCase = true) -> false
        com.sovereignops.sms.core.rules.Addresses.sameNumber(title, phoneNumber) -> false
        else -> title.any(Char::isLetter) || com.sovereignops.sms.core.rules.Addresses.normalize(title).isEmpty()
    }

    private val priorityRules = setOf(RuleId.PRIORITY_CONTACT, RuleId.CONVERSATION_PRIORITY, RuleId.KEYWORD_PRIORITY)

    /** [regular]: the card stage is Regular, which shares the Priority / Regular channel. */
    fun channel(decision: Decision, category: Category, regular: Boolean = false): ChannelKind = when {
        decision.decidedBy == RuleId.QUARANTINED -> ChannelKind.QUARANTINE
        decision.alert == Alert.SILENT -> ChannelKind.QUIET
        decision.decidedBy in priorityRules -> ChannelKind.PRIORITY
        decision.decidedBy == RuleId.ONE_TIME_CODE -> ChannelKind.OTP
        regular -> ChannelKind.PRIORITY
        category == Category.PERSONAL -> ChannelKind.PERSONAL
        category == Category.PROFESSIONAL -> ChannelKind.PROFESSIONAL
        else -> ChannelKind.UNKNOWN
    }
}
